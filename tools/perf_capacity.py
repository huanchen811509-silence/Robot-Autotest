#!/usr/bin/env python3
"""EW32-10 · 容量与压测（并发 / 峰值 / 长稳三份报告）。

与其它工具的分工
----------------
  perf_sample.py    ：单请求只读采样（p95/p99），产出预算门禁报告。
  perf_capacity.py  ：**负载形态**采样——多线程并发、短时突发、长时间稳态，
                      产出三份容量报告（concurrency / peak / soak）与内存/积压曲线。

口径
----
  * 所有请求都经 :8001 入口（前端同源代理），带 Bearer Token 与 X-Project-Id；
  * 读负载场景取自 tools/perf-budget.json 的 api.scenarios，避免预算与压测漂移；
  * 写负载走 POST /api/execution/runs（预算 runChain.createP95Ms 对应的接口），
    每次用唯一 Idempotency-Key，避免被 120s 幂等窗口合并成「假快」；
  * 压测创建的 run 在测完后统一 cancel（失败即忽略），不留下长期运行；
  * 长稳每 10s 采一次 health / summary / JVM 内存 / 4 个读场景，逐行落 JSONL，
    结束时汇总错误率、p95 漂移与内存增长（判断泄漏）。

为什么写接口要单独测
--------------------
  只读采样能躲开写锁与事务，但「运行创建」是用户点一次就等一次的路径；
  预算给的是 p95 ≤200ms。并发下写接口的 p95 才是容量结论的关键。

用法
----
    # 并发（默认 20/50/100，每级读 30s + 写 30 次）
    python3 tools/perf_capacity.py concurrency --levels 20,50,100 --read-seconds 30

    # 峰值突发（默认 200 并发 10s）
    python3 tools/perf_capacity.py peak --peak-workers 200 --peak-seconds 10

    # 长稳（≥2h；先跑 2 分钟验证脚本）
    python3 tools/perf_capacity.py soak --soak-minutes 120 --interval 10

凭据：QA_E2E_PASSWORD 优先，否则读 PERF_ENV_FILE / ~/.config/robot-autotest/env。
脚本不打印口令，报告也不写口令。退出码：0 完成；1 有未达标判据（--strict）；2 执行失败。
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUDGET_FILE = ROOT / "tools" / "perf-budget.json"
EVIDENCE = ROOT / "docs" / "evidence" / "v3.2.0" / "EW32-10"
PERF_DIR = EVIDENCE / "perf"
LOG_DIR = EVIDENCE / "logs"

_LOCAL = threading.local()


def opener() -> urllib.request.OpenerDirector:
    """每线程一个 opener：避免 urllib 连接状态在多线程间共享。"""
    direct = getattr(_LOCAL, "opener", None)
    if direct is None:
        direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        _LOCAL.opener = direct
    return direct


def env_value(key: str) -> str | None:
    if os.environ.get(key):
        return os.environ[key]
    candidates = []
    if os.environ.get("PERF_ENV_FILE"):
        candidates.append(Path(os.environ["PERF_ENV_FILE"]))
    candidates.append(Path.home() / ".config" / "robot-autotest" / "env")
    if os.environ.get("USERPROFILE"):
        candidates.append(Path(os.environ["USERPROFILE"]) / ".config" / "robot-autotest" / "env")
    for path in candidates:
        try:
            for line in path.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if not stripped or stripped.startswith("#") or "=" not in stripped:
                    continue
                name, value = stripped.split("=", 1)
                if name.strip() == key:
                    return value.strip()
        except OSError:
            continue
    return None


@dataclass
class Response:
    status: int
    elapsed_ms: float
    payload: dict | list | None
    error: str | None = None

    @property
    def ok(self) -> bool:
        return self.status == 200 and isinstance(self.payload, dict) \
            and bool(self.payload.get("success"))


def call(base: str, path: str, method: str = "GET", token: str | None = None,
         project_id: int | None = None, body: dict | None = None,
         headers: dict | None = None, timeout: float = 30) -> Response:
    hdrs = {"Accept": "application/json"}
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
        if project_id is not None:
            hdrs["X-Project-Id"] = str(project_id)
    if headers:
        hdrs.update(headers)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(f"{base}{path}", data=data, headers=hdrs, method=method)
    started = time.perf_counter()
    try:
        with opener().open(req, timeout=timeout) as resp:
            elapsed = (time.perf_counter() - started) * 1000
            raw = resp.read()
            try:
                payload = json.loads(raw) if raw else None
            except json.JSONDecodeError:
                payload = None
            return Response(resp.status, elapsed, payload)
    except urllib.error.HTTPError as exc:
        elapsed = (time.perf_counter() - started) * 1000
        raw = exc.read()
        try:
            payload = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            payload = None
        return Response(exc.code, elapsed, payload)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        elapsed = (time.perf_counter() - started) * 1000
        reason = getattr(exc, "reason", exc)
        return Response(0, elapsed, None, f"transport: {reason}")


def code_of(resp: Response) -> str:
    if resp.error:
        return resp.error
    if isinstance(resp.payload, dict):
        name = resp.payload.get("codeName") or resp.payload.get("message")
        if name:
            return str(name)
    return f"HTTP {resp.status}"


def pct(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    pos = q * (len(ordered) - 1)
    lo = int(pos)
    hi = min(lo + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (pos - lo)


def summarize(records: list[dict]) -> dict:
    latencies = [r["ms"] for r in records]
    if not records:
        return {"samples": 0}
    successes = sum(1 for r in records if r["ok"])
    codes: dict[str, int] = {}
    for r in records:
        if not r["ok"]:
            codes[r["code"]] = codes.get(r["code"], 0) + 1
    return {
        "samples": len(records),
        "success": successes,
        "successRate": round(successes / len(records), 4),
        "p50Ms": round(pct(latencies, 0.50), 2),
        "p95Ms": round(pct(latencies, 0.95), 2),
        "p99Ms": round(pct(latencies, 0.99), 2),
        "maxMs": round(max(latencies), 2),
        "errorCodes": codes,
    }


def is_server_error(codes: dict[str, int]) -> int:
    """5xx / 未捕获异常都算服务端错误；429/503 属于受控降级，单独统计。"""
    return sum(count for code, count in codes.items()
               if "HTTP 5" in code or "INTERNAL" in code or "transport" in code)


def login(base: str, user: str, project_id: int, timeout: float) -> str:
    password = env_value("QA_E2E_PASSWORD")
    if not password:
        fail("缺少 QA_E2E_PASSWORD（环境变量或 ~/.config/robot-autotest/env）")
    resp = call(base, "/api/auth/login", method="POST",
                body={"username": user, "password": password},
                project_id=project_id, timeout=timeout)
    token = ((resp.payload or {}).get("data") or {}).get("token") if resp.ok else None
    if not token:
        fail(f"登录失败 HTTP {resp.status}: {code_of(resp)}")
    return token


def fail(message: str):
    print(f"CAPACITY_ERROR {message}", file=sys.stderr)
    sys.exit(2)


def machine() -> dict:
    cpu = platform.processor() or platform.machine()
    try:
        text = Path("/proc/cpuinfo").read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            if line.lower().startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    return {
        "host": platform.node(),
        "platform": platform.platform(),
        "cpu": cpu,
        "cpuCount": os.cpu_count(),
        "python": platform.python_version(),
    }


def load_budget() -> dict:
    try:
        return json.loads(BUDGET_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"预算文件不可读：{BUDGET_FILE}（{exc}）")
    raise AssertionError("unreachable")


def scenario_paths(budget: dict, project_id: int) -> dict[str, str]:
    scenarios = (budget.get("api") or {}).get("scenarios") or {}
    if not scenarios:
        fail("预算文件没有 api.scenarios")
    return {name: str(spec["path"]).replace("{projectId}", str(project_id))
            for name, spec in scenarios.items()}


def write_report(path: Path, report: dict) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    except OSError as exc:
        fail(f"写报告失败：{path}（{exc}）")


# ---------------------------------------------------------------------------
# 并发
# ---------------------------------------------------------------------------

def run_read_level(base: str, token: str, project_id: int, paths: dict[str, str],
                   workers: int, seconds: float, warmup: float, timeout: float) -> dict:
    names = list(paths)
    barrier = threading.Barrier(workers + 1)
    stop = threading.Event()
    records: list[dict] = []
    lock = threading.Lock()

    def worker(index: int):
        barrier.wait()
        cursor = index
        while not stop.is_set():
            name = names[cursor % len(names)]
            cursor += 1
            resp = call(base, paths[name], token=token, project_id=project_id, timeout=timeout)
            with lock:
                records.append({"scenario": name, "ms": resp.elapsed_ms,
                                "ok": resp.ok, "code": "" if resp.ok else code_of(resp)})

    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(workers)]
    for thread in threads:
        thread.start()
    barrier.wait()
    if warmup > 0:
        time.sleep(warmup)
        with lock:
            records.clear()
    time.sleep(seconds)
    stop.set()
    for thread in threads:
        thread.join(timeout=timeout)

    overall = summarize(records)
    per_scenario = {}
    for name in names:
        per_scenario[name] = summarize([r for r in records if r["scenario"] == name])
    return {"workers": workers, "seconds": seconds, "warmupSeconds": warmup,
            "overall": overall, "scenarios": per_scenario}


def run_write_level(base: str, token: str, project_id: int, workers: int,
                    samples: int, timeout: float) -> dict:
    queue = list(range(samples))
    lock = threading.Lock()
    records: list[dict] = []
    run_ids: list[str] = []
    stamp = int(time.time())

    def worker():
        while True:
            with lock:
                if not queue:
                    return
                seq = queue.pop(0)
            key = f"cap-{stamp}-{seq:04d}"[:64]
            body = {
                "projectId": project_id,
                "suitePaths": ["__capacity_probe__/capacity_probe.robot"],
                "concurrency": 1,
                "timeoutSeconds": 30,
                "runRequestId": key,
            }
            resp = call(base, "/api/execution/runs", method="POST", token=token,
                        project_id=project_id, body=body,
                        headers={"Idempotency-Key": key}, timeout=timeout)
            run_id = ((resp.payload or {}).get("data") or {}).get("runId") if resp.ok else None
            with lock:
                records.append({"ms": resp.elapsed_ms, "ok": resp.ok,
                                "code": "" if resp.ok else code_of(resp)})
                if run_id:
                    run_ids.append(str(run_id))

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(workers)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=timeout + 60)

    result = summarize(records)
    result["workers"] = workers
    # 不在这里 cancel：派发器可能刚好开始执行，取消会触发「CANCELED → RUNNING」的
    # 状态跃迁异常日志，把压测自造的竞态混进被测账号的审计里。
    # 探针 suitePaths 指向不存在的套件，run 会自然进入 FAILED(missing)，无进程残留。
    result["probeRunIds"] = run_ids
    return result


def run_write_baseline(base: str, token: str, project_id: int, samples: int,
                       timeout: float, max_wait: float = 15) -> dict:
    """队列空闲基线：每次创建后等 run 到终态再发下一次，排除 pabot 启动争抢。"""
    records: list[dict] = []
    stamp = int(time.time())
    for seq in range(samples):
        key = f"cap-idle-{stamp}-{seq:04d}"[:64]
        body = {
            "projectId": project_id,
            "suitePaths": ["__capacity_probe__/capacity_probe.robot"],
            "concurrency": 1,
            "timeoutSeconds": 30,
            "runRequestId": key,
        }
        resp = call(base, "/api/execution/runs", method="POST", token=token,
                    project_id=project_id, body=body,
                    headers={"Idempotency-Key": key}, timeout=timeout)
        records.append({"ms": resp.elapsed_ms, "ok": resp.ok,
                        "code": "" if resp.ok else code_of(resp)})
        run_id = ((resp.payload or {}).get("data") or {}).get("runId") if resp.ok else None
        if not run_id:
            continue
        deadline = time.time() + max_wait
        while time.time() < deadline:
            probe = call(base, f"/api/execution/runs/{run_id}?projectId={project_id}",
                         token=token, project_id=project_id, timeout=timeout)
            data = (probe.payload or {}).get("data") or {}
            state = str(data.get("state") or data.get("runStatus") or "").upper()
            if state in {"FINISHED", "FAILED", "CANCELED"}:
                break
            time.sleep(0.2)
        else:
            print(f"CAPACITY_WARN 基线 run {run_id} 未在 {max_wait}s 内到终态")
    return summarize(records)


def cmd_concurrency(args, budget: dict) -> int:
    base = args.base.rstrip("/")
    paths = scenario_paths(budget, args.project_id)
    token = login(base, args.user, args.project_id, args.timeout)
    api_budget = budget["api"]["scenarios"]
    chain_budget = budget.get("runChain") or {}

    idle = run_write_baseline(base, token, args.project_id, args.write_idle_samples, args.timeout)
    print(f"CAPACITY_WRITE_IDLE samples={idle.get('samples')} p50={idle.get('p50Ms')} "
          f"p95={idle.get('p95Ms')} p99={idle.get('p99Ms')} success={idle.get('successRate')}")

    levels = []
    for level in [int(x) for x in args.levels.split(",") if x.strip()]:
        print(f"CAPACITY_CONCURRENCY level={level} read={args.read_seconds}s "
              f"writeSamples={args.write_samples}")
        read = run_read_level(base, token, args.project_id, paths, level,
                              args.read_seconds, args.warmup_seconds, args.timeout)
        print(f"  read overall p95={read['overall'].get('p95Ms')} "
              f"p99={read['overall'].get('p99Ms')} "
              f"success={read['overall'].get('successRate')}")
        write = run_write_level(base, token, args.project_id, level,
                                args.write_samples, args.timeout)
        print(f"  write p95={write.get('p95Ms')} p99={write.get('p99Ms')} "
              f"success={write.get('successRate')}")
        levels.append({"level": level, "read": read, "write": write})

    violations = []
    slo_misses = []
    within_slo = []
    if idle.get("successRate", 0) < args.min_success:
        violations.append(f"队列空闲创建成功率 {idle.get('successRate')} < {args.min_success}")
    if idle.get("p95Ms", 0) > float(chain_budget.get("createP95Ms", 200)):
        violations.append(
            f"队列空闲运行创建 p95 {idle.get('p95Ms')}ms "
            f"> 预算 {chain_budget.get('createP95Ms')}ms")
    for index, row in enumerate(levels):
        level = row["level"]
        overall = row["read"]["overall"]
        read_p95_over = []
        for name, spec in api_budget.items():
            got = row["read"]["scenarios"].get(name) or {}
            if got.get("p95Ms", 0) > float(spec["p95Ms"]):
                read_p95_over.append(
                    f"{name} p95 {got.get('p95Ms')}ms > {spec['p95Ms']}ms")
        write_over = row["write"].get("p95Ms", 0) > float(chain_budget.get("createP95Ms", 200))

        # 正确性是硬判据：任何并发等级都不允许失败/5xx。
        if overall.get("successRate", 0) < args.min_success:
            violations.append(
                f"{level} 并发读成功率 {overall.get('successRate')} < {args.min_success}")
        server_errors = is_server_error(overall.get("errorCodes") or {}) \
            + is_server_error(row["write"].get("errorCodes") or {})
        if server_errors:
            violations.append(f"{level} 并发下出现 {server_errors} 个 5xx/未捕获异常")
        if row["write"].get("successRate", 0) < args.min_success:
            violations.append(
                f"{level} 并发写成功率 {row['write'].get('successRate')} < {args.min_success}")

        # 延迟是「容量边界」数据：预算阈值对应单用户/队列空闲基线（预算文件口径），
        # 并发等级超出只记为 SLO 缺口，用于给出建议并发；只有最小并发等级也必须达标。
        if read_p95_over or write_over:
            slo_misses.append({"level": level, "read": read_p95_over,
                               "writeP95Ms": row["write"].get("p95Ms")})
            if index == 0:
                if read_p95_over:
                    violations.append(f"基线并发 {level} 读 p95 超预算：{'；'.join(read_p95_over)}")
                if write_over:
                    violations.append(
                        f"基线并发 {level} 运行创建 p95 {row['write'].get('p95Ms')}ms "
                        f"> {chain_budget.get('createP95Ms')}ms")
        else:
            within_slo.append(level)

    recommended = max(within_slo) if within_slo else 0
    report = {
        "version": 1,
        "mode": "concurrency",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "environment": {"base": base, "projectId": args.project_id, "user": args.user,
                        "readSeconds": args.read_seconds, "warmupSeconds": args.warmup_seconds,
                        "writeSamplesPerLevel": args.write_samples, "timeoutSeconds": args.timeout},
        "machine": machine(),
        "budget": {"apiScenarios": api_budget, "runChain": chain_budget,
                   "note": "延迟阈值对应单用户/队列空闲基线；并发等级只用于给出容量边界"},
        "writeIdleBaseline": idle,
        "levels": levels,
        "sloMisses": slo_misses,
        "withinSloLevels": within_slo,
        "recommendedMaxConcurrency": recommended,
        "violations": violations,
    }
    write_report(args.out, report)
    print(f"CAPACITY_REPORT {args.out}")
    print(f"  recommendedMaxConcurrency={recommended} withinSlo={within_slo}")
    if violations:
        print(f"CAPACITY_RESULT FAIL({len(violations)})")
        return 1 if args.strict else 0
    print("CAPACITY_RESULT PASS")
    return 0


# ---------------------------------------------------------------------------
# 峰值
# ---------------------------------------------------------------------------

def cmd_peak(args, budget: dict) -> int:
    base = args.base.rstrip("/")
    paths = scenario_paths(budget, args.project_id)
    token = login(base, args.user, args.project_id, args.timeout)
    names = list(paths)

    actuator = args.actuator_base.rstrip("/")
    before = call(actuator, "/actuator/health", timeout=args.timeout)
    barrier = threading.Barrier(args.peak_workers + 1)
    stop = threading.Event()
    records: list[dict] = []
    lock = threading.Lock()

    def worker(index: int):
        barrier.wait()
        cursor = index
        while not stop.is_set():
            name = names[cursor % len(names)]
            cursor += 1
            resp = call(base, paths[name], token=token, project_id=args.project_id,
                        timeout=args.timeout)
            with lock:
                records.append({"scenario": name, "ms": resp.elapsed_ms,
                                "ok": resp.ok, "code": "" if resp.ok else code_of(resp)})

    threads = [threading.Thread(target=worker, args=(i,), daemon=True)
               for i in range(args.peak_workers)]
    for thread in threads:
        thread.start()
    barrier.wait()
    time.sleep(args.peak_seconds)
    stop.set()
    for thread in threads:
        thread.join(timeout=args.timeout)

    overall = summarize(records)
    rejections = sum(count for code, count in (overall.get("errorCodes") or {}).items()
                     if "429" in code or "503" in code or "Too Many" in code
                     or "UNAVAILABLE" in code)
    server_errors = sum(count for code, count in (overall.get("errorCodes") or {}).items()
                        if "HTTP 5" in code or "INTERNAL" in code)

    time.sleep(args.recovery_seconds)
    after = call(actuator, "/actuator/health", timeout=args.timeout)
    recovery = [call(base, paths[name], token=token, project_id=args.project_id,
                     timeout=args.timeout) for name in names]

    violations = []
    if overall.get("successRate", 0) < args.min_success:
        violations.append(
            f"峰值成功率 {overall.get('successRate')} < {args.min_success}"
            f"（拒绝={rejections}，5xx={server_errors}）")
    if after.status != 200:
        violations.append(f"峰值后 health 不可用：HTTP {after.status} {code_of(after)}")
    if server_errors > 0:
        violations.append(f"峰值出现 {server_errors} 个 5xx（应降级为 429/503，不得崩）")

    report = {
        "version": 1,
        "mode": "peak",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "environment": {"base": base, "projectId": args.project_id,
                        "peakWorkers": args.peak_workers, "peakSeconds": args.peak_seconds,
                        "recoverySeconds": args.recovery_seconds, "timeoutSeconds": args.timeout},
        "machine": machine(),
        "overall": overall,
        "rejections": rejections,
        "serverErrors": server_errors,
        "healthBefore": {"status": before.status},
        "healthAfter": {"status": after.status},
        "recovery": summarize([{"ms": r.elapsed_ms, "ok": r.ok,
                                "code": "" if r.ok else code_of(r)} for r in recovery]),
        "violations": violations,
    }
    write_report(args.out, report)
    print(f"CAPACITY_REPORT {args.out}")
    print(f"  peak success={overall.get('successRate')} p95={overall.get('p95Ms')} "
          f"p99={overall.get('p99Ms')} rejections={rejections} 5xx={server_errors}")
    if violations:
        print(f"CAPACITY_RESULT FAIL({len(violations)})")
        return 1 if args.strict else 0
    print("CAPACITY_RESULT PASS")
    return 0


# ---------------------------------------------------------------------------
# 长稳
# ---------------------------------------------------------------------------

def metric_value(payload: dict | None) -> float | None:
    if not isinstance(payload, dict):
        return None
    measurements = payload.get("measurements") or []
    if measurements and isinstance(measurements[0], dict):
        value = measurements[0].get("value")
        return float(value) if isinstance(value, (int, float)) else None
    return None


def cmd_soak(args, budget: dict) -> int:
    base = args.base.rstrip("/")
    paths = scenario_paths(budget, args.project_id)
    token = login(base, args.user, args.project_id, args.timeout)
    actuator = args.actuator_base.rstrip("/")
    total_seconds = args.soak_minutes * 60
    deadline = time.time() + total_seconds
    samples: list[dict] = []

    args.samples_out.parent.mkdir(parents=True, exist_ok=True)
    with args.samples_out.open("w", encoding="utf-8") as sink:
        index = 0
        while time.time() < deadline:
            started = time.time()
            index += 1
            health = call(actuator, "/actuator/health", timeout=args.timeout)
            summary = call(base, "/api/admin/observability/summary", token=token,
                           project_id=args.project_id, timeout=args.timeout)
            memory = call(actuator, "/actuator/metrics/jvm.memory.used", timeout=args.timeout)
            cpu = call(actuator, "/actuator/metrics/process.cpu.usage", timeout=args.timeout)
            scenario_rows = {}
            for name, path in paths.items():
                resp = call(base, path, token=token, project_id=args.project_id,
                            timeout=args.timeout)
                scenario_rows[name] = {"ms": round(resp.elapsed_ms, 2),
                                       "ok": resp.ok,
                                       "code": "" if resp.ok else code_of(resp)}
            row = {
                "at": datetime.now(timezone.utc).isoformat(),
                "elapsedSeconds": round(started - (deadline - total_seconds), 1),
                "health": health.status,
                "success": summary.ok,
                "summary": (summary.payload or {}).get("data") if summary.ok else None,
                "jvmMemoryUsed": metric_value(memory.payload),
                "cpuUsage": metric_value(cpu.payload),
                "scenarios": scenario_rows,
            }
            samples.append(row)
            sink.write(json.dumps(row, ensure_ascii=False) + "\n")
            sink.flush()
            if index % 10 == 0:
                print(f"SOAK_SAMPLE n={index} memory={row['jvmMemoryUsed']} "
                      f"health={health.status} summary={summary.status}")
            remaining = args.interval - (time.time() - started)
            if remaining > 0:
                time.sleep(remaining)
            else:
                print(f"SOAK_WARN sample n={index} 用时 {-remaining:.1f}s 超出间隔，未补采")

    scenario_lists: dict[str, list[dict]] = {name: [] for name in paths}
    for row in samples:
        for name, rec in row["scenarios"].items():
            scenario_lists[name].append(rec)

    memories = [r["jvmMemoryUsed"] for r in samples if r["jvmMemoryUsed"] is not None]
    first_memory = memories[0] if memories else None
    last_memory = memories[-1] if memories else None
    growth = None if not memories else last_memory - first_memory
    growth_pct = None if not memories or first_memory == 0 else growth / first_memory
    failures = [row for row in samples if row["health"] != 200 or not row["success"]]
    async_queued = [((r.get("summary") or {}).get("async") or {}).get("queued", 0)
                    for r in samples if r.get("summary")]
    slow_errored = [((r.get("summary") or {}).get("slowQuery") or {}).get("errored", 0)
                    for r in samples if r.get("summary")]

    p95_by_scenario = {name: round(pct([x["ms"] for x in rows], 0.95), 2)
                       for name, rows in scenario_lists.items()}
    first_half = samples[: len(samples) // 2]
    second_half = samples[len(samples) // 2:]
    drift = {}
    for name in paths:
        def p95_of(rows: list[dict]) -> float:
            values = [row["scenarios"][name]["ms"] for row in rows
                      if row["scenarios"].get(name)]
            return pct(values, 0.95)
        before = p95_of(first_half) if first_half else 0.0
        after = p95_of(second_half) if second_half else 0.0
        drift[name] = {"firstHalfP95Ms": round(before, 2),
                       "secondHalfP95Ms": round(after, 2),
                       "deltaPct": round((after - before) / before * 100, 1) if before else None}

    violations = []
    if failures:
        violations.append(f"{len(failures)} 个采样点 health/summary 失败（共 {len(samples)}）")
    if last_memory is not None and first_memory is not None:
        allowed = max(first_memory * args.max_memory_growth_pct, args.max_memory_growth_bytes)
        if growth > allowed:
            violations.append(
                f"JVM 堆增长 {growth / 1e6:.1f}MB > 允许 {allowed / 1e6:.1f}MB"
                f"（{growth_pct * 100:.1f}%）")
    for name, row in drift.items():
        # 百分比漂移在小基数上没有意义（20ms→42ms 也是 100%+），加绝对地板。
        if row["deltaPct"] is not None and row["deltaPct"] > args.max_drift_pct \
                and row["secondHalfP95Ms"] > args.drift_floor_ms:
            violations.append(
                f"{name} 后半程 p95 漂移 {row['deltaPct']}% > {args.max_drift_pct}%"
                f"（后半 p95={row['secondHalfP95Ms']}ms，地板 {args.drift_floor_ms}ms）")
    if not memories:
        violations.append("没有采到 JVM 内存指标（检查 actuator-base 是否可达）")

    report = {
        "version": 1,
        "mode": "soak",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "environment": {"base": base, "projectId": args.project_id,
                        "plannedSeconds": total_seconds, "intervalSeconds": args.interval,
                        "samples": len(samples)},
        "machine": machine(),
        "memory": {"firstBytes": first_memory, "lastBytes": last_memory,
                   "maxBytes": max(memories) if memories else None,
                   "minBytes": min(memories) if memories else None,
                   "growthBytes": growth,
                   "growthPct": round(growth_pct * 100, 2) if growth_pct is not None else None},
        "failures": len(failures),
        "p95ByScenario": p95_by_scenario,
        "drift": drift,
        "asyncQueuedMax": max(async_queued) if async_queued else 0,
        "slowQueryErroredMax": max(slow_errored) if slow_errored else 0,
        "violations": violations,
    }
    write_report(args.out, report)
    print(f"CAPACITY_REPORT {args.out}")
    print(f"  soak samples={len(samples)} memoryGrowth={report['memory']['growthPct']}% "
          f"failures={len(failures)} drift={drift}")
    if violations:
        print(f"CAPACITY_RESULT FAIL({len(violations)})")
        return 1 if args.strict else 0
    print("CAPACITY_RESULT PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="v3.2.0 容量与压测")
    parser.add_argument("mode", choices=["concurrency", "peak", "soak"])
    parser.add_argument("--base", default=os.environ.get("PERF_BASE", "http://127.0.0.1:8001"))
    parser.add_argument("--actuator-base",
                        default=os.environ.get("PERF_ACTUATOR_BASE", "http://127.0.0.1:8081"),
                        help="Actuator 直连端口；入口 :8001 只代理 /api")
    parser.add_argument("--project-id", type=int,
                        default=int(os.environ.get("PERF_PROJECT_ID", "29")))
    parser.add_argument("--user", default=os.environ.get("PERF_USER", "qa-e2e-admin"))
    parser.add_argument("--timeout", type=float, default=float(os.environ.get("PERF_TIMEOUT", "30")))
    parser.add_argument("--strict", action="store_true", help="未达标时返回 1")

    parser.add_argument("--levels", default="20,50,100")
    parser.add_argument("--read-seconds", type=float, default=30)
    parser.add_argument("--warmup-seconds", type=float, default=5)
    parser.add_argument("--write-samples", type=int, default=30)
    parser.add_argument("--write-idle-samples", type=int, default=10,
                        help="队列空闲创建基线次数（每次等 run 终态）")
    parser.add_argument("--min-success", type=float, default=0.99)

    parser.add_argument("--peak-workers", type=int, default=200)
    parser.add_argument("--peak-seconds", type=float, default=10)
    parser.add_argument("--recovery-seconds", type=float, default=5)

    parser.add_argument("--soak-minutes", type=int, default=120)
    parser.add_argument("--interval", type=float, default=10)
    parser.add_argument("--max-memory-growth-pct", type=float, default=0.20)
    parser.add_argument("--max-memory-growth-bytes", type=float, default=50 * 1024 * 1024)
    parser.add_argument("--max-drift-pct", type=float, default=30)
    parser.add_argument("--drift-floor-ms", type=float, default=50)

    parser.add_argument("--out", type=Path)
    parser.add_argument("--samples-out", type=Path)
    args = parser.parse_args()

    budget = load_budget()
    PERF_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    if args.out is None:
        args.out = PERF_DIR / f"{args.mode}.json"
    if args.samples_out is None:
        args.samples_out = PERF_DIR / f"{args.mode}-samples.jsonl"

    print(f"CAPACITY_MODE {args.mode} base={args.base} project={args.project_id}")
    if args.mode == "concurrency":
        return cmd_concurrency(args, budget)
    if args.mode == "peak":
        return cmd_peak(args, budget)
    return cmd_soak(args, budget)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
