#!/usr/bin/env python3
"""导航能力白名单机检（v5.21.0 / FR-v5.21-02 / W71-04）。

判两件事：

1. **每个导航叶子必须二选一**：声明 `capability`，或声明 `public: true`。
   两者都没有 = 机检判红。这是从"黑名单"改成"白名单"的落点——旧口径
   "不填 = 所有登录用户可见"，新增一个管理页忘了写能力，全员都能从侧栏进去。
2. 叶子声明的 `capability` 字面量必须 ⊆ 后端 `MENU_CAPABILITIES`（与
   `check_menu_capability_contract.py` 同源；那边查"前后端码表一致"，
   这边查"叶子有没有声明"，两条都必要）。

为什么不用 AST：`navigation.ts` 是数据字面量，叶子的边界靠 `key:` / `path:` 就能
稳定切出来；用正则块判能让脚本零依赖（门禁在纯 Python 环境也要能跑）。

用法：
    python3 tools/check_nav_capability.py
    python3 tools/check_nav_capability.py --self-test   # 注入缺声明，必须判红
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NAV = ROOT / "frontend/src/config/navigation.ts"
MATRIX = ROOT / "backend/src/main/java/com/qa/autotest/domain/iam/PermissionMatrix.java"

KEY_RE = re.compile(r"^\s*key:\s*'([^']+)'")
PATH_RE = re.compile(r"^\s*path:\s*'([^']+)'")
CAP_RE = re.compile(r"^\s*capability:\s*'([^']+)'")
PUBLIC_RE = re.compile(r"^\s*public:\s*true")


def backend_capabilities(text: str) -> set[str]:
    m = re.search(r"MENU_CAPABILITIES\s*=\s*List\.of\((.*?)\);", text, re.S)
    if not m:
        raise SystemExit("找不到 MENU_CAPABILITIES 定义（PermissionMatrix.java 结构变了？）")
    return set(re.findall(r'"([^"]+)"', m.group(1)))


def leaves(text: str) -> list[dict]:
    """按 `key:` 切块，只保留含 `path:` 的块（= 叶子）。"""
    blocks: list[dict] = []
    current: dict | None = None
    for i, line in enumerate(text.splitlines(), start=1):
        km = KEY_RE.match(line)
        if km:
            current = {"key": km.group(1), "line": i, "path": None, "capability": None,
                       "public": False}
            blocks.append(current)
            continue
        if current is None:
            continue
        pm = PATH_RE.match(line)
        if pm and current["path"] is None:
            current["path"] = pm.group(1)
        cm = CAP_RE.match(line)
        if cm and current["capability"] is None:
            current["capability"] = cm.group(1)
        if PUBLIC_RE.match(line):
            current["public"] = True
    return [b for b in blocks if b["path"]]


def check(found: list[dict], back: set[str]) -> list[str]:
    problems: list[str] = []
    for leaf in found:
        cap = leaf["capability"]
        if not cap and not leaf["public"]:
            problems.append(
                f"叶子 `{leaf['key']}`（{leaf['path']}，L{leaf['line']}）既没有 capability "
                f"也没有 public: true —— 白名单制下会被判为不可见")
            continue
        if cap and cap != "public" and cap not in back:
            problems.append(
                f"叶子 `{leaf['key']}` 用了 `{cap}`，后端 MENU_CAPABILITIES 里没有")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true",
                    help="注入一条缺声明，断言判红（纯内存，不改文件）")
    args = ap.parse_args()

    if not NAV.exists() or not MATRIX.exists():
        print(f"[ERROR] 找不到文件：\n  {NAV}\n  {MATRIX}")
        return 2

    nav_text = NAV.read_text(encoding="utf-8")
    back = backend_capabilities(MATRIX.read_text(encoding="utf-8"))
    found = leaves(nav_text)

    if not found:
        print("[FAIL] 一个叶子都没解析到 —— 解析规则与 navigation.ts 结构脱节了")
        return 1

    public_count = sum(1 for x in found if x["public"] or x["capability"] == "public")
    print(f"叶子 {len(found)} 个（public {public_count} 个），后端能力码 {len(back)} 个")
    problems = check(found, back)

    if args.self_test:
        # 注入：把第一个非 public 叶子的能力声明抹掉（内存里改），必须被判红。
        injected = [dict(x) for x in found]
        victim = next(x for x in injected if x["capability"] and not x["public"])
        victim["capability"] = None
        victim["public"] = False
        injected_problems = check(injected, back)
        if not any(victim["key"] in p for p in injected_problems):
            print(f"[FAIL] --self-test：抹掉 `{victim['key']}` 的能力后仍未判红，检查逻辑失效")
            return 1
        print(f"[PASS] --self-test：抹掉 `{victim['key']}` 的能力后判红")
        if problems:
            print(f"[WARN] 还原后仍有 {len(problems)} 条真实问题")
        return 0

    if problems:
        print(f"\n[FAIL] {len(problems)} 条白名单违规：")
        for p in problems:
            print("  - " + p)
        return 1

    print("[PASS] 每个叶子都有 capability 或 public，且能力码都在后端白名单内")
    return 0


if __name__ == "__main__":
    sys.exit(main())
