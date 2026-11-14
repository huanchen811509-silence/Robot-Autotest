#!/usr/bin/env python3
"""W58-09 · 性能数据集隔离导入（独立 PERF- 压测工程）。

为什么需要它
------------
`tools/perf_dataset.py` 只产出 CSV，不碰数据库；而 W58-10 要量的 10000 行列表
必须真的躺在库里。把 10000 行塞进既有工程会污染统计（列表总数、覆盖率、首页
卡片都会变），所以这里只做三件事：

① **只往独立工程写**：工程 code 固定 `PERF-` 前缀，可识别、可一键清空；
② **导入前后做读数对比**：证明「既有工程数 / 既有用例数」没变（这是 W58-09
   的出口判据 ②，靠输出对比表，不靠「我确认改的是别的工程」）；
③ **把对比读数写进证据 JSON**：交给 W58-10 与机检引用，避免读数只活在终端里。

判据（不成立即退出 1，不写「大概没变」）
----------------------------------------
  before.projectsNonPerf == after.projectsNonPerf
  before.casesNonPerf    == after.casesNonPerf
  after.casesInPerf      == manifest.counts.cases

「非 PERF」一律按 `project.code NOT LIKE 'PERF-%'` 界定，而不是按 id 白名单——
白名单会随新建工程失效，前缀不会。

用法
----
    python3 tools/perf_dataset.py --scale standard --out /tmp/perf-ds
    python3 tools/perf_seed.py --dataset /tmp/perf-ds              # 导入（幂等，先清后写）
    python3 tools/perf_seed.py --dataset /tmp/perf-ds --verify      # 只读校验，不写库
    python3 tools/perf_seed.py --purge                              # 清空 PERF- 工程的用例并删工程

前置：`. ~/.config/robot-autotest/env`（DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD）。

退出码：0 = 成功且判据成立；1 = 判据不成立；2 = 参数 / IO / 数据库错误。
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CODE = "PERF-LONGLIST"
DEFAULT_NAME = "PERF · 10000 行长列表压测"
DEFAULT_REPORT = ROOT / "docs/evidence/v5.8.0/perf/seed-report.json"

# 单条 INSERT 的行数。500 行 × 13 列 ≈ 40KB 一条语句，既躲开 max_allowed_packet，
# 也不至于把 10000 行拆成 10000 次往返。
BATCH = 500

PERF_PREFIX = "PERF-"

# 既有 2491 条用例的取值口径（status=1 / ledger / auto / LX / draft / import / active / version 1）。
# 这里刻意**照抄既有分布**而不是自造一套：列表页的渲染成本与取值无关，但「看起来像真数据」
# 能让后续截图与人工核对不至于一眼看出是夹具。
CASE_COLUMNS = (
    "project_id", "case_code", "title", "status", "source_type", "exec_mode",
    "safety_level", "review_status", "source", "lifecycle_status", "version_no",
    "priority", "domain_tag",
)

COUNT_SQL = """
SELECT
  (SELECT COUNT(*) FROM project) AS projects_total,
  (SELECT COUNT(*) FROM project WHERE code LIKE 'PERF-%') AS perf_projects,
  (SELECT COUNT(*) FROM test_case) AS cases_total,
  (SELECT COUNT(*) FROM test_case tc
     JOIN project p ON p.id = tc.project_id
    WHERE p.code LIKE 'PERF-%') AS perf_cases;
"""


def _client() -> str:
    for name in ("mariadb", "mysql"):
        found = shutil.which(name)
        if found:
            return found
    print("[FAIL] 未找到 mariadb / mysql 客户端（见 scripts/db-common.sh）", file=sys.stderr)
    raise SystemExit(2)


def _conn_args() -> list[str]:
    missing = [key for key in ("DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD")
               if not os.environ.get(key)]
    if missing:
        print(f"[FAIL] 缺少环境变量：{', '.join(missing)}"
              "（先执行 . ~/.config/robot-autotest/env）", file=sys.stderr)
        raise SystemExit(2)
    return [
        f"--host={os.environ['DB_HOST']}", f"--port={os.environ['DB_PORT']}",
        f"--user={os.environ['DB_USERNAME']}", f"--password={os.environ['DB_PASSWORD']}",
        "--default-character-set=utf8mb4", "--batch", "--skip-column-names",
        "-D", os.environ["DB_NAME"],
    ]


def _query(sql: str, timeout: int = 120) -> list[list[str]]:
    done = subprocess.run([_client(), *_conn_args(), "-e", sql],
                          capture_output=True, text=True, timeout=timeout)
    if done.returncode != 0:
        print(f"[FAIL] SQL 失败：{done.stderr.strip()}", file=sys.stderr)
        raise SystemExit(2)
    return [line.split("\t") for line in done.stdout.splitlines() if line.strip()]


def _run_file(sql_path: Path, timeout: int = 600) -> None:
    """整段 SQL 走 stdin。10000 行拼成一条命令行会撞 ARG_MAX，所以不传 -e。"""
    with sql_path.open("rb") as handle:
        done = subprocess.run([_client(), *_conn_args()], stdin=handle,
                              capture_output=True, text=True, timeout=timeout)
    if done.returncode != 0:
        print(f"[FAIL] 执行 {sql_path.name} 失败：{done.stderr.strip()}", file=sys.stderr)
        raise SystemExit(2)


def snapshot() -> dict:
    row = _query(COUNT_SQL)[0]
    total_projects, perf_projects, total_cases, perf_cases = (int(value) for value in row)
    return {
        "projectsTotal": total_projects,
        "projectsNonPerf": total_projects - perf_projects,
        "perfProjects": perf_projects,
        "casesTotal": total_cases,
        "casesNonPerf": total_cases - perf_cases,
        "casesInPerf": perf_cases,
    }


def ensure_project(code: str, name: str) -> tuple[int, bool]:
    """有就复用、没有就建。返回 (projectId, 是否新建)。"""
    if not code.startswith(PERF_PREFIX):
        print(f"[FAIL] 压测工程 code 必须以 {PERF_PREFIX} 开头（出口判据③），收到 {code}",
              file=sys.stderr)
        raise SystemExit(2)
    if not name.startswith("PERF"):
        print(f"[FAIL] 压测工程名称也要带 PERF 前缀，便于界面识别，收到 {name}", file=sys.stderr)
        raise SystemExit(2)
    existing = _query(f"SELECT id FROM project WHERE code = '{_q(code)}';")
    if existing:
        return int(existing[0][0]), False
    # created_at / updated_at 是 DEFAULT_GENERATED，不显式写；
    # visibility 用 public：CDP 量测要用另一个账号登进去看这一页，private 会直接看不见。
    _query(
        "INSERT INTO project (code, name, description, status, visibility, test_kernel, kind) "
        f"VALUES ('{_q(code)}', '{_q(name)}', "
        "'v5.8.0 W58-09 性能基线夹具工程，可由 tools/perf_seed.py --purge 清除', "
        "1, 'public', 'robot', 'standard');"
    )
    created = _query(f"SELECT id FROM project WHERE code = '{_q(code)}';")
    if not created:
        print("[FAIL] 建工程后仍查不到 id", file=sys.stderr)
        raise SystemExit(2)
    return int(created[0][0]), True


def _q(value: str) -> str:
    """SQL 单引号转义。数据集里的 title 是本工具自己生成的，但仍走统一转义。"""
    return value.replace("\\", "\\\\").replace("'", "''")


def build_sql(csv_path: Path, project_id: int, sql_path: Path) -> int:
    """把 cases.csv 流式翻译成多行 INSERT，返回写入行数。"""
    written = 0
    pending: list[str] = []

    def flush(handle) -> None:
        nonlocal pending
        if not pending:
            return
        handle.write(f"INSERT INTO test_case ({', '.join(CASE_COLUMNS)}) VALUES\n")
        handle.write(",\n".join(pending))
        handle.write(";\n")
        pending = []

    with csv_path.open(newline="", encoding="utf-8") as source, \
            sql_path.open("w", encoding="utf-8") as sink:
        reader = csv.DictReader(source)
        for row in reader:
            pending.append(
                "({}, '{}', '{}', 1, 'ledger', 'auto', 'LX', 'draft', 'import', 'active', 1, '{}', "
                "'perf-longlist')".format(
                    project_id,
                    _q(row["case_key"]),
                    _q(row["name"]),
                    _q(row.get("priority") or "P2"),
                )
            )
            written += 1
            if len(pending) >= BATCH:
                flush(sink)
        flush(sink)
    return written


def purge(code: str) -> int:
    if not code.startswith(PERF_PREFIX):
        print(f"[FAIL] 拒绝清除非 {PERF_PREFIX} 工程：{code}", file=sys.stderr)
        return 2
    found = _query(f"SELECT id FROM project WHERE code = '{_q(code)}';")
    if not found:
        print(f"[PURGE] 工程不存在，无需清除：{code}")
        return 0
    project_id = int(found[0][0])
    before = snapshot()
    _query(f"DELETE FROM test_case WHERE project_id = {project_id};")
    _query(f"DELETE FROM project WHERE id = {project_id} AND code LIKE '{PERF_PREFIX}%';")
    after = snapshot()
    print(f"[PURGE] 已删除 {code}（id={project_id}）及其用例")
    print(f"BEFORE perf_projects={before['perfProjects']} perf_cases={before['casesInPerf']}")
    print(f"AFTER  perf_projects={after['perfProjects']} perf_cases={after['casesInPerf']}")
    ok = after["projectsNonPerf"] == before["projectsNonPerf"] and \
        after["casesNonPerf"] == before["casesNonPerf"]
    print(f"PURGE_RESULT {'PASS' if ok else 'FAIL'}（既有 %d 工程 / %d 用例未受影响）"
          % (after["projectsNonPerf"], after["casesNonPerf"]))
    return 0 if ok else 1


def load_manifest(dataset: Path) -> dict:
    path = dataset / "manifest.json"
    if not path.is_file():
        print(f"[FAIL] 缺少 manifest：{path}（先跑 tools/perf_dataset.py）", file=sys.stderr)
        raise SystemExit(2)
    manifest = json.loads(path.read_text(encoding="utf-8"))
    files = {item["path"]: item for item in manifest.get("files", [])}
    if "cases.csv" not in files:
        print("[FAIL] manifest 里没有 cases.csv", file=sys.stderr)
        raise SystemExit(2)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description="W58-09 性能数据集隔离导入")
    parser.add_argument("--dataset", type=Path, default=ROOT / "artifacts" / "perf-dataset")
    parser.add_argument("--project-code", default=DEFAULT_CODE)
    parser.add_argument("--project-name", default=DEFAULT_NAME)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--verify", action="store_true", help="只读校验，不写库")
    parser.add_argument("--purge", action="store_true", help="清空 PERF- 工程用例并删工程")
    args = parser.parse_args()

    print(f"DB={os.environ.get('DB_NAME', '?')}")

    if args.purge:
        return purge(args.project_code)

    dataset: Path = args.dataset
    manifest = load_manifest(dataset)
    cases_file = next(item for item in manifest["files"] if item["path"] == "cases.csv")
    expected_rows = int(cases_file["rows"])

    if args.verify:
        found = _query(f"SELECT id FROM project WHERE code = '{_q(args.project_code)}';")
        if not found:
            print(f"[FAIL] 找不到压测工程 {args.project_code}", file=sys.stderr)
            return 1
        rows = int(_query(
            f"SELECT COUNT(*) FROM test_case WHERE project_id = {int(found[0][0])};")[0][0])
        ok = rows == expected_rows
        print(f"[VERIFY] {args.project_code} 库内用例 {rows} / manifest {expected_rows}")
        print(f"VERIFY_RESULT {'PASS' if ok else 'FAIL'}")
        return 0 if ok else 1

    before = snapshot()
    project_id, created = ensure_project(args.project_code, args.project_name)
    _query(f"DELETE FROM test_case WHERE project_id = {project_id};")

    with tempfile.TemporaryDirectory(prefix="perf-seed-") as tmp:
        sql_path = Path(tmp) / "seed.sql"
        written = build_sql(dataset / "cases.csv", project_id, sql_path)
        if written != expected_rows:
            print(f"[FAIL] CSV 行数 {written} 与 manifest {expected_rows} 不一致", file=sys.stderr)
            return 1
        _run_file(sql_path)

    after = snapshot()
    checks = [
        ("既有工程数不变",
         before["projectsNonPerf"] == after["projectsNonPerf"],
         f"{before['projectsNonPerf']} → {after['projectsNonPerf']}"),
        ("既有用例数不变",
         before["casesNonPerf"] == after["casesNonPerf"],
         f"{before['casesNonPerf']} → {after['casesNonPerf']}"),
        ("压测工程用例数等于数据集行数",
         after["casesInPerf"] == expected_rows,
         f"{after['casesInPerf']} vs {expected_rows}"),
        ("压测工程 code 带 PERF- 前缀",
         args.project_code.startswith(PERF_PREFIX),
         args.project_code),
    ]
    ok = all(item[1] for item in checks)

    print("")
    print(f"BEFORE projects_non_perf={before['projectsNonPerf']} "
          f"cases_non_perf={before['casesNonPerf']} perf_projects={before['perfProjects']}")
    print(f"AFTER  projects_non_perf={after['projectsNonPerf']} "
          f"cases_non_perf={after['casesNonPerf']} perf_projects={after['perfProjects']}")
    print(f"PERF   project={args.project_code} id={project_id} "
          f"{'（新建）' if created else '（复用）'} cases={after['casesInPerf']}")
    for name, passed, detail in checks:
        print(f"  [{'OK' if passed else 'FAIL'}] {name}：{detail}")
    print(f"DATASET contentHash={manifest.get('contentHash', '')[:16]}… "
          f"cases_sha256={cases_file['sha256'][:16]}… rows={expected_rows}")

    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "db": os.environ.get("DB_NAME"),
        "dataset": {
            "dir": str(dataset),
            "scale": manifest.get("scale"),
            "seed": manifest.get("seed"),
            "contentHash": manifest.get("contentHash"),
            "casesRows": expected_rows,
            "casesSha256": cases_file["sha256"],
            "casesBytes": cases_file["bytes"],
        },
        "project": {"id": project_id, "code": args.project_code,
                    "name": args.project_name, "created": created},
        "before": before,
        "after": after,
        "checks": [{"name": name, "ok": passed, "detail": detail}
                   for name, passed, detail in checks],
        "result": "PASS" if ok else "FAIL",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    # manifest 必须随证据归档（PRD FR-v5.8-06 判据③）：数据集的 dir 在 /tmp 里，
    # 不归档的话「行数/sha256 可复现」这句话下一个人没法验。
    manifest_copy = args.report.parent / "dataset-manifest.json"
    shutil.copyfile(dataset / "manifest.json", manifest_copy)
    print(f"SEED_MANIFEST {manifest_copy.relative_to(ROOT) if manifest_copy.is_relative_to(ROOT) else manifest_copy}")
    print(f"SEED_REPORT {args.report.relative_to(ROOT) if args.report.is_relative_to(ROOT) else args.report}")
    print(f"SEED_RESULT {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.TimeoutExpired:
        print("[FAIL] 数据库操作超时", file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        sys.exit(130)
