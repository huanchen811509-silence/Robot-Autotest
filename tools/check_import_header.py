#!/usr/bin/env python3
"""台账表头对账：拿真实 Excel 的表头去撞 CaseImportService 的别名表。

为什么要这个工具
----------------
CAS-03 的判据是「preview 能读该结构（N 行、0 错误）」。但 preview 失败时只报一句
「缺少必需列：用例编号 / 标题」，看不出是哪几列对不上、差多少。等真跑导入才发现，
返工成本高（要改别名、重跑、重新对账）。

本工具把这一步前移成**静态对账**：不连数据库、不起后端，直接比对
「Excel 实际表头」与「代码认可的别名」，逐列给出 命中 / 未命中，并判定必需列是否齐。

别名表从 Java 源码实时提取（不复制一份到 Python），避免源码改了而这里没跟上。

用法
----
    python tools/check_import_header.py fixtures/case-ledger/ledger-sample-226.xlsx
    python tools/check_import_header.py <任意台账.xlsx> --java <另一个 CaseImportService.java>

退出码：必需列齐且表头行可定位 → 0；否则 1（可当 CI 检查项）。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    import openpyxl
except ImportError:
    sys.exit(
        "[ENV] 缺少 openpyxl：先执行 `python3 -m pip install -r tools/requirements.txt`。"
        " 表头对账是 CAS-03 的静态守门，不可跳过，故不以降级方式放行。"
    )

DEFAULT_JAVA = "backend/src/main/java/com/qa/autotest/application/caseasset/CaseImportService.java"
REQUIRED = ("caseCode", "title")
# 与 Java 侧 findHeaderRow 一致：只在前 10 行里找表头
HEADER_SCAN_ROWS = 10

# OPT-02 / FR-49-05：参考《基本功能导入测试》5 张「详细测试流程」表（表头第 3 行）
# 的 14 列规范。用「字段名」而非表头名对账，兼容「用例编号/测试编号」
# 「用例名称/测试用例」等逐 sheet 变体。
# 注意：「测试资源」沿用 ed202Coverage 别名（兼容既有别名单测），其值在导入器里
# 分流到独立列 test_resource，不再写入 ed202_coverage（FR-49-06）。
EXPECTED_14_FIELDS = (
    "caseCode", "category", "categoryL2", "title", "caseClass", "priority",
    "precondition", "steps", "aSideCommand", "bSideCommand", "switchConfig",
    "expected", "testResult", "ed202Coverage",
)


def strip_java_comments(src: str) -> str:
    """剥掉 // 行注释与 /* */ 块注释，字符串字面量整段原样保留。

    为什么必须剥
    ------------
    alias(...) 调用内部夹注释是合法 Java 写法，但正则跨不过 `//`：

        alias("caseCode", "用例编号", ..., "external_id",
                // 这三行注释
                "测试编号");

    第一版没剥注释，正则匹配到 "external_id" 后要接 `,` 再 `"`，实际撞上 `/`，
    于是**整条调用被丢弃**。工具却仍打印「8 个字段」，看着像正常结果 —— 静默少抓
    比直接报错危险得多（据此得出的「表头对不上」结论是假的）。
    """
    out: list[str] = []
    i, n = 0, len(src)
    while i < n:
        ch = src[i]
        if ch in ('"', "'"):
            quote = ch
            out.append(ch)
            i += 1
            while i < n:
                c = src[i]
                out.append(c)
                if c == "\\" and i + 1 < n:      # 转义：连下一个字符一起吞掉
                    out.append(src[i + 1])
                    i += 2
                    continue
                i += 1
                if c == quote:
                    break
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j                # 保留换行，由下一轮照抄
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")                       # 空格占位，避免 token 粘连
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def parse_aliases(java_path: Path) -> tuple[dict[str, str], dict[str, str]]:
    """从 Java 源码提取 alias("field", "名1", "名2", ...)。

    返回 (别名小写 → field, field → 展示名)。
    """
    src = strip_java_comments(java_path.read_text(encoding="utf-8"))
    calls = re.findall(r'\balias\(\s*"([A-Za-z0-9_]+)"((?:\s*,\s*"[^"]*")*)\s*\)', src)
    if not calls:
        raise SystemExit("未从 %s 提取到 alias(...) 调用，检查源码结构是否变化" % java_path)

    # 自检：调用点数量必须与解析出的条数一致。少抓一条就会漏掉整个字段的别名，
    # 而表面输出（字段数、命中数）依旧像正常值 —— 必须拦住，不许带着假数据下结论。
    call_sites = len(re.findall(r'\balias\s*\(\s*"', src))
    if call_sites != len(calls):
        raise SystemExit(
            "[FAIL] 源码里有 %d 处 alias(...) 调用，但解析器只认出 %d 条 —— 解析器有漏抓。\n"
            "       先修 strip_java_comments / 正则，再用本工具的结论。" % (call_sites, len(calls)))

    lookup: dict[str, str] = {}
    fields: dict[str, str] = {}
    for field, rest in calls:
        names = re.findall(r'"([^"]*)"', rest)
        fields[field] = field
        for n in names:
            lookup[n.strip().lower()] = field
    return lookup, fields


def main() -> int:
    ap = argparse.ArgumentParser(description="台账表头与导入器别名静态对账")
    ap.add_argument("excel", help="待对账的 xlsx")
    ap.add_argument("--java", default=DEFAULT_JAVA, help="CaseImportService.java 路径")
    ap.add_argument("--sheet", help="指定工作表（默认第一张）")
    ap.add_argument("--expect-14", action="store_true",
                    help="按 14 列规范对账（仅显式启用；旧口径 14 列台账列名不同，不能自动判定）")
    args = ap.parse_args()

    java_path = Path(args.java)
    if not java_path.is_file():
        raise SystemExit("找不到 %s（在仓库根目录执行，或用 --java 指定）" % java_path)

    lookup, _ = parse_aliases(java_path)
    wb = openpyxl.load_workbook(args.excel, read_only=True, data_only=True)
    ws = wb[args.sheet] if args.sheet else wb.worksheets[0]
    rows = [list(r) for r in ws.iter_rows(max_row=HEADER_SCAN_ROWS, values_only=True)]

    # 与 Java 逻辑一致：第一处含任一已知别名的行即表头行
    hidx = None
    for i, r in enumerate(rows):
        for v in r:
            if isinstance(v, str) and v.strip().lower() in lookup:
                hidx = i
                break
        if hidx is not None:
            break

    print("Excel      : %s" % args.excel)
    print("工作表     : %s" % ws.title)
    print("别名来源   : %s（%d 个别名 / %d 个字段）" % (java_path, len(lookup), len(set(lookup.values()))))

    if hidx is None:
        print("\n[FAIL] 前 %d 行内找不到表头行 —— 导入时 findHeaderRow 会抛「未找到表头行」" % HEADER_SCAN_ROWS)
        return 1

    header = [str(v).strip() if v is not None else "" for v in rows[hidx]]
    print("表头行     : 第 %d 行（Excel 行号 %d）" % (hidx, hidx + 1))
    print("")

    print("%-3s %-28s %-14s %s" % ("#", "Excel 列名", "映射字段", "结果"))
    print("-" * 74)
    mapped: list[str] = []
    for ci, name in enumerate(header):
        if not name:
            continue
        field = lookup.get(name.lower())
        if field:
            mapped.append(field)
            print("%-3d %-28s %-14s %s" % (ci + 1, name[:28], field, "命中"))
        else:
            print("%-3d %-28s %-14s %s" % (ci + 1, name[:28], "—", "未命中"))

    print("-" * 74)
    hit = len(mapped)
    total = len([h for h in header if h])
    print("列命中     : %d / %d" % (hit, total))

    # OPT-02 / FR-49-05：仅 --expect-14 显式启用时做 14 列规范对账。
    # 不按「表头恰好 14 列」自动判定：v0.2.9.2 等旧口径台账也是 14 列但列名带括号后缀，
    # 自动判定会把它们误判为缺列。默认行为保持与旧版完全一致。
    if args.expect_14:
        hit14 = [f for f in EXPECTED_14_FIELDS if f in mapped]
        print("14 列对账  : %d / %d" % (len(hit14), len(EXPECTED_14_FIELDS)))
        if len(hit14) != len(EXPECTED_14_FIELDS):
            print("[FAIL] 14 列规范未全命中，缺字段：%s" % " / ".join(
                f for f in EXPECTED_14_FIELDS if f not in mapped))
            return 1

    # 多列撞同一字段 = 后面的列会被静默丢掉（Java 侧 columns 用 containsKey 判重，
    # 先出现的那列赢）。命中数看着好看，实际丢数据 —— 必须显式点出来。
    by_field: dict[str, list[str]] = {}
    for ci, name in enumerate(header):
        f = lookup.get(name.lower()) if name else None
        if f:
            by_field.setdefault(f, []).append("第%d列「%s」" % (ci + 1, name))
    collisions = {f: cols for f, cols in by_field.items() if len(cols) > 1}
    if collisions:
        print("")
        print("[WARN] 多列映射到同一字段，只有最左一列会生效，其余静默丢弃：")
        for f, cols in collisions.items():
            print("       %-12s %s → 生效的是 %s" % (f, " / ".join(cols), cols[0]))
        print("       要么把多余列从别名表移除（表示「不认」），要么确认丢弃可接受。")

    missing = [f for f in REQUIRED if f not in mapped]
    if missing:
        print("\n[FAIL] 缺少必需列：%s" % " / ".join(
            {"caseCode": "用例编号（caseCode）", "title": "标题（title）"}.get(m, m) for m in missing))
        print("       preview 会直接抛「缺少必需列：用例编号 / 标题」，一条都进不来。")
        print("       两条路：① 在 CaseImportService.HEADER_ALIASES 补该表头的别名；")
        print("               ② 换用字段口径匹配的台账（历史 461 条口径：编号/标题/分类/优先级/…）。")
        return 1

    print("\n[PASS] 必需列齐，表头可定位")
    unmapped = [h for h in header if h and h.lower() not in lookup]
    if unmapped:
        print("       未命中的列会被忽略（不报错）：%s" % " / ".join(unmapped[:6]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
