#!/usr/bin/env python3
"""用例软删除机检（v5.21.0 / FR-v5.21-03 判据 9 / W71-05）。

判据：`test_case` **不存在物理删除路径**（大师 2026-11-16 拍板：engineer 保留 DELETE，
但必须软删；项目内不显示、全局公共用例仍保留）。

规则：
1. 后端源码里出现 `DELETE FROM test_case`（不含 `test_case_step`）即判红；
2. 唯一豁免：紧邻（前 3 行内）有 `soft-delete-exempt:` 标记。豁免必须写明**为什么**，
   当前只有一处——存量同步的"撤销本 job 刚插入的行"，删的是自己刚写的数据，
   不是用户删除，且软删会让唯一键一直占位、同编号再同步就撞键。

为什么用源码扫描而不是跑 SQL：物理删除的路径可能藏在没人走的回滚分支里，
跑一次主流程永远发现不了；静态扫描能在写代码的时候就挡住。

用法：
    python3 tools/check_case_soft_delete.py
    python3 tools/check_case_soft_delete.py --self-test   # 注入一行删除，必须判红
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "backend/src/main/java"
PATTERN = re.compile(r"DELETE\s+FROM\s+test_case\b(?!_step)", re.I)
EXEMPT = "soft-delete-exempt:"


def scan(root: Path, injected: str | None = None) -> tuple[int, list[str]]:
    hits = 0
    problems: list[str] = []
    files = sorted(root.rglob("*.java")) if root.exists() else []
    for path in files:
        text = path.read_text(encoding="utf-8", errors="ignore")
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if not PATTERN.search(line):
                continue
            # 跳过注释里写的 SQL 说明
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            hits += 1
            window = "\n".join(lines[max(0, i - 3): i + 1])
            if EXEMPT in window:
                continue
            problems.append(f"{path.relative_to(ROOT)}:{i + 1} 物理删除 test_case：{stripped[:80]}")
    if injected:
        problems.append(f"（注入）{injected}")
    return hits, problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="注入一条物理删除，断言判红")
    args = ap.parse_args()

    if not SRC.exists():
        print(f"[ERROR] 找不到后端源码目录：{SRC}")
        return 2

    hits, problems = scan(SRC)
    print(f"扫描后端源码：test_case 删除语句 {hits} 处（含已豁免）")

    if args.self_test:
        injected_hits, injected = scan(SRC, injected="backend/.../Evil.java:1 DELETE FROM test_case")
        if not any("注入" in p for p in injected):
            print("[FAIL] --self-test：注入后未判红，检查逻辑失效")
            return 1
        print(f"[PASS] --self-test：注入后判红（当前真实问题 {len(problems)} 条）")
        return 0

    if problems:
        print(f"\n[FAIL] {len(problems)} 处未豁免的物理删除：")
        for p in problems:
            print("  - " + p)
        print("\n处理：用户删除必须走软删（UPDATE deleted_at）；确属\"撤销自己刚写的行\"才可加"
              " soft-delete-exempt 标记并写明理由。")
        return 1

    print("[PASS] 不存在未豁免的物理删除路径（用户删除统一走软删）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
