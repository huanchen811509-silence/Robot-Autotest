#!/usr/bin/env bash
# 逻辑备份（FR-v3.11-D03 / FR-v4.0-05）：数据库全量 + 工作区目录 + 环境文件的**键名清单**，
# 绝不导出明文口令。升级前由 update.sh 调用，工作区随 DB 一起进备份，避免升级丢用户数据。
#
#   ./bin/backup.sh                 # 默认 dry-run，只打印将做什么
#   ./bin/backup.sh --apply         # 真做，产物落到 $BACKUP_DIR
#
# 与 scripts/backup-drill.sh 的区别：drill 是「演练恢复」验收脚本；本脚本是日常运维导出。
set -euo pipefail
# shellcheck source=/dev/null
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

APPLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) APPLY=0; shift ;;
    --apply) APPLY=1; shift ;;
    --help|-h) printf '用法: backup.sh [--dry-run|--apply]\n'; usage_tail; exit 0 ;;
    *) die "未知参数 $1" ;;
  esac
done

load_env || die "缺少凭据文件 $ENV_FILE"
: "${DB_NAME:?环境文件未给 DB_NAME}"
: "${DB_USERNAME:?环境文件未给 DB_USERNAME}"
: "${DB_PASSWORD:?环境文件未给 DB_PASSWORD}"

STAMP="$(date '+%Y%m%d-%H%M%S')"
OUT="$BACKUP_DIR/${DB_NAME}-${STAMP}.sql.gz"
KEYS_OUT="$BACKUP_DIR/${DB_NAME}-${STAMP}.env-keys.txt"
WS_DIR="$APP_HOME/workspace"
WS_OUT="$BACKUP_DIR/${DB_NAME}-${STAMP}.workspace.tar.gz"

log "目标库     $DB_NAME @ ${DB_HOST:-127.0.0.1}:${DB_PORT:-3306}"
log "输出       $OUT"
log "工作区     $([[ -d "$WS_DIR" ]] && printf '%s → %s' "$WS_DIR" "$WS_OUT" || printf '无（不存在 %s）' "$WS_DIR")"
log "键名清单   $KEYS_OUT（只记键名，不记值）"
if [[ "$APPLY" == "0" ]]; then
  log 'dry-run：未创建任何文件。确认后加 --apply。'
  exit 0
fi

has_cmd mysqldump || die '缺少 mysqldump（安装 mysql-client 后再试）'
mkdir -p "$BACKUP_DIR"

# 口令通过 MYSQL_PWD 传递，不出现在命令行与日志里。
MYSQL_PWD="$DB_PASSWORD" mysqldump \
  -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"$DB_USERNAME" \
  --single-transaction --routines --triggers --default-character-set=utf8mb4 \
  "$DB_NAME" 2> "$BACKUP_DIR/${DB_NAME}-${STAMP}.dump-warnings.log" \
  | gzip -c > "$OUT"

[[ -s "$OUT" ]] || die "备份文件为空，已判定失败（$OUT）"
awk -F= '/^[A-Za-z_][A-Za-z0-9_]*=/ {print $1}' "$ENV_FILE" > "$KEYS_OUT"
printf '[ops] 备份完成 %s 字节 sha256=%s\n' "$(wc -c < "$OUT" | tr -d ' ')" "$(sha_of "$OUT")"
printf 'BACKUP PASS file=%s\n' "$OUT"

# 工作区归档：与 DB 备份同一时间戳，恢复时两者配套使用（FR-v4.0-05 / RPO=0）。
if [[ -d "$WS_DIR" ]]; then
  tar -czf "$WS_OUT" -C "$APP_HOME" workspace
  [[ -s "$WS_OUT" ]] || die "工作区归档为空，已判定失败（$WS_OUT）"
  printf '[ops] 工作区备份完成 %s 字节 sha256=%s\n' "$(wc -c < "$WS_OUT" | tr -d ' ')" "$(sha_of "$WS_OUT")"
  printf 'WORKSPACE PASS file=%s\n' "$WS_OUT"
else
  log "工作区目录不存在，跳过归档：$WS_DIR"
fi
