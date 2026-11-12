#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, pathlib, re, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "frontend/src/config/resultStates.ts"
LOCALES = ROOT / "frontend/src/locales"
EXPECT = {"PASS", "PARTIAL", "NOT_RUN", "FAIL", "BLOCKED", "DEGRADED"}


def check(text):
    # 只扫字典定义体：注释里写「不新增第七态」不该被当成真的第七态。
    m = re.search(r"export const RESULT_STATES = \{(.*?)\} as const", text, re.S)
    body = m.group(1) if m else ""
    bad = []
    found = re.findall(r"^\s+([A-Z_]+): '([^']+)'", body, re.M)
    vals = {v for _, v in found}
    if vals != EXPECT:
        bad.append("六态集合不符: %s" % sorted(vals))
    if len(found) != 6:
        bad.append("状态条数 %d != 6" % len(found))
    for k, v in found:
        if k != v:
            bad.append("键与值不一致: %s / %s" % (k, v))
    if "不适用" in body or "N/A" in body:
        bad.append("出现第七态")
    if "as const" not in text:
        bad.append("缺 as const（值应只读）")
    return bad

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if not SRC.exists(): print("[FAIL] 读不到 resultStates.ts"); return 2
    t = SRC.read_text(encoding="utf-8")
    if a.selfcheck:
        broken = t.replace("  BLOCKED: 'BLOCKED',", "  BLOCKED: 'BLOCK',")
        if not check(broken): print("[FAIL] selfcheck：改状态值未判红"); return 1
        if check(t): print("[FAIL] selfcheck：干净字典不应判红"); return 1
        print("[PASS] states selfcheck：改值判红 / 干净判绿"); return 0
    bad = check(t)
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] 结果六态单一真源：6 态齐全、键值一致、无第七态"); return 0


if __name__ == "__main__":
    raise SystemExit(main())
