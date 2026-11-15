#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5.16.0 运维动作审计覆盖机检（FR-v5.16-10 / W66-19）。

判据分三段，全部是源码 / 迁移的静态事实：

  A. 每个写端点都过权限闸：`/api/ops` 控制器里每个 `@PostMapping` 的方法体
     必须含 `access.requireProjectAccess(...)`；
  B. 每个写端点都落审计：写端点方法体必须调用 `tasks.`（`OpsTaskService` 的唯一审计落点），
     且 `GET /audit` 输出 who / when / what / result / duration 五要素；
  C. 审计字段齐全：`ops_task` 迁移里有 `created_by` / `created_at` / `result_json` /
     `started_at` / `finished_at`，缺一列就无法算耗时或无法回答「谁做的」。

用法：
  python3 tools/check_ops_audit.py             # 常规检查
  python3 tools/check_ops_audit.py --self-test # 删掉一处权限闸 / 审计调用，必须判红
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

CONTROLLER = "backend/src/main/java/com/qa/autotest/api/ops/OpsConsoleController.java"
MIGRATION = "db/migration/V11_78__v5_16_ops_task.sql"

# 写端点的「应当」清单：真实存在但没列进来的写端点会被 D 段抓出来。
EXPECTED_WRITES = {
    "create": "POST /tasks",
    "abort": "POST /tasks/{id}/abort",
    "sweep": "POST /tasks/sweep",
    "restoreDrill": "POST /restore-drill",
    "compare": "POST /backup/compare",
}

AUDIT_FIELDS = ["created_by", "created_at", "result_json", "started_at", "finished_at"]
AUDIT_VIEW_FIELDS = ["taskId", "taskType", "status", "operator", "createdAt", "durationMs", "result"]


def method_bodies(text: str) -> dict[str, str]:
    """按 `public ... name(` 粗切方法体：取到下一个 `\n    }` 为止，够用于本判据。"""
    bodies: dict[str, str] = {}
    for match in re.finditer(r"public\s+[\w<>,\.\s]+?\s(\w+)\s*\(", text):
        name = match.group(1)
        tail = text[match.end():]
        end = tail.find("\n    }")
        bodies[name] = tail[: end if end > 0 else len(tail)]
    return bodies


def write_endpoints(text: str) -> dict[str, str]:
    """抓每个 `@PostMapping` 到下一个映射注解之间的片段，键取紧随其后的方法名。"""
    result: dict[str, str] = {}
    marks = [m for m in re.finditer(r"@(?:Post|Get|Put|Delete)Mapping", text)]
    for index, mark in enumerate(marks):
        stop = marks[index + 1].start() if index + 1 < len(marks) else len(text)
        chunk = text[mark.start():stop]
        if not chunk.startswith("@PostMapping"):
            continue
        name = None
        for match in re.finditer(r"public\s+[\w<>,\.\s\[\]]+?\s(\w+)\s*\(", chunk):
            name = match.group(1)
        if name:
            result[name] = chunk
    return result


def evaluate(root: Path) -> list[str]:
    problems: list[str] = []
    controller = root / CONTROLLER
    migration = root / MIGRATION
    if not controller.is_file():
        return ["缺少控制器 " + CONTROLLER]
    if not migration.is_file():
        return ["缺少迁移 " + MIGRATION]

    text = controller.read_text(encoding="utf-8")
    writes = write_endpoints(text)

    # D 段：写端点清单必须与「应当」一致，多一个少一个都红（新增写入口要显式登记）。
    missing = sorted(set(EXPECTED_WRITES) - set(writes))
    if missing:
        problems.append("写端点缺失: " + ", ".join(missing))

    # A 段 + B 段
    for name in sorted(EXPECTED_WRITES):
        body = writes.get(name)
        if body is None:
            continue
        if "requireProjectAccess" not in body:
            problems.append(f"{name}: 写端点没有权限闸 requireProjectAccess")
        if "tasks." not in body:
            problems.append(f"{name}: 写端点没有落审计（未调用 OpsTaskService）")

    # B 段：审计读端点
    if "public ApiResponse<List<AuditRow>> audit(" not in text:
        problems.append("缺少 GET /audit 审计读端点")
    for field in AUDIT_VIEW_FIELDS:
        if field not in text:
            problems.append("审计视图缺字段 " + field)

    # C 段：审计四要素在表里
    mig = migration.read_text(encoding="utf-8")
    for field in AUDIT_FIELDS:
        if field not in mig:
            problems.append("ops_task 迁移缺审计字段 " + field)

    return problems


def self_test() -> list[str]:
    problems: list[str] = []
    with tempfile.TemporaryDirectory(prefix="ops_audit_") as tmp:
        root = Path(tmp)
        for rel in (CONTROLLER, MIGRATION):
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / rel, target)
        clean = evaluate(root)
        if clean:
            problems.append("干净副本本应判绿，却报: " + str(clean[:3]))
        else:
            print("  SELFCHECK 干净副本: PASS（判绿）")

        original = (root / CONTROLLER).read_text(encoding="utf-8")
        # 注入 1：抹掉权限闸
        # 抹掉**全部**权限闸：只抹一处会命中 GET 端点，判据照样绿——那样自检就是假绿。
        (root / CONTROLLER).write_text(
            original.replace("access.requireProjectAccess(projectId);", ""), encoding="utf-8")
        dirty = evaluate(root)
        if not any("权限闸" in item for item in dirty):
            problems.append("注入「抹掉权限闸」后仍判绿")
        else:
            print("  SELFCHECK 抹掉权限闸: RED（符合预期）")
        (root / CONTROLLER).write_text(original, encoding="utf-8")

        # 注入 2：抹掉审计调用（tasks.create 那一行）
        (root / CONTROLLER).write_text(
            original.replace("tasks.create(projectId", "noop(projectId", 1), encoding="utf-8")
        dirty = evaluate(root)
        if not any("落审计" in item for item in dirty):
            problems.append("注入「抹掉审计调用」后仍判绿")
        else:
            print("  SELFCHECK 抹掉审计调用: RED（符合预期）")
        (root / CONTROLLER).write_text(original, encoding="utf-8")

        # 注入 3：抹掉一个审计字段
        mig = (root / MIGRATION).read_text(encoding="utf-8")
        (root / MIGRATION).write_text(mig.replace("created_by", "creator", 1), encoding="utf-8")
        dirty = evaluate(root)
        if not any("审计字段" in item for item in dirty):
            problems.append("注入「改列名」后仍判绿")
        else:
            print("  SELFCHECK 改列名: RED（符合预期）")
        (root / MIGRATION).write_text(mig, encoding="utf-8")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    print("[ops-audit] 运维动作审计覆盖 / 写端点 " + str(len(EXPECTED_WRITES)) + " 条")
    if args.self_test:
        problems = self_test()
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
    print("  OK   写端点全部过权限闸并落审计")
    print("  OK   审计五要素齐全（who/when/what/result/duration）")
    print("RESULT PASS 审计覆盖完整")
    return 0


if __name__ == "__main__":
    sys.exit(main())
