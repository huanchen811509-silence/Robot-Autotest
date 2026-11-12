#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""手工 / 自动分离统计机检（v5.4.1 / FR-v5.4-04 / FR-v5.4-05 / W54-04 / W54-05）。

为什么需要这条机检
------------------
「通过率」最容易在两条路上悄悄失真，且两条都不报错：

1. **手工结果混进自动通过率**——手工用例点一下「通过」就算过，自动化用例要真跑。
   混在一起算，数字会变好看，但对外报出去等于把「人点了一下」和「机器跑通了」当同一件事；
2. **未确认步骤也能提交**——手工执行的核心约束是「每个步骤都要有人确认」。
   一旦 `submit()` 少了那道校验，执行记录会变成一份没有人操作过的「记录」。

这两条都只能在契约层面守：代码里少一次调用，编译和单测都不会红。

守什么
------
1. **分列不是合计**：`ResultSplitService` 必有 `auto` / `manual` / `total` 三份 + `consistent`；
2. **分母干净**：`exec_mode` 统计只算 `PASS` / `FAIL`（把 SKIP/PLANNED 算进分母会虚低）；
3. **未确认不能提交**：`ManualExecutionService.submit()` 必须调用 `unconfirmedSteps()` 并拒绝；
4. **库结构到位**（`--db`）：`test_case.exec_mode` / `test_result.exec_mode` /
   `manual_execution` / `manual_step_result` 都在，且 `test_result.exec_mode` 无 NULL。

用法
----
    python3 tools/check_manual_split.py
    python3 tools/check_manual_split.py --db           # 额外做真库校验
    python3 tools/check_manual_split.py --self-test    # 真注入：去掉未确认校验，必须判红

退出码：0 = 通过；1 = 违规；2 = 扫描失败（找不到源码，疑似假绿）。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest"
SPLIT = BASE / "application" / "report" / "ResultSplitService.java"
EXEC_REPO = BASE / "infrastructure" / "ExecModeStatsRepositoryJdbc.java"
MANUAL = BASE / "application" / "execution" / "ManualExecutionService.java"

EXEC_MODES = ["auto", "manual"]


def ok(message: str) -> None:
    print(f"  OK     {message}")


def fail(problems: list[str], message: str) -> None:
    problems.append(message)
    print(f"  FAIL   {message}")


def check_split(text: str, problems: list[str]) -> None:
    missing = [name for name in ("auto", "manual", "total", "consistent") if name not in text]
    if missing:
        fail(problems, f"ResultSplitService 缺少分离统计的组成部分：{', '.join(missing)}")
    else:
        ok("通过率拆 auto / manual / total 三份并带一致性标记")

    if "hasManual" not in text:
        fail(problems, "ResultSplitService 没有 hasManual()（没法判断要不要标「含手工 N 条」）")
    else:
        ok("能判断本批次是否含手工结果")


def check_repo(text: str, problems: list[str]) -> None:
    if "verdict IN ('PASS', 'FAIL')" not in text and 'verdict IN ("PASS", "FAIL")' not in text:
        fail(problems, "exec_mode 统计的分母没有限定在 PASS/FAIL（SKIP/PLANNED 会虚低通过率）")
    else:
        ok("分母只含 PASS / FAIL")
    if "exec_mode" not in text:
        fail(problems, "执行方式统计没有读 exec_mode 列")
    else:
        ok("按 exec_mode 维度取数")


def check_manual(text: str, problems: list[str]) -> None:
    match = re.search(r"public SubmitResult submit\((.*?)\n    \}", text, re.S)
    if not match:
        fail(problems, "找不到 ManualExecutionService.submit(...)")
        return
    body = match.group(1)
    if "unconfirmedSteps()" not in body:
        fail(problems, "submit() 没有校验未确认步骤（手工执行会变成一份没人操作过的记录）")
    elif "throw new BusinessException" not in body:
        fail(problems, "submit() 读了未确认步骤但没有拒绝（只记录不拦等于没校验）")
    else:
        ok("未确认全部步骤时拒绝提交")


def mysql_env() -> dict[str, str] | None:
    env = dict(os.environ)
    env_file = Path.home() / ".config" / "robot-autotest" / "env"
    if env_file.is_file():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                key, _, value = line.partition("=")
                env.setdefault(key.strip(), value.strip())
    return env if env.get("DB_HOST") else None


def query(env: dict[str, str], sql: str) -> str | None:
    command = [
        "mysql", f"-h{env['DB_HOST']}", f"-P{env.get('DB_PORT', '3306')}",
        f"-u{env.get('DB_USERNAME', 'root')}", f"-p{env.get('DB_PASSWORD', '')}",
        "-N", "-B", env.get("DB_NAME", "robot_dev"), "-e", sql,
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"  NOTE   真库校验无法执行（{error}），跳过")
        return None
    if result.returncode != 0:
        print(f"  NOTE   真库查询失败（{result.stderr.strip()[:120]}），跳过")
        return None
    return result.stdout.strip()


def to_int(value: str) -> int:
    text = (value or "").strip()
    if text in ("", "NULL"):
        return 0
    try:
        return int(float(text))
    except ValueError:
        return 0


def check_database(problems: list[str]) -> None:
    env = mysql_env()
    if env is None:
        print("  NOTE   没有数据库环境变量，跳过真库校验")
        return

    tables = query(env, "SELECT table_name FROM information_schema.tables "
                        f"WHERE table_schema = '{env.get('DB_NAME', 'robot_dev')}' "
                        "AND table_name IN ('manual_execution','manual_step_result');")
    if tables is None:
        return
    present = {line.strip() for line in tables.splitlines() if line.strip()}
    missing = {"manual_execution", "manual_step_result"} - present
    if missing:
        fail(problems, f"库中缺表：{', '.join(sorted(missing))}")
    else:
        ok("手工执行两张表在库")

    columns = query(env, "SELECT table_name, column_name FROM information_schema.columns "
                         f"WHERE table_schema = '{env.get('DB_NAME', 'robot_dev')}' "
                         "AND ((table_name = 'test_case' AND column_name = 'exec_mode') "
                         "OR (table_name = 'test_result' AND column_name = 'exec_mode'));")
    if columns is None:
        return
    found = {tuple(line.split("\t")) for line in columns.splitlines() if "\t" in line}
    if len(found) < 2:
        fail(problems, f"exec_mode 列不全（应 test_case 与 test_result 各一列）：{sorted(found)}")
    else:
        ok("test_case.exec_mode 与 test_result.exec_mode 都在")

    stats = query(env, "SELECT SUM(exec_mode IS NULL) AS nulls, COUNT(*) AS total, "
                       "SUM(exec_mode NOT IN ('auto','manual')) AS unknown FROM test_result;")
    if stats is None:
        return
    row = (stats.splitlines() or [""])[0].split("\t")
    if len(row) < 3:
        print("  NOTE   真库查询没有返回预期列，跳过")
        return
    nulls, total, unknown = to_int(row[0]), to_int(row[1]), to_int(row[2])
    if nulls > 0:
        fail(problems, f"test_result 有 {nulls} 行 exec_mode 为空（分离统计会把它当未知）")
    if unknown > 0:
        fail(problems, f"test_result 有 {unknown} 行 exec_mode 既不是 auto 也不是 manual")
    if nulls == 0 and unknown == 0:
        ok(f"真库校验通过（{total} 条结果，exec_mode 全部有值且合法）")


def run(with_db: bool) -> int:
    print(f"[check_manual_split] 分离统计={SPLIT.relative_to(ROOT)}")
    for file in (SPLIT, EXEC_REPO, MANUAL):
        if not file.is_file():
            print(f"check_manual_split: FAIL（找不到 {file}）")
            return 2
    problems: list[str] = []
    check_split(SPLIT.read_text(encoding="utf-8"), problems)
    check_repo(EXEC_REPO.read_text(encoding="utf-8"), problems)
    check_manual(MANUAL.read_text(encoding="utf-8"), problems)
    if with_db:
        check_database(problems)
    else:
        print("  NOTE   未指定 --db，只做静态检查")
    if problems:
        print(f"check_manual_split: FAIL（{len(problems)} 项）")
        return 1
    print("check_manual_split: PASS")
    return 0


def self_test() -> int:
    """真注入：把未确认步骤的校验删掉，必须判红。"""
    print("--- self-test：去掉未确认步骤校验后应判失败 ---")
    global MANUAL
    original = MANUAL
    backup = original.read_text(encoding="utf-8")
    broken = backup.replace("execution.unconfirmedSteps()", "java.util.List.<Integer>of()", 1)
    if broken == backup:
        print("  FAIL   自检注入点没找到（实现结构变了），无法自证")
        print("check_manual_split: SELF-TEST FAIL")
        return 2
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".java", delete=False,
                                         encoding="utf-8") as handle:
            handle.write(broken)
            temp_path = Path(handle.name)
        MANUAL = temp_path
        problems: list[str] = []
        check_manual(MANUAL.read_text(encoding="utf-8"), problems)
        if not any("未确认步骤" in item for item in problems):
            print(f"  FAIL   自检未按预期判红：{problems}")
            print("check_manual_split: SELF-TEST FAIL")
            return 1
        print("  OK     去掉校验后判红")
    finally:
        MANUAL = original
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)

    print("check_manual_split: SELF-TEST PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="手工 / 自动分离统计机检")
    parser.add_argument("--db", action="store_true", help="额外做真库校验")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检")
    args = parser.parse_args()
    return self_test() if args.self_test else run(args.db)


if __name__ == "__main__":
    sys.exit(main())
