#!/usr/bin/env python3
"""路由能力声明机检（v5.21.0 / FR-v5.21-03 判据 7 / W71-06）。

判两件事：

1. **路由表里每条非公开路由都必须声明 `meta.capability`**（或 `meta.public`、
   或纯重定向）。缺一条 = 那条路径对所有登录用户敞开——菜单藏了、URL 还能进，
   这是越权最常见的形态。
2. 声明的能力码必须 ⊆ 后端 `MENU_CAPABILITIES`（与菜单契约机检同源）。

为什么用正则块而不是 AST：`router/index.ts` 是一棵对象字面量数组，`path:` 到下一个
`path:` 之间就是这个路由的全部属性；这样判零依赖、不需要 TS 工具链，门禁里直接跑。

用法：
    python3 tools/check_route_capability.py
    python3 tools/check_route_capability.py --self-test   # 抹掉一条能力，必须判红
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROUTER = ROOT / "frontend/src/router/index.ts"
MATRIX = ROOT / "backend/src/main/java/com/qa/autotest/domain/iam/PermissionMatrix.java"

PATH_RE = re.compile(r"^\s*path:\s*'([^']+)'")
CAP_RE = re.compile(r"meta:\s*\{[^}]*capability:\s*'([^']+)'")
PUBLIC_RE = re.compile(r"meta:\s*\{[^}]*public:\s*true")
REDIRECT_RE = re.compile(r"^\s*redirect:")


def backend_capabilities(text: str) -> set[str]:
    m = re.search(r"MENU_CAPABILITIES\s*=\s*List\.of\((.*?)\);", text, re.S)
    if not m:
        raise SystemExit("找不到 MENU_CAPABILITIES 定义（PermissionMatrix.java 结构变了？）")
    return set(re.findall(r'"([^"]+)"', m.group(1)))


def routes(text: str) -> list[dict]:
    """按 `path:` 切块；块 = 本行到下一个 `path:` 行之前。"""
    lines = text.splitlines()
    starts = [(i, PATH_RE.match(line)) for i, line in enumerate(lines) if PATH_RE.match(line)]
    out = []
    for idx, (i, m) in enumerate(starts):
        end = starts[idx + 1][0] if idx + 1 < len(starts) else len(lines)
        block = "\n".join(lines[i:end])
        cap = CAP_RE.search(block)
        out.append(
            {
                "path": m.group(1),
                "line": i + 1,
                "capability": cap.group(1) if cap else None,
                "public": bool(PUBLIC_RE.search(block)),
                "redirect": any(REDIRECT_RE.match(x) for x in lines[i:end]),
            }
        )
    return out


def check(found: list[dict], back: set[str]) -> list[str]:
    problems: list[str] = []
    for route in found:
        if route["capability"] or route["public"] or route["redirect"]:
            pass
        else:
            problems.append(
                f"路由 `{route['path']}`（L{route['line']}）没有 meta.capability —— "
                f"菜单里藏了也挡不住手输 URL")
            continue
        cap = route["capability"]
        if cap and cap != "public" and cap not in back:
            problems.append(f"路由 `{route['path']}` 用了 `{cap}`，后端白名单里没有")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="抹掉一条能力声明，断言判红（纯内存）")
    args = ap.parse_args()

    if not ROUTER.exists() or not MATRIX.exists():
        print(f"[ERROR] 找不到文件：\n  {ROUTER}\n  {MATRIX}")
        return 2

    text = ROUTER.read_text(encoding="utf-8")
    back = backend_capabilities(MATRIX.read_text(encoding="utf-8"))
    found = routes(text)
    if not found:
        print("[FAIL] 一条路由都没解析到 —— 解析规则与 router/index.ts 结构脱节了")
        return 1

    guarded = sum(1 for r in found if r["capability"])
    public = sum(1 for r in found if r["public"] and not r["capability"])
    redirect = sum(1 for r in found if r["redirect"] and not r["capability"] and not r["public"])
    print(f"路由 {len(found)} 条：能力受限 {guarded} / 公开 {public} / 纯重定向 {redirect}")
    problems = check(found, back)

    if args.self_test:
        injected = [dict(r) for r in found]
        victim = next(r for r in injected if r["capability"])
        victim["capability"] = None
        injected_problems = check(injected, back)
        if not any(victim["path"] in p for p in injected_problems):
            print(f"[FAIL] --self-test：抹掉 `{victim['path']}` 的能力后仍未判红")
            return 1
        print(f"[PASS] --self-test：抹掉 `{victim['path']}` 的能力后判红")
        if problems:
            print(f"[WARN] 还原后仍有 {len(problems)} 条真实问题")
        return 0

    if problems:
        print(f"\n[FAIL] {len(problems)} 条路由缺能力声明：")
        for p in problems:
            print("  - " + p)
        return 1

    print("[PASS] 每条非公开路由都有能力声明，且能力码都在后端白名单内")
    return 0


if __name__ == "__main__":
    sys.exit(main())
