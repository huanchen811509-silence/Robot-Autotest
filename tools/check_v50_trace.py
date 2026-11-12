#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, pathlib, re, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
P = ROOT / "docs/plan"
PRD = P / "v5.0.X存量打磨-产品PRD-20261112.md"
DEV = P / "v5.0.X存量打磨-开发计划-20261112.md"
TEST = P / "v5.0.X存量打磨-测试计划-20261112.md"
STATES = {"未执行", "PASS", "PARTIAL", "NOT-RUN", "BLOCKED", "FAIL"}


def read(prd=None, dev=None, test=None):
    return (prd if prd is not None else PRD.read_text(encoding="utf-8"),
            dev if dev is not None else DEV.read_text(encoding="utf-8"),
            test if test is not None else TEST.read_text(encoding="utf-8"))


def check(prd, dev, test):
    bad = []
    fr = sorted({int(x) for x in re.findall(r"^#### (?:FR-v5\.0-)(\d{2})\b", prd, re.M)})
    tc = sorted({int(x) for x in re.findall(r"^\| (?:TC-v5\.0-)(\d{2}) \|", test, re.M)})
    wb = sorted({int(x) for x in re.findall(r"^\| (?:W50-)(\d{2}) \|", dev, re.M)})
    if fr != list(range(1, 31)): bad.append("FR 编号异常 %s" % fr)
    if tc != list(range(1, 43)): bad.append("TC 编号异常 %s" % tc)
    if wb != list(range(1, 31)): bad.append("WBS 编号异常 %s" % wb)
    rows = re.findall(r"^\| (TC-v5\.0-\d{2}) \|.*\|\s*([^|]+?)\s*\|\s*$", test, re.M)
    for tid, st in rows:
        if st not in STATES: bad.append("%s 状态非法: %s" % (tid, st))
    m = re.search(r"### [^\n]*FR → TC 覆盖矩阵[^\n]*\n(.*?)(?=\n### |\Z)", test, re.S)
    if not m:
        bad.append("缺 FR → TC 覆盖矩阵")
    else:
        fr2tc = {}
        for line in m.group(1).splitlines():
            if not line.startswith("| FR-v5.0"): continue
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            for i in range(0, len(cells) - 1, 2):
                f = cells[i].replace("FR-v5.0-", "")
                if f.isdigit():
                    fr2tc.setdefault(int(f), set()).update(
                        int(x) for x in re.findall(r"TC-v5\.0-(\d{2})", cells[i + 1]))
        cf, ct = set(fr2tc), set().union(*fr2tc.values()) if fr2tc else set()
        if cf != set(range(1, 31)): bad.append("矩阵 FR 缺口 %s" % sorted(set(range(1, 31)) - cf))
        if ct != set(range(1, 43)): bad.append("矩阵 TC 缺口 %s" % sorted(set(range(1, 43)) - ct))
    return bad


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    try: prd, dev, test = read()
    except OSError as e:
        print("[FAIL] 读不到规划文档: %s" % e); return 2
    if a.selfcheck:
        broken = re.sub(r"^\| TC-v5\.0-42 \|.*$", "", test, count=1, flags=re.M)
        if not check(prd, dev, broken):
            print("[FAIL] selfcheck：删一条 TC 未判红"); return 1
        if check(prd, dev, test):
            print("[FAIL] selfcheck：干净文档不应判红"); return 1
        print("[PASS] trace selfcheck：删 TC 判红 / 干净判绿"); return 0
    bad = check(prd, dev, test)
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] 追溯：FR 30 / TC 42 / WBS 30 连续，覆盖矩阵双向零孤儿"); return 0


if __name__ == "__main__":
    raise SystemExit(main())
