#!/usr/bin/env python3
"""EW32-01 · 只读接口性能采样（产出可被 check_perf_budget.py 判定的报告）。

与既有 `perf_baseline.py` 的分工
--------------------------------
  perf_baseline.py：门禁第 17 步的**小型回归基线**，比对历史 p95 是否劣化。
  perf_sample.py   ：v3.2.0 四类预算的**规模采样器**，按 perf-budget.json 的场景
                     采样并落 JSON，交给 check_perf_budget.py 对绝对预算判定。

为什么不用 JUnit 采样
--------------------
规划原文写的是「JUnit 压测采样」。落地时改为独立 Python 采样器，原因有三：
  1. 采样对象是**已运行的 HTTP 服务**，不需要 Spring 上下文，JUnit 只会多一层启动成本；
  2. Windows 与 WSL 共用同一脚本，避免再维护一套 Java 采样客户端；
  3. 报告结构与门禁（Python）同源，字段不会漂移。
该偏差已记入 docs/evidence/v3.2.0/EW32-01/README.md（不隐藏实现差异）。

采样口径
--------
  每场景先预热 warmupRounds 轮，再跑 rounds 轮 × samplesPerRound 次；
  每轮算 p50/p95/p99，最终取**各轮同一分位数的中位数**（比「取最好一轮」更抗噪，
  也避免把偶发毛刺直接判成回归）。本机冒烟用 localSamplesPerRound（默认 20），
  CI 固定机用 samplesPerRound（默认 200，D32-5）。

用法
----
    # 本机冒烟（需 8001 可用 + 凭据）
    python3 tools/perf_sample.py --local --dataset-manifest artifacts/perf-dataset-standard/manifest.json
    # CI 完整采样
    python3 tools/perf_sample.py --rounds 3 --samples 200

凭据
----
  QA_E2E_PASSWORD 优先；否则读 PERF_ENV_FILE / ~/.config/robot-autotest/env。
  采样器不打印口令，报告里也不写口令。

退出码：0 = 采样完成；2 = 登录/请求/写文件失败。
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUDGET_FILE = ROOT / "tools" / "perf-budget.json"
DEFAULT_OUT = ROOT / "docs" / "evidence" / "v3.2.0" / "EW32-01" / "perf" / "perf-report.json"

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


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


def fail(message: str, code: int = 2):
    print(f"PERF_ERROR {message}", file=sys.stderr)
    sys.exit(code)


def request(base: str, path: str, token: str | None = None, method: str = "GET",
            body: dict | None = None, project_id: int | None = None,
            timeout: float = 30) -> tuple[int, dict | list | None, float]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
        if project_id is not None:
            headers["X-Project-Id"] = str(project_id)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"{base}{path}", data=data, headers=headers, method=method)
    started = time.perf_counter()
    try:
        with _OPENER.open(req, timeout=timeout) as resp:
            elapsed_ms = (time.perf_counter() - started) * 1000
            raw = resp.read()
            payload = json.loads(raw) if raw else None
            return resp.status, payload, elapsed_ms
    except urllib.error.HTTPError as exc:
        detail = exc.read()[:300].decode("utf-8", "replace")
        fail(f"{path} HTTP {exc.code}: {detail}")
    except urllib.error.URLError as exc:
        fail(f"无法连接 {base}（{exc.reason}）—— 采样需要后端可用")
    raise AssertionError("unreachable")


def login(base: str, user: str, project_id: int, timeout: float) -> str:
    password = env_value("QA_E2E_PASSWORD")
    if not password:
        fail("缺少 QA_E2E_PASSWORD（环境变量或 ~/.config/robot-autotest/env）")
    status, payload, _ = request(base, "/api/auth/login", method="POST",
                                 body={"username": user, "password": password},
                                 project_id=project_id, timeout=timeout)
    token = ((payload or {}).get("data") or {}).get("token") if status == 200 else None
    if not token:
        fail(f"登录失败 HTTP {status}: {json.dumps(payload, ensure_ascii=False)[:200]}")
    return token


def pct(values: list[float], q: float) -> float:
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    pos = q * (len(ordered) - 1)
    lo = int(pos)
    hi = min(lo + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (pos - lo)


def sample_scenario(base: str, token: str, project_id: int, path: str,
                    rounds: int, samples: int, warmups: int,
                    timeout: float) -> dict:
    for _ in range(warmups):
        request(base, path, token, project_id=project_id, timeout=timeout)

    round_rows = []
    for _ in range(rounds):
        times = []
        for _ in range(samples):
            status, payload, elapsed = request(base, path, token,
                                               project_id=project_id, timeout=timeout)
            if status != 200 or not (payload or {}).get("success"):
                fail(f"{path} 返回异常 HTTP {status}: "
                     f"{json.dumps(payload, ensure_ascii=False)[:200]}")
            times.append(elapsed)
        round_rows.append({
            "p50Ms": pct(times, 0.50),
            "p95Ms": pct(times, 0.95),
            "p99Ms": pct(times, 0.99),
            "roundsP95Ms": [],
        })
    return {
        "p50Ms": round(statistics.median([r["p50Ms"] for r in round_rows]), 2),
        "p95Ms": round(statistics.median([r["p95Ms"] for r in round_rows]), 2),
        "p99Ms": round(statistics.median([r["p99Ms"] for r in round_rows]), 2),
        "roundsP95Ms": [round(r["p95Ms"], 2) for r in round_rows],
        "samples": rounds * samples,
        "path": path,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="v3.2.0 只读接口性能采样")
    parser.add_argument("--base", default=os.environ.get("PERF_BASE", "http://127.0.0.1:8001"))
    parser.add_argument("--project-id", type=int,
                        default=int(os.environ.get("PERF_PROJECT_ID", "29")))
    parser.add_argument("--user", default=os.environ.get("PERF_USER", "qa-e2e-admin"))
    parser.add_argument("--rounds", type=int)
    parser.add_argument("--samples", type=int)
    parser.add_argument("--local", action="store_true", help="本机冒烟：用 localSamplesPerRound")
    parser.add_argument("--dataset-manifest", type=Path)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--timeout", type=float, default=float(os.environ.get("PERF_TIMEOUT", "30")))
    parser.add_argument("--tag", default="", help="报告备注（如本机冒烟/CI 固定机）")
    args = parser.parse_args()

    try:
        budget = json.loads(BUDGET_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"预算文件不可读：{BUDGET_FILE}（{exc}）")

    sampling = budget.get("sampling") or {}
    rounds = args.rounds if args.rounds is not None else int(sampling.get("rounds", 3))
    if args.samples is not None:
        samples = args.samples
    elif args.local:
        samples = int(sampling.get("localSamplesPerRound", 20))
    else:
        samples = int(sampling.get("samplesPerRound", 200))
    warmups = int(sampling.get("warmupRounds", 2))
    if rounds <= 0 or samples <= 0:
        fail("rounds / samples 必须为正整数")

    base = args.base.rstrip("/")
    token = login(base, args.user, args.project_id, args.timeout)

    scenarios = (budget.get("api") or {}).get("scenarios") or {}
    if not scenarios:
        fail("预算文件没有 api.scenarios")

    result = {}
    for name, spec in scenarios.items():
        path = str(spec["path"]).replace("{projectId}", str(args.project_id))
        print(f"PERF_SAMPLE {name} rounds={rounds} samples={samples} path={path}")
        result[name] = sample_scenario(base, token, args.project_id, path,
                                       rounds, samples, warmups, args.timeout)
        row = result[name]
        print(f"  {name:<14} p50={row['p50Ms']:.1f} p95={row['p95Ms']:.1f} "
              f"p99={row['p99Ms']:.1f} roundsP95={row['roundsP95Ms']}")

    dataset = {}
    if args.dataset_manifest:
        try:
            manifest = json.loads(args.dataset_manifest.read_text(encoding="utf-8"))
            dataset = {
                "scale": manifest.get("scale"),
                "seed": manifest.get("seed"),
                "contentHash": manifest.get("contentHash"),
                "counts": manifest.get("counts"),
            }
        except (OSError, json.JSONDecodeError) as exc:
            fail(f"数据集 manifest 不可读：{args.dataset_manifest}（{exc}）")
    else:
        dataset = budget.get("dataset") or {}

    report = {
        "version": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "environment": {
            "base": base,
            "projectId": args.project_id,
            "user": args.user,
            "rounds": rounds,
            "samplesPerRound": samples,
            "warmupRounds": warmups,
            "mode": "local-smoke" if args.local else "ci",
            "tag": args.tag,
        },
        "dataset": dataset,
        "api": result,
    }
    try:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
    except OSError as exc:
        fail(f"写报告失败：{args.out}（{exc}）")

    print(f"PERF_REPORT {args.out.resolve()}")
    print("PERF_RESULT PASS")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
