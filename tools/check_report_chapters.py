#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""报告 8 章机检（v5.4.0 / FR-v5.4-02 / W54-02）。

为什么需要这条机检
------------------
「缺数据的章要标无数据，不能省略」是这份报告最容易被悄悄破坏的约定：
只要有人为了「页面好看」在某章外面加一个 `if (!empty)`，报告就少了一章，
而读者看到的是「这份报告很干净」——完全相反的结论。

这类退化单测很难拦住（要恰好构造出那一章的缺失场景），所以门禁里直接数
`chapters.add(` 的出现次数：八章必须是八次无条件添加。

守什么
------
1. **八章齐**：`TITLES` 恰 8 项且顺序与 PRD 一致；
2. **无条件添加**：`build()` 里 `chapters.add(` 恰好 8 次（不是「按条件添加」）；
3. **三条声明**：`DECLARATIONS` 恰 3 条，且分别覆盖覆盖率 / PLANNED / AI；
4. **降级不是省略**：`optional(...)` 返回的是 `Status.NO_DATA` 的章对象，不是 null；
5. **判定建议不吃 AI**：`verdictAdvice` 方法体内不得出现 AI 调用。

用法
----
    python3 tools/check_report_chapters.py
    python3 tools/check_report_chapters.py --self-test   # 真注入：删掉一章，必须判红

退出码：0 = 通过；1 = 违规；2 = 扫描失败（找不到源码，疑似假绿）。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILDER = (ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest"
           / "application" / "report" / "ReportChapterBuilder.java")

EXPECTED_TITLES = [
    "结论摘要", "执行环境", "结果分布", "失败分析",
    "时间分析", "覆盖影响", "命令集合摘要", "附录与口径",
]

DECLARATION_KEYWORDS = ["覆盖率", "PLANNED", "AI"]


def ok(message: str) -> None:
    print(f"  OK     {message}")


def fail(problems: list[str], message: str) -> None:
    problems.append(message)
    print(f"  FAIL   {message}")


def parse_titles(text: str) -> list[str]:
    """从 TITLES 常量里取出 8 个标题（保持顺序）。"""
    match = re.search(r"TITLES\s*=\s*List\.of\((.*?)\);", text, re.S)
    if not match:
        return []
    return re.findall(r'"([^"]+)"', match.group(1))


def parse_declarations(text: str) -> list[str]:
    match = re.search(r"DECLARATIONS\s*=\s*List\.of\((.*?)\);", text, re.S)
    if not match:
        return []
    return re.findall(r'"([^"]+)"', match.group(1))


def check(text: str, problems: list[str]) -> None:
    titles = parse_titles(text)
    if not titles:
        fail(problems, "ReportChapterBuilder 里找不到 TITLES 常量（结构变了，脚本要同步）")
        return
    if len(titles) != 8:
        fail(problems, f"TITLES 应为 8 章，实际 {len(titles)} 章：{titles}")
    elif titles != EXPECTED_TITLES:
        fail(problems, f"章标题/顺序与 PRD 不一致：{titles}")
    else:
        ok("八章标题与顺序与 PRD 一致")

    # 无条件添加：build() 方法体内 chapters.add( 必须恰好 8 次
    body_match = re.search(r"public static List<Chapter> build\((.*?)\n    \}", text, re.S)
    if not body_match:
        fail(problems, "找不到 build(...) 方法体")
    else:
        adds = len(re.findall(r"chapters\.add\(", body_match.group(1)))
        if adds != 8:
            fail(problems, f"build() 里 chapters.add( 出现 {adds} 次，应为 8 次"
                           "（少了说明某章被条件跳过——缺数据的章必须仍在，标「无数据」）")
        else:
            ok("build() 无条件添加 8 章")

    declarations = parse_declarations(text)
    if len(declarations) != 3:
        fail(problems, f"DECLARATIONS 应为 3 条，实际 {len(declarations)} 条")
    else:
        joined = " ".join(declarations)
        missing = [key for key in DECLARATION_KEYWORDS if key not in joined]
        if missing:
            fail(problems, f"三条口径声明缺关键词：{', '.join(missing)}")
        else:
            ok("三条口径声明齐备（覆盖率 / PLANNED / AI）")

    # 降级不是省略：optional() 必须返回 new Chapter(... NO_DATA ...)，不能 return null
    optional_match = re.search(r"private static Chapter optional\((.*?)\n    \}",
                               text, re.S)
    if not optional_match:
        fail(problems, "找不到 optional(...) 降级方法")
    else:
        body = optional_match.group(1)
        if "Status.NO_DATA" not in body:
            fail(problems, "optional() 没有返回 NO_DATA 状态的章（降级变成了省略）")
        elif re.search(r"return\s+null\s*;", body):
            fail(problems, "optional() 里出现 return null（缺数据的章不能消失）")
        else:
            ok("optional() 降级为 NO_DATA 章对象，不返回 null")

    # 判定建议不得引入 AI
    advice_match = re.search(r"public static String verdictAdvice\((.*?)\n    \}", text, re.S)
    if not advice_match:
        fail(problems, "找不到 verdictAdvice(...)")
    else:
        body = advice_match.group(1)
        if re.search(r"\bai\b|Ai|AIModel|llm|LLM", body):
            fail(problems, "verdictAdvice 里出现 AI/LLM 调用（判定建议必须由规则推导）")
        else:
            ok("判定建议由规则推导，无 AI 参与")


def run() -> int:
    print(f"[check_report_chapters] 源码={BUILDER.relative_to(ROOT)}")
    if not BUILDER.is_file():
        print(f"check_report_chapters: FAIL（找不到 {BUILDER}）")
        return 2
    problems: list[str] = []
    check(BUILDER.read_text(encoding="utf-8"), problems)
    if problems:
        print(f"check_report_chapters: FAIL（{len(problems)} 项）")
        return 1
    print("check_report_chapters: PASS")
    return 0


def self_test() -> int:
    """真注入：删掉一章的添加语句，检查必须判红。"""
    print("--- self-test：删掉「命令集合摘要」章的添加后应判失败 ---")
    text = BUILDER.read_text(encoding="utf-8")
    broken = text.replace(
        "        chapters.add(optional(7, commands, \"未采集（本次执行未开启监视器）\"));\n", "")
    if broken == text:
        print("  FAIL   自检注入点没找到（源码结构变了），无法自证")
        print("check_report_chapters: SELF-TEST FAIL")
        return 2
    problems: list[str] = []
    check(broken, problems)
    if not any("chapters.add(" in p for p in problems):
        print(f"  FAIL   自检未按预期判红：{problems}")
        print("check_report_chapters: SELF-TEST FAIL")
        return 1
    print("  OK     删掉一章后判红")

    print("check_report_chapters: SELF-TEST PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="报告 8 章结构与降级机检")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检")
    args = parser.parse_args()
    return self_test() if args.self_test else run()


if __name__ == "__main__":
    sys.exit(main())
