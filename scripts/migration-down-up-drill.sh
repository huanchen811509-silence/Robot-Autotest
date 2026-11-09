#!/usr/bin/env bash
# 迁移 down→up 真实演练（只允许集成测试库，库名必须以 _it 结尾）。
#
# 为什么要有这个脚本：`DownScriptGuardTest` 只能证明「up 与 down 文件配对、且不含
# 目标库不支持的语法」，它不会真去执行 down —— 列名写错、外键顺序反了、少了 DROP，
# 单测一律照绿。v3.9.0 补 V11_24 回滚脚本时就靠这个演练证明三列真的能被摘掉再装回来。
#
# 用法：
#   bash scripts/migration-down-up-drill.sh V11_24__v3_9_case_command
#   bash scripts/migration-down-up-drill.sh V11_25__v3_10_report_source
#
# **目标表与目标列从 up 脚本里自动解析**（`ALTER TABLE x` + `ADD COLUMN y`），不再写死：
# v3.9.0 初版把 test_result / command% 钉在脚本里，拿去演练 V11_25 会「通过」一个
# 它根本没看的表 —— 这种假绿比没有演练更危险。
#
# 判据（脚本自动判定，任一条不满足即 FAIL）：
#   1. down 前：目标列存在，且记录下类型 / 可空性
#   2. down 后：目标列一个不剩
#   3. up   后：目标列回来，且类型 / 可空性与此前一致
#   4. 全程目标表数据行数不被改动
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

MIGRATION="${1:-}"
if [[ -z "$MIGRATION" ]]; then
    echo "用法: bash scripts/migration-down-up-drill.sh <迁移文件名去后缀>（如 V11_25__v3_10_report_source）" >&2
    exit 2
fi

DOWN="./db/down/${MIGRATION}.down.sql"
UP="./db/migration/${MIGRATION}.sql"
for f in "$DOWN" "$UP"; do
    [[ -f "$f" ]] || { echo "[drill] 缺少文件: $f" >&2; exit 2; }
done

# ---- 从 up 脚本解析目标表与目标列 ----
# 两种形态都要支持：
#   a) ALTER TABLE x ADD COLUMN y —— 取该表的这些列（v3.9.0 / v3.10.0 的迁移都是这种）
#   b) CREATE TABLE x            —— 整表新建，判据变成「表存在 / 不存在」（v3.11.0）
# 注释行要先剔掉，否则注释里的示例列名也会被算进来。
UP_SQL="$(sed 's/--.*$//' "$UP")"
MODE="${MODE:-}"
TABLE="${TABLE:-}"
if [[ -z "$TABLE" ]]; then
    TABLE="$(printf '%s\n' "$UP_SQL" | grep -oiE 'ALTER TABLE `[a-z_0-9]+`' | head -1 | sed 's/.*`\(.*\)`/\1/')"
    [[ -n "${TABLE:-}" ]] && MODE="${MODE:-alter}"
fi
if [[ -z "${TABLE:-}" ]]; then
    TABLE="$(printf '%s\n' "$UP_SQL" | grep -oiE 'CREATE TABLE `[a-z_0-9]+`' | head -1 | sed 's/.*`\(.*\)`/\1/')"
    [[ -n "${TABLE:-}" ]] && MODE="${MODE:-table}"
fi
if [[ -z "$MODE" ]]; then MODE="alter"; fi

if [[ "$MODE" == "alter" ]]; then
    COLUMNS="${COLUMNS:-$(printf '%s\n' "$UP_SQL" | grep -oiE 'ADD COLUMN `[a-z_0-9]+`' | sed 's/.*`\(.*\)`/\1/' | paste -sd, -)}"
    if [[ -z "${TABLE:-}" || -z "$COLUMNS" ]]; then
        echo "[drill] 无法从 $UP 解析出目标表/列；可用 TABLE=... COLUMNS=a,b 显式指定" >&2
        exit 2
    fi
    # 列名拼成 SQL IN 列表：'a','b','c'
    COLUMN_SQL="$(printf '%s' "$COLUMNS" | sed "s/[^,]*/'&'/g")"
    COLUMN_FILTER="AND column_name IN ($COLUMN_SQL)"
else
    if [[ -z "${TABLE:-}" ]]; then
        echo "[drill] 无法从 $UP 解析出目标表；可用 TABLE=... 显式指定" >&2
        exit 2
    fi
    # 整表形态：比的是这张表的全部列
    COLUMN_SQL=""
    COLUMN_FILTER=""
fi

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

column_state() {
    db_exec "$DB_NAME" -N -B -e "SELECT column_name, column_type, is_nullable
      FROM information_schema.columns
      WHERE table_schema='$DB_NAME' AND table_name='$TABLE' $COLUMN_FILTER
      ORDER BY ordinal_position;"
}

rowcount() {
    # 表不存在时返回 n/a（整表形态下 down 之后本来就没有表）
    local exists
    exists="$(db_exec "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema='$DB_NAME' AND table_name='$TABLE';")"
    if [[ "$exists" != "1" ]]; then
        echo "n/a"
        return
    fi
    db_exec "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM \`$TABLE\`;"
}

if [[ "$MODE" == "alter" ]]; then
    EXPECTED_COLS="$(printf '%s' "$COLUMNS" | tr ',' '\n' | grep -c .)"
else
    EXPECTED_COLS=""
fi

echo "[drill] target=$DB_USERNAME@$DB_HOST:$DB_PORT/$DB_NAME migration=$MIGRATION"
if [[ "$MODE" == "alter" ]]; then
    echo "[drill] 模式=加列 目标表=$TABLE 目标列=$COLUMNS（共 $EXPECTED_COLS 列）"
else
    echo "[drill] 模式=建表 目标表=$TABLE（比全部列）"
fi
echo "[drill] 提示：mysql 的 'Using a password' 是既有告警，可忽略"

echo "===== 0. 演练前 ====="
BEFORE="$(column_state)"
printf '%s\n' "$BEFORE"
FOUND_BEFORE="$(printf '%s\n' "$BEFORE" | grep -c . || true)"
ROWS_BEFORE="$(rowcount)"
echo "[drill] $TABLE 行数=$ROWS_BEFORE"
if [[ "$MODE" == "alter" && "$FOUND_BEFORE" != "$EXPECTED_COLS" ]]; then
    echo "[drill] FAIL：down 前只找到 $FOUND_BEFORE 列（期望 $EXPECTED_COLS）——up 没跑过或列名解析错" >&2
    exit 1
fi
if [[ "$MODE" == "table" && "$FOUND_BEFORE" -eq 0 ]]; then
    echo "[drill] FAIL：down 前表 $TABLE 不存在——up 没跑过" >&2
    exit 1
fi

echo "===== 1. down ====="
if ! db_exec "$DB_NAME" < "$DOWN"; then
    echo "[drill] down 失败：目标列不存在或 SQL 有误（down 只能用在 up 之后，不能重复执行）" >&2
    exit 1
fi
LEFT="$(column_state)"
echo "[drill] down OK；残留结构（应为空）："
printf '%s\n' "$LEFT"
if [[ -n "$(printf '%s' "$LEFT" | tr -d '[:space:]')" ]]; then
    echo "[drill] FAIL：down 之后仍有目标列残留" >&2
    exit 1
fi

echo "===== 2. up ====="
db_exec "$DB_NAME" < "$UP" || { echo "[drill] up 失败" >&2; exit 1; }
AFTER="$(column_state)"
if [[ "$MODE" == "alter" ]]; then
    echo "[drill] up OK；目标列（应为 $EXPECTED_COLS 行）："
else
    echo "[drill] up OK；表列清单（应与演练前一致）："
fi
printf '%s\n' "$AFTER"

ROWS_AFTER="$(rowcount)"
echo "[drill] $TABLE 行数=$ROWS_AFTER（应等于 $ROWS_BEFORE）"

FAILS=0
[[ "$BEFORE" == "$AFTER" ]] || { echo "[drill] FAIL：up 之后的列定义与演练前不一致（类型/可空性/列清单变了）" >&2; FAILS=$((FAILS + 1)); }
[[ "$ROWS_BEFORE" == "$ROWS_AFTER" ]] || { echo "[drill] FAIL：行数前后不一致" >&2; FAILS=$((FAILS + 1)); }

if [[ "$FAILS" -gt 0 ]]; then
    echo "[drill] RESULT FAIL fails=$FAILS" >&2
    exit 1
fi
echo "[drill] RESULT PASS fails=0"
echo "[drill] DONE"
