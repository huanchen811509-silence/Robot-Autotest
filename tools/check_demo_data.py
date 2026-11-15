#!/usr/bin/env python3
"""演示数据治理机检（v5.9.0 / FR-v5.9-09 / W59-25）。

三条规约，每条都对应一个「会真的删错数据」的失误：

1. **有标记位**：`V11_65` 必须给 `app_user` 与 `project` 各加一列 `is_demo`。
   没有这一位，清理就只能按名字猜。
2. **默认不删**：`scripts/ops/purge-demo.sh` 默认 dry-run，`--apply` 还要
   `PURGE_DEMO_CONFIRM=YES`。少一条护栏，一次手滑就是不可逆的数据丢失。
3. **只认标记**：清理脚本里的 `DELETE` 必须落在 `is_demo = 1` 的子查询上，
   且不得出现 `LIKE` / `name =` 之类的名字匹配。

用法：
    python3 tools/check_demo_data.py
    python3 tools/check_demo_data.py --self-test   # 注入违规 → 判红 → 还原 → 判绿
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "db/migration/V11_65__demo_flag.sql"
PURGE = ROOT / "scripts/ops/purge-demo.sh"

# DELETE 语句里必须能找到这个条件（大小写与空格容错）
MARKER = re.compile(r"is_demo\s*=\s*1", re.IGNORECASE)
DELETE = re.compile(r"\bDELETE\s+FROM\b", re.IGNORECASE)
# 按名字猜的两种写法：LIKE 匹配、name/code 等值匹配
NAME_GUESS = re.compile(r"\bLIKE\b|\b(?:name|code)\s*=\s*'", re.IGNORECASE)


def scan_migration() -> list[str]:
    problems: list[str] = []
    if not MIGRATION.exists():
        return [f"缺少迁移 {MIGRATION.relative_to(ROOT)}"]
    text = MIGRATION.read_text(encoding="utf-8")
    for table in ("app_user", "project"):
        pattern = re.compile(r"ALTER\s+TABLE\s+`?" + table + r"`?[\s\S]{0,200}?is_demo",
                             re.IGNORECASE)
        if not pattern.search(text):
            problems.append(f"迁移未给 {table} 加 is_demo 列")
    return problems


def scan_purge(path: Path | None = None) -> list[str]:
    target = path or PURGE
    problems: list[str] = []
    if not target.exists():
        return [f"缺少清理脚本 {target.relative_to(ROOT)}"]
    raw = target.read_text(encoding="utf-8")
    # 注释里会**说明**「禁止 LIKE / 禁止按 name 猜」，那是规约文字不是代码，
    # 不剥掉注释就会把解释条款当成违规（首版机检就误报了这条）。
    text = "\n".join(line for line in raw.splitlines()
                     if not line.lstrip().startswith("#"))

    if "APPLY=0" not in text:
        problems.append("清理脚本没有 APPLY=0 默认值——默认必须 dry-run")
    if "PURGE_DEMO_CONFIRM" not in text:
        problems.append("清理脚本缺少 PURGE_DEMO_CONFIRM 二次确认")
    if "dry-run" not in text:
        problems.append("清理脚本没有 dry-run 分支")

    # 逐行看 DELETE：shell 里的语句不带分号（`q "DELETE ..."` 一行一条），
    # 按分号切一条都切不到——首版就是这么写的，注入违规却判不红。
    #
    # 只看**当前行**，不向后看窗口：向后看会把下一条（合法的）DELETE 的
    # `is_demo = 1` 借过来，违规照样漏判。代价是跨行书写的 DELETE 会误报，
    # 故本脚本约定「一条 DELETE 一行」，这条约定由本机检本身强制。
    for line in text.splitlines():
        if not DELETE.search(line):
            continue
        if not MARKER.search(line):
            problems.append("DELETE 未限定 is_demo = 1 -> " + line.strip()[:90])
    if NAME_GUESS.search(text):
        problems.append("清理脚本出现按名字匹配（LIKE / name = '...'）：改名即漏且会误删真实数据")
    return problems


def scan() -> list[str]:
    return scan_migration() + scan_purge()


def main() -> int:
    self_test = "--self-test" in sys.argv
    problems = scan()

    if self_test:
        if problems:
            print("[FAIL] --self-test 前置不干净")
            for item in problems:
                print("  " + item)
            return 1
        original = PURGE.read_text(encoding="utf-8")
        injected = original.replace(
            'q "DELETE FROM project WHERE is_demo = 1"',
            'q "DELETE FROM project WHERE 1 = 1"',
            1,
        )
        if injected == original:
            print("[FAIL] --self-test 注入点未找到（脚本结构变了？）")
            return 1
        PURGE.write_text(injected, encoding="utf-8")
        try:
            hit = scan()
        finally:
            PURGE.write_text(original, encoding="utf-8")
        if not hit:
            print("[FAIL] --self-test 注入了无标记 DELETE 却没有判红")
            return 1
        if scan():
            print("[FAIL] --self-test 还原后仍有违规")
            return 1
        print("[PASS] --self-test：注入无标记 DELETE → 判红 → 还原 → 判绿")
        return 0

    if problems:
        print(f"[FAIL] 演示数据治理不合规：{len(problems)} 处")
        for item in problems:
            print("  " + item)
        return 1
    print("[PASS] 演示数据治理：标记位齐备、默认 dry-run、删除只认 is_demo = 1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
