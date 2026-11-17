#!/usr/bin/env bash
# v5.22.0 / W3 验收探针：计数口径（FR-v5.22-05）
#
# 判据：
#   05-1 迁移后 desc run 含 skipped_tests
#   05-2 回填后 run 四列与 test_result 聚合逐 run 一致（抽 20 个：pytest 10 + robot 10）
#   05-3 回填脚本连跑两次摘要一致（幂等）
#   05-4 新入库的 run 满足 passed+failed+skipped = total
#   05-5 DISPATCHED 不计入 total，且能单独查出条数（SQL + 接口读数）
#
# 会对 run EHT-FAKE-1794805219801 做一次「打回旧形状再入库」的实验（幂等，可重复跑），
# 不改任何报告/模板状态。
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."      # → 仓库根
set -a; . ~/.config/robot-autotest/env; set +a
source scripts/db-common.sh

BASE="http://127.0.0.1:8081"
PROJECT=95
RUN_ID="EHT-FAKE-1794805219801"

q() { db_exec -D "$DB_NAME" -e "$1" 2>/dev/null | tr -d '\r'; }

token() {
    python3 - "$BASE" <<'PY'
import json, os, sys, urllib.request
base = sys.argv[1]
body = json.dumps({"username": os.environ.get("QA_E2E_ADMIN") or "qa-e2e-admin",
                   "password": os.environ.get("QA_E2E_PASSWORD", "")}).encode()
r = urllib.request.Request(base + "/api/auth/login", data=body,
                           headers={"Content-Type": "application/json"}, method="POST")
with urllib.request.urlopen(r, timeout=60) as resp:
    d = (json.loads(resp.read() or b"{}").get("data") or {})
    print(d.get("token") or d.get("accessToken") or "")
PY
}

echo "############ 05-1 desc run 含 skipped_tests ############"
q "SHOW COLUMNS FROM run WHERE Field LIKE '%tests%';"

echo
echo "############ 05-3 幂等：连跑两次只比 SUMMARY 行 ############"
bash scripts/db/backfill-run-counts.sh 2>/dev/null | grep -E '^\[backfill\] (SUMMARY|repaired)' > /tmp/w522_bf1.txt
bash scripts/db/backfill-run-counts.sh 2>/dev/null | grep -E '^\[backfill\] (SUMMARY|repaired)' > /tmp/w522_bf2.txt
echo "--- 第一次 ---"; cat /tmp/w522_bf1.txt
echo "--- 第二次 ---"; cat /tmp/w522_bf2.txt
grep '^\[backfill\] SUMMARY' /tmp/w522_bf1.txt > /tmp/w522_s1.txt
grep '^\[backfill\] SUMMARY' /tmp/w522_bf2.txt > /tmp/w522_s2.txt
if diff -q /tmp/w522_s1.txt /tmp/w522_s2.txt >/dev/null; then
    echo "[05-3] PASS 两次 SUMMARY 完全一致"
else
    echo "[05-3] FAIL 两次 SUMMARY 不一致"; diff /tmp/w522_s1.txt /tmp/w522_s2.txt
fi

echo
echo "############ 05-2 抽样 20 个 run（pytest 10 + robot 10） ############"
q "
SELECT r.kernel, r.run_id,
       r.total_tests, agg.p+agg.f+agg.s AS agg_total,
       r.passed_tests, agg.p AS agg_pass,
       r.failed_tests, agg.f AS agg_fail,
       r.skipped_tests, agg.s AS agg_skip,
       CASE WHEN r.total_tests=agg.p+agg.f+agg.s
             AND r.passed_tests=agg.p AND r.failed_tests=agg.f
             AND r.skipped_tests=agg.s THEN 'OK' ELSE 'DIFF' END AS verdict
  FROM run r
  JOIN (SELECT run_id rid, SUM(verdict='PASS') p, SUM(verdict='FAIL') f, SUM(verdict='SKIP') s
          FROM test_result GROUP BY run_id) agg ON agg.rid=r.id
 WHERE r.run_id IN (
        SELECT run_id FROM (SELECT r2.run_id FROM run r2 JOIN test_result t2 ON t2.run_id=r2.id
                             WHERE r2.kernel='pytest' GROUP BY r2.run_id ORDER BY r2.id DESC LIMIT 10) a
        UNION ALL
        SELECT run_id FROM (SELECT r3.run_id FROM run r3 JOIN test_result t3 ON t3.run_id=r3.id
                             WHERE COALESCE(r3.kernel,'robot')<>'pytest' GROUP BY r3.run_id
                             ORDER BY r3.id DESC LIMIT 10) b
       )
 ORDER BY r.kernel, r.id DESC;"
echo "--- 全库差异行数（应为 0） ---"
q "
SELECT COUNT(*) FROM run r
  JOIN (SELECT run_id rid, SUM(verdict='PASS') p, SUM(verdict='FAIL') f, SUM(verdict='SKIP') s
          FROM test_result GROUP BY run_id) agg ON agg.rid=r.id
 WHERE NOT (r.total_tests <=> agg.p+agg.f+agg.s)
    OR NOT (r.passed_tests <=> agg.p)
    OR NOT (r.failed_tests <=> agg.f)
    OR NOT (r.skipped_tests <=> agg.s);"

echo
echo "############ 05-5 DISPATCHED 不计入 total，单列可查 ############"
q "SELECT COUNT(*) AS dispatched_rows FROM test_result WHERE verdict='DISPATCHED';"
q "
SELECT r.project_id, r.run_id, r.total_tests,
       (SELECT COUNT(*) FROM test_result t WHERE t.run_id=r.id) AS all_rows,
       (SELECT COUNT(*) FROM test_result t WHERE t.run_id=r.id AND t.verdict='DISPATCHED') AS dispatched
  FROM run r WHERE r.id IN (SELECT run_id FROM test_result WHERE verdict='DISPATCHED');"

TOKEN="$(token)"
[[ -n "$TOKEN" ]] || { echo "[FAIL] 登录失败，后端未就绪（05-4 / 05-5 接口读数需要后端）"; exit 1; }
echo "--- 接口读数（报告详情 counts 不含 DISPATCHED） ---"
for pair in $(q "SELECT CONCAT(project_id,':',run_id) FROM run WHERE id IN (SELECT run_id FROM test_result WHERE verdict='DISPATCHED') LIMIT 2;"); do
    pid="${pair%%:*}"; rid="${pair#*:}"
    printf '%s → ' "$rid"
    curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" \
        "$BASE/api/reports/$rid?projectId=$pid" \
      | python3 -c 'import json,sys; d=(json.load(sys.stdin).get("data") or {}); c=d.get("counts") or {}; print("counts.pass=%s counts.fail=%s counts.skip=%s" % (c.get("pass"),c.get("fail"),c.get("skip")))'
done

echo
echo "############ 05-4 打回旧形状 → 真实入库 → 等式成立 ############"
echo "--- 原始 ---"
q "SELECT run_id,total_tests,passed_tests,failed_tests,skipped_tests,pass_count,fail_count,skip_count
     FROM run WHERE project_id=$PROJECT AND run_id='$RUN_ID';"
q "UPDATE run SET passed_tests=0, total_tests=70, skipped_tests=NULL, pass_count=0
    WHERE project_id=$PROJECT AND run_id='$RUN_ID';"
echo "--- 打回旧 pytest 形状（passed=0 / skipped=NULL） ---"
q "SELECT run_id,total_tests,passed_tests,failed_tests,skipped_tests,pass_count,fail_count,skip_count
     FROM run WHERE project_id=$PROJECT AND run_id='$RUN_ID';"
CODE="$(curl -s --noproxy '*' -o /tmp/w522_ingest.json -w '%{http_code}' \
        -X POST -H "Authorization: Bearer $TOKEN" \
        "$BASE/api/reports/$RUN_ID/parse?projectId=$PROJECT")"
echo "[05-4] 入库接口 http=$CODE"
echo "--- 入库后（diff 必须为 0） ---"
q "SELECT run_id, total_tests, passed_tests, failed_tests, skipped_tests,
          passed_tests+failed_tests+skipped_tests AS sum3,
          total_tests-(passed_tests+failed_tests+skipped_tests) AS diff,
          pass_count, fail_count, skip_count
     FROM run WHERE project_id=$PROJECT AND run_id='$RUN_ID';"
