#!/usr/bin/env bash
# W4 · 备份演练（v3.0）
#
# 目的：证明"能备份"之外的"能恢复"——mysqldump 出开发库一份全量快照 →
# 覆盖恢复到同实例的空测试库（默认 robot_it）→ 逐表比对行数 → 校验 V9_6
# （execution_shard）的租约/防脑裂字段确实在恢复库里 → **再把目标库还原成演练前的样子**。
#
# 为什么不用临时库：业务账号 robot@% 只有 robot_dev / robot_it / robot_it_local 的
# 库级权限，没有 CREATE DATABASE。robot_it_local 并不存在且同样无法创建，因此
# 唯一安全的可写目标是 robot_it。演练前先给 robot_it 做自身备份，结束（含异常退出）自动还原。
#
# 用法：
#   bash scripts/backup-drill.sh
#   DRILL_TARGET_DB=robot_it_local bash scripts/backup-drill.sh   # 有建库权限时的隔离目标
#   KEEP_DUMP=1 bash scripts/backup-drill.sh                      # 保留 dump 供人工取证
#
# 前置：~/.config/robot-autotest/env 里的 DB_*（或已导出同名环境变量）。
# 注意：演练会短暂覆盖 robot_it 的库结构，不要在集成测试并行执行时运行。
# 退出码：0 = 通过；非 0 = 备份/恢复/比对/还原失败。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

if [ -z "${DB_PASSWORD:-}" ] && [ -f "$HOME/.config/robot-autotest/env" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$HOME/.config/robot-autotest/env"
  set +a
fi

SRC_DB="${DB_NAME:-robot_dev}"
DST_DB="${DRILL_TARGET_DB:-robot_it}"
[ "$SRC_DB" = "$DST_DB" ] && { echo "DRILL_FAIL 源库与目标库不能相同（$SRC_DB）"; exit 1; }

OUT_DIR="${DRILL_OUT_DIR:-docs/evidence/v3.0/raw}"
STATUS_JSON="${DRILL_STATUS_JSON:-docs/evidence/ops/backup-drill-latest.json}"
STAMP="$(date +%Y%m%d_%H%M%S)"
T0=$(date +%s)
DUMP="$OUT_DIR/backup-drill-$STAMP.sql"
PRE="$OUT_DIR/drill-pre-target-$STAMP.sql"
LOG="$OUT_DIR/backup-drill.log"
mkdir -p "$OUT_DIR"
: > "$LOG"

say() { printf '%s\n' "$*" | tee -a "$LOG"; }
die() { say "DRILL_FAIL $*"; exit 1; }

dump_db() {
  MYSQL_PWD="$DB_PASSWORD" mysqldump -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" \
    --single-transaction --routines --triggers --no-tablespaces "$1" 2>>"$LOG"
}
mysql_run() {
  local db="$1"; shift
  MYSQL_PWD="$DB_PASSWORD" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$db" "$@"
}
restore_target() {
  mysql_run "$DST_DB" < "$PRE" 2>>"$LOG"
}

cleanup() {
  local rc=$?
  if [ -s "$PRE" ]; then
    if restore_target; then
      say "DRILL_ROLLBACK ok $DST_DB 已还原为演练前状态"
      rm -f "$PRE"
    else
      say "DRILL_ROLLBACK FAIL 请手工导入 $PRE 还原 $DST_DB（文件保留）"
      rc=1
    fi
  fi
  if [ "${KEEP_DUMP:-0}" != "1" ]; then rm -f "$DUMP"; fi
  exit "$rc"
}
trap cleanup EXIT

say "DRILL_START $(date '+%F %T') source=$SRC_DB target=$DST_DB"

# 1) 备份源库：单事务一致性快照（InnoDB），带例程与触发器。
dump_db "$SRC_DB" > "$DUMP" || die "mysqldump 失败（见 $LOG）"
[ -s "$DUMP" ] || die "dump 文件为空: $DUMP"
say "DRILL_BACKUP ok size=$(wc -c < "$DUMP" | tr -d ' ')B file=$DUMP"

# 2) 备份目标库（演练后还原用），再覆盖导入源库快照。
dump_db "$DST_DB" > "$PRE" || die "目标库 $DST_DB 预备份失败"
say "DRILL_PRE ok size=$(wc -c < "$PRE" | tr -d ' ')B file=$PRE"
mysql_run "$DST_DB" < "$DUMP" 2>>"$LOG" || die "导入失败（见 $LOG）"
RESTORED_TABLES=$(mysql_run "$DST_DB" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DST_DB'" | tr -d ' ')
say "DRILL_RESTORE ok tables=$RESTORED_TABLES"

# 3) 行数比对：核心业务表必须逐表一致。
KEY_TABLES="run test_result test_report execution_shard execution_node audit_log robot_file execution_plan project app_user"
say "DRILL_COUNT table                source      restored    verdict"
bad=0
for t in $KEY_TABLES; do
  src=$(mysql_run "$SRC_DB" -N -B -e "SELECT COUNT(*) FROM \`$t\`" 2>/dev/null) || { say "  SKIP $t（源库无此表）"; continue; }
  dst=$(mysql_run "$DST_DB" -N -B -e "SELECT COUNT(*) FROM \`$t\`" 2>/dev/null) || dst="MISSING"
  if [ "$src" = "$dst" ]; then verdict=OK; else verdict=DIFF; bad=$((bad + 1)); fi
  printf '  %-20s %10s %10s    %s\n' "$t" "$src" "$dst" "$verdict" | tee -a "$LOG"
done

# 4) V9_6 校验：execution_shard 的租约/防脑裂字段必须在恢复库里。
say "DRILL_SCHEMA execution_shard 字段:"
cols=$(mysql_run "$DST_DB" -N -B -e \
  "SELECT COLUMN_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='$DST_DB' AND TABLE_NAME='execution_shard' ORDER BY ORDINAL_POSITION" | tr '\n' ' ')
say "  $cols"
for need in fencing_token attempt lease_owner lease_until shard_index state; do
  case " $cols " in
    *" $need "*) : ;;
    *) say "  MISSING $need"; bad=$((bad + 1)) ;;
  esac
done

# 5) 恢复库可用性冒烟：在恢复库上跑一条真实业务聚合。
if mysql_run "$DST_DB" -N -B -e "SELECT COUNT(*) FROM execution_shard WHERE state IN ('PENDING','LEASED','LOST')" >/dev/null 2>&1; then
  say "DRILL_QUERY ok execution_shard 聚合可执行"
else
  say "DRILL_QUERY fail execution_shard 聚合失败"; bad=$((bad + 1))
fi

if [ "$bad" -eq 0 ]; then
  say "DRILL_RESULT PASS"
else
  say "DRILL_RESULT FAIL(issues=$bad)"
fi

# 6) 写机器可读状态（运维中心与门禁都读它，不靠日志里的人工解读）。
RTO=$(( $(date +%s) - T0 ))
if [ "$bad" -eq 0 ] && [ "$RTO" -le 7200 ]; then VERDICT=PASS; else VERDICT=FAIL; fi
mkdir -p "$(dirname "$STATUS_JSON")"
cat > "$STATUS_JSON" <<JSON
{
  "generatedAt": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "rtoSeconds": $RTO,
  "rpoSeconds": 0,
  "rpoNote": "演练为即时全量快照，恢复点不早于 dump 开始；正式 RPO 由备份计划决定",
  "verdict": "$VERDICT",
  "tables": ${RESTORED_TABLES:-0},
  "database": "$DST_DB",
  "source": "$SRC_DB",
  "issues": $bad,
  "log": "$LOG"
}
JSON
say "DRILL_STATUS wrote=$STATUS_JSON verdict=$VERDICT rto=${RTO}s"
exit "$bad"
