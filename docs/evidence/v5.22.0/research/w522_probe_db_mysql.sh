#!/usr/bin/env bash
# v5.22 调研：库内三方计数比对（只读 SELECT）
set -uo pipefail
set -a; . ~/.config/robot-autotest/env; set +a
export MYSQL_PWD="$DB_PASSWORD"
M="mysql -h $DB_HOST -P $DB_PORT -u $DB_USERNAME -D $DB_NAME --default-character-set=utf8mb4 -t"

echo "=== 0) 相关表 ==="
$M -e "show tables like '%report%'; show tables like '%run%'; show tables like '%result%';" 2>&1 | head -40

echo
echo "=== 1) run_record 最近 10 条 ==="
$M -e "select id, run_id, project_id, state, total_tests, passed_tests, failed_tests, skipped_tests
       from run_record order by id desc limit 10;" 2>&1 | head -20

echo
echo "=== 2) test_report 最近 8 条 + 快照计数 ==="
$M -e "select id, project_id, run_id, state, version,
  snapshot_json->>'\$.passCount' as pass,
  snapshot_json->>'\$.failCount' as fail,
  snapshot_json->>'\$.skipCount' as skip,
  snapshot_json->>'\$.caseTotal' as caseTotal,
  snapshot_json->>'\$.passRate'  as passRate
 from test_report order by id desc limit 8;" 2>&1 | head -20

echo
echo "=== 3) 快照 metadata 与键集（最近 3 份）==="
$M -e "select id, run_id,
  json_keys(snapshot_json) as top_keys,
  snapshot_json->'\$.metadata' as metadata
 from test_report order by id desc limit 3\G" 2>&1 | head -40

echo
echo "=== 4) test_result 按 run 分组（最近 8 个 run）==="
$M -e "select run_id, count(*) total,
  sum(case when upper(status)='PASS' then 1 else 0 end) passed,
  sum(case when upper(status)='FAIL' then 1 else 0 end) failed,
  sum(case when upper(status)='SKIP' then 1 else 0 end) skipped
 from test_result group by run_id order by max(id) desc limit 8;" 2>&1 | head -15

echo
echo "=== 5) 同一 run 多份报告（>1）==="
$M -e "select run_id, project_id, count(*) n, group_concat(concat(id,':',state) order by id) details
 from test_report group by run_id, project_id having count(*)>1 order by n desc limit 10;" 2>&1 | head -15
