#!/usr/bin/env bash
# v5.22 调研：库内三方计数比对 v3（只读 SELECT）
set -uo pipefail
set -a; . ~/.config/robot-autotest/env; set +a
export MYSQL_PWD="$DB_PASSWORD"
M="mysql -h $DB_HOST -P $DB_PORT -u $DB_USERNAME -D $DB_NAME --default-character-set=utf8mb4 -t"

echo "=== 1) run 表最近 10 条计数 ==="
$M -e "select id, run_id, project_id, kernel, run_status, total_tests, passed_tests, failed_tests
       from \`run\` order by id desc limit 10;" 2>&1 | head -20

echo
echo "=== 2) test_result 按 run 分组（最近 10 个 run）==="
$M -e "select r.id run_pk, r.run_id, count(t.id) n,
  sum(case when upper(t.verdict)='PASS' then 1 else 0 end) pass,
  sum(case when upper(t.verdict)='FAIL' then 1 else 0 end) fail,
  sum(case when upper(t.verdict)='SKIP' then 1 else 0 end) skip_v,
  r.total_tests, r.passed_tests, r.failed_tests
 from \`run\` r left join test_result t on t.run_id = r.id
 group by r.id, r.run_id, r.total_tests, r.passed_tests, r.failed_tests
 order by r.id desc limit 10;" 2>&1 | head -20

echo
echo "=== 3) 有报告的 run：库计数 vs 快照计数 ==="
$M -e "select tr.id report_id, tr.run_id, tr.state,
  tr.snapshot_json->>'\$.passCount' snap_pass,
  tr.snapshot_json->>'\$.failCount' snap_fail,
  tr.snapshot_json->>'\$.caseTotal' snap_total,
  r.passed_tests run_pass, r.failed_tests run_fail, r.total_tests run_total,
  (select count(*) from test_result t where t.run_id=r.id) result_rows
 from test_report tr left join \`run\` r on r.run_id = tr.run_id
 order by tr.id desc limit 10;" 2>&1 | head -20

echo
echo "=== 4) verdict 取值分布（全库）==="
$M -e "select verdict, count(*) n from test_result group by verdict order by n desc limit 10;" 2>&1 | head -15

echo
echo "=== 5) run.kernel 分布（报告可导出的范围）==="
$M -e "select kernel, count(*) n from \`run\` group by kernel;" 2>&1 | head -10
