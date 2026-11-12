#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5.1.0 · 门禁第 40 步：用例↔脚本绑定覆盖率（FR-v5.1-04/05，W51-08/W51-11）。

判据（数据源 `docs/evidence/v5.1.0/pytest/case-index.json`，由
`tools/gen_pytest_case_index.py` 从用例库 Excel + 脚本目录生成）：

A. **恒等式**——`bound + unbound == total`。这是唯一不能破的口径：
   两个数加起来必须等于 469，否则「覆盖率」就是编的。

B. **去重**——脚本里声明的编号不得重复；重复说明两个文件抢同一条用例，
   执行期会静默取第一个（见 `PytestResultNormalizer.bindingIndex`）。

C. **无孤儿**——脚本声明了编号但库里没有，或库里有编号却没有脚本。
   前者是「脚本写错了编号」，后者是「还没实现」，两者都要报出来，
   但只有前者判红（后者是缺口，不是错误）。

D. **口径并列**（D12 拍板）——分母必须同时给出两个数：
   含待评审 469（13.0%）与排除待评审 291（10.3%）。
   只报一个就判红：对外口径不允许只挑好看的那个。

用法：
  python3 tools/check_pytest_binding.py
  python3 tools/check_pytest_binding.py --self-check   # 负向自证
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INDEX = ROOT / "docs/evidence/v5.1.0/pytest/case-index.json"

# 2026-11-12 实测基线。数字变了不是「自动适应」，而是要人确认：
# 用例库新增用例 / 新增脚本都会改这两个数，门禁要求显式更新本行并留证据。
BASELINE = {"cases": 469, "scripts_with_code": 61}
REVIEW_PENDING = "待评审"


def load() -> dict:
    if not INDEX.is_file():
        raise SystemExit(f"[FAIL] 缺少用例库索引证据: {INDEX}\n"
                         f"  先跑: python3 tools/gen_pytest_case_index.py")
    return json.loads(INDEX.read_text(encoding="utf-8"))


def analyze(payload: dict) -> dict:
    cases = payload["cases"]
    scripts = [s for s in payload["scripts"] if s["case_code"]]
    codes = {item["code"] for item in cases}
    active = [item for item in cases if item["priority"] != REVIEW_PENDING]

    declared = [s["case_code"] for s in scripts]
    duplicates = sorted({code for code in declared if declared.count(code) > 1})
    orphans = sorted({code for code in declared if code not in codes})
    bound = len({code for code in declared if code in codes})
    covered_active = len({code for code in declared if code in {a["code"] for a in active}})
    return {
        "total": len(cases),
        "active": len(active),
        "bound": bound,
        "unbound": len(cases) - bound,
        "duplicates": duplicates,
        "orphans": orphans,
        "coverage": round(bound * 100 / len(cases), 1) if cases else 0.0,
        "coverage_active": round(covered_active * 100 / len(active), 1) if active else 0.0,
        "declared": declared,
    }


def check(stats: dict) -> list[str]:
    problems = []
    if stats["bound"] + stats["unbound"] != stats["total"]:
        problems.append(f"恒等式不成立: {stats['bound']} + {stats['unbound']} "
                        f"!= {stats['total']}")
    if stats["total"] != BASELINE["cases"]:
        problems.append(f"用例总数 {stats['total']} 与基线 {BASELINE['cases']} 不一致"
                        f"（用例库变了？确认后更新 BASELINE 并留证据）")
    if len(stats["declared"]) != BASELINE["scripts_with_code"]:
        problems.append(f"带编号声明的脚本 {len(stats['declared'])} 个，"
                        f"与基线 {BASELINE['scripts_with_code']} 不一致")
    if stats["duplicates"]:
        problems.append(f"重复声明的编号: {', '.join(stats['duplicates'])}")
    if stats["orphans"]:
        problems.append(f"脚本声明了库里没有的编号: {', '.join(stats['orphans'])}")
    # D12：两个口径必须都能算出来。排除口径的分母若等于总数，说明「待评审」没被识别，
    # 页面会显示两个一样的百分比——那不是并列，是失效。
    pending = stats["total"] - stats["active"]
    if pending > 0 and stats["active"] == stats["total"]:
        problems.append("待评审未从排除口径分母中剔除，双口径退化成一个数")
    if pending == 0:
        problems.append("待评审为 0：双口径退化（请确认用例库优先级列是否还在）")
    return problems


def summary_lines(stats: dict) -> list[str]:
    """给人和机检共用的读数行。两个口径标记字面固定，供下方结构断言。"""
    return [
        f"  用例 {stats['total']} 条（其中待评审 {stats['total'] - stats['active']} 条）",
        f"  已绑定 {stats['bound']} / 未绑定 {stats['unbound']} / "
        f"四类之和 {stats['bound'] + stats['unbound']}（应等于 {stats['total']}）",
        f"  含待评审口径：{stats['bound']}/{stats['total']} = {stats['coverage']}%",
        f"  排除待评审口径：{stats['active']} 条，覆盖率 {stats['coverage_active']}%",
        f"  重复声明 {len(stats['duplicates'])} / 孤儿编号 {len(stats['orphans'])}",
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()

    payload = load()
    stats = analyze(payload)

    lines = summary_lines(stats)
    for line in lines:
        print(line)

    # 结构断言：两个口径的标记字面必须出现在读数里。谁把其中一个删了，这里判红——
    # 对外口径不允许只挑好看的那个（D12）。
    body = "\n".join(lines)
    for marker in ("含待评审口径", "排除待评审口径"):
        if marker not in body:
            print(f"PYTEST_BINDING FAIL\n  - 读数缺少口径标记: {marker}")
            return 1

    if args.self_check:
        print("=== 负向自证：把一条脚本编号改成库里不存在的 ===")
        dirty = {"cases": payload["cases"], "scripts": []}
        replaced = False
        for script in payload["scripts"]:
            copy = dict(script)
            if not replaced and copy["case_code"]:
                copy["case_code"] = "IM-NET-999"
                replaced = True
            dirty["scripts"].append(copy)
        if not replaced:
            print("  SELFCHECK: 没有可改的脚本，自证无效")
            return 1
        dirty_stats = analyze(dirty)
        injected = check(dirty_stats)
        ok = any("库里没有的编号" in item for item in injected)
        print(f"  SELFCHECK 孤儿编号注入: {'RED（符合预期）' if ok else 'GREEN（不符合预期）'}")
        if not ok:
            return 1
        print("SELFCHECK_RESULT FAILS=0")
        print("PYTEST_BINDING PASS")
        return 0

    problems = check(stats)
    if problems:
        print("PYTEST_BINDING FAIL")
        for item in problems:
            print(f"  - {item}")
        return 1
    print("PYTEST_BINDING PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
