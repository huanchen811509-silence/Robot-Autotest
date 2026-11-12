#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""双内核统一视图机检（v5.4.2 / FR-v5.4-06 / W54-06）。

为什么需要这条机检
------------------
双内核视图的唯一硬要求是「一份视图里两个内核各自算各自的，不许合并」。
一旦有人为了「页面只显示一个数」把两个内核的用例数加起来，通过率立刻变成一个
没有任何含义的数——RF 跑了 200 条、pytest 跑了 3 条，合计通过率 91% 听起来很好，
但它既不代表 RF 的成熟度，也不代表 pytest 的成熟度。

这类改动只改一行 SQL（去掉 GROUP BY）或一行 Java（取平均而不是分列），
编译、类型、既有单测全都不会红。

守什么
------
1. **两条 SQL 分开聚合**：`RUN_SQL` 与 `CASE_SQL` 都在，且都带 `GROUP BY`；
   run 与用例分开查是因为一条 run 对多条结果，join 起来 COUNT 会把 run 数放大；
2. **手工不进自动视图**：两条 SQL 都排除 `manual`；
3. **分列而非合并**：`KernelLane` 同时有 `cases` 与 `passedCases`，通过率由这一对算出，
   不存在「总通过率」字段；
4. **诚实降级**：`UnifiedView` 有 `bothKernels` 与 `note`，只有一个内核时如实说明。

用法
----
    python3 tools/check_dual_kernel_view.py
    python3 tools/check_dual_kernel_view.py --self-test   # 真注入：删掉 GROUP BY，必须判红

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
REPO_JDBC = BASE / "infrastructure" / "KernelViewRepositoryJdbc.java"
REPO = BASE / "application" / "repository" / "KernelViewRepository.java"
SERVICE = BASE / "application" / "report" / "KernelUnifiedViewService.java"


def ok(message: str) -> None:
    print(f"  OK     {message}")


def fail(problems: list[str], message: str) -> None:
    problems.append(message)
    print(f"  FAIL   {message}")


def sql_of(text: str, name: str) -> str:
    match = re.search(rf"String {name} = \"\"\"(.*?)\"\"\";", text, re.S)
    return match.group(1) if match else ""


def check(jdbc_text: str, repo_text: str, service_text: str, problems: list[str]) -> None:
    run_sql = sql_of(jdbc_text, "RUN_SQL")
    case_sql = sql_of(jdbc_text, "CASE_SQL")
    if not run_sql or not case_sql:
        fail(problems, "找不到 RUN_SQL / CASE_SQL（run 与用例必须分开聚合，结构变了请同步脚本）")
        return

    missing_group = [name for name, sql in (("RUN_SQL", run_sql), ("CASE_SQL", case_sql))
                     if "GROUP BY" not in sql.upper()]
    if missing_group:
        fail(problems, f"{', '.join(missing_group)} 没有 GROUP BY（两内核会被合成一行）")
    else:
        ok("run 与用例各自按内核聚合（两条 SQL 都有 GROUP BY）")

    for name, sql in (("RUN_SQL", run_sql), ("CASE_SQL", case_sql)):
        if "manual" not in sql:
            fail(problems, f"{name} 没有排除 manual（手工结果会混进自动视图）")
    if all("manual" in sql for sql in (run_sql, case_sql)):
        ok("两条 SQL 都排除 manual")

    lane_match = re.search(r"record KernelLane\((.*?)\)\s*\{", repo_text, re.S)
    if not lane_match:
        fail(problems, "找不到 KernelLane record")
    else:
        body = lane_match.group(1)
        if "passedCases" not in body or "cases" not in body:
            fail(problems, "KernelLane 缺少 cases / passedCases（通过率无法按内核单独算）")
        elif re.search(r"total(PassRate|Rate)|merged(PassRate|Rate)", body):
            fail(problems, "KernelLane 出现合并通过率字段（两内核必须分列）")
        else:
            ok("KernelLane 按内核分列（cases / passedCases），无合并通过率")

    if "bothKernels" not in service_text or "note" not in service_text:
        fail(problems, "UnifiedView 缺 bothKernels / note（只有一个内核时页面无法如实说明）")
    else:
        ok("只有一个内核时如实降级（bothKernels + note）")

    if re.search(r"lanes\.stream\(\).*?mapToInt\(KernelLane::cases\).*?sum\(\)", service_text,
                 re.S):
        fail(problems, "服务层把各内核用例数求和了（合计通过率没有业务含义）")
    else:
        ok("服务层不对各内核用例数求和")


def run() -> int:
    print(f"[check_dual_kernel_view] 视图仓储={REPO_JDBC.relative_to(ROOT)}")
    for file in (REPO_JDBC, REPO, SERVICE):
        if not file.is_file():
            print(f"check_dual_kernel_view: FAIL（找不到 {file}）")
            return 2
    problems: list[str] = []
    check(REPO_JDBC.read_text(encoding="utf-8"), REPO.read_text(encoding="utf-8"),
          SERVICE.read_text(encoding="utf-8"), problems)
    if problems:
        print(f"check_dual_kernel_view: FAIL（{len(problems)} 项）")
        return 1
    print("check_dual_kernel_view: PASS")
    return 0


def self_test() -> int:
    """真注入：删掉 RUN_SQL 的 GROUP BY，分列断言必须判红。"""
    print("--- self-test：去掉 RUN_SQL 的 GROUP BY 后应判失败 ---")
    global REPO_JDBC
    original = REPO_JDBC
    backup = original.read_text(encoding="utf-8")
    broken = backup.replace("             GROUP BY COALESCE(r.kernel, 'robot')",
                            "             ORDER BY COALESCE(r.kernel, 'robot')", 1)
    if broken == backup:
        print("  FAIL   自检注入点没找到（SQL 结构变了），无法自证")
        print("check_dual_kernel_view: SELF-TEST FAIL")
        return 2
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".java", delete=False,
                                         encoding="utf-8") as handle:
            handle.write(broken)
            temp_path = Path(handle.name)
        REPO_JDBC = temp_path
        problems: list[str] = []
        check(REPO_JDBC.read_text(encoding="utf-8"), REPO.read_text(encoding="utf-8"),
              SERVICE.read_text(encoding="utf-8"), problems)
        if not any("GROUP BY" in item for item in problems):
            print(f"  FAIL   自检未按预期判红：{problems}")
            print("check_dual_kernel_view: SELF-TEST FAIL")
            return 1
        print("  OK     去掉 GROUP BY 后判红")
    finally:
        REPO_JDBC = original
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)

    print("check_dual_kernel_view: SELF-TEST PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="双内核统一视图机检")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检")
    args = parser.parse_args()
    return self_test() if args.self_test else run()


if __name__ == "__main__":
    sys.exit(main())
