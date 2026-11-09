#!/usr/bin/env python3
"""模板里用到的自建组件必须真的 import 过 —— 漏 import 不会报错，只会静默不渲染。

为什么需要这条
--------------
`<script setup>` 里漏掉 `import EmptyState from ...` 时，Vue 不会报错：模板编译器把
未注册的 PascalCase 标签当**未知自定义元素**处理，运行时只在控制台留一条 warning
（生产构建直接丢掉），页面上就是「本该出现空态的地方什么都没有」。
`vue-tsc` 也拦不住 —— 项目没开 `strictTemplates`，模板表达式和标签都不做类型检查。

2026-11-05 实测踩到：UX-04b 首批把三个视图的空态换成 `<EmptyState>`，三处都没 import，
结果三个页面在「无数据」时一片空白，而构建、单测、lint 全绿。这类缺陷只能靠静态对账兜。

判定规则
--------
对每个 .vue 文件，收集模板里出现的 `<PascalCase ...>` 标签，逐个要求在 script 里能找到：
  - 默认导入：`import Foo from '...'`
  - 具名导入：`import { Foo } from '...'` / `import { Foo as Bar }`
  - 本地异步组件：`const Foo = defineAsyncComponent(...)`（EW32-06 编辑器懒加载写法，
    组件确实是 `<script setup>` 的本地绑定，不是漏 import）
命中任一即通过。以下白名单不检查：
  - Vue 内置：Teleport / Transition / TransitionGroup / KeepAlive / Suspense / Component
  - vue-router 全局注册：RouterView / RouterLink
  - 带点或冒号的动态标签（`<component :is>`、`<Foo.Bar>`）不参与

用法
----
    python3 tools/check_component_imports.py
    python3 tools/check_component_imports.py --root /tmp/fake   # 指向别的目录

退出码：0 = 全部有 import；1 = 有漏（逐条打印文件与标签名）。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

BUILTIN_TAGS = {
    "Teleport",
    "Transition",
    "TransitionGroup",
    "KeepAlive",
    "Suspense",
    "Component",
    "RouterView",
    "RouterLink",
}

# 只认 PascalCase 起始、且不含 `.` `:` 的自闭合/开标签
TAG_RE = re.compile(r"<([A-Z][A-Za-z0-9]*)[\s/>]")
TEMPLATE_RE = re.compile(r"<template>(.*)</template>", re.S)


def imported_names(script: str) -> set[str]:
    names: set[str] = set()
    # import Foo from '...'，以及 `import Foo, { type Bar } from '...'` 这种默认名与具名同行的写法
    # （UX-05 第一次这么写时漏检：正则要求默认名后面直接是 ` from`，遇到 `,` 就不匹配了）
    names.update(
        re.findall(
            r"^\s*import\s+([A-Z][A-Za-z0-9]*)\s*(?:,\s*\{[^}]*\})?\s*from\s", script, re.M
        )
    )
    # import { A, B as C } from '...'
    for block in re.findall(r"^\s*import\s*\{([^}]*)\}\s*from\s", script, re.M):
        for item in block.split(","):
            item = item.strip()
            if not item:
                continue
            # `A as B` 取本地名；`type A` 之类去掉 type 前缀
            item = re.sub(r"^type\s+", "", item)
            local = item.split(" as ")[-1].strip()
            if local:
                names.add(local)
    # const Foo = defineAsyncComponent(...)，按本地组件绑定处理
    names.update(
        re.findall(
            r"^\s*const\s+([A-Z][A-Za-z0-9]*)\s*=\s*defineAsyncComponent\s*\(",
            script,
            re.M,
        )
    )
    return names


def collect(path: Path) -> tuple[set[str], set[str]]:
    """返回（模板里用到的 PascalCase 标签集合，script 里导入的名字集合）。"""
    text = path.read_text(encoding="utf-8")
    template_match = TEMPLATE_RE.search(text)
    template = template_match.group(1) if template_match else ""
    script_match = re.search(r"<script[^>]*>(.*?)</script>", text, re.S)
    script = script_match.group(1) if script_match else ""
    # 递归组件在 <script setup> 里可以直接用文件名自引用，无需 import（KeywordSteps / SuiteTree）
    self_name = path.stem
    tags = {tag for tag in TAG_RE.findall(template) if tag not in BUILTIN_TAGS and tag != self_name}
    return tags, imported_names(script)


def main() -> int:
    parser = argparse.ArgumentParser(description="模板组件 ↔ import 对账")
    parser.add_argument("--root", type=Path, default=ROOT, help="仓库根目录（自检用）")
    args = parser.parse_args()

    src = args.root / "frontend" / "src"
    if not src.is_dir():
        print(f"[SKIP] 未找到 {src}，跳过", file=sys.stderr)
        return 0

    files = 0
    problems: list[str] = []
    for path in sorted(src.rglob("*.vue")):
        files += 1
        tags, imported = collect(path)
        for tag in sorted(tags - imported):
            problems.append(f"{path.relative_to(args.root)}：模板用了 <{tag}> 但没有 import")

    if problems:
        for line in problems:
            print(f"[FAIL] {line}", file=sys.stderr)
        print(f"\n[FAIL] {len(problems)} 处漏 import（共扫描 {files} 个 .vue）", file=sys.stderr)
        return 1

    print(f"[PASS] {files} 个 .vue：模板用到的自建组件全部有 import")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
