#!/usr/bin/env python3
"""W58-10 · 10000 行长列表滚动帧率判据（读真实量测，不编数字）。

为什么要有这一层
----------------
`scripts/perf-longlist.mjs` 只负责采数（CDP + rAF，五轮原始读数落 `longlist-raw.json`）。
「达标没有、算不算样本」必须由**另一个能判红的机检**来说，否则采集脚本自己说自己合格
等于自证。这里就是那个判红的地方：

  · 有效轮次 < 3 → **退出 2，不产出结论**（不写 PASS 也不写 FAIL：
    「没采到」和「不达标」是两件事，混成一个字会让下一个人搞不清该修代码还是补采样）
  · 一帧不落地的轮次不算样本：`scrollable=false` 或 `scrolledPx <= 1000` 一律剔除，
    否则「什么都没滚动」会给出漂亮的 60fps
  · 达标线读 `tools/perf-budget.json` 的 `longList.minFps`，不在这里写死第二份 50

双口径（v5.8.0 大师拍板）
-------------------------
产品把每页硬顶在 100 行（`PageQuery.MAX_SIZE = 100`），「10000 行同屏」在产品里到不了。
所以：

  product  10000 条数据集 + 每页 100 行（产品上限）的真页面 ← **status 只由它决定**
  harness  一次性 HTML 渲染 10000 行 ← 补充基线，只回答「浏览器扛不扛得住」

harness 判定单独记在 `harness.verdict` 里，**不参与** `status`：
它不是产品路径，拿它判产品红等于用体温计测血压。

用法
----
    node scripts/perf-longlist.mjs --rounds 5 --seconds 3 \
         --out docs/evidence/v5.8.0/perf/longlist-raw.json
    python3 tools/perf_longlist.py                 # 判据 + 落两份证据
    python3 tools/perf_longlist.py --selfcheck     # 负向自检（注入反例必须判红）

退出码：0 = PASS；1 = 不达标（FAIL）；2 = 证据不足 / 读不到 / 参数错。
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RAW = ROOT / "docs/evidence/v5.8.0/perf/longlist-raw.json"
DEFAULT_OUT = ROOT / "docs/evidence/v5.8.0/perf/perf_longlist.json"
DEFAULT_REPORT = ROOT / "docs/evidence/v5.8.0/perf/perf-report.json"
BUDGET = ROOT / "tools/perf-budget.json"

MIN_VALID_ROUNDS = 3
MIN_SCROLLED_PX = 1000


def budget_min_fps() -> float:
    try:
        data = json.loads(BUDGET.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"[FAIL] 读不到预算文件：{BUDGET}（{exc}）", file=sys.stderr)
        raise SystemExit(2)
    value = (data.get("longList") or {}).get("minFps")
    if not isinstance(value, (int, float)):
        print("[FAIL] perf-budget.json 的 longList.minFps 缺失或非数字", file=sys.stderr)
        raise SystemExit(2)
    return float(value)


def valid_rounds(scope: dict | None) -> list[dict]:
    """挑出「真的滚动了」的轮次。空转的 rAF 也是 60fps，不剔掉就是自欺。"""
    if not isinstance(scope, dict):
        return []
    rows = scope.get("rounds") or []
    return [
        row for row in rows
        if isinstance(row, dict)
        and row.get("scrollable") is True
        and isinstance(row.get("fps"), (int, float))
        and isinstance(row.get("scrolledPx"), (int, float))
        and row["scrolledPx"] > MIN_SCROLLED_PX
    ]


def summarize(scope: dict | None, min_fps: float, require_rounds: bool = True) -> dict:
    """把一侧口径折算成 {validRounds, minFps, p50, p95, status, problems}。"""
    valid = valid_rounds(scope)
    problems: list[str] = []
    if not valid:
        return {"validRounds": 0, "minFps": None, "p50": None, "p95": None,
                "status": "FAIL" if not require_rounds else "UNJUDGED",
                "problems": [f"有效轮次 0 < 要求 {MIN_VALID_ROUNDS}"]}
    fps = [float(row["fps"]) for row in valid]
    worst = min(fps)
    # 样本不足就不下结论。之前这里只往 problems 里记一句、status 照算，
    # 结果「2 轮 60fps」能判成 PASS——自检把这个洞抓出来了，别再放回去。
    if require_rounds and len(valid) < MIN_VALID_ROUNDS:
        return {
            "validRounds": len(valid), "minFps": round(worst, 1),
            "p50": round(statistics.median(fps), 1), "p95": round(max(fps), 1),
            "status": "UNJUDGED",
            "problems": [f"有效轮次 {len(valid)} < 要求 {MIN_VALID_ROUNDS}"],
        }
    status = "PASS" if worst >= min_fps else "FAIL"
    if worst < min_fps:
        problems.append(f"minFps {worst:.1f} < 预算 {min_fps:.0f}")
    return {
        "validRounds": len(valid),
        "minFps": round(worst, 1),
        "p50": round(statistics.median(fps), 1),
        "p95": round(max(fps), 1),
        "status": status,
        "problems": problems,
        "rows": valid[0].get("rows"),
        "scrolledPx": [int(row["scrolledPx"]) for row in valid],
    }


def selfcheck() -> int:
    """负向自检：这五组反例如果有一组没被判红，说明判据本身是假的。"""
    min_fps = 50.0

    def scope(*rounds):
        return {"rows": 100, "rounds": list(rounds)}

    def ok_round(fps, scrolled=30000, scrollable=True):
        return {"fps": fps, "scrollable": scrollable, "scrolledPx": scrolled, "rows": 100}

    cases = [
        ("0 轮冒充达标", scope(), False),
        ("2 轮不足样本", scope(ok_round(60), ok_round(60)), False),
        ("空转轮次（scrollable=false）不算样本", scope(
            ok_round(60, scrollable=False), ok_round(60, scrollable=False),
            ok_round(60, scrollable=False), ok_round(60, scrollable=False)), False),
        ("滚动像素不足不算样本", scope(
            ok_round(60, scrolled=10), ok_round(60, scrolled=10),
            ok_round(60, scrolled=10), ok_round(60, scrolled=10)), False),
        ("3 轮全 40fps 必须判红", scope(ok_round(40), ok_round(40), ok_round(40)), False),
        ("3 轮全 60fps 必须判绿", scope(ok_round(60), ok_round(60), ok_round(60)), True),
    ]
    failed = 0
    for name, payload, should_pass in cases:
        verdict = summarize(payload, min_fps)
        passed = verdict["status"] == "PASS"
        if passed != should_pass:
            print(f"[FAIL] selfcheck 反例未生效：{name} → {verdict['status']}")
            failed += 1

    # 双口径隔离：harness 不达标不许把 product 的 PASS 拖成 FAIL，反之亦然。
    product = summarize(scope(ok_round(60), ok_round(60), ok_round(60)), min_fps)
    harness = summarize(scope(ok_round(20), ok_round(20), ok_round(20)), min_fps,
                        require_rounds=False)
    if product["status"] != "PASS" or harness["status"] != "FAIL":
        print("[FAIL] selfcheck 双口径串味：harness 判定污染了 product")
        failed += 1

    if failed:
        return 1
    print("[PASS] perf_longlist selfcheck：0 轮/少样本/空转轮次/不达标 4 类反例全部判红")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="W58-10 长列表滚动帧率判据")
    parser.add_argument("--from", dest="source", type=Path, default=DEFAULT_RAW)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--selfcheck", action="store_true")
    args = parser.parse_args()

    if args.selfcheck:
        return selfcheck()

    if not args.source.is_file():
        print(f"[FAIL] 缺原始量测证据：{args.source}\n"
              "       先跑 node scripts/perf-longlist.mjs --rounds 5 --seconds 3 "
              f"--out {args.source}", file=sys.stderr)
        return 2
    try:
        raw = json.loads(args.source.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"[FAIL] 原始证据不是合法 JSON：{exc}", file=sys.stderr)
        return 2

    min_fps = budget_min_fps()
    product = summarize(raw.get("product"), min_fps)
    if product["status"] == "UNJUDGED":
        print(f"[FAIL] 产品口径有效轮次不足（{product['validRounds']}/{MIN_VALID_ROUNDS}）："
              f"{'；'.join(product['problems'])}。样本不足不产出结论，请重跑量测。",
              file=sys.stderr)
        return 2

    harness = summarize(raw.get("harness"), min_fps, require_rounds=False)
    scenario = raw.get("scenario") or {}
    environment = raw.get("environment") or {}

    reason = (f"产品口径（数据集 {scenario.get('datasetRows')} 条 / 每页 "
              f"{scenario.get('pageSize')} 行，产品分页上限）minFps {product['minFps']}"
              f" {'≥' if product['status'] == 'PASS' else '<'} 预算 {min_fps:.0f}")
    if harness.get("minFps") is not None:
        reason += (f"；harness 口径（{harness.get('rows')} 行同屏，非产品页面）"
                   f"minFps {harness['minFps']}（{harness['status']}）")

    payload = {
        "metric": "长列表滚动 minFps（双口径：产品每页 100 行 / 独立 10000 行）",
        "target": f"minFps >= {min_fps:.0f}",
        "status": product["status"],
        "mode": "lab",
        "capturedAt": raw.get("capturedAt") or datetime.now(timezone.utc).isoformat(),
        "sampleSize": product["validRounds"],
        "machine": environment.get("machine"),
        "rounds": len((raw.get("product") or {}).get("rounds") or []),
        "min_fps": product["minFps"],
        "p50": product["p50"],
        "p95": product["p95"],
        "product": {
            "scope": (raw.get("product") or {}).get("scope"),
            "rows": (raw.get("product") or {}).get("rows"),
            "datasetRows": scenario.get("datasetRows"),
            "pageSize": scenario.get("pageSize"),
            "minFps": product["minFps"],
            "validRounds": product["validRounds"],
            "scrolledPx": product["scrolledPx"],
            "rounds": (raw.get("product") or {}).get("rounds"),
        },
        "harness": {
            "scope": (raw.get("harness") or {}).get("scope"),
            "rows": harness.get("rows"),
            "minFps": harness.get("minFps"),
            "verdict": harness.get("status"),
            "validRounds": harness["validRounds"],
            "rounds": (raw.get("harness") or {}).get("rounds"),
        },
        "reason": reason,
        "note": ("status 只由产品口径决定；harness 是补充基线（一次性 HTML，非产品页面），"
                 "不参与判定。产品分页上限 100 行由 PageQuery.MAX_SIZE 决定，"
                 "「10000 行同屏」在产品形态下不可达——口径偏差见 "
                 "docs/release/v5.8.0/DEV-PLAN.md D-58-08，harness 不达标见 D-58-09"),
        "source": str(args.source.relative_to(ROOT) if args.source.is_relative_to(ROOT) else args.source),
        "budget": str(BUDGET.relative_to(ROOT)),
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # check_perf_budget 只认 report.longList.fps / rows，所以另落一份最小报告；
    # 刻意不覆盖 v3.2.0 的 perf-report.json（那是 API 采样报告，属别的版本的证据）。
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": payload["source"],
        "longList": {
            "rows": payload["product"]["rows"],
            "datasetRows": payload["product"]["datasetRows"],
            "fps": payload["min_fps"],
            "rounds": payload["sampleSize"],
            "scope": payload["product"]["scope"],
        },
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"PRODUCT rows={payload['product']['rows']} "
          f"dataset={payload['product']['datasetRows']} "
          f"min_fps={payload['min_fps']} p50={payload['p50']} rounds={payload['sampleSize']}")
    print(f"HARNESS rows={payload['harness']['rows']} min_fps={payload['harness']['minFps']} "
          f"verdict={payload['harness']['verdict']}（不参与 status）")
    print(f"REASON {reason}")
    print(f"EVIDENCE {payload['source']} → {args.out} / {args.report}")
    for problem in product["problems"]:
        print(f"[FAIL] 产品口径：{problem}")
    print(f"PERF_LONGLIST_STATUS {payload['status']}")
    return 0 if payload["status"] == "PASS" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
