#!/usr/bin/env python3
"""对外报告字段白名单同源机检（v5.22.0 / FR-v5.22-07 判据 07-1 / W72-09）。

判据：`docs/plan/C5-对外报告字段白名单-20261106.md` §2 的字段表与
`ReportFieldWhitelist.EXTERNAL_ORDER` **逐项相等，顺序也一致**（顺序即导出列序）。

为什么必须机检：v5.22.0 之前字段集散落在 6 处——白名单常量、C5 文档、后端 3 个单测、
前端 1 个单测、中英语言包。手工同步的结果是**文档写着 21 项、代码已经 22 项**，
而两边各自的测试都是绿的（各自只校验自己那一份）。谁都没错，合起来就是错的。

命名差异是要处理的：文档用 `snake_case`（`pass_count`），代码用 camelCase（`passCount`）。
比对前统一折成 snake_case——不折就会得出「22 项全不一致」这种假红。

用法：
    python3 tools/check_report_whitelist_doc.py
    python3 tools/check_report_whitelist_doc.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/plan/C5-对外报告字段白名单-20261106.md"
JAVA = ROOT / "backend/src/main/java/com/qa/autotest/application/report/ReportFieldWhitelist.java"

# §2 表格的一行：| 12 | 范围 | `sw_build` | 软件版本… |
ROW = re.compile(r"^\|\s*\d+\s*\|[^|]*\|\s*`([a-z][a-z0-9_]*)`\s*\|")
# `EXTERNAL_ORDER = List.of( "a", "b" );`（跨行）
LIST = re.compile(r"(\w+_ORDER)\s*=\s*List\.of\((.*?)\);", re.S)
QUOTED = re.compile(r'"([^"]+)"')


def snake(name: str) -> str:
    """camelCase → snake_case。文档用后者、代码用前者，不折就没法比。

    已经带下划线的保持原样只转小写：`METADATA_ORDER` 里装的是 metadata 键（`SW_BUILD`），
    对它跑「大写前插下划线」会得到 `s_w__b_u_i_l_d` 这种垃圾。
    """
    if "_" in name or name.isupper():
        return name.lower()
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def read_doc_fields(text: str) -> list[str]:
    """只取 §2 那张表，不碰 §2.1（被移除字段）与 §2.2（用例明细列）。"""
    start = text.find("## 2. ")
    if start < 0:
        raise SystemExit("C5 文档缺少 §2 允许字段表")
    end = text.find("### 2.1", start)
    section = text[start:end if end > 0 else len(text)]
    return [snake(m.group(1)) for m in map(ROW.match, section.splitlines()) if m]


def read_code_lists(text: str) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for name, body in LIST.findall(text):
        out[name] = QUOTED.findall(body)
    return out


def compare(doc_fields: list[str], external: list[str], metadata: list[str]) -> list[str]:
    """返回问题清单；空表示同源。纯函数，`--self-test` 直接拿它做注入验证。"""
    problems: list[str] = []
    if not doc_fields:
        problems.append("C5 §2 没有解析到任何字段（表格格式变了？）")
    if not external:
        problems.append("代码里没有解析到 EXTERNAL_ORDER")
        return problems

    missing = [f for f in external if snake(f) not in doc_fields]
    extra = [f for f in doc_fields if f not in {snake(x) for x in external}]
    if missing:
        problems.append("白名单里有、文档没有：" + ", ".join(snake(f) for f in missing))
    if extra:
        problems.append("文档里有、白名单没有：" + ", ".join(extra))
    if not missing and not extra and doc_fields != [snake(f) for f in external]:
        # 集合相同但顺序不同：导出列序会跟文档承诺的不一样
        problems.append(
            "字段集相同但顺序不一致：\n  文档 " + ", ".join(doc_fields)
            + "\n  代码 " + ", ".join(snake(f) for f in external)
        )

    # 文档标题里写的「共 N 项」也得是真值：这个数字最容易被漏改
    declared = re.search(r"§2 允许字段（[^）]*共\s*(\d+)\s*项", DOC.read_text(encoding="utf-8"))
    if declared and int(declared.group(1)) != len(external):
        problems.append(f"文档标题声明 {declared.group(1)} 项，白名单实际 {len(external)} 项")

    # METADATA_ORDER 的每一项都必须是 22 项里的一个（否则它渲染不出来）
    stray = [k for k in metadata if snake(k) not in {snake(x) for x in external}]
    if stray:
        problems.append("METADATA_ORDER 含白名单之外的键：" + ", ".join(stray))
    if len(metadata) != 7:
        problems.append(f"METADATA_ORDER 应为 7 键，实际 {len(metadata)} 键")
    return problems


def check_text(doc_text: str, java_text: str) -> tuple[list[str], int, int]:
    """完整读取路径：文档文本 + 源文本 → (问题清单, 文档字段数, 白名单字段数)。"""
    doc_fields = read_doc_fields(doc_text)
    lists = read_code_lists(java_text)
    external = lists.get("EXTERNAL_ORDER", [])
    metadata = lists.get("METADATA_ORDER", [])
    return compare(doc_fields, external, metadata), len(doc_fields), len(external)


def self_test(doc_fields: list[str], external: list[str], metadata: list[str]) -> int:
    """真注入：三种典型漂移都必须判红，最后原样还原必须判绿。"""
    cases: list[tuple[str, list[str], list[str]]] = [
        ("代码多一个字段、文档没跟", doc_fields, external + ["bogusField"]),
        ("文档多一个字段、代码已删", doc_fields + ["bogus_field"], external),
        ("集合相同但顺序被换", doc_fields, [external[1], external[0], *external[2:]]),
    ]
    failed = 0
    for label, doc_in, ext_in in cases:
        problems = compare(doc_in, ext_in, metadata)
        if problems:
            print(f"[self-test] 判红（符合预期）：{label} → {problems[0].splitlines()[0]}")
        else:
            print(f"[self-test] 假绿！注入后仍然 PASS：{label}")
            failed += 1

    # 第二层：走完整读取路径，在临时副本上注入——证明「解析 + 比对」整条链都认得出漂移，
    # 而不只是那个纯函数。真文件一个字节都不动（中途挂了也不会留半成品）。
    with tempfile.TemporaryDirectory() as tmp:
        java_copy = Path(tmp) / "ReportFieldWhitelist.java"
        mutated = JAVA.read_text(encoding="utf-8").replace(
            '"watermark");', '"watermark", "bogusField");')
        if mutated == JAVA.read_text(encoding="utf-8"):
            print("[self-test] 注入点没命中（EXTERNAL_ORDER 的写法变了？）——自检失效")
            return 1
        java_copy.write_text(mutated, encoding="utf-8")
        problems, _, _ = check_text(DOC.read_text(encoding="utf-8"), java_copy.read_text(encoding="utf-8"))
        if problems:
            print(f"[self-test] 文件级判红（符合预期）：{problems[0].splitlines()[0]}")
        else:
            print("[self-test] 假绿！文件副本注入后仍 PASS")
            failed += 1

    restored = compare(doc_fields, external, metadata)
    if restored:
        print("[self-test] 还原后仍判红，说明比对本身有问题：" + restored[0].splitlines()[0])
        return 1
    print("[self-test] 还原后判绿，符合预期")
    if failed:
        return 1
    print("[self-test] 3 组纯函数注入 + 1 组文件级注入全部判红，还原判绿 → PASS")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="注入三种漂移，断言全部判红")
    args = ap.parse_args()

    doc_fields = read_doc_fields(DOC.read_text(encoding="utf-8"))
    lists = read_code_lists(JAVA.read_text(encoding="utf-8"))
    external = lists.get("EXTERNAL_ORDER", [])
    metadata = lists.get("METADATA_ORDER", [])

    if args.self_test:
        return self_test(doc_fields, external, metadata)

    problems, doc_count, ext_count = check_text(
        DOC.read_text(encoding="utf-8"), JAVA.read_text(encoding="utf-8"))
    print(f"[whitelist-doc] C5 文档 {doc_count} 项 / EXTERNAL_ORDER {ext_count} 项"
          f" / METADATA_ORDER {len(metadata)} 键")
    if problems:
        for item in problems:
            print("[FAIL] " + item)
        return 1
    print("[PASS] C5 文档与白名单逐项相等（含顺序）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
