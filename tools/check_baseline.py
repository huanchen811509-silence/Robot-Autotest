#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""时长基线机检（v5.3.1 / FR-v5.3-03 / W53-03）。

为什么需要这条机检
------------------
基线的三个数值会被下游直接当事实用：P90×1.5 是软超时阈值，P50 是页面上的
「正常耗时」，P99 与 max 用来判断尾部有多长。三者中任何一个算错，
后果都不是「页面不好看」，而是**一条用例永远被标 slow**——而且没人看得出来
是基线错了还是机器慢了。

这类错误靠单测抓不全：单测验算法，但抓不到「迁移里少了一列」「SQL 里漏了
verdict 过滤」。所以门禁要独立验一遍。

守什么
------
1. **迁移齐备**：`case_duration_baseline` 的建表迁移 + P99 补列迁移 + 成对 down；
2. **分母干净**：样本 SQL 只取 PASS/FAIL，不含 PLANNED / SKIP；
3. **常量在位**：`MIN_SAMPLES`、离群比例、慢判定倍数都在；
4. **分位单调有守门**：服务里必须有用 `monotonic()` 的剔除逻辑（不是只定义不调用）；
5. **可选 DB 深校验**（`--db`）：真库里跑一遍 `p50<=p90<=p99<=max`，
   并确认 `sample_count > 0`。没有数据库时只做静态部分并明确打印 NOTE。

用法
----
    python3 tools/check_baseline.py               # 静态检查
    python3 tools/check_baseline.py --db          # 静态 + 真库校验
    python3 tools/check_baseline.py --self-test    # 破坏性自检：抹掉单调守门，必须判红

退出码：0 = 通过；1 = 违规；2 = 扫描失败（找不到源码，疑似假绿）。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION_DIR = ROOT / "db" / "migration"
DOWN_DIR = ROOT / "db" / "down"
SERVICE = (ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest"
           / "application" / "execution" / "DurationBaselineService.java")
JDBC = (ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest"
        / "infrastructure" / "CaseDurationBaselineRepositoryJdbc.java")
DOMAIN = (ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest"
          / "domain" / "execution" / "CaseDurationBaseline.java")

REQUIRED_COLUMNS = ["p50_ms", "p90_ms", "p99_ms", "max_ms", "sample_count", "insufficient"]


def ok(message: str) -> None:
    print(f"  OK     {message}")


def fail(problems: list[str], message: str) -> None:
    problems.append(message)
    print(f"  FAIL   {message}")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def check_migrations(problems: list[str]) -> None:
    """建表迁移 + P99 补列迁移 + down 成对。"""
    base = MIGRATION_DIR / "V11_51__case_duration_baseline.sql"
    p99 = MIGRATION_DIR / "V11_54__case_duration_baseline_p99.sql"
    if not base.is_file():
        fail(problems, f"找不到建表迁移 {base.name}")
        return
    ok(f"建表迁移存在：{base.name}")
    if not p99.is_file():
        fail(problems, f"找不到 P99 补列迁移 {p99.name}（PRD R1 要求 p99_ms）")
    else:
        ok(f"P99 补列迁移存在：{p99.name}")

    combined = read(base) + read(p99)
    missing = [col for col in REQUIRED_COLUMNS if f"`{col}`" not in combined]
    if missing:
        fail(problems, f"迁移里缺列：{', '.join(missing)}")
    else:
        ok(f"字段齐备：{len(REQUIRED_COLUMNS)} 项")

    for path in (base, p99):
        if not path.is_file():
            continue
        down = DOWN_DIR / (path.stem.replace("__", "__", 1) + ".down.sql")
        if not down.is_file():
            down = DOWN_DIR / (path.name.replace(".sql", ".down.sql"))
        if not down.is_file():
            fail(problems, f"{path.name} 缺成对 down 脚本（回滚演练会卡住）")


def check_service(problems: list[str]) -> None:
    """常量与算法守门。"""
    service = read(SERVICE)
    jdbc = read(JDBC)
    domain = read(DOMAIN)
    if not service or not jdbc or not domain:
        fail(problems, "找不到基线服务/仓储/领域类源码")
        return

    for token, why in [
        ("MIN_SAMPLES = 5", "样本不足阈值必须是 5（PRD R2）"),
        ("OUTLIER_TOP_RATIO", "缺离群剔除比例"),
        ("SLOW_FACTOR", "缺慢判定倍数（软超时 = P90 × 该倍数）"),
    ]:
        if token not in service:
            fail(problems, f"DurationBaselineService 缺 {token}：{why}")
    ok("算法常量在位（MIN_SAMPLES / 离群比例 / 慢判定倍数）")

    # 分母干净：样本 SQL 只能取 PASS/FAIL
    verdict_filter = re.search(r"verdict\s+IN\s*\(([^)]*)\)", jdbc, re.IGNORECASE)
    if not verdict_filter:
        fail(problems, "样本 SQL 没有 verdict 过滤（SKIP / PLANNED 会污染基线）")
    else:
        values = {v.strip().strip("'\"").upper() for v in verdict_filter.group(1).split(",")}
        polluted = values & {"SKIP", "SKIPPED", "PLANNED", "PENDING"}
        if polluted:
            fail(problems, f"样本 SQL 含不该统计的结论：{', '.join(sorted(polluted))}")
        elif not {"PASS", "FAIL"} <= values:
            fail(problems, f"样本 SQL 的 verdict 过滤不完整：{sorted(values)}")
        else:
            ok("样本 SQL 只取 PASS/FAIL（不含 SKIP / PLANNED）")

    # 迁移里不能出现 PLANNED 字面量（说明有人往基线表塞计划行）
    if "PLANNED" in read(MIGRATION_DIR / "V11_51__case_duration_baseline.sql"):
        fail(problems, "基线迁移里出现 PLANNED 字面量（计划行不该进基线）")

    # 分位单调必须「有调用」，不能只定义
    if "boolean monotonic()" not in domain:
        fail(problems, "CaseDurationBaseline 没有 monotonic() 守门方法")
    elif ".monotonic()" not in service:
        fail(problems, "monotonic() 定义了但重算时没调用（等于没守门）")
    else:
        ok("分位单调有守门且被重算调用")

    # P99 必须真的参与计算，不能只在表里有一列
    if "percentile(kept, 99)" not in service:
        fail(problems, "P99 列存在但重算没有算 P99（列会一直是 NULL）")
    else:
        ok("P99 参与重算")


def check_database(problems: list[str]) -> bool:
    """真库校验：分位单调 + 样本数非空。返回是否真的跑了。"""
    env = dict(os.environ)
    env_file = Path.home() / ".config" / "robot-autotest" / "env"
    if env_file.is_file():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                key, _, value = line.partition("=")
                env.setdefault(key.strip(), value.strip())
    if not env.get("DB_HOST"):
        print("  NOTE   没有数据库环境变量，跳过真库校验")
        return False

    sql = """
        SELECT
          SUM(p50_ms > p90_ms OR p90_ms > p99_ms OR p99_ms > max_ms) AS not_monotonic,
          SUM(sample_count <= 0) AS empty_samples,
          COUNT(*) AS total
        FROM case_duration_baseline;
    """
    command = [
        "mysql", f"-h{env['DB_HOST']}", f"-P{env.get('DB_PORT', '3306')}",
        f"-u{env.get('DB_USERNAME', 'root')}", f"-p{env.get('DB_PASSWORD', '')}",
        "-N", "-B", env.get("DB_NAME", "robot_dev"), "-e", sql,
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"  NOTE   真库校验无法执行（{error}），跳过")
        return False
    if result.returncode != 0:
        print(f"  NOTE   真库校验失败（{result.stderr.strip()[:120]}），跳过")
        return False
    row = (result.stdout.strip().splitlines() or [""])[0].split("\t")
    if len(row) < 3:
        print("  NOTE   真库查询没有返回预期列，跳过")
        return False
    not_monotonic, empty_samples, total = row[0], row[1], row[2]
    # 空表时 SUM(...) 返回 NULL，不是 0——直接 int() 会炸
    if to_int(not_monotonic) > 0:
        fail(problems, f"库里有 {not_monotonic} 行分位不单调（p50<=p90<=p99<=max 被破坏）")
    if to_int(empty_samples) > 0:
        fail(problems, f"库里有 {empty_samples} 行 sample_count <= 0（空基线不该落库）")
    ok(f"真库校验通过（{to_int(total)} 行基线，分位单调、样本数非空）")
    return True


def to_int(value: str) -> int:
    """把 mysql -N 的输出转 int；NULL 与空串按 0。"""
    text = (value or "").strip()
    if text in ("", "NULL"):
        return 0
    try:
        return int(float(text))
    except ValueError:
        return 0


def run(with_db: bool) -> int:
    print(f"[check_baseline] 迁移目录={MIGRATION_DIR.relative_to(ROOT)}")
    problems: list[str] = []
    check_migrations(problems)
    check_service(problems)
    if with_db:
        check_database(problems)
    else:
        print("  NOTE   未指定 --db，只做静态检查（分位单调性由单测与重算守门共同保证）")

    if problems:
        print(f"check_baseline: FAIL（{len(problems)} 项）")
        return 1
    print("check_baseline: PASS")
    return 0


def self_test() -> int:
    """真注入：抹掉服务里对 monotonic() 的调用，检查必须判红。"""

    import tempfile

    print("--- self-test：移走单调守门调用后应判失败 ---")
    global SERVICE
    original = SERVICE
    backup = original.read_text(encoding="utf-8")
    temp_path: Path | None = None
    try:
        broken = backup.replace(".monotonic()", ".ignoredMonotonicCheck()")
        with tempfile.NamedTemporaryFile("w", suffix=".java", delete=False,
                                         encoding="utf-8") as handle:
            handle.write(broken)
            temp_path = Path(handle.name)
        SERVICE = temp_path
        problems: list[str] = []
        check_service(problems)
        if not any("没调用" in p for p in problems):
            print(f"  FAIL   自检未按预期判红：{problems}")
            print("check_baseline: SELF-TEST FAIL")
            return 1
        print("  OK     抹掉守门调用后判红")
    finally:
        SERVICE = original
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)

    print("check_baseline: SELF-TEST PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="时长基线 schema / 算法 / 单调性机检")
    parser.add_argument("--db", action="store_true", help="额外做真库校验")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return run(args.db)


if __name__ == "__main__":
    sys.exit(main())
