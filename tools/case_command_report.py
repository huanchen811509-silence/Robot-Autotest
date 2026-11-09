#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v3.9.0 · 用例级命令覆盖率取证（FR-v3.9-02 判据 ④）。

从**库里真实数据**统计「每条用例是否都有命令原文或显式 NONE」，按内核分别汇总，
输出 docs/evidence/v3.9.0/EW39-02/case-command-coverage.json，供门禁第 28 步校验。

为什么不在检查器里直接查库：门禁要能离线复算（证据入库存档后才可追溯），
取证与判据分开，「数据怎么来的」和「算不算达标」互不掩盖。

用法：
  python3 tools/case_command_report.py --run RF=RUN-20261109090000-e80000 \\
                                       --run PYTEST=RUN-20261109091000-a30000
  python3 tools/case_command_report.py --run RF=... --out /tmp/x.json --samples 10

退出码：0 = 已写出证据；2 = 查库失败；3 = 指定的 run 在库里没有用例行。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = ROOT / "docs/evidence/v3.9.0/EW39-02/case-command-coverage.json"
DATABASE = os.environ.get("DB_NAME", "robot_dev")

# 与 CaseCommandExtractor.allowedSources() 同一份口径；门禁会再校验两侧一致。
ALLOWED_SOURCES = ["RF_KEYWORD", "PYTEST_MARK", "STDOUT_EXTRACT", "NONE"]


def env_value(key: str) -> str | None:
    """先看环境变量，再读 ~/.config/robot-autotest/env（与其它工具同一约定）。"""
    if os.environ.get(key):
        return os.environ[key]
    path = Path.home() / ".config" / "robot-autotest" / "env"
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            name, value = stripped.split("=", 1)
            if name.strip() == key:
                return value.strip()
    except OSError:
        return None
    return None


def mysql(sql: str) -> list[list[str]]:
    env = dict(os.environ)
    env["MYSQL_PWD"] = env_value("DB_PASSWORD") or ""
    command = ["mysql", "-h" + (env_value("DB_HOST") or "127.0.0.1"),
               "-P" + (env_value("DB_PORT") or "3306"),
               "-u" + (env_value("DB_USERNAME") or "robot"),
               "-N", "-B", DATABASE, "-e", sql]
    result = subprocess.run(command, capture_output=True, text=True, env=env, timeout=60)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip()[:300])
    return [line.split("\t") for line in result.stdout.splitlines()]


def sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def run_summary(run_id: str, kernel: str, samples: int) -> dict:
    where = f"r.run_id = {sql_quote(run_id)}"
    rows = mysql(
        "SELECT r.project_id, COUNT(t.id), "
        "SUM(CASE WHEN t.command_text IS NULL THEN 1 ELSE 0 END), "
        "SUM(CASE WHEN t.command_text = 'NONE' THEN 1 ELSE 0 END), "
        "SUM(CASE WHEN t.command_text IS NOT NULL AND t.command_text <> 'NONE' THEN 1 ELSE 0 END) "
        f"FROM `run` r JOIN test_result t ON t.run_id = r.id WHERE {where} GROUP BY r.project_id")
    if not rows:
        return {"kernel": kernel, "runId": run_id, "total": 0, "missing": True}

    project_id, total, null_count, none_count, with_count = rows[0][:5]
    total = int(total or 0)
    null_count = int(null_count or 0)
    none_count = int(none_count or 0)
    with_count = int(with_count or 0)

    sources: dict[str, int] = {}
    for line in mysql(
            "SELECT COALESCE(t.command_source, 'NULL'), COUNT(*) "
            "FROM `run` r JOIN test_result t ON t.run_id = r.id "
            f"WHERE {where} GROUP BY COALESCE(t.command_source, 'NULL')"):
        sources[line[0]] = int(line[1])

    sample_rows = mysql(
        "SELECT t.case_key, t.command_text, COALESCE(t.command_source, 'NULL'), "
        "COALESCE(t.command_exit_code, 'NULL'), t.log_offset, t.log_span "
        "FROM `run` r JOIN test_result t ON t.run_id = r.id "
        f"WHERE {where} ORDER BY t.id LIMIT {int(samples)}")

    return {
        "kernel": kernel,
        "runId": run_id,
        "projectId": int(project_id),
        "total": total,
        "withCommand": with_count,
        "explicitNone": none_count,
        # 历史行（V11_24 之前入库）三列为 NULL：不算「无命令」，算「没采集」，
        # 单独计数，避免用它把覆盖率凑成 100%。
        "nullLegacy": null_count,
        "coveragePct": round(100.0 * (with_count + none_count) / total, 2) if total else 0.0,
        "sources": sources,
        "samples": [
            {
                "caseKey": row[0],
                "commandText": row[1],
                "commandSource": row[2],
                "commandExitCode": None if row[3] == "NULL" else int(row[3]),
                "logOffset": None if row[4] in ("", "NULL") else int(row[4]),
                "logSpan": None if row[5] in ("", "NULL") else row[5],
            }
            for row in sample_rows
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="导出双内核命令覆盖率证据")
    parser.add_argument("--run", action="append", default=[], metavar="KERNEL=RUN_ID",
                        help="可重复；KERNEL 取 RF / PYTEST")
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--samples", type=int, default=5, help="每个 run 抽多少条样例")
    args = parser.parse_args()

    specs = []
    for item in args.run:
        if "=" not in item:
            print(f"BAD_ARG 需要 KERNEL=RUN_ID，收到：{item}", file=sys.stderr)
            return 3
        kernel, run_id = item.split("=", 1)
        specs.append((kernel.strip().upper(), run_id.strip()))
    if not specs:
        print("BAD_ARG 至少给一个 --run KERNEL=RUN_ID", file=sys.stderr)
        return 3

    try:
        runs = [run_summary(run_id, kernel, args.samples) for kernel, run_id in specs]
    except RuntimeError as exc:
        print(f"DB_FAIL {exc}", file=sys.stderr)
        return 2

    missing = [r["runId"] for r in runs if r.get("missing")]
    if missing:
        print(f"NO_CASES 以下 run 在库里没有用例行：{', '.join(missing)}", file=sys.stderr)
        return 3

    payload = {
        "generatedAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "database": DATABASE,
        "allowedSources": ALLOWED_SOURCES,
        "runs": runs,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    for run in runs:
        print(f"{run['kernel']:<7} {run['runId']} total={run['total']} "
              f"with={run['withCommand']} none={run['explicitNone']} "
              f"null={run['nullLegacy']} coverage={run['coveragePct']}%")
    print(f"WROTE {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
