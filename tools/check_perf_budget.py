#!/usr/bin/env python3
"""EW32-01 · 性能预算门禁（API / 长列表 / 运行链）。

为什么需要它
------------
v3.2.0 的「快」必须是可判定的：没有预算文件、没有采样报告、没有越线即红，
性能优化就只是感觉。本工具把 `tools/perf-budget.json` 的阈值与
`tools/perf_sample.py` 产出的报告对账，越线返回 1。

覆盖范围与分工
--------------
  本工具：API 场景 p95/p99、长列表 fps、运行链时延/吞吐（有数据才判）。
  前端：入口 ≤300 KiB gzip、单路由块 ≤150 KiB gzip，由
        `tools/check_bundle_budget.py` 判（产物量级，不需要接口采样）。

报告缺失时的口径
----------------
默认 SKIP（退出 0）并显式打印原因，避免本机没起后端就把门禁变红；
CI 用 `--require` 把「缺报告」升级为失败，防止「忘了跑采样」被当成通过。

基线 diff
---------
`--baseline old-report.json` 时追加相对回退判定：p95 劣化 >10% 且绝对增量 >30ms
即红（与既有 `perf_baseline.py` 口径一致）。

用法
----
    python3 tools/check_perf_budget.py --report docs/evidence/v3.2.0/EW32-01/perf/perf-report.json
    python3 tools/check_perf_budget.py --self-test
    python3 tools/check_perf_budget.py --require        # CI：报告缺失即失败

退出码：0 = 通过/SKIP；1 = 越线；2 = 预算或报告本身不可读（疑似假绿）。
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BUDGET = ROOT / "tools" / "perf-budget.json"
DEFAULT_REPORT = ROOT / "docs" / "evidence" / "v3.2.0" / "EW32-01" / "perf" / "perf-report.json"


@dataclass
class Result:
    violations: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)
    error: str | None = None

    @property
    def ok(self) -> bool:
        return self.error is None and not self.violations


def load_json(path: Path, label: str) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"{label} 不存在：{path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"{label} 不是合法 JSON：{path}（{exc}）") from exc
    if not isinstance(data, dict):
        raise ValueError(f"{label} 顶层必须是对象：{path}")
    return data


def _num(row: dict, key: str) -> float | None:
    value = row.get(key)
    return float(value) if isinstance(value, (int, float)) else None


def evaluate(report: dict, budget: dict, baseline: dict | None = None) -> Result:
    r = Result()

    api_budget = (budget.get("api") or {}).get("scenarios") or {}
    api_report = report.get("api")
    if api_report is None:
        r.skipped.append("API 场景（报告无 api 段）")
    elif not isinstance(api_report, dict):
        r.violations.append("报告 api 段格式错误（必须是对象）")
    elif not api_report:
        # 报告存在但 api 为空：多半是采样脚本中途失败被写成了空报告，
        # 这种情况不能当 SKIP 放过去，否则「没采到数据」会被误读成「达标」。
        r.violations.append("报告 api 段为空：没有可用采样")
    else:
        for name, spec in api_budget.items():
            row = api_report.get(name)
            if not isinstance(row, dict):
                r.violations.append(f"API 场景缺采样：{name}（预算有定义但报告没有）")
                continue
            p95 = _num(row, "p95Ms")
            p99 = _num(row, "p99Ms")
            limit95 = float(spec["p95Ms"])
            limit99 = float(spec["p99Ms"])
            bad95 = p95 is None or p95 > limit95
            if p95 is None:
                r.violations.append(f"{name} 缺 p95Ms，无法判定（不允许空值过关）")
            elif bad95:
                r.violations.append(
                    f"{name} p95 {p95:.1f}ms > 预算 {limit95:.1f}ms")
            else:
                r.notes.append(f"{name} p95 {p95:.1f}ms ≤ {limit95:.1f}ms")
            if p99 is None:
                r.skipped.append(f"{name} 的 p99（报告未给出）")
            elif p99 > limit99:
                r.violations.append(
                    f"{name} p99 {p99:.1f}ms > 预算 {limit99:.1f}ms")

    long_budget = budget.get("longList") or {}
    long_report = report.get("longList")
    if not isinstance(long_report, dict) or not long_report:
        r.notes.append("长列表帧率未在报告中出现（浏览器项由 EW32-06/CI 覆盖）")
    else:
        fps = _num(long_report, "fps")
        rows = long_report.get("rows")
        min_fps = float(long_budget.get("minFps", 50))
        if fps is None:
            r.violations.append("长列表缺 fps，无法判定")
        elif fps < min_fps:
            r.violations.append(
                f"长列表 fps {fps:.1f} < 预算 {min_fps:.0f}（rows={rows}）")
        else:
            r.notes.append(f"长列表 fps {fps:.1f} ≥ {min_fps:.0f}（rows={rows}）")

    chain_budget = budget.get("runChain") or {}
    chain_report = report.get("runChain")
    if not isinstance(chain_report, dict) or not chain_report:
        r.notes.append("运行链未在报告中出现（写接口压测由 EW32-10 覆盖）")
    else:
        create = _num(chain_report, "createP95Ms")
        limit_create = float(chain_budget.get("createP95Ms", 200))
        if create is not None:
            if create > limit_create:
                r.violations.append(
                    f"运行创建 p95 {create:.1f}ms > 预算 {limit_create:.0f}ms")
            else:
                r.notes.append(f"运行创建 p95 {create:.1f}ms ≤ {limit_create:.0f}ms")
        throughput = _num(chain_report, "logRowsPerSecond")
        limit_rows = float(chain_budget.get("logRowsPerSecond", 5000))
        if throughput is not None:
            if throughput < limit_rows:
                r.violations.append(
                    f"日志吞吐 {throughput:.0f} 行/s < 预算 {limit_rows:.0f}")
            else:
                r.notes.append(f"日志吞吐 {throughput:.0f} 行/s ≥ {limit_rows:.0f}")

    # 前端预算由 check_bundle_budget 负责；报告里若自带数据，这里做交叉核对。
    fe_budget = budget.get("frontend") or {}
    fe_report = report.get("frontend")
    if isinstance(fe_report, dict) and fe_report:
        entry = fe_report.get("entryGzipBytes")
        route = fe_report.get("maxRouteGzipBytes")
        if isinstance(entry, (int, float)) and entry > float(fe_budget["entryGzipBytes"]):
            r.violations.append(
                f"前端入口 gzip {entry} B > 预算 {fe_budget['entryGzipBytes']} B")
        if isinstance(route, (int, float)) and route > float(fe_budget["routeGzipBytes"]):
            r.violations.append(
                f"单路由块 gzip {route} B > 预算 {fe_budget['routeGzipBytes']} B")
    else:
        r.notes.append("前端包体由 tools/check_bundle_budget.py 判（本报告不含 frontend 段）")

    if baseline:
        base_api = baseline.get("api") or {}
        reg = (budget.get("api") or {}).get("regression") or {}
        pct = float(reg.get("pct", 0.10))
        floor = float(reg.get("floorMs", 30))
        for name, row in (api_report or {}).items():
            base = base_api.get(name)
            if not isinstance(base, dict):
                continue
            cur95 = _num(row, "p95Ms")
            base95 = _num(base, "p95Ms")
            if cur95 is None or base95 is None or base95 <= 0:
                continue
            delta = (cur95 - base95) / base95
            if delta > pct and (cur95 - base95) > floor:
                r.violations.append(
                    f"{name} 相对回退 {delta * 100:+.1f}%（{base95:.1f}→{cur95:.1f}ms，"
                    f"阈值 +{pct * 100:.0f}% 且 >{floor:.0f}ms）")

    return r


def _fixture(budget: dict, p95: float, p99: float,
             include_api: bool = True) -> dict:
    scenario = next(iter(budget["api"]["scenarios"]))
    report = {
        "version": 1,
        "api": {scenario: {"p50Ms": p95 * 0.6, "p95Ms": p95, "p99Ms": p99}}
        if include_api else {},
    }
    return report


def self_test() -> int:
    budget = {
        "api": {
            "regression": {"pct": 0.10, "floorMs": 30},
            "scenarios": {"cases_list": {"p95Ms": 150, "p99Ms": 300}},
        },
        "frontend": {"entryGzipBytes": 307200, "routeGzipBytes": 153600},
        "longList": {"minFps": 50},
        "runChain": {"createP95Ms": 200, "logRowsPerSecond": 5000},
    }
    cases = [
        ("健康", _fixture(budget, 100, 220), False),
        ("p95 越线", _fixture(budget, 160, 280), True),
        ("p99 越线", _fixture(budget, 90, 320), True),
        ("场景缺失", _fixture(budget, 100, 220, include_api=False), True),
    ]
    failures = 0
    for label, report, expect_red in cases:
        result = evaluate(report, budget)
        red = bool(result.violations)
        if red == expect_red:
            detail = result.violations[0] if result.violations else "无越线"
            print(f"[SELFTEST] 通过：{label} → {'FAIL' if red else 'PASS'}（{detail}）")
        else:
            print(f"[FAIL] 自检失准：{label} 期望 {'FAIL' if expect_red else 'PASS'}，"
                  f"实际 {'FAIL' if red else 'PASS'}：{result.violations}", file=sys.stderr)
            failures += 1

    # 长列表 / 运行链各一条，证明非 API 段也能判红
    extra = [
        ("长列表低帧率", {"longList": {"rows": 10000, "fps": 30}}, "fps", True),
        ("日志吞吐不足", {"runChain": {"logRowsPerSecond": 3000}}, "吞吐", True),
        ("长列表达标", {"longList": {"rows": 10000, "fps": 55}}, "fps", False),
    ]
    for label, report, keyword, expect_red in extra:
        result = evaluate(report, budget)
        matched = [v for v in result.violations if keyword in v]
        red = bool(matched)
        if red == expect_red:
            print(f"[SELFTEST] 通过：{label} → {'FAIL' if red else 'PASS'}")
        else:
            print(f"[FAIL] 自检失准：{label}（violations={result.violations}）",
                  file=sys.stderr)
            failures += 1

    # 相对回退：基线 100ms、当前 140ms（+40% 且 +40ms）必须红
    baseline = {"api": {"cases_list": {"p95Ms": 100}}}
    result = evaluate({"api": {"cases_list": {"p95Ms": 140, "p99Ms": 200}}}, budget, baseline)
    if any("相对回退" in v for v in result.violations):
        print("[SELFTEST] 通过：相对回退 +40% 被判红")
    else:
        print(f"[FAIL] 自检失准：相对回退未判红（{result.violations}）", file=sys.stderr)
        failures += 1

    if failures:
        print(f"[FAIL] 自检失败：{failures} 个用例未按预期工作", file=sys.stderr)
        return 1
    print("[SELFTEST] 通过：p95/p99/缺场景/长列表/吞吐/相对回退均可判红")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="v3.2.0 性能预算门禁")
    parser.add_argument("--budget", type=Path, default=DEFAULT_BUDGET)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--baseline", type=Path, help="历史报告，用于相对回退判定")
    parser.add_argument("--require", action="store_true", help="报告缺失即失败（CI）")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    try:
        budget = load_json(args.budget, "预算文件")
    except (FileNotFoundError, ValueError) as exc:
        print(f"[FAIL] 预算不可读：{exc}", file=sys.stderr)
        return 2

    for key in ("api", "frontend", "longList", "runChain"):
        if key not in budget:
            print(f"[FAIL] 预算缺少段落：{key}（{args.budget}）", file=sys.stderr)
            return 2

    if not args.report.is_file():
        message = f"未找到性能报告：{args.report}（先跑 tools/perf_sample.py）"
        if args.require:
            print(f"[FAIL] {message}（--require 模式）", file=sys.stderr)
            return 1
        print(f"[SKIP] {message}；本机冒烟口径不判红")
        return 0

    try:
        report = load_json(args.report, "性能报告")
    except ValueError as exc:
        print(f"[FAIL] 报告不可读：{exc}", file=sys.stderr)
        return 2

    baseline = None
    if args.baseline:
        try:
            baseline = load_json(args.baseline, "基线报告")
        except (FileNotFoundError, ValueError) as exc:
            print(f"[FAIL] 基线不可读：{exc}", file=sys.stderr)
            return 2

    env = report.get("environment") or {}
    print(f"PERF_BUDGET report={args.report}")
    print(f"  环境 base={env.get('base')} project={env.get('projectId')} "
          f"rounds={env.get('rounds')} samplesPerRound={env.get('samplesPerRound')} "
          f"mode={env.get('mode')}")
    dataset = report.get("dataset") or {}
    print(f"  数据集 scale={dataset.get('scale')} seed={dataset.get('seed')} "
          f"contentHash={str(dataset.get('contentHash', ''))[:16]}…")

    result = evaluate(report, budget, baseline)
    for note in result.notes:
        print(f"  [OK]   {note}")
    for skip in result.skipped:
        print(f"  [SKIP] {skip}")
    for violation in result.violations:
        print(f"  [FAIL] {violation}")

    if result.violations:
        print(f"PERF_RESULT FAIL({len(result.violations)})")
        return 1
    print("PERF_RESULT PASS")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
