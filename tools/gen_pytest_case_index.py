#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成公共用例库索引证据（v5.1.0 · W51-08/W51-11 的数据源）。

为什么要有这一步：覆盖率分母（469 条）存在用例库的 Excel 里，而门禁不能
每次去读一个 Excel——路径在另一个仓、解析依赖 openpyxl、且 Excel 会被人改。
这里把「编号 / 大类 / 优先级 / 评审态」抽成一份 JSON 证据入库，
门禁只读 JSON；Excel 变了就重跑本脚本，diff 就是「用例库改了什么」。

产物：`docs/evidence/v5.1.0/pytest/case-index.json`

用法：
  python3 tools/gen_pytest_case_index.py [--cases-root /path/to/pytest-cases]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

DEFAULT_ROOT = Path("/mnt/e/workspace/自动化测试/pytest-cases")
OUTPUT = Path(__file__).resolve().parents[1] / "docs/evidence/v5.1.0/pytest/case-index.json"
SHEET = "详细测试流程"
CASE_ID = re.compile(r"^[A-Za-z][A-Za-z0-9]*-[A-Za-z][A-Za-z0-9]*-(\d+)$")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases-root", default=str(DEFAULT_ROOT))
    args = parser.parse_args()

    root = Path(args.cases_root)
    if not root.is_dir():
        print(f"[FAIL] 用例库目录不存在: {root}")
        return 1
    books = sorted(root.glob("*.xlsx"))
    if not books:
        print(f"[FAIL] 用例库目录下没有 xlsx: {root}")
        return 1
    book = books[0]
    try:
        import openpyxl
    except ImportError:
        print("[FAIL] 缺少 openpyxl")
        return 1

    wb = openpyxl.load_workbook(book, read_only=True, data_only=True)
    if SHEET not in wb.sheetnames:
        print(f"[FAIL] 缺少 sheet: {SHEET}（现有 {wb.sheetnames}）")
        return 1
    ws = wb[SHEET]

    cases = []
    seen = set()
    for row in ws.iter_rows(min_row=4, values_only=True):
        raw = row[0]
        if raw is None:
            continue
        code = str(raw).strip()
        if not CASE_ID.match(code):
            continue
        if code in seen:
            continue
        seen.add(code)
        cases.append({
            "code": code,
            "category": str(row[1]).strip() if row[1] is not None else "",
            "priority": str(row[5]).strip() if len(row) > 5 and row[5] is not None else "",
            "title": str(row[2]).strip() if len(row) > 2 and row[2] is not None else "",
        })

    # 脚本清单：文件名与 docstring 编号两条线索都记，与 Java 侧扫描口径一致。
    scripts = []
    for path in sorted((root / "tests").rglob("test_*.py")):
        text = path.read_text(encoding="utf-8", errors="replace")
        declared = re.search(r"用例编号\s*[:：]\s*([A-Za-z][A-Za-z0-9]*-[A-Za-z][A-Za-z0-9]*-\d+)", text)
        functions = re.findall(r"^def\s+(test_\w+)\s*\(", text, re.M)
        scripts.append({
            "path": str(path.relative_to(root)).replace("\\", "/"),
            "case_code": declared.group(1).upper() if declared else "",
            "source": "DOCSTRING" if declared else "NONE",
            "test_functions": functions,
        })

    payload = {
        "schema": "pytest-case-index/1",
        "source_book": book.name,
        "source_sheet": SHEET,
        "totals": {
            "cases": len(cases),
            "scripts": len(scripts),
            "scripts_with_declared_code": sum(1 for s in scripts if s["case_code"]),
        },
        "priority_counts": {},
        "cases": cases,
        "scripts": scripts,
    }
    for item in cases:
        key = item["priority"] or "(空)"
        payload["priority_counts"][key] = payload["priority_counts"].get(key, 0) + 1

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"  用例 {len(cases)} 条 / 脚本 {len(scripts)} 个 / 有编号声明 "
          f"{payload['totals']['scripts_with_declared_code']} 个")
    print(f"  写出 {OUTPUT.relative_to(OUTPUT.parents[3])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
