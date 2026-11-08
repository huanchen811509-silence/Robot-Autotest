set -a; . /home/evergreen/.config/robot-autotest/env; set +a
OUT=/home/evergreen/robot-autotest/docs/evidence/v3.0.1/v23/UI-02/raw
M="mysql -h$DB_HOST -P$DB_PORT -u$DB_USERNAME -p$DB_PASSWORD $DB_NAME --table"
{
echo "== 独立口径复核：窗口 = now() - 7 day，与端点输出逐项比对 =="
$M -e "
SELECT p.id,
       (SELECT COUNT(*) FROM test_case tc WHERE tc.project_id = p.id) AS case_count,
       (SELECT COUNT(*) FROM run r WHERE r.project_id = p.id
          AND COALESCE(r.started_at, r.created_at) >= NOW() - INTERVAL 7 DAY) AS run_count_7d,
       (SELECT CONCAT(FORMAT(SUM(CASE WHEN r.run_status='finished' THEN r.passed_tests ELSE 0 END)
                             / NULLIF(SUM(CASE WHEN r.run_status='finished' THEN r.total_tests ELSE 0 END),0)
                             * 10000, 0))
          FROM run r WHERE r.project_id = p.id
             AND COALESCE(r.started_at, r.created_at) >= NOW() - INTERVAL 7 DAY) AS pass_rate_bp_7d,
       (SELECT MAX(COALESCE(r.started_at, r.created_at)) FROM run r WHERE r.project_id = p.id) AS last_run_at,
       (SELECT COUNT(*) FROM device d WHERE d.project_id = p.id) AS device_count,
       (SELECT COUNT(*) FROM project_member pm WHERE pm.project_id = p.id) AS member_count,
       (SELECT pm.member_role FROM project_member pm
          WHERE pm.project_id = p.id AND pm.user_id = 17) AS viewer_role
  FROM project p ORDER BY p.id;" 2>/dev/null
echo
echo "== 窗口差异核对：最早一条 run 的时间（若都在 7 天内，则 7d 与 30d 读数应当相同）=="
$M -e "SELECT MIN(COALESCE(started_at, created_at)) AS earliest_run, MAX(COALESCE(started_at, created_at)) AS latest_run, COUNT(*) AS total_runs FROM run;" 2>/dev/null
} | tee "$OUT/db-crosscheck.txt"
