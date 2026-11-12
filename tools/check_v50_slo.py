#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
F = ROOT / "docs/OPS-MANUAL.md"
KEYS = ["`:8001` 入口", "`:8081` 后端", "执行链", "报告生成", "错误预算", ":8081"]


def check(text):
    return ["缺 SLO 要点: " + k for k in KEYS if k not in text] + (
        [] if "不外接任何告警通知通道" in text else ["缺 D5 不外接声明"])


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if not F.exists(): print("[FAIL] 读不到 OPS-MANUAL.md"); return 2
    t = F.read_text(encoding="utf-8")
    if a.selfcheck:
        if not check(""): print("[FAIL] selfcheck：空文档未判红"); return 1
        if check(t): print("[FAIL] selfcheck：完整文档不应判红"); return 1
        print("[PASS] slo selfcheck：缺规则判红 / 完整判绿"); return 0
    bad = check(t)
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] 四类 SLI/SLO 与错误预算规则齐、D5 不外接通道"); return 0


if __name__ == "__main__":
    raise SystemExit(main())
