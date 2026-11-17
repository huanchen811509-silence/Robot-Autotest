"""校验本规划目录的编号完整性：每版 3 份文档、FR/WBS/TC 编号连续且无缺口。

用法：python3 check_plan_numbers.py
退出码：0 = 全部通过；1 = 有缺口或文件缺失。

纪律：编号**禁补号**（插入即整体重排），新增一律追加到该版区间末尾。
每版收口前跑一次，避免"文档说 18 条、实际只有 17 个 TC"。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
VERSIONS = [f"v5.{n}.0" for n in range(23, 31)]
EXPECTED_FILES = 3

ok = True
print(f"{'版本':10} {'文件':>4} {'FR':>4} {'WBS':>4} {'TC':>4}  连续性")
total_fr = total_wbs = total_tc = 0

for v in VERSIONS:
    d = os.path.join(ROOT, v)
    if not os.path.isdir(d):
        print(f"{v:10} 目录缺失")
        ok = False
        continue

    files = sorted(f for f in os.listdir(d) if f.endswith(".md"))
    short = ".".join(v[1:].split(".")[:2])          # v5.23.0 -> 5.23
    prefix = int(short.split(".")[1]) + 50          # 5.23 -> W73

    fr_pat = re.compile(rf"^\| FR-v{re.escape(short)}-(\d\d)", re.M)
    wbs_pat = re.compile(rf"^\| W{prefix}-(\d\d)", re.M)
    tc_pat = re.compile(rf"^\| TC-v{re.escape(short)}-(\d\d)", re.M)

    frs, wbss, tcs = set(), set(), set()
    for f in files:
        with open(os.path.join(d, f), encoding="utf-8") as fh:
            text = fh.read()
        frs |= {int(m) for m in fr_pat.findall(text)}
        wbss |= {int(m) for m in wbs_pat.findall(text)}
        tcs |= {int(m) for m in tc_pat.findall(text)}

    def cont(seq):
        """连续且从 1 开始。"""
        if not seq:
            return False, "无", []
        exp = list(range(1, max(seq) + 1))
        missing = sorted(set(exp) - set(seq))
        return (not missing), ("连续" if not missing else f"缺口{missing}"), missing

    fr_ok, fr_st, _ = cont(sorted(frs))
    wb_ok, wb_st, _ = cont(sorted(wbss))
    tc_ok, tc_st, _ = cont(sorted(tcs))

    print(f"{v:10} {len(files):>4} {len(frs):>4} {len(wbss):>4} {len(tcs):>4}  FR {fr_st} | WBS {wb_st} | TC {tc_st}")

    if len(files) != EXPECTED_FILES:
        print(f"  !! 文件数 {len(files)} != {EXPECTED_FILES}（应为 PRD/DEV-PLAN/TEST-PLAN）")
        ok = False
    if not (fr_ok and wb_ok and tc_ok):
        ok = False

    total_fr += len(frs)
    total_wbs += len(wbss)
    total_tc += len(tcs)

print(f"\n合计 FR={total_fr} WBS={total_wbs} TC={total_tc}")
print("RESULT:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
