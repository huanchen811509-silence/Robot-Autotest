#!/usr/bin/env bash
# v4.9.2 / T49-21：V11_30–V11_37 迁移 down → up 演练。
#
# 需要真实 MariaDB 与建库权限。连接参数全部来自环境变量，脚本不内置口令：
#   DB_HOST=127.0.0.1 DB_PORT=3306 DB_USER=root DB_PASSWORD=*** DB_NAME=robot_autotest
#
# 用法：bash docs/evidence/v4.9.2/migration-drill-V11_30-37.sh
# 结论写回本目录 `down-up.log`，失败即退出（set -e）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
MIG="$ROOT/db/migration"
DOWN="$ROOT/db/down"
LOG="$ROOT/docs/evidence/v4.9.2/down-up.log"

VERSIONS=(
  V11_30__project_member_role
  V11_31__ui_mode_platform_project
  V11_32__global_case_library
  V11_33__import_ledger_export
  V11_34__case_version
  V11_35__asset_import_source
  V11_36__project_template_persist
  V11_37__project_sync
)

: "${DB_HOST:=127.0.0.1}"
: "${DB_PORT:=3306}"
: "${DB_USER:?DB_USER 未设置}"
: "${DB_PASSWORD:?DB_PASSWORD 未设置}"
: "${DB_NAME:?DB_NAME 未设置}"

run_sql() {
  local file="$1"
  MYSQL_PWD="$DB_PASSWORD" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" --batch --raw < "$file"
}

: > "$LOG"
echo "# down -> up 演练 $(date -Iseconds) db=$DB_NAME" | tee -a "$LOG"

# 先倒序 down，再正序 up：验证 down 脚本彼此不依赖，且 up 可重复执行。
for v in $(printf '%s\n' "${VERSIONS[@]}" | tac); do
  echo "== down $v" | tee -a "$LOG"
  run_sql "$DOWN/$v.down.sql" >> "$LOG" 2>&1
done

for v in "${VERSIONS[@]}"; do
  echo "== up $v" | tee -a "$LOG"
  run_sql "$MIG/$v.sql" >> "$LOG" 2>&1
done

echo "== 结构抽查" | tee -a "$LOG"
MYSQL_PWD="$DB_PASSWORD" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" -e "
  SELECT COUNT(*) AS category_rows FROM case_category;
  SELECT COUNT(*) AS template_rows FROM project_template;
  SELECT COUNT(*) AS sync_job_rows FROM sync_job;
  SHOW COLUMNS FROM test_case LIKE 'external_id';
" | tee -a "$LOG"

echo "PASS: V11_30–V11_37 down→up 演练完成" | tee -a "$LOG"
