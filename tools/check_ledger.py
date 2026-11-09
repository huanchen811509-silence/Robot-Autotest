#!/usr/bin/env python3
"""EW35-09 · 遗留与现场项统一台账机检。

用法：
  python3 tools/check_ledger.py                       # 检查默认台账
  python3 tools/check_ledger.py <path>               # 指定台账
  python3 tools/check_ledger.py --selfcheck          # 负向自检：缺原因 / 无证据 PASS 必红
"""
import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT = ROOT / "docs" / "plan" / "遗留与现场项统一台账-v3.5.md"
FIELD_PACKS = ["WS-05", "EXE-05", "EXE-06", "EXEC-02", "EXEC-03", "EXEC-05"]
GATES = ["G4", "G5", "G6", "G7", "G8"]
KEYWORDS = ["ADR-0013", "CDP", "未判", "BLOCKED"]


def check(text):
    fails = []
    lines = text.splitlines()
    rows = [ln for ln in lines if ln.strip().startswith("|")]
    # 1 六个现场包逐条存在且三态
    for pack in FIELD_PACKS:
        hit = [r for r in rows if re.search(r"\|\s*" + re.escape(pack) + r"\s*\|", r)]
        if not hit:
            fails.append("缺现场包 %s" % pack)
            continue
        row = hit[0]
        if not re.search(r"\|\s*(PASS|FAIL|BLOCKED)\s*\|", row):
            fails.append("%s 状态非三态" % pack)
        elif re.search(r"\|\s*BLOCKED\s*\|", row):
            cells = [c.strip() for c in row.strip().strip("|").split("|")]
            if len(cells) < 4 or len(cells[2]) < 8 or len(cells[3]) < 8:
                fails.append("%s 的 BLOCKED 未写阻塞原因与所需条件" % pack)
    # 2 G 门状态与全局项
    for g in GATES:
        if g not in text:
            fails.append("缺 G 门状态 %s" % g)
    for kw in KEYWORDS:
        if kw not in text:
            fails.append("缺关键状态项 %s" % kw)
    # 3 禁止无证据的 PASS：现场包行若 PASS 必须带证据路径
    for pack in FIELD_PACKS:
        for r in rows:
            if re.search(r"\|\s*" + re.escape(pack) + r"\s*\|", r) and re.search(r"\|\s*PASS\s*\|", r):
                if "docs/" not in r and "evidence" not in r:
                    fails.append("%s 判 PASS 但无证据路径（疑似推演）" % pack)
    return fails


def selfcheck():
    fails = 0
    base = DEFAULT.read_text(encoding="utf-8")
    # 删一个现场包 -> 必红
    t = re.sub(r"\|\s*WS-05\s*\|[^\n]*\n", "", base)
    if check(t):
        print("PASS  删现场包判红")
    else:
        print("FAIL  删现场包未判红")
        fails += 1
    # BLOCKED 行抹掉原因与条件 -> 必红
    t = base.replace("缺真实工作台 / 现场设备与网络环境，无法执行实链路验证", "x")
    t = t.replace("现场设备、网络拓扑、授权时段与 ADR-0013 解禁", "y")
    if check(t):
        print("PASS  BLOCKED 缺原因判红")
    else:
        print("FAIL  BLOCKED 缺原因未判红")
        fails += 1
    # 无证据 PASS -> 必红
    t = base.replace("| WS-05 | BLOCKED |", "| WS-05 | PASS |")
    if check(t):
        print("PASS  无证据 PASS 判红")
    else:
        print("FAIL  无证据 PASS 未判红")
        fails += 1
    # 正常台账 -> 必绿
    ok = check(base)
    if ok:
        print("FAIL  正常台账被误判：%s" % "; ".join(ok[:3]))
        fails += 1
    else:
        print("PASS  正常台账通过")
    print("SELFCHECK_RESULT FAILS=%d" % fails)
    return 1 if fails else 0


if __name__ == "__main__":
    if "--selfcheck" in sys.argv:
        sys.exit(selfcheck())
    p = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1] != "--selfcheck" else DEFAULT
    if not p.exists():
        print("FAIL  台账不存在:", p)
        sys.exit(1)
    f = check(p.read_text(encoding="utf-8"))
    for x in f:
        print("FAIL ", x)
    print("LEDGER_RESULT FAILS=%d  packs=%d" % (len(f), len(FIELD_PACKS)))
    sys.exit(1 if f else 0)
