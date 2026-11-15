#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DTO 与表结构一致性机检（v5.11.0 / FR-v5.11-04 / W61-10）。

为什么需要这条机检
------------------
`frontend/src/views/__tests__/columnIdempotency.spec.ts` 守的是「表格列 ⊆ 详情字段」，
但那只证明**前端**这两份清单互相对得上。还有一层没人守：详情字段本身有没有数据来源？
——DTO 里加一个 `reviewStatus`，SQL 里忘了 `SELECT review_status`，前端照样编译、
单测照样绿，运行时那个字段永远是 null，用户看到「源表无值」，却没人知道是「库里没有」
还是「SQL 没取」。

守什么
------
1. `CaseDetailView` / `GlobalCaseRow` 的每个字段，必须满足二者之一：
   - 驼峰转下划线后是 `test_case` 的真实列（迁移脚本里存在）；
   - 在 `DERIVED` 里显式声明来源与算法（计算字段、跨表字段、脱敏常量）。
2. `DERIVED` 不得声明不存在的字段（防止声明表自我膨胀成「万能表」）。
3. 表列解析结果不得为空（解析逻辑坏了要报错，而不是"恰好没有违规"）。

计算字段为什么允许
------------------
PRD FR-v5.11-04 降级预案：`sourceBadge`、`scriptName` 这类确实是算出来的。
但**必须写下算法**——不写就退化成「源表无此列」那种谎报。

用法
----
    python3 tools/check_dto_columns.py              # 常规检查
    python3 tools/check_dto_columns.py --self-test  # 真注入一个无来源字段，必须判红

退出码：0 = 通过；1 = 违规；2 = 扫描失败（找不到文件 / 解析出 0 列）。
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION_DIR = ROOT / "db" / "migration"
SERVICE = ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest" / "application" / "caseasset" / "GlobalCaseService.java"

# 记录名 → 它的字段允许从哪张表取。目前两处契约都直接落在 test_case 上。
RECORD_TABLES = {
    "CaseDetailView": "test_case",
    "GlobalCaseRow": "test_case",
}

# 计算 / 跨表 / 脱敏字段：字段名 → (来源, 算法或说明)。
# 这里每一条都是**承诺**：写了就得真按这个算。写不出来的字段说明它根本不该在 DTO 里。
DERIVED = {
    "syncJobId": ("sync_job_item", "按 local_id 或 remote_id 取 MAX(job_id)"),
    "localOverride": ("test_case.source", "source = 'local_override' 时为 true"),
    "sourceBadge": (
        "计算",
        "localOverride → LOCAL_OVERRIDE；有 syncJobId → GLOBAL_SYNC；否则 LOCAL",
    ),
    "keyMaskedCredential": ("脱敏", "恒为 '****'，绝不返回凭据原文"),
    "steps": ("test_case_step", "按 test_case_id 取 step_no / action / expectation"),
    "scriptPath": ("case_script_binding", "按 project_id + case_id + kernel 取 script_path"),
    "scriptName": ("计算", "fileNameOf(scriptPath)：取最后一个路径段的文件名"),
    "scriptNodeId": ("case_script_binding", "取 node_id（pytest nodeid）"),
    "bindingState": (
        "计算",
        "verified=1 → BOUND_VERIFIED；有绑定但未校验 → BOUND_UNVERIFIED；"
        "无绑定且内核支持脚本 → UNBOUND；内核不支持（RF）→ NOT_APPLICABLE",
    ),
    "otherScriptCount": ("case_script_binding", "同一用例除首选内核外的绑定条数"),
    "scriptAbsoluteSource": ("计算", "原始 script_path 是否为绝对路径（历史脏数据）"),
}

CREATE_HEAD = re.compile(r"^\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?", re.I)
ALTER_HEAD = re.compile(r"^\s*ALTER\s+TABLE\s+`?(\w+)`?", re.I)
ADD_COL = re.compile(
    r"\bADD\s+(?:COLUMN\s+)?(?!INDEX\b|KEY\b|UNIQUE\b|CONSTRAINT\b|FOREIGN\b|PRIMARY\b"
    r"|FULLTEXT\b|SPATIAL\b|CHECK\b)`?([a-z_][a-z0-9_]*)`?",
    re.I,
)
COL_LINE = re.compile(r"^\s*`?([a-z_][a-z0-9_]*)`?\s+[A-Za-z]", re.I)
SQL_COMMENT = re.compile(r"/\*.*?\*/|--[^\n]*", re.S)
SKIP_WORDS = {
    "PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT",
    "FOREIGN", "FULLTEXT", "SPATIAL", "CHECK",
}


def ok(message: str) -> None:
    print(f"  OK     {message}")


def fail(problems: list[str], message: str) -> None:
    print(f"  FAIL   {message}")
    problems.append(message)


def snake(name: str) -> str:
    """camelCase → snake_case（`scriptNodeId` → `script_node_id`）。"""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def parse_tables() -> dict[str, list[str]]:
    """从迁移脚本解析「表 → 列」。CREATE 与后续 ALTER ADD COLUMN 都算。"""
    if not MIGRATION_DIR.is_dir():
        raise SystemExit(f"check_dto_columns: 找不到迁移目录 {MIGRATION_DIR}")
    tables: dict[str, list[str]] = {}

    def add(table: str, column: str) -> None:
        cols = tables.setdefault(table.lower(), [])
        if column.lower() not in cols:
            cols.append(column.lower())

    for path in sorted(MIGRATION_DIR.glob("*.sql")):
        text = SQL_COMMENT.sub("", path.read_text(encoding="utf-8"))
        for statement in text.split(";"):
            if not statement.strip():
                continue
            head = CREATE_HEAD.match(statement)
            if head:
                body_start = statement.find("(", head.end())
                body_end = statement.rfind(")")
                if body_start < 0 or body_end <= body_start:
                    continue
                for line in statement[body_start + 1:body_end].splitlines():
                    word = line.strip().split(" ")[0].strip("`").upper()
                    if word in SKIP_WORDS:
                        continue
                    match = COL_LINE.match(line)
                    if match:
                        add(head.group(1), match.group(1))
                continue
            head = ALTER_HEAD.match(statement)
            if head:
                for column in ADD_COL.findall(statement):
                    add(head.group(1), column)
    return tables


def record_fields(text: str, record: str) -> list[str]:
    """取 `public record X(...)` 的字段名（按顶层逗号切，`<>` 里的逗号不算）。"""
    match = re.search(rf"public record {record}\(", text)
    if not match:
        raise SystemExit(f"check_dto_columns: 找不到 record {record}（结构变了，脚本要同步）")
    index = match.end()
    depth = 1
    start = index
    while index < len(text) and depth > 0:
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        index += 1
    body = re.sub(r"//[^\n]*", "", text[start:index - 1])
    parts: list[str] = []
    buffer = ""
    angle = 0
    for char in body:
        if char == "<":
            angle += 1
        elif char == ">":
            angle -= 1
        if char == "," and angle == 0:
            parts.append(buffer)
            buffer = ""
        else:
            buffer += char
    if buffer.strip():
        parts.append(buffer)
    fields = []
    for part in parts:
        tokens = part.split()
        if len(tokens) < 2:
            continue
        fields.append(tokens[-1])
    return fields


def check(service_text: str, tables: dict[str, list[str]]) -> list[str]:
    problems: list[str] = []
    seen_fields: set[str] = set()

    for record, table in RECORD_TABLES.items():
        columns = set(tables.get(table, []))
        if not columns:
            fail(problems, f"表 {table} 解析出 0 列（解析逻辑或迁移目录坏了，不敢给出 PASS）")
            continue
        fields = record_fields(service_text, record)
        if not fields:
            fail(problems, f"record {record} 解析出 0 个字段（结构变了，脚本要同步）")
            continue
        unknown: list[str] = []
        for field in fields:
            seen_fields.add(field)
            if snake(field) in columns or field in DERIVED:
                continue
            unknown.append(field)
        if unknown:
            fail(problems, f"{record} 有 {len(unknown)} 个字段既不是 {table} 的列，"
                           f"也没在 DERIVED 声明来源：{unknown}")
        else:
            ok(f"{record}: {len(fields)} 个字段全部有来源（列 / DERIVED 声明）")

    stale = sorted(field for field in DERIVED if field not in seen_fields)
    if stale:
        fail(problems, f"DERIVED 声明了 {len(stale)} 个不存在的字段（声明表在自我膨胀）：{stale}")
    else:
        ok(f"DERIVED 声明 {len(DERIVED)} 项，全部对得上真实字段")

    return problems


def self_test(service_text: str, tables: dict[str, list[str]]) -> int:
    """真注入一个无来源字段：要么被拦住，要么说明这条机检形同虚设。"""
    print("[self-test] 注入 `String bogusNoSourceColumn` 到 CaseDetailView，必须判红")
    injected = service_text.replace(
        "public record CaseDetailView(",
        "public record CaseDetailView(\n            String bogusNoSourceColumn,",
        1,
    )
    if injected == service_text:
        print("check_dto_columns: FAIL（self-test 注入点没找到，脚本要同步）")
        return 2
    problems = check(injected, tables)
    if not problems:
        print("check_dto_columns: FAIL（self-test 注入后仍然 PASS —— 这条机检是假绿）")
        return 1
    hit = [item for item in problems if "bogusNoSourceColumn" in item]
    if not hit:
        print("check_dto_columns: FAIL（判红了，但不是因为注入的字段）")
        return 1
    print(f"[self-test] 已判红：{hit[0]}")
    print("[self-test] 再用真实源码复跑，必须 PASS")
    if check(service_text, tables):
        print("check_dto_columns: FAIL（真实源码本应 PASS）")
        return 1
    print("check_dto_columns: self-test PASS（注入判红 / 还原转绿）")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="DTO 字段 ↔ 表结构一致性机检")
    parser.add_argument("--self-test", action="store_true", help="注入无来源字段验证会判红")
    args = parser.parse_args()

    for path in (SERVICE, MIGRATION_DIR):
        if not path.exists():
            print(f"check_dto_columns: FAIL（找不到 {path}）")
            return 2

    tables = parse_tables()
    service_text = SERVICE.read_text(encoding="utf-8")

    print(f"[check_dto_columns] 迁移表数 = {len(tables)} / 契约文件 = {SERVICE.name}")

    if args.self_test:
        return self_test(service_text, tables)

    problems = check(service_text, tables)
    if problems:
        print(f"check_dto_columns: FAIL（{len(problems)} 项）")
        return 1
    print("check_dto_columns: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
