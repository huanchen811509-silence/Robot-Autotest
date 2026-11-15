#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5.16.0 四条运维硬护栏机检（FR-v5.16-10 / W66-18）。

判据（全部为源码 / 迁移静态事实，缺一条即红）：
  P1 dry-run 默认：backup.sh / purge-demo.sh / update.sh / /api/ops/tasks 端点；
  P2 恢复演练库名守卫：restore-drill 端点必须显式调用 requireDrillDatabase；
  P3 任务被杀判 ABORTED：ops_task 迁移、OpsTaskService、sweep 端点三处同源；
  P4 全量审计：ops_task 带审计四列 + 所有仓储 SQL 过 TenantGuard。

用法：
  python3 tools/check_ops_guardrails.py               # 常规检查
  python3 tools/check_ops_guardrails.py --self-check  # 临时副本逐条注入脏数据，必须判红

不改 scripts/gates.sh：该脚本自带「删步骤判红」的结构自证，新增步骤会破坏它。
"""
from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

RULES = [
    ("P1 backup 默认 dry-run", "scripts/ops/backup.sh", ["APPLY=0", "dry-run"]),
    ("P1 purge 双护栏", "scripts/ops/purge-demo.sh", ["PURGE_DEMO_CONFIRM", "is_demo"]),
    ("P1 update 默认 dry-run", "scripts/ops/update.sh", ["--dry-run", "--apply"]),
    ("P1 任务端点 dryRun 缺省 true", "backend/src/main/java/com/qa/autotest/api/ops/OpsConsoleController.java",
     ["dryRun", "Boolean.FALSE.equals"]),
    ("P2 恢复演练库名守卫", "backend/src/main/java/com/qa/autotest/api/ops/OpsConsoleController.java",
     ["requireDrillDatabase"]),
    ("P2 守卫实现只认 _it", "backend/src/main/java/com/qa/autotest/application/ops/OpsTaskService.java",
     ["endsWith(\"_it\")"]),
    ("P3 迁移含 ABORTED 状态", "db/migration/V11_78__v5_16_ops_task.sql", ["ABORTED", "lease_until"]),
    ("P3 任务服务含 ABORTED 与 sweep", "backend/src/main/java/com/qa/autotest/application/ops/OpsTaskService.java",
     ["ABORTED", "sweep"]),
    ("P3 仓储超时对账写 ABORTED", "backend/src/main/java/com/qa/autotest/infrastructure/OpsTaskRepositoryJdbc.java",
     ["ABORTED", "lease_until < NOW(3)"]),
    ("P4 审计四列", "db/migration/V11_78__v5_16_ops_task.sql",
     ["created_at", "created_by", "updated_at", "updated_by"]),
    ("P4 租户守卫", "backend/src/main/java/com/qa/autotest/infrastructure/OpsTaskRepositoryJdbc.java",
     ["TenantGuard.requireProjectScope"]),
    ("P4 互斥锁", "db/migration/V11_78__v5_16_ops_task.sql",
     ["active_lock", "uk_ops_task_active"]),
]


def evaluate(root: Path) -> list[str]:
    problems: list[str] = []
    for title, rel, needles in RULES:
        path = root / rel
        if not path.is_file():
            problems.append(title + ": 缺少文件 " + rel)
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for needle in needles:
            if needle not in text:
                problems.append(title + ": 缺少关键事实 " + needle)
    return problems


def self_check() -> list[str]:
    """逐条在临时副本里删掉一个关键事实，判据必须变红；干净副本必须判绿。"""
    problems: list[str] = []
    with tempfile.TemporaryDirectory(prefix="ops_guard_") as tmp:
        root = Path(tmp)
        needed = sorted({rel for _, rel, _ in RULES})
        for rel in needed:
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / rel, target)
        clean = evaluate(root)
        if clean:
            problems.append("干净副本本应判绿，却报: " + str(clean[:3]))
        else:
            print("  SELFCHECK 干净副本: PASS（判绿）")
        for title, rel, needles in RULES:
            target = root / rel
            original = target.read_text(encoding="utf-8")
            dirty_text = original
            for needle in needles:
                if needle not in original:
                    problems.append(title + ": 自检样本里找不到 " + needle)
                dirty_text = dirty_text.replace(needle, "")
            target.write_text(dirty_text, encoding="utf-8")
            dirty = evaluate(root)
            if not any(title in problem for problem in dirty):
                problems.append(title + ": 删掉关键事实后仍判绿（判据失灵）")
            else:
                print("  SELFCHECK " + title + ": RED（符合预期）")
            target.write_text(original, encoding="utf-8")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    print("[ops-guardrails] 四条硬护栏 / " + str(len(RULES)) + " 条静态判据")
    if args.self_check:
        problems = self_check()
        print("SELFCHECK_RESULT " + ("FAILS=%d" % len(problems) if problems else "FAILS=0"))
        for item in problems:
            print("  FAIL " + item)
        return 1 if problems else 0
    problems = evaluate(ROOT)
    if problems:
        for item in problems:
            print("  FAIL " + item)
        print("RESULT FAILS=%d" % len(problems))
        return 1
    for title, _, _ in RULES:
        print("  OK   " + title)
    print("RESULT PASS 四条硬护栏均有源码事实（" + str(len(RULES)) + " 条判据）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
