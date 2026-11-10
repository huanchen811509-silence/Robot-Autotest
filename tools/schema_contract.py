#!/usr/bin/env python3
"""FR-v4.2-02：SchemaIT / db-audit 的同源常量读取器。

默认读 `db/schema-contract.json`；`SCHEMA_CONTRACT_PATH` 可指向临时副本，
用于负向自证（把 expectedTables 改坏后 db-audit 必须判红）。

用法：
    python3 tools/schema_contract.py expected-tables
    python3 tools/schema_contract.py append-only-sql
    python3 tools/schema_contract.py createdAt-required-count
    python3 tools/schema_contract.py created-at-sql
    python3 tools/schema_contract.py dump
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PATH = ROOT / "db" / "schema-contract.json"


def contract_path() -> Path:
    return Path(os.environ.get("SCHEMA_CONTRACT_PATH", str(DEFAULT_PATH)))


def load() -> dict:
    path = contract_path()
    if not path.is_file():
        raise SystemExit(f"[schema-contract] 缺少契约文件: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"[schema-contract] JSON 非法: {exc}") from exc
    if data.get("schemaVersion") != 1:
        raise SystemExit("[schema-contract] schemaVersion != 1")
    expected = data.get("expectedTables")
    if not isinstance(expected, int) or expected <= 0:
        raise SystemExit("[schema-contract] expectedTables 必须是正整数")
    for key in ("appendOnlyTables", "createdAtRequired"):
        value = data.get(key)
        if not isinstance(value, list) or not value or not all(isinstance(v, str) and v for v in value):
            raise SystemExit(f"[schema-contract] {key} 必须是非空字符串数组")
    append_only = set(data["appendOnlyTables"])
    created_at = set(data["createdAtRequired"])
    missing = [name for name in data["createdAtRequired"] if name not in append_only]
    if missing:
        raise SystemExit(f"[schema-contract] createdAtRequired 必须属于 appendOnlyTables: {missing}")
    anchors = data.get("appendOnlyTimeColumns", {})
    if not isinstance(anchors, dict):
        raise SystemExit("[schema-contract] appendOnlyTimeColumns 必须是对象")
    for table, column in anchors.items():
        if table not in append_only:
            raise SystemExit(f"[schema-contract] appendOnlyTimeColumns 的 {table} 必须属于 appendOnlyTables")
        if not isinstance(column, str) or not column.strip():
            raise SystemExit(f"[schema-contract] {table} 的时间锚点列名不能为空")
        if table in created_at:
            raise SystemExit(f"[schema-contract] {table} 已有 created_at，不要再声明时间锚点")
    # append-only 表的「何时发生」必须可追溯：created_at 或业务时间锚点二选一。
    uncovered = sorted(append_only - created_at - set(anchors))
    if uncovered:
        raise SystemExit(f"[schema-contract] append-only 表缺少 created_at / 时间锚点: {uncovered}")
    return data


def sql_list(names: list[str]) -> str:
    return ", ".join("'" + name.replace("'", "''") + "'" for name in names)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    data = load()
    mode = argv[1]
    if mode == "expected-tables":
        print(data["expectedTables"])
    elif mode == "append-only-sql":
        print(sql_list(data["appendOnlyTables"]))
    elif mode == "createdAt-required-count":
        print(len(data["createdAtRequired"]))
    elif mode == "created-at-sql":
        print(sql_list(data["createdAtRequired"]))
    elif mode == "dump":
        print(json.dumps(data, ensure_ascii=False, indent=2))
    else:
        print(f"[schema-contract] 未知模式: {mode}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
