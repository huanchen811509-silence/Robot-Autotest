#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""失败归因分布与环境失败分流机检（v5.2.3 / FR-v5.2-06 / W52-10）。

为什么需要这条机检
------------------
「环境失败不进产品通过率分母」是一个**只有一行代码**的口径。把 `passed + product`
改成 `passed + product + env`，编译过、类型过、所有既有单测过，页面照常显示一个
百分比——只是这个百分比从此同时受管理网抖动影响。

它的危害是双向的：
  * 环境差的时候把产品判死（该发的版本不敢发）；
  * 环境长期差的时候让人对通过率麻木（真缺陷淹没在噪声里）。
两份后果都不会让任何自动化测试变红，所以必须有一条机检专门守这一行。

守什么
------
1. **四侧常量齐**：产品 / 环境 / 跳过 / 未归类，缺一个就说明有人把某一类悄悄并进别的类；
2. **分母只含通过 + 产品失败**：断言 `denominator = passed + product` 的字面写法；
3. **未归类不进产品侧**：`sideOf` 的 `default` 分支必须落到 UNKNOWN 侧；
4. **计划未执行不重复计数**：服务层对 PLANNED/DISPATCHED/PENDING 必须 continue，
   否则 total 会与「通过 + 失败 + 未执行」对不上；
5. **无失败时第 4 章标 NO_DATA**：不许跳过该章（跳过 = 读者以为没跑失败分析）；
6. **取数排除 PASS 且带 project_id**：租户隔离与「只取失败侧」两件事都在 SQL 里；
7. **页面与报告同一份计算**：控制器两个端点都走同一个 service，不许各算一遍。

用法
----
    python3 tools/check_failure_breakdown.py
    python3 tools/check_failure_breakdown.py --self-test   # 真注入：把环境失败并进分母，必须判红

退出码：0 = 通过；1 = 违规；2 = 扫描失败（找不到源码，疑似假绿）。
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest"
BREAKDOWN = BASE / "application" / "report" / "FailureBreakdown.java"
SERVICE = BASE / "application" / "report" / "RunFailureBreakdownService.java"
JDBC = BASE / "infrastructure" / "RunFailureBreakdownRepositoryJdbc.java"
CONTROLLER = BASE / "api" / "report" / "FailureBreakdownController.java"

SIDES = ("SIDE_PRODUCT", "SIDE_ENV", "SIDE_SKIP", "SIDE_UNKNOWN")


def ok(message: str) -> None:
    print(f"  OK     {message}")


def fail(problems: list[str], message: str) -> None:
    problems.append(message)
    print(f"  FAIL   {message}")


def check(breakdown: str, service: str, jdbc: str, controller: str,
          problems: list[str]) -> None:
    missing = [side for side in SIDES if f'String {side} =' not in breakdown]
    if missing:
        fail(problems, f"缺少侧别常量 {', '.join(missing)}"
                       "（少一个就可能把某一类悄悄并进别的类）")
    else:
        ok("四侧常量齐（产品 / 环境 / 跳过 / 未归类）")

    if re.search(r"\bdenominator\s*=\s*passed\s*\+\s*product\s*;", breakdown):
        ok("产品通过率分母 = 通过 + 产品失败")
    else:
        fail(problems, "找不到 `denominator = passed + product;`（分母口径被改了："
                       "环境/跳过/未归类一旦进来，通过率就同时受管理网抖动影响）")

    side_block = re.search(r"static String sideOf\(Sample row\)(.*?)\n    \}", breakdown, re.S)
    if not side_block:
        fail(problems, "找不到 sideOf()（侧别判定被搬走或改名，请同步脚本）")
    else:
        body = side_block.group(1)
        if "default -> SIDE_UNKNOWN" not in body:
            fail(problems, "sideOf() 的 default 分支不是 SIDE_UNKNOWN"
                           "（归因不足的用例会被算成产品缺陷）")
        elif '"SKIP"' not in body:
            fail(problems, "sideOf() 没有先按 SKIP 结论分流")
        else:
            ok("归因不足落未归类侧；SKIP 结论优先归跳过")

    if re.search(r"samples == 0", breakdown) and "Status.NO_DATA" in breakdown:
        ok("无失败时第 4 章如实标无数据（不省略该章）")
    else:
        fail(problems, "第 4 章在无失败时没有返回 NO_DATA（该章会被跳过，"
                       "读者会以为失败分析没跑）")

    planned_hits = [w for w in ("PLANNED", "DISPATCHED", "PENDING") if w in service]
    if len(planned_hits) != 3:
        fail(problems, "服务层没有把 PLANNED / DISPATCHED / PENDING 三类都当计划未执行")
    elif not re.search(r"if \(planned\(row\.verdict\(\)\)\)\s*\{(?:\s*//[^\n]*\n)*\s*continue;",
                       service):
        fail(problems, "服务层没有对已派发未回的行 continue（会与计数重复计一遍，总账对不上）")
    else:
        ok("已派发未回只进计划未执行、不重复计数")

    if "tr.verdict <> 'PASS'" not in jdbc:
        fail(problems, "取数 SQL 没有排除 PASS（会把通过用例也算成失败侧样本）")
    else:
        ok("取数只取非 PASS 行")

    if "r.project_id = ?" not in jdbc:
        fail(problems, "取数 SQL 缺 project_id 过滤（跨项目串数据）")
    else:
        ok("取数带 project_id（租户隔离）")

    if "service.of(projectId, runId)" in controller and "service.chapterOf(projectId, runId)" in controller:
        ok("页面分布与报告章节走同一份计算")
    else:
        fail(problems, "控制器没有同时复用 service.of / service.chapterOf"
                       "（页面与报告会显示两个不同的通过率）")


def run() -> int:
    print(f"[check_failure_breakdown] 归因分布={BREAKDOWN.relative_to(ROOT)}")
    for file in (BREAKDOWN, SERVICE, JDBC, CONTROLLER):
        if not file.is_file():
            print(f"check_failure_breakdown: FAIL（找不到 {file}）")
            return 2
    problems: list[str] = []
    check(BREAKDOWN.read_text(encoding="utf-8"), SERVICE.read_text(encoding="utf-8"),
          JDBC.read_text(encoding="utf-8"), CONTROLLER.read_text(encoding="utf-8"), problems)
    if problems:
        print(f"check_failure_breakdown: FAIL（{len(problems)} 项）")
        return 1
    print("check_failure_breakdown: PASS")
    return 0


def self_test() -> int:
    """真注入：把环境失败并进分母，口径断言必须判红。"""
    print("--- self-test：把环境失败并进产品分母后应判失败 ---")
    global BREAKDOWN
    original = BREAKDOWN
    backup = original.read_text(encoding="utf-8")
    broken = backup.replace("int denominator = passed + product;",
                            "int denominator = passed + product + env;", 1)
    if broken == backup:
        print("  FAIL   自检注入点没找到（分母写法变了），无法自证")
        print("check_failure_breakdown: SELF-TEST FAIL")
        return 2
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".java", delete=False,
                                         encoding="utf-8") as handle:
            handle.write(broken)
            temp_path = Path(handle.name)
        BREAKDOWN = temp_path
        problems: list[str] = []
        check(BREAKDOWN.read_text(encoding="utf-8"), SERVICE.read_text(encoding="utf-8"),
              JDBC.read_text(encoding="utf-8"), CONTROLLER.read_text(encoding="utf-8"), problems)
        if not any("分母" in item for item in problems):
            print(f"  FAIL   自检未按预期判红：{problems}")
            print("check_failure_breakdown: SELF-TEST FAIL")
            return 1
        print("  OK     环境失败并进分母后判红")
    finally:
        BREAKDOWN = original
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)

    print("check_failure_breakdown: SELF-TEST PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="失败归因分布与环境失败分流机检")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检")
    args = parser.parse_args()
    return self_test() if args.self_test else run()


if __name__ == "__main__":
    sys.exit(main())
