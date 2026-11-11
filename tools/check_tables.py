#!/usr/bin/env python3
"""UX-05 表格规范守门：数据网格必须走 `DataTable` 壳，原生 `<table>` 只许留在三类白名单文件。

为什么需要这条守门
------------------
「12 处表格全部走规范壳」这条判据，肉眼看不出真假：壳是**可选**的，谁再写一个
`<NDataTable>` 页面照样能跑、测试照样能过（naive-ui 内部类名没变），只是又悄悄绕过了
骨架、空态、横滚宽度、表头底色这一整套规范。等到下次调表头样式，就要在 13 个地方改。

同理，原生 `<table>` 在其余页面也能跑，但会绕开主题（暗色下表头/sticky 全部另作一套）。

守什么
------
1. `NDataTable` 只允许出现在 `components/DataTable.vue`（壳自己）——其余 .vue 出现即 FAIL；
2. `<DataTable>` 实例数不得低于现状下限（防「壳不好用就删掉整张表」式过关）；
3. 壳必须保留三条实现探针：骨架条、默认 EmptyState、`max-height`（粘性表头前提）。
   探针是字符串匹配，写死在这里是有意的 —— 它们不是「可选优化」，是规范本身；
4. 原生 `<table>` 白名单只有三处：
   - `ReportPrintView.vue`：打印页按设计**零库依赖**（§2.2 步骤 5），不能引组件库；
   - `ReportDetailView.vue`：十项 metadata 是**定义表**（th + td 两列），不是数据网格，
     塞进 NDataTable 会多一行表头并丢掉 `<th>` 语义（TC-S1-17）；
   - `StyleguideView.vue`：设计系统展示页，需要演示原生表格样式本身。

用法
----
    python3 tools/check_tables.py               # 常规检查
    python3 tools/check_tables.py --self-test   # 破坏性自检：临时树注入裸表/删壳，必须被拦

退出码：0 = 通过；1 = 有违规；2 = 扫描本身失败（找不到文件/计数过低，疑似假绿）。
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "frontend" / "src"
SHELL = SRC / "components" / "DataTable.vue"

# 现状快照（v4.11.0 / 2026-11-11，W-E-03/04 把最后 2 处原生表改壳后重算）：
# 55 处 DataTable 壳 / 35 个文件。下限只防「壳不好用就把整张表删了/换回裸 NDataTable」，
# 不追求贴合每次新增——加表不会红，减表才会红。
MIN_TABLES = 55
MIN_VUE_FILES = 100  # 低于这个数说明扫描范围没覆盖到（现状 112）

NATIVE_TABLE_ALLOW = {
    "ReportPrintView.vue": "打印页零库依赖（§2.2 步骤 5）",
    "ReportDetailView.vue": "十项 metadata 是定义表，不是数据网格",
    "StyleguideView.vue": "设计系统展示页，演示原生表格样式",
}

# 壳的实现探针：(说明, 必须在壳里出现的片段)
SHELL_PROBES = [
    ("骨架行", "skeletonRows"),
    ("骨架条样式", "data-table__bar"),
    ("默认空态", "EmptyState"),
    ("粘性表头前提（max-height）", "max-height"),
]

COMMENT_RE = re.compile(r"<!--.*?-->", re.S)
CSS_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)
NATIVE_TABLE_RE = re.compile(r"<table\b")


@dataclass
class Result:
    files: int = 0
    shell_instances: int = 0
    per_file: dict[str, int] = field(default_factory=dict)
    violations: list[str] = field(default_factory=list)


def clean(text: str) -> str:
    return CSS_COMMENT_RE.sub("", COMMENT_RE.sub("", text))


def check_shell(shell: Path) -> list[str]:
    if not shell.is_file():
        return [f"找不到表格规范壳：{shell}"]
    text = shell.read_text(encoding="utf-8", errors="replace")
    problems = []
    for label, probe in SHELL_PROBES:
        if probe not in text:
            problems.append(
                f"DataTable.vue 缺少「{label}」实现（探针 `{probe}`）——"
                "这不是可选优化，是 UX-05 表格规范的一部分"
            )
    return problems


def scan(root: Path) -> Result:
    r = Result()
    files = sorted(p for p in root.rglob("*.vue"))
    r.files = len(files)
    if r.files < MIN_VUE_FILES:
        r.violations.append(
            f"{root} 下只有 {r.files} 个 .vue（下限 {MIN_VUE_FILES}），扫描范围可疑"
        )
        return r

    for path in files:
        text = clean(path.read_text(encoding="utf-8", errors="replace"))
        rel = path.relative_to(root).as_posix()

        bare = text.count("<NDataTable")
        if bare and path != SHELL:
            r.violations.append(
                f"{rel} 直接使用 NDataTable {bare} 处：数据网格必须走 @/components/DataTable.vue"
                "（否则骨架/空态/横滚宽度/表头底色这套规范被绕过）"
            )

        instances = len(re.findall(r"<DataTable\b", text))
        if instances:
            r.per_file[rel] = instances
            r.shell_instances += instances

        native = NATIVE_TABLE_RE.findall(text)
        if native and path.name not in NATIVE_TABLE_ALLOW:
            r.violations.append(
                f"{rel} 出现原生 <table> {len(native)} 处：除 "
                + "、".join(f"{k}（{v}）" for k, v in NATIVE_TABLE_ALLOW.items())
                + " 之外，表格一律走 DataTable 壳"
            )
    return r


def evaluate(shell: Path, root: Path) -> Result:
    shell_problems = check_shell(shell)
    if shell_problems:
        r = Result()
        r.violations.extend(shell_problems)
        return r
    r = scan(root)
    if r.shell_instances < MIN_TABLES:
        r.violations.append(
            f"只找到 {r.shell_instances} 处 DataTable（下限 {MIN_TABLES}）："
            "要么有表格被删/被换回裸 NDataTable，要么扫描正则失配 —— 不能当通过"
        )
    return r


def report(r: Result) -> None:
    print(f"扫描 {r.files} 个 .vue；DataTable 壳实例 {r.shell_instances} 处 / {len(r.per_file)} 个文件")
    for rel, count in sorted(r.per_file.items()):
        print(f"  {rel:44s} {count}")


def self_test() -> int:
    """破坏性自检：临时树里注入两类问题，都必须被拦住。"""
    print("[SELFTEST] 开始：临时目录内构造最小树，不触碰真实 frontend/src")
    ok = True

    shell_body = (
        "<script setup lang=\"ts\">\n"
        "const props = withDefaults(defineProps<{ skeletonRows?: number }>(), { skeletonRows: 6 })\n"
        "</script>\n<template>\n"
        "# skeletonRows # EmptyState .data-table__bar max-height\n"
        "</template>\n"
    )

    def build(tmp: Path, views: dict[str, str], with_shell: bool = True) -> tuple[Path, Path]:
        src = tmp / "frontend" / "src"
        shell = src / "components" / "DataTable.vue"
        shell.parent.mkdir(parents=True, exist_ok=True)
        if with_shell:
            shell.write_text(shell_body, encoding="utf-8")
        view_dir = src / "views"
        view_dir.mkdir(parents=True, exist_ok=True)
        for name, body in views.items():
            (view_dir / name).write_text(body, encoding="utf-8")
        return shell, src

    # 基线必须同时满足两条下限（壳实例数、.vue 文件数），否则自检会因为「扫描范围可疑」
    # 而不是因为注入的问题判红——那样自检本身就失去意义。
    baseline = max(MIN_TABLES, MIN_VUE_FILES)
    many = {f"V{i}.vue": "<template><DataTable /></template>\n" for i in range(baseline)}
    many["ReportPrintView.vue"] = "<template><table><tr><td>x</td></tr></table></template>\n"

    # 1) 干净基线必须 PASS
    with tempfile.TemporaryDirectory() as d:
        shell, src = build(Path(d), dict(many))
        r = evaluate(shell, src)
        if r.violations:
            ok = False
            print(f"[SELFTEST] FAIL 干净基线被误判：{r.violations}")
        else:
            print(f"[SELFTEST] 通过：干净基线不假红（{r.shell_instances} 处壳、打印页原生表放行）")

    # 2) 注入裸 NDataTable 必须 FAIL
    with tempfile.TemporaryDirectory() as d:
        views = dict(many)
        views["Cheat.vue"] = '<template><NDataTable :columns="c" :data="d" /></template>\n'
        shell, src = build(Path(d), views)
        r = evaluate(shell, src)
        if any("Cheat.vue" in v for v in r.violations):
            print("[SELFTEST] 通过：绕过壳直接写 NDataTable 被拦截")
        else:
            ok = False
            print(f"[SELFTEST] FAIL 裸 NDataTable 没被拦：{r.violations}")

    # 3) 白名单外的原生 <table> 必须 FAIL
    with tempfile.TemporaryDirectory() as d:
        views = dict(many)
        views["Rollback.vue"] = "<template><table><tr><td>x</td></tr></table></template>\n"
        shell, src = build(Path(d), views)
        r = evaluate(shell, src)
        if any("Rollback.vue" in v for v in r.violations):
            print("[SELFTEST] 通过：白名单外的原生 <table> 被拦截")
        else:
            ok = False
            print(f"[SELFTEST] FAIL 原生 table 没被拦：{r.violations}")

    # 4) 壳被删/探针缺失必须 FAIL（不能「没有壳 = 零违规」）
    with tempfile.TemporaryDirectory() as d:
        shell, src = build(Path(d), dict(many), with_shell=False)
        r = evaluate(shell, src)
        if r.violations and any("找不到表格规范壳" in v for v in r.violations):
            print("[SELFTEST] 通过：壳缺失时按「无法判定」报错，不当通过")
        else:
            ok = False
            print(f"[SELFTEST] FAIL 壳缺失没被拦：{r.violations}")

    print("[SELFTEST] " + ("通过：四类场景均符合预期" if ok else "存在未拦截场景"))
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="UX-05 表格规范守门")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    try:
        r = evaluate(SHELL, SRC)
    except OSError as exc:
        print(f"[FAIL] 扫描失败：{exc}", file=sys.stderr)
        return 2

    report(r)
    if r.violations:
        for v in r.violations:
            print(f"[FAIL] {v}", file=sys.stderr)
        print(f"\n[FAIL] 共 {len(r.violations)} 项表格规范问题", file=sys.stderr)
        return 1
    print(
        f"\n[PASS] 表格规范：{r.shell_instances} 处数据网格全部走壳；"
        f"原生 <table> 仅出现在白名单 {len(NATIVE_TABLE_ALLOW)} 个文件"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
