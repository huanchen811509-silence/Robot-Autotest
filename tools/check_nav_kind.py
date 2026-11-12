#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""导航一二级菜单机检（v5.2.0 / FR-v5.2-01 / W52-01 / C-1）。

为什么需要这条机检
------------------
菜单最容易出的两种错都不报编译错：
1. **孤儿入口**——导航里加了一项，但路由没加（或路由改名了）。点进去白屏，
   而 `vue-tsc` 与单测都发现不了：导航只是一个字符串数组。
2. **入口悄悄变少**——重构时删掉一个叶子，没人会注意到；等到有人反馈
   「XX 页去哪了」，已经过去几个版本。

守什么
------
1. **孤儿叶子 = 0**：`navigation.ts` + `settingsNav.ts` 里每个 `path` 都能在
   `router/index.ts` 找到同名字面量；
2. **叶子总数 = 基线**：数量变化必须显式改本文件的 `EXPECTED_LEAVES`，
   让「少了一个入口」成为一次可见的提交，而不是一次静默的删除；
3. **路径格式合法**：以 `/` 开头、不以 `/` 结尾（除根）。

用法
----
    python3 tools/check_nav_kind.py
    python3 tools/check_nav_kind.py --self-test   # 真注入：删一个叶子，必须判红

退出码：0 = 通过；1 = 违规；2 = 扫描失败（找不到源码，疑似假绿）。
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NAV = ROOT / "frontend" / "src" / "config" / "navigation.ts"
SETTINGS_NAV = ROOT / "frontend" / "src" / "config" / "settingsNav.ts"
ROUTER = ROOT / "frontend" / "src" / "router" / "index.ts"

# 叶子基线：改动菜单时同步改这里，让数量的增减留下痕迹
EXPECTED_LEAVES = 39

PATH_RE = re.compile(r"path:\s*['\"](/[^'\"]*)['\"]")


def ok(message: str) -> None:
    print(f"  OK     {message}")


def fail(problems: list[str], message: str) -> None:
    problems.append(message)
    print(f"  FAIL   {message}")


def leaves_of(*files: Path) -> list[str]:
    found: list[str] = []
    for file in files:
        found.extend(PATH_RE.findall(file.read_text(encoding="utf-8")))
    return found


def check(problems: list[str]) -> None:
    leaves = leaves_of(NAV, SETTINGS_NAV)
    routes = set(PATH_RE.findall(ROUTER.read_text(encoding="utf-8")))

    if not leaves:
        fail(problems, "navigation.ts / settingsNav.ts 里找不到任何 path（结构变了，脚本要同步）")
        return
    if not routes:
        fail(problems, "router/index.ts 里找不到任何 path（结构变了，脚本要同步）")
        return

    orphans = sorted({item for item in leaves if item not in routes})
    if orphans:
        fail(problems, f"{len(orphans)} 个导航入口没有对应路由（点了会白屏）：{orphans}")
    else:
        ok(f"孤儿叶子 = 0（{len(set(leaves))} 个入口全部有路由）")

    # 基线按**去重后**的 path 数：同一 path 带不同 query 是合法用法（如 /assets 的三个页签），
    # 但「又一个新页面没了」必须体现在这个数上
    unique = len(set(leaves))
    if unique != EXPECTED_LEAVES:
        fail(problems, f"叶子数 {unique} 与基线 {EXPECTED_LEAVES} 不一致"
                       "（菜单增减请同步改 tools/check_nav_kind.py 的 EXPECTED_LEAVES）")
    else:
        ok(f"叶子数 = 基线 {EXPECTED_LEAVES}")

    bad = [item for item in leaves if not item.startswith("/") or (len(item) > 1 and item.endswith("/"))]
    if bad:
        fail(problems, f"{len(bad)} 个路径格式非法（须以 / 开头、不以 / 结尾）：{bad}")
    else:
        ok("路径格式合法")


def run() -> int:
    print(f"[check_nav_kind] 导航={NAV.relative_to(ROOT)}")
    for file in (NAV, SETTINGS_NAV, ROUTER):
        if not file.is_file():
            print(f"check_nav_kind: FAIL（找不到 {file}）")
            return 2
    problems: list[str] = []
    check(problems)
    if problems:
        print(f"check_nav_kind: FAIL（{len(problems)} 项）")
        return 1
    print("check_nav_kind: PASS")
    return 0


def self_test() -> int:
    """真注入：把一个导航叶子的 path 改成路由里不存在的值，孤儿检查必须判红。"""
    print("--- self-test：把一个叶子改成不存在的路由后应判失败 ---")
    global NAV
    original = NAV
    backup = original.read_text(encoding="utf-8")
    # 用「改名」而不是「删行」：同一 path 在文件里可能出现多次（不同 query），
    # 删一次未必改变去重后的数量，改成孤儿才是稳定的判据
    broken = backup.replace("path: '/g/projects'", "path: '/g/projects-orphan'", 1)
    if broken == backup:
        print("  FAIL   自检注入点没找到（导航结构变了），无法自证")
        print("check_nav_kind: SELF-TEST FAIL")
        return 2
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".ts", delete=False,
                                         encoding="utf-8") as handle:
            handle.write(broken)
            temp_path = Path(handle.name)
        NAV = temp_path
        problems: list[str] = []
        check(problems)
        if not any("没有对应路由" in item for item in problems):
            print(f"  FAIL   自检未按预期判红：{problems}")
            print("check_nav_kind: SELF-TEST FAIL")
            return 1
        print("  OK     造出一个孤儿入口后判红")
    finally:
        NAV = original
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)

    print("check_nav_kind: SELF-TEST PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="导航叶子可达性与数量基线机检")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检")
    args = parser.parse_args()
    return self_test() if args.self_test else run()


if __name__ == "__main__":
    sys.exit(main())
