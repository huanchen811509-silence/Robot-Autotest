#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, pathlib, re, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
F = ROOT / "docs/release/v3.5.0/FEATURE-FLAGS.md"
REQUIRED = {"V50_RUM_ENABLED": "false", "V50_ALERT_NOTIFY_ENABLED": "false",
            "V50_AI_LIVE_EVAL_ENABLED": "false"}


def check(text):
    bad = []
    for k, v in REQUIRED.items():
        m = re.search(r"^\| `%s` \| `([^`]+)` \|" % re.escape(k), text, re.M)
        if not m: bad.append("缺登记: " + k); continue
        if m.group(1) != v: bad.append("%s 默认值应为 %s，实为 %s" % (k, v, m.group(1)))
    if "机检类检查" not in text: bad.append("缺机检常开说明")
    return bad


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if not F.exists(): print("[FAIL] 读不到 FEATURE-FLAGS.md"); return 2
    t = F.read_text(encoding="utf-8")
    if a.selfcheck:
        broken = t.replace("| `V50_RUM_ENABLED` | `false` |", "| `V50_RUM_ENABLED` | `true` |")
        if not check(broken): print("[FAIL] selfcheck：开关改 true 未判红"); return 1
        if check(t): print("[FAIL] selfcheck：干净文件不应判红"); return 1
        print("[PASS] flags selfcheck：改默认判红 / 干净判绿"); return 0
    bad = check(t)
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] 3 个 v5.0 能力开关均登记且默认 false（D5/D6）"); return 0


if __name__ == "__main__":
    raise SystemExit(main())
