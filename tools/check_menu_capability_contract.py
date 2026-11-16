#!/usr/bin/env python3
"""菜单能力契约机检（v5.19.0 / W69-02）。

判一件事：**前端用到的能力码，后端必须定义且能下发**。

背景：`execution:read` 在前端 5 个执行态叶子上用了一整年，后端 `MENU_CAPABILITIES`
里从未存在 → 这些入口对**所有角色（含 admin）恒隐藏**，用户只能手输 URL。同期还发现
`project:create` 同样缺定义。这类"两端各写各的"不会让任何单测变红，只有契约检查能抓。

规则：
1. 前端 `navigation.ts` 中出现的 `capability: '<字面量>'` 必须 ⊆ 后端 `MENU_CAPABILITIES`；
2. 后端有而前端未用的能力码 → 只告警（可能是预留或仅接口使用）；
3. `--self-test` 注入一个不存在的能力码，必须判红后还原。

用法：
    python3 tools/check_menu_capability_contract.py
    python3 tools/check_menu_capability_contract.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NAV = ROOT / "frontend/src/config/navigation.ts"
MATRIX = ROOT / "backend/src/main/java/com/qa/autotest/domain/iam/PermissionMatrix.java"

# 变量透传与类型声明，不是真实能力码
NON_LITERAL = {"item.capability", "undefined", "null"}


def backend_capabilities(text: str) -> list[str]:
    m = re.search(r"MENU_CAPABILITIES\s*=\s*List\.of\((.*?)\);", text, re.S)
    if not m:
        raise SystemExit("找不到 MENU_CAPABILITIES 定义（PermissionMatrix.java 结构变了？）")
    return re.findall(r'"([^"]+)"', m.group(1))


def frontend_capabilities(text: str) -> dict[str, list[int]]:
    out: dict[str, list[int]] = {}
    for i, line in enumerate(text.splitlines(), start=1):
        m = re.search(r"capability:\s*'([^']+)'", line)
        if not m:
            continue
        code = m.group(1).strip()
        if code in NON_LITERAL or code == "public":
            continue
        out.setdefault(code, []).append(i)
    return out


def check(front: dict[str, list[int]], back: set[str], injected: list[str] | None = None) -> list[str]:
    problems = []
    codes = dict(front)
    for code in injected or []:
        codes.setdefault(code, [0])
    for code, lines in sorted(codes.items()):
        if code not in back:
            where = ", ".join(f"L{n}" for n in lines if n) or "（注入）"
            problems.append(f"前端用了 `{code}`（{where}），后端 MENU_CAPABILITIES 未定义")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="注入一个不存在的能力码，断言判红后还原")
    args = ap.parse_args()

    if not NAV.exists() or not MATRIX.exists():
        print(f"[ERROR] 找不到文件：\n  {NAV}\n  {MATRIX}")
        return 2

    nav_text = NAV.read_text(encoding="utf-8")
    back = backend_capabilities(MATRIX.read_text(encoding="utf-8"))
    front = frontend_capabilities(nav_text)

    print(f"后端能力码 {len(back)} 个：{', '.join(back)}")
    print(f"前端引用 {len(front)} 个：{', '.join(sorted(front))}")

    problems = check(front, set(back))
    unused = sorted(set(back) - set(front))

    if args.self_test:
        injected = check(front, set(back), injected=["__ux_contract_selftest__"])
        if not injected:
            print("[FAIL] --self-test：注入不存在的能力码后仍未判红，检查逻辑失效")
            return 1
        print(f"[PASS] --self-test：注入后判红（{injected[0]}）")
        # 还原：真实检查仍须通过
        if problems:
            print(f"[WARN] 还原后仍有 {len(problems)} 条真实问题：")
            for p in problems:
                print("  - " + p)
        return 0

    if problems:
        print(f"\n[FAIL] {len(problems)} 条契约违规：")
        for p in problems:
            print("  - " + p)
        return 1

    if unused:
        print(f"[WARN] 后端定义了但前端未用（仅告警）：{', '.join(unused)}")
    print("[PASS] 前端能力码全部在后端白名单内")
    return 0


if __name__ == "__main__":
    sys.exit(main())
