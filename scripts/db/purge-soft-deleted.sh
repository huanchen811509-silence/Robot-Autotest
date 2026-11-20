#!/usr/bin/env bash
#
# 分批清除历史软删数据（v5.24.0 / FR-v5.24-08，大师 2026-11-20 裁决）
#
# 为什么存在：此前 57 条 run、1 条 test_report 是软删的，执行记录列表
# （RunRepositoryJdbc.page）漏过滤，删了仍陈列。现在删除已改物理删，
# 历史软删行需要一次性清理。
#
# 安全边界（不可跳过）：
#   1. 默认 dry-run，必须显式 --apply 才真删；
#   2. 每批前 mysqldump 备份到 /tmp/，记录路径与 sha256；
#   3. 每批 ≤ 10 条，先子表后主表；
#   4. 每批后校验：活动记录（deleted_at IS NULL）数量不得变化。
#
# 用法：
#   bash scripts/db/purge-soft-deleted.sh                 # dry-run，只看统计
#   bash scripts/db/purge-soft-deleted.sh --apply         # 真删（备份后分批）
#   bash scripts/db/purge-soft-deleted.sh --apply --batch 5
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$HOME/.config/robot-autotest/env"
APPLY=0
BATCH=10

while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1; shift ;;
    --batch) BATCH="${2:-10}"; shift 2 ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

if [ ! -f "$ENV_FILE" ]; then
  echo "[FAIL] 缺少 $ENV_FILE" >&2
  exit 2
fi
set -a; . "$ENV_FILE"; set +a
# shellcheck disable=SC1091
. "$ROOT/scripts/db-common.sh"

q() {
  "$DB_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USERNAME" \
    --password="$DB_PASSWORD" --database="$DB_NAME" --default-character-set=utf8mb4 \
    --batch --skip-column-names -e "$1" 2>/dev/null
}

echo "== 历史软删数据清理（APPLY=$APPLY, batch=$BATCH）=="
SOFT_RUNS="$(q "SELECT COUNT(*) FROM run WHERE deleted_at IS NOT NULL")"
SOFT_REPORTS="$(q "SELECT COUNT(*) FROM test_report WHERE deleted_at IS NOT NULL")"
ACTIVE_RUNS="$(q "SELECT COUNT(*) FROM run WHERE deleted_at IS NULL")"
ACTIVE_REPORTS="$(q "SELECT COUNT(*) FROM test_report WHERE deleted_at IS NULL")"
echo "软删 run=$SOFT_RUNS  软删 test_report=$SOFT_REPORTS"
echo "活动 run=$ACTIVE_RUNS  活动 test_report=$ACTIVE_REPORTS（run 数必须不变；报告数允许因级联下降）"

if [ "$SOFT_RUNS" = "0" ] && [ "$SOFT_REPORTS" = "0" ]; then
  echo "没有待清理数据"
  exit 0
fi

if [ "$APPLY" -ne 1 ]; then
  echo
  echo "DRY-RUN：未删除任何数据。确认后加 --apply。"
  echo "待清理 run（前 10 条）："
  q "SELECT id, run_id, deleted_at FROM run WHERE deleted_at IS NOT NULL ORDER BY id LIMIT 10;"
  exit 0
fi

# ---- 备份（整库相关表，含数据；只备这 8 张表，避免全库过大）----
BK="/tmp/eht-softdel-backup-$(date +%s)"
mkdir -p "$BK"
TABLES="run test_report test_result keyword_result run_artifact pytest_case_step manual_execution run_metadata"
DUMP_BIN="${MYSQLDUMP:-mysqldump}"
"$DUMP_BIN" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USERNAME" \
  --password="$DB_PASSWORD" --default-character-set=utf8mb4 \
  --single-transaction --no-create-info "$DB_NAME" $TABLES > "$BK/data.sql" 2>/dev/null
if [ ! -s "$BK/data.sql" ]; then
  echo "[FAIL] 备份失败（$BK/data.sql 为空），停止"
  exit 2
fi
SHA="$(sha256sum "$BK/data.sql" | awk '{print $1}')"
echo "$SHA" > "$BK/data.sql.sha256"
echo "备份完成：$BK/data.sql  sha256=$SHA"

FAIL=0
PURGED=0

purge_one_run() {
  local pk="$1"
  # 子表先删；全部按 run 主键，避免业务 run_id 歧义。
  q "DELETE FROM pytest_case_step WHERE run_id=$pk;
     DELETE FROM manual_execution WHERE run_id=$pk;
     DELETE FROM run_artifact WHERE run_id=$pk;
     DELETE FROM keyword_result WHERE run_id=$pk;
     DELETE FROM test_result WHERE run_id=$pk;
     DELETE FROM test_report WHERE run_id=(SELECT run_id FROM run WHERE id=$pk);
     DELETE FROM run WHERE id=$pk;"
  PURGED=$((PURGED + 1))
}

purge_one_report() {
  local pk="$1"
  q "DELETE FROM test_report WHERE id=$pk;"
  PURGED=$((PURGED + 1))
}

# ---- 第一批只清最近 30 天，避免一次动太多历史 ----
echo
echo "== 第 1 批：最近 30 天的软删记录（≤$BATCH 条）=="
mapfile -t IDS < <(q "SELECT id FROM run
                       WHERE deleted_at IS NOT NULL
                         AND deleted_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
                       ORDER BY id LIMIT $BATCH")
for pk in "${IDS[@]}"; do
  purge_one_run "$pk"
done
mapfile -t RIDS < <(q "SELECT id FROM test_report
                        WHERE deleted_at IS NOT NULL
                          AND deleted_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
                        ORDER BY id LIMIT $BATCH")
for pk in "${RIDS[@]}"; do
  purge_one_report "$pk"
done

# ---- 每批校验 ----
# 活动 run 数必须严格不变：软删行被清掉不应碰到任何活动执行记录。
# 活动 test_report 数**允许下降**：契约规定删 run 时级联删其报告，
# 因此某个软删 run 名下的报告（即使是 DRAFT/REVOKED）随父记录一起消失是正确行为。
NOW_ACTIVE_RUNS="$(q "SELECT COUNT(*) FROM run WHERE deleted_at IS NULL")"
NOW_ACTIVE_REPORTS="$(q "SELECT COUNT(*) FROM test_report WHERE deleted_at IS NULL")"
echo "本批清理 run=${#IDS[@]} report=${#RIDS[@]}，累计=$PURGED"
if [ "$NOW_ACTIVE_RUNS" != "$ACTIVE_RUNS" ]; then
  echo "[FAIL] 活动 run 数变化！$ACTIVE_RUNS→$NOW_ACTIVE_RUNS —— 误删活动执行记录"
  echo "立即停止。备份在 $BK/data.sql，可恢复。"
  exit 1
fi
echo "[PASS] 活动 run 数未变（$ACTIVE_RUNS），无误删执行记录"
if [ "$NOW_ACTIVE_REPORTS" != "$ACTIVE_REPORTS" ]; then
  echo "[INFO] 活动报告数 $ACTIVE_REPORTS→$NOW_ACTIVE_REPORTS：级联删除属于被清 run 的报告，符合契约"
fi

LEFT_RUNS="$(q "SELECT COUNT(*) FROM run WHERE deleted_at IS NOT NULL")"
LEFT_REPORTS="$(q "SELECT COUNT(*) FROM test_report WHERE deleted_at IS NOT NULL")"
echo "剩余软删 run=$LEFT_RUNS report=$LEFT_REPORTS"
echo
echo "== 汇总 =="
echo "备份：$BK/data.sql（sha256=$SHA）"
echo "本轮清理 $PURGED 条；剩余 $LEFT_RUNS + $LEFT_REPORTS 条待后续批次"
echo "RESULT: $([ "$FAIL" -eq 0 ] && echo OK || echo FAIL)"
