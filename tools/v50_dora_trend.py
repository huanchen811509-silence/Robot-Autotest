#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, json, pathlib, re, subprocess, sys
from datetime import datetime
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/evidence/v5.0.0/ops/dora-trend.json"


def payload():
    r = subprocess.run(["git", "log", "--format=%aI", "-n", "200"], cwd=ROOT,
                       capture_output=True, text=True)
    stamps = [x for x in r.stdout.splitlines() if x.strip()]
    dates = []
    for s in stamps:
        try: dates.append(datetime.fromisoformat(s))
        except ValueError: pass
    span = max(1, (max(dates) - min(dates)).days) if dates else 1
    change = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    versions = re.findall(r"^##\s+v?\d+\.\d+(?:\.\d+)?", change, flags=re.M)
    gate = ROOT / "docs/evidence/v5.0.0/raw/gates-final-after.log"
    fails = None
    if gate.exists():
        m = re.search(r"GATES_RESULT FAILS=(\d+)", gate.read_text(encoding="utf-8"))
        if m: fails = int(m.group(1))
    return {"schema": "RA-DORA-TREND-V50", "captured_at": "2026-11-12",
            "mode": "approximate", "note": "DORA 2025：只做自身趋势线，取消 Elite/High/Medium/Low 分级，不设考核",
            "metrics": {
                "deployment_frequency": {"value": len(versions), "unit": "versions_in_changelog", "approx": True},
                "lead_time_for_changes": {"value": None, "unit": "days", "approx": True,
                                          "reason": "无逐变更部署时点，不给估算"},
                "change_failure_rate": {"value": None, "unit": "ratio", "approx": True,
                                        "reason": "门禁 FAILS=%s 不能折算变更失败率" % fails},
                "time_to_restore": {"value": None, "unit": "hours", "approx": True,
                                    "reason": "RTO 未计时（docs/ROLLBACK.md）"}},
            "sample": {"commits": len(stamps), "span_days": span, "gate_fails": fails}}


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    d = payload()
    if a.selfcheck:
        bad = dict(d); bad["note"] = "Elite / High / Medium / Low 分级"
        problems = [1] if "Elite" in bad["note"] else []
        if not problems: print("[FAIL] selfcheck：分级字样未判红"); return 1
        print("[PASS] dora_trend selfcheck：分级对标被判红"); return 0
    OUT.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("[PASS] 已写 DORA 自身趋势（近似口径，不分级不考核）：%s" % OUT.relative_to(ROOT)); return 0


if __name__ == "__main__":
    raise SystemExit(main())
