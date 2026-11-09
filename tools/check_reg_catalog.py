#!/usr/bin/env python3
"""EW35-07 · REG-* 回归目录机检：11 类新能力各 ≥1 条，且每条带可复现命令与证据路径。

用法：
  python3 tools/check_reg_catalog.py
  python3 tools/check_reg_catalog.py --selfcheck   # 删一类 / 删命令必红
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DOC = ROOT / "docs" / "evidence" / "v3.5.0" / "EW35-07" / "REG-CATALOG.md"
DOMAINS = ["搜索", "保存视图", "通知", "缓存", "契约", "流式", "证据问答",
           "归档", "准入", "安全头", "SLO"]


def check(text):
    fails = []
    for d in DOMAINS:
        rows = [ln for ln in text.splitlines()
                if ln.strip().startswith("|") and re.search(r"\|\s*REG-[A-Z]+-\d+\s*\|", ln)
                and ("| " + d + " |") in ln]
        if not rows:
            fails.append("能力域 %s 无 REG 用例" % d)
            continue
        for r in rows:
            cells = [c.strip() for c in r.strip().strip("|").split("|")]
            if len(cells) < 5 or "`" not in cells[3] or "docs/" not in cells[4]:
                fails.append("%s 的用例缺可复现命令或证据路径: %s" % (d, cells[0]))
    if "负向注入" not in text:
        fails.append("缺负向注入说明")
    return fails


def selfcheck():
    base = DOC.read_text(encoding="utf-8")
    fails = 0
    t = "\n".join(ln for ln in base.splitlines() if "| 搜索 |" not in ln)
    if check(t):
        print("PASS  删能力域判红")
    else:
        print("FAIL  删能力域未判红")
        fails += 1
    t = base.replace("`python3 tools/gen_api_contract.py --check`", "无命令")
    if check(t):
        print("PASS  删可复现命令判红")
    else:
        print("FAIL  删可复现命令未判红")
        fails += 1
    ok = check(base)
    if ok:
        print("FAIL  正常目录被误判：%s" % "; ".join(ok[:3]))
        fails += 1
    else:
        print("PASS  正常目录通过")
    print("SELFCHECK_RESULT FAILS=%d" % fails)
    return 1 if fails else 0


if __name__ == "__main__":
    if "--selfcheck" in sys.argv:
        sys.exit(selfcheck())
    if not DOC.exists():
        print("FAIL  目录不存在:", DOC)
        sys.exit(1)
    f = check(DOC.read_text(encoding="utf-8"))
    for x in f:
        print("FAIL ", x)
    print("REG_RESULT FAILS=%d  domains=%d" % (len(f), len(DOMAINS)))
    sys.exit(1 if f else 0)
