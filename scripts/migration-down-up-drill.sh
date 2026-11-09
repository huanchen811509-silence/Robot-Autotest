#!/usr/bin/env bash
# 迁移 down→up 真实演练（只允许集成测试库，库名必须以 _it 结尾）。
#
# 为什么要有这个脚本：`DownScriptGuardTest` 只能证明「up 与 down 文件配对、且不含
# 目标库不支持的语法」，它不会真去执行 down —— 列名写错、外键顺序反了、少了 DROP，
# 单测一律照绿。v3.9.0 补 V11_24 回滚脚本时就靠这个演练证明三列真的能被摘掉再装回来。
#
# 用法：
#   bash scripts/migration-down-up-drill.sh V11_24__v3_9_case_command
#
# 判据（脚本会打印，人按输出判）：
#   1. down 前：目标列存在
#   2. down 后：目标列全部消失
#   3. up   后：目标列回来，且类型 / 可空性与此前一致
#   4. 全程数据行数不被改动
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

MIGRATION="${1:-}"
if [[ -z "$MIGRATION" ]]; then
    echo "用法: bash scripts/migration-down-up-drill.sh <迁移文件名去后缀>（如 V11_24__v3_9_case_command）" >&2
    exit 2
fi

DOWN="./db/down/${MIGRATION}.down.sql"
UP="./db/migration/${MIGRATION}.sql"
for f in "$DOWN" "$UP"; do
    [[ -f "$f" ]] || { echo "[drill] 缺少文件: $f" >&2; exit 2; }
done

set -a
# shellcheck disable=SC1091
. "$HOME/.config/robot-autotest/env" 2>/dev/null || true
set +a
DB_NAME="${DB_NAME:-robot_it}"
# 环境文件里配的是开发库（robot_dev），而演练只允许动集成库：
# 这里不报错退出，而是改用 robot_it 并明说，否则每次都得记着传变量。
if [[ "$DB_NAME" != *_it ]]; then
    echo "[drill] 环境 DB_NAME=$DB_NAME 不是集成库，本次改用 robot_it"
    DB_NAME=robot_it
fi
# shellcheck disable=SC1091
source ./scripts/db-common.sh

if [[ "$DB_NAME" != *_it ]]; then
    echo "[drill] 拒绝执行：DB_NAME=$DB_NAME 不以 _it 结尾（只允许集成测试库）" >&2
    exit 1
fi

echo "[drill] target=$DB_USERNAME@$DB_HOST:$DB_PORT/$DB_NAME migration=$MIGRATION"
echo "[drill] 提示：mysql 的 'Using a password on the command line' 是既有告警，可忽略"

echo "===== 0. 演练前 ====="
db_exec "$DB_NAME" -e "SELECT column_name, column_type, is_nullable
  FROM information_schema.columns
  WHERE table_schema='$DB_NAME' AND table_name='test_result' AND column_name LIKE 'command%'
  ORDER BY ordinal_position;"
ROWS_BEFORE="$(db_exec "$DB_NAME" -N -B -e 'SELECT COUNT(*) FROM test_result;')"
echo "[drill] test_result 行数=$ROWS_BEFORE"

echo "===== 1. down ====="
if ! db_exec "$DB_NAME" < "$DOWN"; then
    echo "[drill] down 失败：目标列不存在或 SQL 有误（down 只能用在 up 之后，不能重复执行）" >&2
    exit 1
fi
echo "[drill] down OK；残留 command* 列（应为空）："
db_exec "$DB_NAME" -e "SELECT column_name FROM information_schema.columns
  WHERE table_schema='$DB_NAME' AND table_name='test_result' AND column_name LIKE 'command%';"

echo "===== 2. up ====="
db_exec "$DB_NAME" < "$UP" || { echo "[drill] up 失败" >&2; exit 1; }
echo "[drill] up OK；command* 列（应为 3 行）："
db_exec "$DB_NAME" -e "SELECT column_name, column_type, is_nullable
  FROM information_schema.columns
  WHERE table_schema='$DB_NAME' AND table_name='test_result' AND column_name LIKE 'command%'
  ORDER BY ordinal_position;"

ROWS_AFTER="$(db_exec "$DB_NAME" -N -B -e 'SELECT COUNT(*) FROM test_result;')"
echo "[drill] test_result 行数=$ROWS_AFTER（应等于 $ROWS_BEFORE）"

if [[ "$ROWS_BEFORE" != "$ROWS_AFTER" ]]; then
    echo "[drill] FAIL：行数前后不一致" >&2
    exit 1
fi
echo "[drill] DONE"
