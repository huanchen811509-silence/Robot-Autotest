#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v3.4.0 · 容量报告（FR-3.4-05，门禁第 26 步的输入之一）。

测量而不是估算：
  - 真实行数（test_case / test_result / keyword_result / run / audit_log）；
  - information_schema 的真实表体积与平均行长；
  - 归档候选 SQL 的 EXPLAIN：必须走索引，不允许全表扫描；
  - 按 FR-3.4-01 的目标规模（5 万用例 / 100 万日志）用**实测平均行长**外推体积。

输出 docs/evidence/v3.4.0/capacity-report.json。
退出码：0 = 索引到位且外推体积在预算内；1 = 不达标；2 = 无法连接数据库。
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_FILE = Path(os.environ.get("CAPACITY_REPORT_OUT",
                               ROOT / "docs/evidence/v3.4.0/capacity-report.json"))
DATABASE = os.environ.get("DB_NAME", "robot_dev")
BUDGET_MB = float(os.environ.get("CAPACITY_BUDGET_MB", "2048"))
TARGET_CASES = int(os.environ.get("CAPACITY_TARGET_CASES", "50000"))
TARGET_RESULTS = int(os.environ.get("CAPACITY_TARGET_RESULTS", "1000000"))


def env_value(key: str) -> str | None:
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
    rows = []
    for line in result.stdout.splitlines():
        rows.append(line.split("\t"))
    return rows


def scalar(sql: str, fallback: float = 0.0) -> float:
    rows = mysql(sql)
    if not rows or not rows[0]:
        return fallback
    try:
        return float(rows[0][0])
    except ValueError:
        return fallback


def main() -> int:
    try:
        counts = {
            "testCase": int(scalar("SELECT COUNT(*) FROM test_case")),
            "testResult": int(scalar("SELECT COUNT(*) FROM test_result")),
            "keywordResult": int(scalar("SELECT COUNT(*) FROM keyword_result")),
            "run": int(scalar("SELECT COUNT(*) FROM run")),
            "auditLog": int(scalar("SELECT COUNT(*) FROM audit_log")),
        }
    except Exception as exc:  # noqa: BLE001
        print("CAPACITY_ERROR 数据库不可用：%s" % exc, file=sys.stderr)
        return 2

    sizes = {}
    rows = mysql("SELECT TABLE_NAME, DATA_LENGTH + INDEX_LENGTH, TABLE_ROWS "
                 "FROM information_schema.TABLES WHERE TABLE_SCHEMA='%s' "
                 "AND TABLE_NAME IN ('test_case','test_result','keyword_result','run','audit_log')"
                 % DATABASE)
    for name, bytes_total, approx_rows in rows:
        sizes[name] = {"bytes": int(float(bytes_total or 0)),
                       "approxRows": int(float(approx_rows or 0))}

    indexes = [row[2] for row in mysql("SHOW INDEX FROM run") if len(row) > 2]
    has_created_index = "idx_run_project_created" in indexes
    plan = mysql("EXPLAIN SELECT id FROM run WHERE project_id=1 "
                 "AND created_at < NOW() - INTERVAL 180 DAY "
                 "ORDER BY created_at LIMIT 200")
    plan_key = plan[0][5] if plan and len(plan[0]) > 5 else ""
    plan_type = plan[0][3] if plan and len(plan[0]) > 3 else ""
    single_scan = plan_type in ("ALL", "index") and plan_key in ("", None)

    def per_row(table: str) -> float:
        info = sizes.get(table) or {"bytes": 0, "approxRows": 0}
        if info["approxRows"] > 0:
            return info["bytes"] / info["approxRows"]
        return 0.0

    projected = (per_row("test_case") * TARGET_CASES
                 + per_row("test_result") * TARGET_RESULTS
                 + per_row("audit_log") * max(counts["auditLog"], TARGET_RESULTS // 10))
    projected_mb = round(projected / 1024 / 1024, 1)
    total_current_mb = round(sum(item["bytes"] for item in sizes.values()) / 1024 / 1024, 1)
    verdict = "PASS" if has_created_index and not single_scan and projected_mb <= BUDGET_MB else "FAIL"

    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "database": DATABASE,
        "counts": counts,
        "currentSizeMb": total_current_mb,
        "indexes": sorted(set(indexes)),
        "archiveQuery": {"type": plan_type, "key": plan_key, "fullScan": single_scan},
        "targets": {"cases": TARGET_CASES, "results": TARGET_RESULTS},
        "projectedSizeMb": projected_mb,
        "budgetMb": BUDGET_MB,
        "verdict": verdict,
    }
    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("CAPACITY %s cases=%d results=%d current=%.1fMB projected=%sMB budget=%sMB "
          "index=%s plan=%s/%s -> %s"
          % (verdict, counts["testCase"], counts["testResult"], total_current_mb,
             projected_mb, BUDGET_MB, has_created_index, plan_type, plan_key, OUT_FILE))
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
