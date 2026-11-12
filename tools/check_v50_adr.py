#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。
import argparse, pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
D = ROOT / "docs/adr"
SECTIONS = ["## 1. 背景", "## 2. 决策", "## 3. 备选方案", "## 4. 后果", "## 5. 复核方式"]


def check():
    bad = []
    for i in range(1, 9):
        p = D / ("ADR-50-%02d.md" % i)
        if not p.exists():
            bad.append("缺 ADR-50-%02d" % i)
            continue
        t = p.read_text(encoding="utf-8")
        for s in SECTIONS:
            if s not in t:
                bad.append("ADR-50-%02d 缺段落 %s" % (i, s))
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if a.selfcheck:
        p = D / "ADR-50-08.md"
        bak = D / "ADR-50-08.md.selfcheck-bak"
        p.rename(bak)
        try:
            bad = check()
        finally:
            bak.rename(p)
        if not bad:
            print("[FAIL] selfcheck：真删一份 ADR 未判红"); return 1
        if check():
            print("[FAIL] selfcheck：还原后不应判红"); return 1
        print("[PASS] adr selfcheck：真删判红 / 还原判绿"); return 0
    bad = check()
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] ADR-50-01~08 编号完整、五段齐全"); return 0


if __name__ == "__main__":
    raise SystemExit(main())
