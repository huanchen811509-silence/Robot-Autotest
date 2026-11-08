#!/usr/bin/env python3
"""W4 · 只读接口性能基线（门禁第 17 步）。

五条只读场景（GET，不写库、不生成文件）：

  1. audit_list        审计列表页（keyset 游标口径）
  2. reports_list      报告列表（分页）
  3. report_detail     报告详情
  4. report_trend      用例趋势（带 caseKey）
  5. failure_clusters  失败聚类计算

判据：每个场景取 N 次采样的 P50 / P95。与 ``docs/evidence/perf-baseline.json``
比对，**P95 劣化 >10% 且绝对增量 >30ms** 即判红。30ms 是这台 WSL 宿主机的
调度噪声下限（同机并行跑 MySQL/Chromium/Java，单个 GET 的 P95 会从 18ms 抖到
34ms），不加绝对下限时门禁每周都会因邻居负载假红；真实回归（连接池耗尽、
N+1 查询、全表扫描）通常是把 P95 抬高数十至数百毫秒。

首次运行（基线文件不存在）或显式 ``--update`` 时写入基线并 PASS；
之后门禁只读基线、不复写，防止"跑一次就抬高阈值"。

环境：
  PERF_BASE         默认 http://127.0.0.1:8001（统一入口，代理到 :8081）
  PERF_PROJECT_ID   默认 29
  PERF_USER         默认 qa-e2e-admin
  PERF_SAMPLES      默认 5
  QA_E2E_PASSWORD   优先环境变量，其次 ~/.config/robot-autotest/env

用法：
  python3 tools/perf_baseline.py --update   # 重建基线
  python3 tools/perf_baseline.py            # 门禁口径：比对基线，0 = PASS
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE_FILE = Path(os.environ.get("PERF_BASELINE_FILE", ROOT / "docs/evidence/perf-baseline.json"))
BASE = os.environ.get("PERF_BASE", "http://127.0.0.1:8001").rstrip("/")
PROJECT_ID = int(os.environ.get("PERF_PROJECT_ID", "29"))
USER = os.environ.get("PERF_USER", "qa-e2e-admin")
SAMPLES = int(os.environ.get("PERF_SAMPLES", "9"))
ROUNDS = int(os.environ.get("PERF_ROUNDS", "3"))
REGRESS_PCT = float(os.environ.get("PERF_REGRESS_PCT", "0.10"))
FLOOR_MS = float(os.environ.get("PERF_FLOOR_MS", "30"))
TIMEOUT = float(os.environ.get("PERF_TIMEOUT", "30"))
TOKEN: str | None = None

# 绕过 WSL 里的 Windows 代理：127.0.0.1 请求不应该走外部代理。
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def env_value(key: str) -> str | None:
    """环境变量优先；其次读 WSL 的 ~/.config/robot-autotest/env（不入仓）。"""
    if os.environ.get(key):
        return os.environ[key]
    path = Path.home() / ".config" / "robot-autotest" / "env"
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            name, value = stripped.split("=", 1)
            if name.strip() == key:
                return value.strip()
    except OSError:
        return None
    return None


def fail(message: str, code: int = 2):
    print(f"PERF_ERROR {message}", file=sys.stderr)
    sys.exit(code)


def request(path: str, token: str | None = None, method: str = "GET",
            body: dict | None = None) -> tuple[int, dict | list | None, float]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
        headers["X-Project-Id"] = str(PROJECT_ID)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"{BASE}{path}", data=data, headers=headers, method=method)
    started = time.perf_counter()
    try:
        with _OPENER.open(req, timeout=TIMEOUT) as resp:
            elapsed_ms = (time.perf_counter() - started) * 1000
            raw = resp.read()
            payload = json.loads(raw) if raw else None
            return resp.status, payload, elapsed_ms
    except urllib.error.HTTPError as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000
        detail = exc.read()[:300].decode("utf-8", "replace")
        fail(f"{path} HTTP {exc.code}: {detail}")
    except urllib.error.URLError as exc:
        fail(f"无法连接 {BASE}（{exc.reason}）——门禁第 17 步需要 :8001 可用")
    raise AssertionError("unreachable")


def login() -> str:
    password = env_value("QA_E2E_PASSWORD")
    if not password:
        fail("缺少 QA_E2E_PASSWORD（~/.config/robot-autotest/env）")
    status, payload, _ = request("/api/auth/login", method="POST",
                                 body={"username": USER, "password": password})
    token = ((payload or {}).get("data") or {}).get("token") if status == 200 else None
    if not token:
        fail(f"登录失败 HTTP {status}: {json.dumps(payload, ensure_ascii=False)[:200]}")
    return token


def first_text(value, *keys):
    if isinstance(value, dict):
        for key in keys:
            if value.get(key):
                return value[key]
    return None


def discover_fixture(token: str) -> tuple[str, str]:
    """找一条有真实用例数据的 run，并从套件树里取第一个 caseKey（趋势接口需要）。

    报告按 run 倒序返回，最新的可能是空套件（E2E / 冒烟夹具每跑一次都会新增一条，
    单测套件本身也会造 run）。因此不能只扫前 N 条固定上限——2026-11-08 实测：
    项目 29 前 12 条全无 caseKey，第一条带 caseKey 的排在第 13 位，固定「前 10 条」
    会让门禁假红。这里改为按列表顺序全量扫描（上限由 PERF_SCAN_LIMIT 控制，
    默认 50 = 列表页 size），并打印扫描条数与被选中位置，便于事后核对。
    """
    status, payload, _ = request(
        f"/api/reports?projectId={PROJECT_ID}&page=1&size=50", token)
    data = (payload or {}).get("data") or {}
    items = data.get("items") if isinstance(data, dict) else None
    if not items:
        fail("报告列表为空：性能基线需要至少 1 条已入库报告（先跑一次用例）")

    limit = min(int(os.environ.get("PERF_SCAN_LIMIT", "50")), len(items))
    for index, item in enumerate(items[:limit], start=1):
        run_id = first_text(item, "runId", "run_id")
        if not run_id:
            continue
        status, payload, _ = request(f"/api/reports/{run_id}/tree?projectId={PROJECT_ID}", token)
        tree = ((payload or {}).get("data") or {})
        case_key = _first_case_key(tree)
        if case_key:
            print(f"PERF_FIXTURE run={run_id} caseKey={case_key} "
                  f"扫到第 {index}/{limit} 条命中（列表共 {len(items)} 条）")
            return str(run_id), case_key
    fail(f"扫描上限内（{limit} 条，列表共 {len(items)} 条）报告的套件树都没有非空 "
         f"caseKey（趋势场景无从取样）：需要一条带用例键的真实 run")


def _first_case_key(node) -> str | None:
    if isinstance(node, dict):
        for key in ("caseKey", "case_key"):
            if node.get(key):
                return str(node[key])
        for key in ("cases", "children", "suites", "tests"):
            value = node.get(key)
            if value:
                found = _first_case_key(value)
                if found:
                    return found
    elif isinstance(node, list):
        for item in node:
            found = _first_case_key(item)
            if found:
                return found
    return None


def pct(values: list[float], q: float) -> float:
    """线性插值分位数：9 个采样时 P95 不会被单个毛刺直接拉成最大值。"""
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    pos = q * (len(ordered) - 1)
    lo = int(pos)
    hi = min(lo + 1, len(ordered) - 1)
    frac = pos - lo
    return ordered[lo] + (ordered[hi] - ordered[lo]) * frac


def measure(scenarios: list[tuple[str, str]]) -> dict:
    """每场景跑 ROUNDS 轮、每轮 SAMPLES 次；取各轮 P95 的最小值。

    这台 WSL 宿主机的 load average 常在 3 以上（同一台机上还有 MySQL/浏览器/构建任务），
    单轮采样会被邻居负载抬高几十毫秒。取"最好一轮"既保留回归灵敏度（真实劣化会
    同时抬高所有轮次），又不会被偶发调度抖动刷成假红。
    """
    result = {}
    for name, path in scenarios:
        for _ in range(2):  # 预热：JIT / 连接池 / 页缓存
            request(path, TOKEN)
        round_p95 = []
        round_times = []
        for _ in range(ROUNDS):
            times = []
            for _ in range(SAMPLES):
                status, payload, elapsed = request(path, TOKEN)
                if status != 200 or not (payload or {}).get("success"):
                    fail(f"{name} 返回异常 HTTP {status}: "
                         f"{json.dumps(payload, ensure_ascii=False)[:200]}")
                times.append(elapsed)
            round_times.append(times)
            round_p95.append(pct(times, 0.95))
        best = round_times[round_p95.index(min(round_p95))]
        result[name] = {
            "p50Ms": round(pct(best, 0.50), 2),
            "p95Ms": round(min(round_p95), 2),
            "roundsP95Ms": [round(v, 2) for v in round_p95],
        }
    return result


def compare(current: dict, baseline: dict) -> int:
    bad = 0
    print(f"PERF_BASE {BASE} project={PROJECT_ID} samples={SAMPLES} "
          f"limit=+{REGRESS_PCT * 100:.0f}%/floor={FLOOR_MS:.0f}ms")
    for name, cur in current.items():
        base = (baseline.get("scenarios") or {}).get(name)
        if not base:
            print(f"  MISS  {name} 基线缺项，按 --update 重建")
            bad += 1
            continue
        base_p95 = float(base["p95Ms"])
        cur_p95 = float(cur["p95Ms"])
        delta = (cur_p95 - base_p95) / base_p95 if base_p95 > 0 else 0.0
        breached = delta > REGRESS_PCT and (cur_p95 - base_p95) > FLOOR_MS
        ok = "PASS" if not breached else "FAIL"
        print(f"  {ok:4}  {name:17} base p50/p95={base['p50Ms']:.1f}/{base_p95:.1f}ms "
              f"cur={cur['p50Ms']:.1f}/{cur_p95:.1f}ms delta={delta * 100:+.1f}%")
        if breached:
            bad += 1
    print(f"PERF_RESULT {'PASS' if bad == 0 else f'FAIL({bad})'}")
    return bad


def main() -> int:
    parser = argparse.ArgumentParser(description="W4 只读接口性能基线")
    parser.add_argument("--update", action="store_true", help="重建基线文件（默认只比对）")
    args = parser.parse_args()

    global TOKEN
    TOKEN = login()
    run_id, case_key = discover_fixture(TOKEN)

    scenarios = [
        ("audit_list", f"/api/audit?projectId={PROJECT_ID}&page=1&size=100"),
        ("reports_list", f"/api/reports?projectId={PROJECT_ID}&page=1&size=50"),
        ("report_detail", f"/api/reports/{run_id}?projectId={PROJECT_ID}"),
        ("report_trend", f"/api/reports/{run_id}/trend?projectId={PROJECT_ID}"
                         f"&caseKey={urllib.parse.quote(case_key)}&limit=20"),
        ("failure_clusters", f"/api/failure-clusters?projectId={PROJECT_ID}"
                             f"&runId={urllib.parse.quote(run_id)}"),
    ]
    current = measure(scenarios)

    baseline = None
    if BASELINE_FILE.exists():
        try:
            baseline = json.loads(BASELINE_FILE.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            fail(f"基线文件不是合法 JSON: {BASELINE_FILE}（{exc}）")

    if baseline is None or args.update:
        BASELINE_FILE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE_FILE.write_text(json.dumps({
            "version": 1,
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "environment": {"base": BASE, "projectId": PROJECT_ID, "samples": SAMPLES,
                            "runId": run_id, "caseKey": case_key},
            "scenarios": {name: {"p50Ms": row["p50Ms"], "p95Ms": row["p95Ms"]}
                          for name, row in current.items()},
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        action = "重建" if args.update else "首次写入"
        print(f"PERF_BASELINE {action}: {BASELINE_FILE}")
        print("PERF_RESULT PASS")
        return 0

    return 1 if compare(current, baseline) else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
