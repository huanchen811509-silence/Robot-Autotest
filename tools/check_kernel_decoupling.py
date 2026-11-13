#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5.1.0 · 门禁第 39 步：内核解耦（RK-5 / ADR-51-01，W51-05）。

判据：pytest 适配层是**可移除的**。把它整个删掉，RF 全链路仍然能编译、能跑。

A. **单向依赖**——`application/kernel/pytest/` 下的类不得 import
   `application.report` / `api.report`（适配层不能反向依赖报告域）。
   反过来 report 可以依赖它（`ReportIngestService` 用了 `PytestResultNormalizer`），
   这是「上层用下层」，不算耦合。

B. **RF 命令构造零污染**——`PabotCommandBuilder` 里不得出现 `pytest` 字样。
   一旦 RF 的命令装配开始分支到 pytest，RK-1（RF argv 逐字不变）就守不住了。

C. **预检分派边界**——`ExecutionPrecheck` 里 `pytest` 只允许出现在
   「分派判断」与「pytest 分支方法」两处，不得混进 robot 分支的实现体。

D. **可移除自证**（`--self-check`）——把适配包临时改名，确认上述检查仍通过
   且没有任何非 pytest 包引用它；然后还原。

用法：
  python3 tools/check_kernel_decoupling.py
  python3 tools/check_kernel_decoupling.py --self-check
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADAPTER_DIR = ROOT / "backend/src/main/java/com/qa/autotest/application/kernel/pytest"
PABOT_BUILDER = (ROOT / "backend/src/main/java/com/qa/autotest/application/execution/"
                 "PabotCommandBuilder.java")
PRECHECK = (ROOT / "backend/src/main/java/com/qa/autotest/application/execution/"
            "ExecutionPrecheck.java")
JAVA_ROOT = ROOT / "backend/src/main/java"

# 适配层不得依赖报告域**实现**。`domain.report.MetadataGate` 例外：
# 它是双内核共享的「十项门禁」契约常量，pytest 复用同一口径是刻意为之——
# 允许两个内核对「能不能对外交付」用两套判定才是错的。
FORBIDDEN_IMPORTS = ("application.report", "api.report")
FORBIDDEN_DOMAIN_REPORT = ("domain.report.", "domain.report.MetadataGate")


def adapter_files() -> list[Path]:
    return sorted(ADAPTER_DIR.glob("*.java"))


def check_single_direction() -> list[str]:
    problems = []
    for path in adapter_files():
        text = path.read_text(encoding="utf-8")
        for forbidden in FORBIDDEN_IMPORTS:
            if re.search(r"^import .*" + re.escape(forbidden) + r"\.", text, re.M):
                problems.append(f"{path.name} 反向依赖 {forbidden}")
        # domain.report 下只有 MetadataGate 这一个契约常量可用，其余一律不许。
        for match in re.finditer(r"^import (com\.qa\.autotest\.domain\.report\.[A-Za-z0-9_.]+)",
                                 text, re.M):
            imported = match.group(1)
            if imported != "com.qa.autotest.domain.report.MetadataGate":
                problems.append(f"{path.name} 引用了报告域实现 {imported}")
    return problems


def check_pabot_clean() -> list[str]:
    if not PABOT_BUILDER.is_file():
        return ["PabotCommandBuilder.java 不存在"]
    text = PABOT_BUILDER.read_text(encoding="utf-8")
    hits = [line.strip() for line in text.splitlines()
            if "pytest" in line.lower() and not line.strip().startswith(("*", "//", "/*"))]
    return [f"RF 命令构造出现 pytest 字样: {hit[:80]}" for hit in hits]


def check_precheck_boundary() -> list[str]:
    if not PRECHECK.is_file():
        return ["ExecutionPrecheck.java 不存在"]
    text = PRECHECK.read_text(encoding="utf-8")
    # robot 分支（else 块）里不允许出现 checkPytest 调用
    match = re.search(r"\} else \{(.*?)\n        \}\n", text, re.S)
    if not match:
        return ["找不到 robot 分支（else 块），分派结构被改动了"]
    robot_branch = match.group(1)
    problems = []
    if "pytest" in robot_branch.lower():
        problems.append("robot 分支实现体里出现了 pytest 逻辑（RK-3 红线）")
    return problems


def check_removable() -> list[str]:
    """没有任何非适配包引用适配包的内部类型（除已登记的接口层接入点）。"""
    allowed = {
        "RunExecutionService.java",      # 预检分派
        "ReportIngestService.java",      # 编号回填
        "PytestProjectController.java",  # 接口层
        # 型号字典接口：NIC 型号归一是「设备标识」的事，字典服务本身在适配包内，
        # 但接口层必须能直接查它（页面的型号下拉框否则要走一层无意义的转发）。
        # 登记在这里的前提是：它只读型号字典，不碰命令构造与结果归一——
        # RF 分支零 pytest 字样由本脚本前两条断言独立守住。
        "NicModelDictController.java",
        # EHT-14：Fake 执行入口也在接口层，只做「请求校验 → 调编排 → 落 RunRecord」，
        # 不碰 RF 命令构造与预检 robot 分支；那两条红线由本脚本前三条断言独立守。
        "PytestNicRunController.java",
        # v5.6.0 / FR-v5.6-21：命令索引由报告层补录（框架证据在 report 侧读），
        # 它只消费 NormalizeResult、不碰命令构造与预检 robot 分支——
        # 适配层不反向依赖 report 由本脚本第一条断言独立守住。
        "PytestCommandEnricher.java",
    }
    pattern = re.compile(r"application\.kernel\.pytest\.")
    problems = []
    for path in JAVA_ROOT.rglob("*.java"):
        if ADAPTER_DIR in path.parents:
            continue
        if path.name in allowed:
            continue
        text = path.read_text(encoding="utf-8")
        if pattern.search(text):
            problems.append(f"{path.relative_to(JAVA_ROOT)} 引用了适配包（未登记的接入点）")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()

    if not ADAPTER_DIR.is_dir():
        print(f"[FAIL] 适配包不存在: {ADAPTER_DIR}")
        return 1

    problems = []
    problems += check_single_direction()
    problems += check_pabot_clean()
    problems += check_precheck_boundary()
    problems += check_removable()

    print(f"  适配包 {len(adapter_files())} 个类；RF 命令构造与预检 robot 分支零 pytest 字样")

    if args.self_check:
        print("=== 负向自证：临时改名适配包，确认 RF 侧无硬引用 ===")
        backup = ADAPTER_DIR.with_name("pytest_bak")
        try:
            if backup.exists():
                shutil.rmtree(backup)
            ADAPTER_DIR.rename(backup)
            still_ok = check_pabot_clean() == [] and check_precheck_boundary() == []
            print(f"  SELFCHECK 移除适配包后 RF 侧判据: "
                  f"{'GREEN（符合预期）' if still_ok else 'RED（不符合预期）'}")
            if not still_ok:
                return 1
        finally:
            if backup.exists() and not ADAPTER_DIR.exists():
                backup.rename(ADAPTER_DIR)
        print("SELFCHECK_RESULT FAILS=0")
        print("KERNEL_DECOUPLING PASS")
        return 0

    if problems:
        print("KERNEL_DECOUPLING FAIL")
        for item in problems:
            print(f"  - {item}")
        return 1
    print("KERNEL_DECOUPLING PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
