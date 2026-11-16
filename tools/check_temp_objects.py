#!/usr/bin/env python3
"""临时对象防复发机检（v5.18.0 / FR-v5.18-07 / W68-16）。

背景：5.8~5.18 期间库里积累了 8 个临时项目与 3 个临时账号，其中**绝大多数没打
`is_demo`**，导致原 `purge-demo.sh`（只认 `is_demo=1`）清不掉。清掉之后真正要防的
是**再次积累**，所以这条机检盯两件事：

1. **登记前缀**：临时对象的编码必须落在已知前缀里（`TEMP_PREFIXES`），且
   `is_demo=0` 的前缀对象一律判红——「建了就忘」是这类残留的唯一生成方式；
2. **已知历史临时账号**：清单写死（不按名字模糊匹配），重现即红。

判定与修复口径写在同一处，避免「机检说红、人不知道怎么改」：
   新建临时对象 → 必须 `is_demo=1`，或把前缀登记进 `TEMP_PREFIXES` 并在本版收口前清掉。

用法：
    python3 tools/check_temp_objects.py
    python3 tools/check_temp_objects.py --self-test   # 注入一条 → 判红 → 还原 → 判绿
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PURGE = ROOT / "scripts/ops/purge-temp.sh"

# 已登记的临时对象前缀：出现即说明「这是造数产物」，收口前必须清零
TEMP_PREFIXES = ("PRJ-RF-2026", "UIAUTO-", "PERF-", "TEST-V59-", "W68-SELFTEST-")
# 已知历史临时账号（清单驱动；新账号不在此列，不做模糊匹配）
TEMP_USERS = ("qa-ui", "prj01-demo", "ast05")


def sql(query: str) -> str:
    script = (
        'set -a; . ~/.config/robot-autotest/env 2>/dev/null; set +a; '
        'source scripts/db-common.sh; '
        f'db_exec "$DB_NAME" -N -B -e {json.dumps(query)} 2>/dev/null'
    )
    result = subprocess.run(["bash", "-lc", script], cwd=ROOT,
                            capture_output=True, text=True, timeout=120)
    return result.stdout.strip()


def scan_static() -> list[str]:
    """清理脚本的护栏是否还在（少一条就是「下次清场会误删或清不掉」）。"""
    problems: list[str] = []
    if not PURGE.exists():
        return ["缺少清理脚本 scripts/ops/purge-temp.sh"]
    text = "\n".join(line for line in PURGE.read_text(encoding="utf-8").splitlines()
                     if not line.lstrip().startswith("#"))
    for token, message in [
        ("APPLY=0", "清理脚本没有 APPLY=0 默认值（默认必须 dry-run）"),
        ("PURGE_TEMP_CONFIRM", "清理脚本缺少 PURGE_TEMP_CONFIRM 二次确认"),
        ("CANDIDATE_PROJECTS", "清理脚本没有显式候选清单（清单驱动是硬要求）"),
        ("CANDIDATE_USERS", "清理脚本没有显式候选账号清单"),
        ("KEEP_PROJECTS", "清理脚本没有保留清单（真对象零变化无从保证）"),
        ("sha256", "清理脚本没有备份校验"),
    ]:
        if token not in text:
            problems.append(message)
    # 候选清单必须是「显式列举」，不能靠 LIKE 模糊匹配生成
    if re.search(r"CANDIDATE_PROJECTS=\(\s*\$\(.*LIKE", text, re.IGNORECASE):
        problems.append("候选清单由 LIKE 生成——按名字猜，会漏也会误删")
    return problems


def scan_database() -> list[str]:
    problems: list[str] = []
    prefix_clause = " OR ".join(f"code LIKE '{p}%'" for p in TEMP_PREFIXES)
    rows = sql(f"SELECT code, IFNULL(is_demo,0) FROM project WHERE {prefix_clause}")
    for line in rows.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        code, demo = parts[0], parts[-1]
        if demo != "1":
            problems.append(
                f"临时前缀项目仍在且未打标：{code}（is_demo={demo}）→ 收口前必须清掉，"
                "或建时置 is_demo=1")
    user_clause = ",".join(f"'{u}'" for u in TEMP_USERS)
    users = sql(f"SELECT username FROM app_user WHERE username IN ({user_clause})")
    for line in users.splitlines():
        if line.strip():
            problems.append(f"历史临时账号仍在：{line.strip()} → 收口前必须清掉")
    return problems


def scan() -> list[str]:
    return scan_static() + scan_database()


def main() -> int:
    self_test = "--self-test" in sys.argv
    problems = scan()

    if self_test:
        if problems:
            print("[FAIL] --self-test 前置不干净：")
            for item in problems:
                print("  " + item)
            return 1
        injected_code = "W68-SELFTEST-TEMP-001"
        # status 是整数列（tinyint），写字面量 'active' 会 1366；created_by 允许 NULL
        sql(f"INSERT INTO project(code,name,status,created_at,created_by,test_kernel,kind,is_demo) "
            f"VALUES('{injected_code}','自证临时项目',1,NOW(),NULL,'robot','standard',0)")
        try:
            hit = scan_database()
        finally:
            sql(f"DELETE FROM project WHERE code='{injected_code}'")
        if not hit:
            print("[FAIL] 注入未打标临时项目却没有判红")
            return 1
        if scan():
            print("[FAIL] 还原后仍判红：")
            for item in scan():
                print("  " + item)
            return 1
        print("[PASS] --self-test：注入未打标临时项目 → 判红 → 还原 → 判绿")
        return 0

    if problems:
        print(f"[FAIL] 临时对象治理不合规：{len(problems)} 处")
        for item in problems:
            print("  " + item)
        print("修复：跑 bash scripts/ops/inventory-temp.sh 盘点，"
              "再按 scripts/ops/purge-temp.sh 的清单清理")
        return 1
    print("[PASS] 无遗留临时对象：前缀清单 0 命中，历史临时账号 0 命中；清理脚本护栏齐全")
    return 0


if __name__ == "__main__":
    sys.exit(main())
