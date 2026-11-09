#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""备份恢复演练状态校验（门禁第 26 步，FR-3.3-10 / FR-3.4-05）。

只读 scripts/backup-drill.sh 写出的 docs/evidence/ops/backup-drill-latest.json，
不代替演练本身。判据：
  - 证据文件存在且可解析（缺失 = 没做过演练，判红）；
  - verdict=PASS；
  - RTO ≤ 7200s（2h）、RPO ≤ 86400s（24h）；
  - 证据新鲜度 ≤ 90 天（超过则需要重跑演练）。

退出码：0 = 通过；1 = 不达标；2 = 证据缺失/不可解析。
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILE = Path(os.environ.get("BACKUP_STATUS_FILE",
                           ROOT / "docs/evidence/ops/backup-drill-latest.json"))
MAX_RTO = int(os.environ.get("BACKUP_MAX_RTO_SECONDS", "7200"))
MAX_RPO = int(os.environ.get("BACKUP_MAX_RPO_SECONDS", "86400"))
MAX_AGE_DAYS = int(os.environ.get("BACKUP_MAX_AGE_DAYS", "90"))


def main() -> int:
    if not FILE.exists():
        print("BACKUP_STATUS FAIL 证据缺失：%s（先跑 bash scripts/backup-drill.sh）" % FILE)
        return 2
    try:
        data = json.loads(FILE.read_text(encoding="utf-8"))
    except ValueError as broken:
        print("BACKUP_STATUS FAIL JSON 不可解析：%s" % broken)
        return 2

    problems = []
    if data.get("verdict") != "PASS":
        problems.append("verdict=%s" % data.get("verdict"))
    rto = int(data.get("rtoSeconds", -1))
    rpo = int(data.get("rpoSeconds", -1))
    if rto <= 0 or rto > MAX_RTO:
        problems.append("rtoSeconds=%s 超出 0–%d" % (rto, MAX_RTO))
    if rpo < 0 or rpo > MAX_RPO:
        problems.append("rpoSeconds=%s 超出 0–%d" % (rpo, MAX_RPO))
    if int(data.get("tables", 0)) <= 0:
        problems.append("tables=%s" % data.get("tables"))
    try:
        generated = datetime.fromisoformat(str(data.get("generatedAt", "")).replace("Z", "+00:00"))
        if generated.tzinfo is None:
            generated = generated.replace(tzinfo=timezone.utc)
        age_days = (datetime.now(timezone.utc) - generated).days
        if age_days > MAX_AGE_DAYS:
            problems.append("证据过期 %d 天（上限 %d）" % (age_days, MAX_AGE_DAYS))
    except ValueError:
        problems.append("generatedAt 不可解析：%s" % data.get("generatedAt"))

    for problem in problems:
        print("  FAIL %s" % problem)
    print("BACKUP_STATUS %s rto=%ss rpo=%ss tables=%s database=%s"
          % ("FAIL" if problems else "PASS", rto, rpo, data.get("tables"),
             data.get("database")))
    return 1 if problems else 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
