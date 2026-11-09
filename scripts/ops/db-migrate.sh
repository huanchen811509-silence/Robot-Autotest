#!/usr/bin/env bash
# 首次安装的库初始化（FR-v3.11-D01 辅助路径）。
#
#   ./bin/db-migrate.sh                  # dry-run：按版本顺序列出将执行的迁移，不连库
#   ./bin/db-migrate.sh --apply         # 真执行；仅允许**空库**，非空库必须显式 --force
#
# 边界（必须写清，避免误用）：
#   - 这是「全新空库」的一次性安装路径，按文件名版本顺序执行 db/migration/V*.sql；
#   - 已有数据的库请走项目标准 Flyway 流程（scripts/db-migrate.sh / 应用启动自动迁移），
#     不要拿本脚本升级，否则会重复执行历史迁移；
#   - 本脚本不创建数据库，库与账号由部署方事先建好。
set -euo pipefail
# shellcheck source=/dev/null
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

APPLY=0
FORCE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) APPLY=0; shift ;;
    --apply) APPLY=1; shift ;;
    --force) FORCE=1; shift ;;
    --help|-h) printf '用法: db-migrate.sh [--dry-run|--apply] [--force]\n'; usage_tail; exit 0 ;;
    *) die "未知参数 $1" ;;
  esac
done

mapfile -t FILES < <(find "$DB_DIR/migration" -maxdepth 1 -name 'V*.sql' -printf '%f\n' | sort -V)
[[ ${#FILES[@]} -gt 0 ]] || die "没有迁移脚本：$DB_DIR/migration"

log "迁移脚本 ${#FILES[@]} 个（按版本升序）"
printf '%s\n' "${FILES[@]}" | sed 's/^/[ops]   /'
if [[ "$APPLY" == "0" ]]; then
  log 'dry-run：未连接数据库、未执行任何 SQL。确认后加 --apply。'
  exit 0
fi

has_cmd mysql || die '缺少 mysql 客户端'
load_env || die "缺少凭据文件 $ENV_FILE"
: "${DB_NAME:?环境文件未给 DB_NAME}"
: "${DB_USERNAME:?环境文件未给 DB_USERNAME}"
: "${DB_PASSWORD:?环境文件未给 DB_PASSWORD}"

MYSQL=(mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"$DB_USERNAME" "$DB_NAME")
export MYSQL_PWD="$DB_PASSWORD"

tables="$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME'" 2>/dev/null || echo 'unknown')"
if [[ "$tables" != "0" && "$FORCE" != "1" ]]; then
  die "目标库 $DB_NAME 已有 $tables 张表；本脚本只服务全新空库。已有库请走 Flyway，或显式 --force（风险自负）"
fi

for f in "${FILES[@]}"; do
  log "执行 $f"
  "${MYSQL[@]}" < "$DB_DIR/migration/$f"
done
ok "迁移执行完毕（${#FILES[@]} 个脚本）"
printf 'DB_MIGRATE PASS files=%s\n' "${#FILES[@]}"
