#!/usr/bin/env python3
"""W57-43 · 全站列表 CRUD 台账机检。

判据（只认 ``docs/evidence/v5.7.0/list-crud-matrix.md`` 这一份台账）：

  1. 主表「列表 CRUD 台账」行数 ≥ 24（枚举完整性，少一页就是没盘全）；
  2. 每行 8 列齐全、分类 ∈ {A,B,C}、对应 FR 非空（现状不许空着按目标倒推）；
  3. C 类（只读派生白名单）每条必须写具体理由，长度 ≥8 且不含占位词；
  4. **白名单外缺口即 FAIL**：缺口列不是 ``无`` / ``不适用`` / ``-`` 的记录，
     其路由必须出现在「缺口白名单」表里；白名单每条同样要求具体理由。

输出固定一行：``LIST_CRUD_RESULT PASS=n FAIL=n``

``--self-test`` 是**真注入**：把台账复制成临时文件，分别注入
「白名单外的缺口」与「C 类占位理由（暂时不做）」，两次都必须判红；
删掉临时文件（还原）后对真实台账再判一次必须判绿。全程不写真实台账。
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "docs" / "evidence" / "v5.7.0" / "list-crud-matrix.md"

MIN_ROWS = 24
EXPECTED_COLUMNS = 8
NO_GAP = {"无", "-", ""}
PLACEHOLDERS = ("暂时不做", "待定", "TODO", "todo", "后续再说", "TBD", "占位", "待补充")
TABLE_SEP_RE = re.compile(r"^[-: ]+$")


def section(text: str, heading: str) -> str:
    """取某个 ## 标题到下一个 ## 标题之间的正文。

    必须按「整行等于标题」匹配：口径说明里会用反引号引用 ``## 缺口白名单``，
    用 str.find() 会命中那段正文、把真正的表格截断掉（实测出现过白名单全表未解析）。
    """
    lines = text.splitlines(keepends=True)
    start = None
    for i, line in enumerate(lines):
        if line.rstrip("\n").strip() == heading.strip():
            start = i
            break
    if start is None:
        return ""
    out: list[str] = []
    for line in lines[start + 1:]:
        if line.startswith("## "):
            break
        out.append(line)
    return "".join(out)


def cells(line: str) -> list[str]:
    """表格行 -> 单元格（去掉首尾空元素）。"""
    if not line.lstrip().startswith("|"):
        return []
    parts = [c.strip() for c in line.strip().strip("|").split("|")]
    return parts


def is_separator(parts: list[str]) -> bool:
    return bool(parts) and all(TABLE_SEP_RE.match(p or "-") for p in parts)


def parse_main(text: str) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for line in section(text, "## 列表 CRUD 台账").splitlines():
        parts = cells(line)
        if len(parts) != EXPECTED_COLUMNS or is_separator(parts):
            continue
        if parts[0] == "路由" and parts[2] == "分类":
            continue  # 表头
        rows.append({
            "route": parts[0], "page": parts[1], "kind": parts[2],
            "current": parts[3], "target": parts[4], "gap": parts[5],
            "fr": parts[6], "reason": parts[7],
        })
    return rows


def parse_whitelist(text: str) -> dict[str, str]:
    whitelist: dict[str, str] = {}
    for line in section(text, "## 缺口白名单").splitlines():
        parts = cells(line)
        if len(parts) != 2 or is_separator(parts):
            continue
        if parts[0] == "路由":
            continue
        whitelist[parts[0]] = parts[1]
    return whitelist


def reason_ok(reason: str) -> bool:
    if len(reason.strip()) < 8:
        return False
    return not any(word in reason for word in PLACEHOLDERS)


def has_gap(gap: str) -> bool:
    g = gap.strip()
    return g not in NO_GAP and "不适用" not in g


def evaluate(path: Path) -> tuple[int, int, list[str]]:
    """返回 (pass, fail, details)。"""
    passed = 0
    failed = 0
    details: list[str] = []
    if not path.is_file():
        return 0, 1, [f"找不到台账 {path}"]

    text = path.read_text(encoding="utf-8")
    rows = parse_main(text)
    whitelist = parse_whitelist(text)

    # 判据 1：枚举完整性
    if len(rows) >= MIN_ROWS:
        passed += 1
    else:
        failed += 1
        details.append(f"主表只有 {len(rows)} 行，要求 ≥ {MIN_ROWS}")

    for row in rows:
        route = row["route"]
        # 判据 2：字段齐全 / 分类合法 / FR 非空
        fields_ok = all(row[k].strip() for k in ("route", "page", "current", "target", "gap", "fr"))
        kind_ok = row["kind"] in ("A", "B", "C")
        if fields_ok and kind_ok:
            passed += 1
        else:
            failed += 1
            details.append(f"{route} 字段缺失或分类非法（{row['kind']}）")

        # 判据 3：C 类白名单理由
        if row["kind"] == "C":
            if reason_ok(row["reason"]):
                passed += 1
            else:
                failed += 1
                details.append(f"{route} C 类白名单理由不具体或含占位词：{row['reason'][:24]}")

        # 判据 4：白名单外缺口即 FAIL
        if has_gap(row["gap"]):
            if route in whitelist:
                passed += 1
            else:
                failed += 1
                details.append(f"{route} 存在白名单外缺口：{row['gap'][:40]}")
        else:
            passed += 1

    # 判据 4b：白名单每条理由必须具体
    for route, reason in whitelist.items():
        if reason_ok(reason):
            passed += 1
        else:
            failed += 1
            details.append(f"缺口白名单 {route} 理由不具体或含占位词：{reason[:24]}")

    return passed, failed, details


def self_test() -> int:
    """真注入 → 判红 → 还原 → 判绿。返回 0 = 自检通过。"""
    tmpdir = Path(tempfile.mkdtemp(prefix="list-crud-selftest-"))
    ok = True
    clean_pass = 0
    clean_fail = 0
    try:
        text = MATRIX.read_text(encoding="utf-8")

        # 注入 1：给一个原本「无缺口」的路由塞一个白名单外缺口。
        lines = text.split("\n")
        injected_gap = False
        for i, line in enumerate(lines):
            if line.startswith("| `/cases` |") and "| 无 |" in line:
                lines[i] = line.replace("| 无 |", "| 未接白名单外缺口注入 |", 1)
                injected_gap = True
                break
        if not injected_gap:
            print("[SELFTEST FAIL] 没能定位注入点 /cases")
            ok = False
        dirty_gap = tmpdir / "gap.md"
        dirty_gap.write_text("\n".join(lines), encoding="utf-8")
        _p, f_gap, _d = evaluate(dirty_gap)
        if f_gap == 0:
            print("[SELFTEST FAIL] 注入白名单外缺口后未判红")
            ok = False
        else:
            print(f"[SELFTEST PASS] 注入白名单外缺口判红 {f_gap} 项")

        # 注入 2：把一个 C 类白名单理由改成占位词。
        lines2 = text.split("\n")
        injected_reason = False
        for i, line in enumerate(lines2):
            if line.startswith("| `/workbench` |"):
                parts = line.split("|")
                parts[-2] = " 暂时不做 "
                lines2[i] = "|".join(parts)
                injected_reason = True
                break
        if not injected_reason:
            print("[SELFTEST FAIL] 没能定位注入点 /workbench")
            ok = False
        dirty_reason = tmpdir / "reason.md"
        dirty_reason.write_text("\n".join(lines2), encoding="utf-8")
        _p2, f_reason, _d2 = evaluate(dirty_reason)
        if f_reason == 0:
            print("[SELFTEST FAIL] 注入占位理由后未判红")
            ok = False
        else:
            print(f"[SELFTEST PASS] 注入占位理由判红 {f_reason} 项")

        # 还原：删临时文件，对真实台账判绿
        for item in (dirty_gap, dirty_reason):
            item.unlink(missing_ok=True)
        clean_pass, clean_fail, clean_detail = evaluate(MATRIX)
        if clean_fail != 0:
            print(f"[SELFTEST FAIL] 还原后真实台账仍判红：{clean_detail[:3]}")
            ok = False
        else:
            print(f"[SELFTEST PASS] 还原后真实台账判绿（PASS={clean_pass}）")
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    print(f"LIST_CRUD_RESULT PASS={clean_pass} FAIL={clean_fail}")
    print(f"SELFTEST_RESULT {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="W57-43 列表 CRUD 台账机检")
    parser.add_argument("--self-test", action="store_true", help="真注入负向自检")
    parser.add_argument("--matrix", help="指定台账路径（自检/调试用）")
    args = parser.parse_args()
    if args.self_test:
        return self_test()

    path = Path(args.matrix) if args.matrix else MATRIX
    passed, failed, details = evaluate(path)
    for line in details[:20]:
        print(f"  FAIL {line}")
    print(f"LIST_CRUD_RESULT PASS={passed} FAIL={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
