#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""菜单信息架构机检（v4.11.0 / FR-v4.11-06、25）。

为什么需要这条机检
------------------
菜单是**纯配置**：写错一个缩进、把两个页签写成同一个 query、或者某组只剩一个子项，
页面照样能跑、单测只要没覆盖到也不会红，但用户看到的是「点了不亮」「分组像假的」。
`navigation.spec.ts` 在运行时断言了大部分不变量；这里补的是**不依赖 TS 运行时**的
那一层——门禁里先跑它，能在编译前就把结构错误挡住。

守什么
------
1. 层级 ≤ 2（不存在三级菜单）；
2. 全局一级恰 5 组、项目一级恰 7 组；
3. 每个一级组 children 非空；全局**每组 ≥ 2 项**（防退化成单叶伪分组）；
4. 同一 path 的叶子其 `query` 两两不同（防两个页签写成一个，选中态互抢）；
5. 每个叶子都有 `path`。

解析约定（与源码格式绑定，由 eslint/prettier 保证稳定）
------------------------------------------------------
- 一级组的 `key:` 行缩进 4 空格；二级叶子的 `key:` 缩进 ≥6（单行 `{ key: ..., path: ... }`
  与多行写法都要支持——**`key` 与 `path` 常常不在同一行**，所以按「`key` 行开块、后续行
  归入该块、块末统一取 path/query」来解析，而不是逐行就地匹配）；
- `children: xxxChildren` 这种「来自另一个模块的常量」按约定映射到 settingsNav.ts 的数组，
  静态解析不了，但可以数它的项数——**不能因为解析不到就跳过检查**，那等于给设置组开后门。

用法
----
    python3 tools/check_navigation.py               # 常规检查
    python3 tools/check_navigation.py --self-test   # 破坏性自检：删掉一个叶子，必须被拦

退出码：0 = 通过；1 = 违规；2 = 扫描失败（找不到文件 / 计数为 0，疑似假绿）。
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NAV = ROOT / "frontend" / "src" / "config" / "navigation.ts"
SETTINGS = ROOT / "frontend" / "src" / "config" / "settingsNav.ts"

GLOBAL_EXPECTED = 5
PROJECT_EXPECTED = 7
GLOBAL_MIN_CHILDREN = 2
# v5.6.0 / FR-v5.6-01、02：全量菜单。pytest 工程与标准工程共用同一套菜单，
# 唯一差别是一个增量叶子。原「第二棵树 + 禁项前缀」判据已随树一起删除——
# 保留它等于把已被证伪的「pytest 工程没有台架资产」假设写死在门禁里。
PYTEST_LEAF_PATH = "/pytest-workbench"
# 增量叶子的常量名（navigation.ts 里的导出名）。删了它菜单就少一个落地面。
PYTEST_LEAF_DECL = "export const pytestWorkbenchLeaf: NavLeaf = {"

KEY_RE = re.compile(r"key: '([^']+)',")
# 注意：**不要求尾逗号**——单行对象写作 `..., path: '/x' },`，`'` 与 `,` 之间还有 ` }`。
PATH_RE = re.compile(r"path: '([^']+)'")
QUERY_RE = re.compile(r"query: \{([^}]*)\}")
CHILDREN_REF_RE = re.compile(r"^\s*children: ([A-Za-z_][A-Za-z0-9_]*),", re.M)

# navigation.ts 里的派生常量 → settingsNav.ts 里的数组名
DYNAMIC_SOURCES = {
    "globalSettingsChildren": "SETTINGS_NAV",
    "projectSettingsChildren": "PROJECT_SETTINGS_NAV",
}


@dataclass
class Leaf:
    key: str
    path: str | None = None
    query: str = ""


@dataclass
class Group:
    key: str
    children: list[Leaf] = field(default_factory=list)
    dynamic: bool = False


def extract_array(text: str, decl: str) -> str:
    """取 `export const X: T[] = [` 到列 0 的 `]` 之间的文本。"""
    start = text.find(decl)
    if start < 0:
        raise SystemExit(f"[FAIL] 找不到声明：{decl}")
    begin = text.find("\n", start) + 1
    end = text.find("\n]", begin)
    if end < 0:
        raise SystemExit(f"[FAIL] {decl} 段没有结束标记")
    return text[begin:end]


def scan_items(block: str) -> list[tuple[int, str, str]]:
    """把数组文本切成条目：`(缩进, key, 该条目全部行拼成的文本)`。

    切法：每个 `key:` 行开启新条目，其后所有行都归入它，直到下一个 `key:` 行。
    这样单行对象（`{ key: 'x', path: '/y' },`）与多行对象（`key` / `path` 分行）都能取到，
    且**叶子条目不会吞掉别人的 path**（下一个叶子/分组一出现就截断）。
    """
    entries: list[list] = []
    for line in block.splitlines():
        if not line.strip():
            continue
        km = KEY_RE.search(line)
        if km:
            entries.append([len(line) - len(line.lstrip()), km.group(1), [line]])
        elif entries:
            entries[-1][2].append(line)
    return [(indent, key, "\n".join(lines)) for indent, key, lines in entries]


def load_dynamic() -> dict[str, list[Leaf]]:
    """把 settingsNav.ts 的两个数组解析成叶列表（key + path）。"""
    text = SETTINGS.read_text(encoding="utf-8")
    out: dict[str, list[Leaf]] = {}
    for const_name in ("SETTINGS_NAV", "PROJECT_SETTINGS_NAV"):
        block = extract_array(text, f"export const {const_name}: readonly SettingsNavItem[] = [")
        leaves: list[Leaf] = []
        for _indent, key, chunk in scan_items(block):
            pm = PATH_RE.search(chunk)
            leaves.append(Leaf(key, pm.group(1) if pm else None))
        out[const_name] = leaves
    return out


def parse(block: str, dynamic: dict[str, list[Leaf]]) -> list[Group]:
    groups: list[Group] = []
    for indent, key, chunk in scan_items(block):
        if indent <= 4:
            group = Group(key)
            ref = CHILDREN_REF_RE.search(chunk)
            if ref:
                source = DYNAMIC_SOURCES.get(ref.group(1))
                if source is None:
                    raise SystemExit(f"[FAIL] 未知的 children 常量：{ref.group(1)}")
                group.children = list(dynamic[source])
                group.dynamic = True
            groups.append(group)
            continue

        if not groups:
            raise SystemExit(f"[FAIL] 叶子 {key} 没有归属的一级组")
        pm = PATH_RE.search(chunk)
        qm = QUERY_RE.search(chunk)
        groups[-1].children.append(
            Leaf(key, pm.group(1) if pm else None, qm.group(1).strip() if qm else "")
        )
    return groups


def check(groups: list[Group], label: str, min_children: int) -> list[str]:
    problems: list[str] = []
    if not groups:
        problems.append(f"{label}：解析出 0 个一级项（扫描失败）")
        return problems

    for group in groups:
        if not group.children:
            problems.append(f"{label}：分组 {group.key} 没有任何二级项")
            continue
        if len(group.children) < min_children:
            problems.append(
                f"{label}：分组 {group.key} 只有 {len(group.children)} 个二级项"
                f"（要求 ≥{min_children}）"
            )
        for leaf in group.children:
            if not leaf.path:
                problems.append(f"{label}：叶子 {leaf.key} 没有 path")

    # 同 path 的叶子 query 必须两两不同
    by_path: dict[str, list[Leaf]] = {}
    for group in groups:
        for leaf in group.children:
            if leaf.path:
                by_path.setdefault(leaf.path, []).append(leaf)
    for path, leaves in by_path.items():
        if len(leaves) < 2:
            continue
        shapes = [leaf.query for leaf in leaves]
        if len(set(shapes)) != len(shapes):
            names = ", ".join(f"{leaf.key}({leaf.query})" for leaf in leaves)
            problems.append(f"{label}：{path} 下有 {len(leaves)} 个叶子但 query 重复：{names}")
    return problems


def run(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    dynamic = load_dynamic()
    global_groups = parse(
        extract_array(text, "export const GLOBAL_NAVIGATION: NavNode[] = ["), dynamic
    )
    project_groups = parse(extract_array(text, "export const NAVIGATION: NavNode[] = ["), dynamic)

    problems: list[str] = []
    if len(global_groups) != GLOBAL_EXPECTED:
        problems.append(f"全局一级项应为 {GLOBAL_EXPECTED}，实际 {len(global_groups)}")
    if len(project_groups) != PROJECT_EXPECTED:
        problems.append(f"项目一级项应为 {PROJECT_EXPECTED}，实际 {len(project_groups)}")

    problems += check(global_groups, "全局", GLOBAL_MIN_CHILDREN)
    problems += check(project_groups, "项目", 2)

    # 增量叶子判据（v5.6.0 / FR-v5.6-01）：全量菜单后，工程类型的唯一差别就是这个叶子。
    if "export const PYTEST_SUITE_NAVIGATION" in text:
        problems.append(
            "PYTEST_SUITE_NAVIGATION 仍然存在——第二棵树已按 FR-v5.6-02 删除"
            "（菜单必须一套，不能按工程类型分树）"
        )
    if PYTEST_LEAF_DECL not in text:
        problems.append(f"找不到增量叶子声明：{PYTEST_LEAF_DECL}")
    else:
        # 单对象不是数组，取到声明处的下一个 `}` 结束
        leaf_block = text[text.find(PYTEST_LEAF_DECL):]
        leaf_block = leaf_block[: leaf_block.find("\n}") + 2]
        pm = PATH_RE.search(leaf_block)
        found = pm.group(1) if pm else None
        if found != PYTEST_LEAF_PATH:
            problems.append(
                f"增量叶子的 path 应为 {PYTEST_LEAF_PATH}，实际 {found}"
            )

    project_paths = [leaf.path for group in project_groups
                     for leaf in group.children if leaf.path]
    if PYTEST_LEAF_PATH in project_paths:
        problems.append(
            f"{PYTEST_LEAF_PATH} 出现在标准树里——它必须是 pytest 工程专属增量，"
            "否则标准工程会多出一个指向 pytest 工作台的入口"
        )

    # 叶子 path 必须存在于路由表（菜单不许指向不存在的页面）
    router = ROOT / "frontend" / "src" / "router" / "index.ts"
    if router.is_file():
        router_text = router.read_text(encoding="utf-8")
        declared = set(re.findall(r"path: '(/[^']*)'", router_text))
        for leaf_path in project_paths + [PYTEST_LEAF_PATH]:
            if leaf_path not in declared:
                problems.append(f"菜单叶子指向不存在的路由：{leaf_path}")

    deep = re.findall(r"^\s{12,}key: '", text, re.M)
    if deep:
        problems.append(f"出现 {len(deep)} 个三级及以上节点（层级必须 ≤2）")

    if problems:
        for item in problems:
            print(f"[FAIL] {item}")
        return 1

    global_leaves = sum(len(group.children) for group in global_groups)
    project_leaves = sum(len(group.children) for group in project_groups)
    dynamic_names = (
        ", ".join(
            group.key
            for group in (*global_groups, *project_groups)
            if group.dynamic
        )
        or "无"
    )
    print("[PASS] 层级 ≤ 2")
    print(f"[PASS] 全局一级 {len(global_groups)} 组 / {global_leaves} 叶（每组 ≥{GLOBAL_MIN_CHILDREN}）")
    print(f"[PASS] 项目一级 {len(project_groups)} 组 / {project_leaves} 叶")
    print(f"[PASS] 全量菜单：pytest 工程 = 标准树 + 1 个增量叶子（{PYTEST_LEAF_PATH}）")
    print(f"[PASS] 同 path 的叶子 query 两两不同；每个叶子都有 path（派生组：{dynamic_names}）")
    return 0


def self_test() -> int:
    """两条破坏性自证：删叶子（结构）与塞禁项（差异化）。"""
    with tempfile.TemporaryDirectory() as tmp:
        fake = Path(tmp) / "navigation.ts"
        shutil.copy(NAV, fake)
        text = fake.read_text(encoding="utf-8")
        text = re.sub(
            r"\n      \{\n        key: 'global-templates-custom',.*?\n      \},",
            "",
            text,
            count=1,
            flags=re.S,
        )
        fake.write_text(text, encoding="utf-8")
        print("--- self-test 1：删掉「自定义模板」叶后应判失败 ---")
        if run(fake) == 0:
            print("[FAIL] 自检未拦住单叶组——机检形同虚设")
            return 1
        print("[PASS] 自检 1 按预期判红")

    # 自证 2（v5.6.0 / FR-v5.6-02）：把增量叶子删掉，机检必须判红。
    # 这条替掉了原来「往 pytest 视图塞 /assets」的注入——那条判据随第二棵树一起删了，
    # 留着当自检就是拿作废的判据自证。
    with tempfile.TemporaryDirectory() as tmp:
        fake = Path(tmp) / "navigation.ts"
        shutil.copy(NAV, fake)
        text = fake.read_text(encoding="utf-8")
        broken = text.replace("export const pytestWorkbenchLeaf: NavLeaf = {",
                              "const removedLeaf: NavLeaf = {", 1)
        if broken == text:
            print("[FAIL] 自检注入点没找到（找到不到 pytestWorkbenchLeaf）")
            return 2
        fake.write_text(broken, encoding="utf-8")
        print("--- self-test 2：删掉 pytest 增量叶子后应判失败 ---")
        if run(fake) == 0:
            print("[FAIL] 增量叶子判据没拦住缺叶子——全量菜单少一个落地面也没人知道")
            return 1
        print("[PASS] 自检 2 按预期判红")

    print("check_navigation: SELF-TEST PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if not NAV.exists() or not SETTINGS.exists():
        print("[FAIL] 找不到 navigation.ts / settingsNav.ts")
        return 2
    return self_test() if args.self_test else run(NAV)


if __name__ == "__main__":
    sys.exit(main())
