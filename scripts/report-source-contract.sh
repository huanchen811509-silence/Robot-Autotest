#!/usr/bin/env bash
# 报告来源契约验证（FR-v3.10-03 判据①②③④，真实 HTTP + 真库）。
#
# 为什么不用单测代替：`TestReportCaseSetIT` 验的是仓储与服务的行为，而这里的
# 风险在**接口契约**——请求体字段名、`sourceType` 的取值口径、错误码、以及
# 「按用例集建的报告列表里看不看得出来源」。这些只有真打一次接口才知道。
#
# 做法：在**开发库**里造一个一次性的用例集 + 两条绑定的 run（跑完即删），
# 然后走真实接口：
#   ① 不传 runId → 取该集最近一次执行
#   ② 传该集内的 runId → 用指定的那一次
#   ③ 传别的集的 runId → 400（不能把别人的 run 当本集的）
#   ④ 旧路径（按 run）→ sourceType 仍是 run、caseSetId 为空（零回归）
#   ⑤ 冻结后删掉源 run → 渲染内容不变（快照自包含）
#
# 用法：bash scripts/report-source-contract.sh
# 输出：逐条 [PASS]/[FAIL]，末行 RESULT PASS/FAIL fails=N
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

set -a
# shellcheck disable=SC1091
. "$HOME/.config/robot-autotest/env" 2>/dev/null || true
set +a
DB_NAME="${DB_NAME:-robot_dev}"
# shellcheck disable=SC1091
source ./scripts/db-common.sh

API="${API_BASE:-http://127.0.0.1:8081}"
USER_NAME="${QA_USER:-qa-e2e-admin}"
PROJECT_ID="${PROJECT_ID:-29}"
STAMP="$(date +%Y%m%d%H%M%S)"
SET_CODE="TS-V310-CT-${STAMP}"
OTHER_CODE="TS-V310-CT-OTHER-${STAMP}"
SET_ID=""
OTHER_SET_ID=""
FAILS=0

pass() { echo "[PASS] $1"; }
fail() { echo "[FAIL] $1"; FAILS=$((FAILS + 1)); }

jq_py() { python3 -c "import json,sys;d=json.load(sys.stdin);print(eval(sys.argv[1],{'d':d}))" "$1"; }

echo "[contract] api=$API db=$DB_NAME project=$PROJECT_ID set=$SET_CODE"

# ---- 0. 造一次性夹具（用例集 2 个 + run 3 条 + 元数据 1 条） ----
db_exec -D "$DB_NAME" -e "INSERT INTO test_set (project_id, set_code, name) VALUES ($PROJECT_ID, '$SET_CODE', 'v3.10 契约用例集');"
SET_ID="$(db_exec -D "$DB_NAME" -N -B -e "SELECT id FROM test_set WHERE project_id=$PROJECT_ID AND set_code='$SET_CODE';")"
db_exec -D "$DB_NAME" -e "INSERT INTO test_set (project_id, set_code, name) VALUES ($PROJECT_ID, '$OTHER_CODE', 'v3.10 契约用例集-其他');"
OTHER_SET_ID="$(db_exec -D "$DB_NAME" -N -B -e "SELECT id FROM test_set WHERE project_id=$PROJECT_ID AND set_code='$OTHER_CODE';")"
if [[ -z "$SET_ID" || -z "$OTHER_SET_ID" ]]; then
    echo "[contract] 夹具创建失败（用例集没插进去）" >&2
    exit 1
fi

OLD_RUN="CT-${STAMP}-OLD"
NEW_RUN="CT-${STAMP}-NEW"
FOREIGN_RUN="CT-${STAMP}-FOREIGN"
seed_run() { # $1=runId $2=testSetId $3=ago_sql
    db_exec -D "$DB_NAME" -e "INSERT INTO run (run_id, project_id, test_set_id, run_status,
        enqueued_at, started_at, finished_at, total_tests, passed_tests, failed_tests, trigger_type)
        VALUES ('$1', $PROJECT_ID, $2, 'finished', $3, $3, $3, 0, 0, 0, 'manual');"
}
seed_run "$OLD_RUN" "$SET_ID" "TIMESTAMPADD(DAY, -2, NOW(3))"
seed_run "$NEW_RUN" "$SET_ID" "TIMESTAMPADD(HOUR, -1, NOW(3))"
seed_run "$FOREIGN_RUN" "$OTHER_SET_ID" "TIMESTAMPADD(MINUTE, -10, NOW(3))"
db_exec -D "$DB_NAME" -e "INSERT INTO run_metadata (project_id, run_id, meta_key, meta_value)
    VALUES ($PROJECT_ID, '$NEW_RUN', 'QA_PROJECT', 'v310-contract');"

cleanup() {
    db_exec -D "$DB_NAME" -e "DELETE FROM test_report WHERE project_id=$PROJECT_ID AND (run_id IN ('$OLD_RUN','$NEW_RUN','$FOREIGN_RUN') OR title LIKE 'CT-${STAMP}%');" 2>/dev/null
    db_exec -D "$DB_NAME" -e "DELETE FROM run_metadata WHERE project_id=$PROJECT_ID AND run_id IN ('$OLD_RUN','$NEW_RUN','$FOREIGN_RUN');" 2>/dev/null
    db_exec -D "$DB_NAME" -e "DELETE FROM run WHERE project_id=$PROJECT_ID AND run_id IN ('$OLD_RUN','$NEW_RUN','$FOREIGN_RUN');" 2>/dev/null
    db_exec -D "$DB_NAME" -e "DELETE FROM test_set WHERE project_id=$PROJECT_ID AND set_code IN ('$SET_CODE','$OTHER_CODE');" 2>/dev/null
}
trap cleanup EXIT

# ---- 1. 登录 ----
# 口令取 QA_E2E_PASSWORD（与探针一致），不拿 DB_PASSWORD 顶替——那是数据库账号。
TOKEN="$(curl -s --noproxy '*' -X POST "$API/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"${QA_E2E_PASSWORD:-}\"}" \
    | jq_py "d.get('data',{}).get('token') or ''")"
if [[ -z "$TOKEN" ]]; then
    echo "[contract] 登录失败：拿不到 token（检查 QA 账号口令）" >&2
    exit 1
fi
AUTH="Authorization: Bearer $TOKEN"

post_report() {
    curl -s --noproxy '*' -o /tmp/ct-body.json -w '%{http_code}' -X POST "$API/api/test-reports" \
        -H "$AUTH" -H 'Content-Type: application/json' -d "$1"
}

# ---- ① 不传 runId：取该集最近一次执行 ----
CODE="$(post_report "{\"projectId\":$PROJECT_ID,\"caseSetId\":$SET_ID,\"title\":\"CT-${STAMP}-latest\",\"sourceType\":\"case_set\"}")"
LATEST_RUN="$(jq_py "d.get('data',{}).get('runId') or ''" < /tmp/ct-body.json)"
LATEST_ID="$(jq_py "d.get('data',{}).get('id') or ''" < /tmp/ct-body.json)"
LATEST_SRC="$(jq_py "d.get('data',{}).get('sourceType') or ''" < /tmp/ct-body.json)"
[[ "$CODE" == "200" ]] && pass "① 按用例集建草稿 HTTP 200" || fail "① 建草稿返回 $CODE"
[[ "$LATEST_RUN" == "$NEW_RUN" ]] && pass "① 默认取最近一次执行（$LATEST_RUN）" || fail "① 期望 $NEW_RUN，实际 '$LATEST_RUN'"
[[ "$LATEST_SRC" == "CASE_SET" ]] && pass "① 来源标为 CASE_SET" || fail "① 来源为 '$LATEST_SRC'（期望 CASE_SET）"

# ---- ② 指定该集内的 runId ----
CODE="$(post_report "{\"projectId\":$PROJECT_ID,\"caseSetId\":$SET_ID,\"runId\":\"$OLD_RUN\",\"title\":\"CT-${STAMP}-pinned\",\"sourceType\":\"case_set\"}")"
PINNED_RUN="$(jq_py "d.get('data',{}).get('runId') or ''" < /tmp/ct-body.json)"
[[ "$CODE" == "200" && "$PINNED_RUN" == "$OLD_RUN" ]] && pass "② 指定执行记录生效（$PINNED_RUN）" \
    || fail "② 期望 200 + $OLD_RUN，实际 $CODE / '$PINNED_RUN'"

# ---- ③ 指定不属于该集的 runId：必须 400 ----
CODE="$(post_report "{\"projectId\":$PROJECT_ID,\"caseSetId\":$SET_ID,\"runId\":\"$FOREIGN_RUN\",\"title\":\"CT-${STAMP}-foreign\",\"sourceType\":\"case_set\"}")"
[[ "$CODE" == "400" ]] && pass "③ 跨用例集的 run 被拒（400）" || fail "③ 跨集 run 期望 400，实际 $CODE"

# ---- ④ 旧路径零回归：按 run 建，来源仍是 run ----
CODE="$(post_report "{\"projectId\":$PROJECT_ID,\"runId\":\"$NEW_RUN\",\"title\":\"CT-${STAMP}-byrun\"}")"
BYRUN_SRC="$(jq_py "d.get('data',{}).get('sourceType') or ''" < /tmp/ct-body.json)"
BYRUN_SET="$(jq_py "d.get('data',{}).get('caseSetId')" < /tmp/ct-body.json)"
[[ "$CODE" == "200" && "$BYRUN_SRC" == "RUN" && "$BYRUN_SET" == "None" ]] \
    && pass "④ 旧路径仍是 RUN / caseSetId=null" \
    || fail "④ 旧路径返回 $CODE sourceType='$BYRUN_SRC' caseSetId='$BYRUN_SET'"

# ---- ⑤ 列表可辨来源 ----
LIST="$(curl -s --noproxy '*' -H "$AUTH" "$API/api/test-reports?projectId=$PROJECT_ID")"
LIST_SET="$(printf '%s' "$LIST" | jq_py "sum(1 for r in d.get('data',[]) if r.get('sourceType')=='CASE_SET' and r.get('caseSetId')==$SET_ID)")"
[[ "$LIST_SET" -ge 1 ]] && pass "⑤ 列表带 sourceType/caseSetId（命中 $LIST_SET 条）" \
    || fail "⑤ 列表里找不到按用例集建的报告"

# ---- ⑥ 冻结后删源 run，渲染内容不变（快照自包含） ----
# 注意：此时同一个 run 上已经有 2 份报告（①按用例集建的 + ④按 run 建的），
# 冻结第一份会把版本号推到 3 —— 刚好复现「版本号按 run 全局分配」这条修正的现场。
FREEZE_CODE="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST \
    "$API/api/test-reports/$LATEST_ID/freeze" -H "$AUTH")"
RENDER_BEFORE="$(curl -s --noproxy '*' -H "$AUTH" "$API/api/test-reports/$LATEST_ID?external=true")"
# 只删源 run 与它的元数据；报告与快照留在库里
db_exec -D "$DB_NAME" -e "DELETE FROM run_metadata WHERE project_id=$PROJECT_ID AND run_id='$NEW_RUN';"
db_exec -D "$DB_NAME" -e "DELETE FROM run WHERE project_id=$PROJECT_ID AND run_id='$NEW_RUN';"
RENDER_AFTER="$(curl -s --noproxy '*' -H "$AUTH" "$API/api/test-reports/$LATEST_ID?external=true")"
[[ "$FREEZE_CODE" == "200" ]] && pass "⑥ 冻结 HTTP 200（同 run 已有 2 份报告）" || fail "⑥ 冻结返回 $FREEZE_CODE"
if [[ -n "$RENDER_BEFORE" && "$RENDER_BEFORE" == "$RENDER_AFTER" ]]; then
    pass "⑥ 源 run 删除后渲染内容逐字不变（长度 ${#RENDER_BEFORE}）"
else
    fail "⑥ 源 run 删除后渲染内容变了（before=${#RENDER_BEFORE} after=${#RENDER_AFTER}）"
fi
SNAP_HAS="$(db_exec -D "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM test_report
    WHERE id=$LATEST_ID AND snapshot_json LIKE '%$NEW_RUN%';")"
[[ "$SNAP_HAS" == "1" ]] && pass "⑥ 快照里记住了源 runId（自包含的实质）" \
    || fail "⑥ 快照里没有源 runId（删掉源数据后无处可查）"

# 留一份真实快照做证据：契约脚本结束时会清库，快照不导出就没了。
SNAPSHOT_OUT="${SNAPSHOT_OUT:-docs/evidence/v3.10.0/EW310-03/snapshot-case-set.json}"
mkdir -p "$(dirname "$SNAPSHOT_OUT")"
db_exec -D "$DB_NAME" -N -B -e "SELECT snapshot_json FROM test_report WHERE id=$LATEST_ID;" \
    | python3 -c 'import json,sys; print(json.dumps(json.loads(sys.stdin.read().strip()), ensure_ascii=False, indent=2))' \
    > "$SNAPSHOT_OUT"
echo "[contract] 快照样本 -> $SNAPSHOT_OUT（$(wc -c < "$SNAPSHOT_OUT") 字节）"

if [[ "$FAILS" -gt 0 ]]; then
    echo "RESULT FAIL fails=$FAILS"
    exit 1
fi
echo "RESULT PASS fails=0"
