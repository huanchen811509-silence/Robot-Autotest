#!/usr/bin/env python3
"""可见性 SQL 单点机检（v5.9.0 / FR-v5.9-04 / W59-05 判据①）。

规约：`application/iam` 包内**任何 SQL 字符串**里出现的 `project_member` / `project_group_role`
只能来自 `ProjectVisibilitySql.java`。别处再写一份就是「列表与判权两套口径」的复发
（本次修的正是这个：列表认成员表、判权也认成员表，owner 与组授权两边都不认）。

注意：审计资源名（`audit.recordChange("...", "project_member", ...)`）不是 SQL，不算违规。
因此判据是「同一行里既有 SQL 关键字（FROM/JOIN/INTO/UPDATE/DELETE）又有表名」。

用法：
    python3 tools/check_visibility_sql.py            # 正常检查
    python3 tools/check_visibility_sql.py --self-test # 注入一条违规 → 判红 → 还原 → 判绿
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IAM_DIR = ROOT / "backend/src/main/java/com/qa/autotest/application/iam"
ALLOWED = {"ProjectVisibilitySql.java"}
TABLES = ("project_member", "project_group_role", "user_group_member")
# 判据是「SQL 关键字**紧跟**表名」——审计资源名 `resource = "project_member"` 不是 SQL，
# 而 `"member:update"` 里的 update 也不该被当成 SQL 关键字（首版机检就误报过这两类）。
SQL_REF = re.compile(
    r"\b(FROM|JOIN|INTO|UPDATE|DELETE\s+FROM)\s+`?(?:\w+`?\.)?(?:"
    + "|".join(TABLES)
    + r")\b",
    re.IGNORECASE,
)
# 注释行与 javadoc 行里的表名是说明文字，不是 SQL。
COMMENT = re.compile(r"^\s*(\*|//|/\*)")


def scan() -> list[str]:
    problems: list[str] = []
    for path in sorted(IAM_DIR.glob("*.java")):
        if path.name in ALLOWED:
            continue
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if COMMENT.match(line):
                continue
            if SQL_REF.search(line):
                problems.append(f"{path.name}:{lineno}: SQL 里出现关联表，应改用 ProjectVisibilitySql -> {line.strip()}")
    return problems


def main() -> int:
    self_test = "--self-test" in sys.argv
    problems = scan()

    if self_test:
        if problems:
            print("[FAIL] --self-test 前置不干净：先修好现有违规再自测")
            for item in problems:
                print("  " + item)
            return 1
        target = IAM_DIR / "AccessControlService.java"
        original = target.read_text(encoding="utf-8")
        injected = original.replace(
            "public class AccessControlService {",
            "public class AccessControlService {\n"
            "    // self-test injection\n"
            '    private static final String BAD = "SELECT 1 FROM project_member WHERE user_id = ?";',
            1,
        )
        if injected == original:
            print("[FAIL] --self-test 注入点未找到（源码结构变了？）")
            return 1
        target.write_text(injected, encoding="utf-8")
        try:
            injected_problems = scan()
        finally:
            target.write_text(original, encoding="utf-8")
        if not injected_problems:
            print("[FAIL] --self-test 注入了违规 SQL 却没有判红")
            return 1
        restored = scan()
        if restored:
            print("[FAIL] --self-test 还原后仍有违规")
            return 1
        print("[PASS] --self-test：注入 → 判红 → 还原 → 判绿")
        return 0

    if problems:
        print(f"[FAIL] 可见性 SQL 未收敛：{len(problems)} 处")
        for item in problems:
            print("  " + item)
        return 1
    print(f"[PASS] 可见性 SQL 单点：{len(TABLES)} 张表的 SQL 只出现在 ProjectVisibilitySql.java")
    return 0


if __name__ == "__main__":
    sys.exit(main())
