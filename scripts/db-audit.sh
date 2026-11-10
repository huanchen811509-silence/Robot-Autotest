#!/usr/bin/env bash
# schema 体检：TC-v0.1-023 / 024 / 028 的脚本化版本（不依赖 JUnit，可单独跑）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/db-common.sh"

echo "[db-audit] $DB_USERNAME@$DB_HOST:$DB_PORT/$DB_NAME"
fail=0

check() {
    local title="$1" expected="$2" sql="$3"
    local actual
    actual="$(db_exec -e "$sql" | tr -d '[:space:]')"
    if [[ "$actual" == "$expected" ]]; then
        echo "  OK    $title（$actual）"
    else
        echo "  FAIL  $title（期望 $expected，实测 $actual）"
        fail=$((fail + 1))
    fi
}

# 口径唯一真源：db/schema-contract.json（FR-v4.2-02 同源常量）。
# 表数演进史与 append-only 清单都在该文件里；SchemaIT 读同一份，改一处即可，
# 另一侧漂移会在双侧判红。SCHEMA_CONTRACT_PATH 可指向临时副本做负向自证。
EXPECTED_TABLES="$(python3 "$ROOT/tools/schema_contract.py" expected-tables)"
APPEND_ONLY_SQL="$(python3 "$ROOT/tools/schema_contract.py" append-only-sql)"
CREATED_AT_REQUIRED="$(python3 "$ROOT/tools/schema_contract.py" createdAt-required-count)"
CREATED_AT_REQUIRED_SQL="$(python3 "$ROOT/tools/schema_contract.py" created-at-sql)"

check "业务表数 = $EXPECTED_TABLES" "$EXPECTED_TABLES" \
    "SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema='$DB_NAME' AND table_name<>'flyway_schema_history';"

# append-only 历史表只要求 created_at：这类表按设计只增不改，没有「更新人」这回事。
# 要求它们也有 updated_at/updated_by 会逼着业务代码写假值，比缺列更糟。
# 豁免名单来自 db/schema-contract.json 的 appendOnlyTables（11 张，只增不改）。
#
# 口径修正（2026-11-06）：原写法用 `column_name IN (四列) ... HAVING COUNT(*) < 4`，
# **一列都没有的表根本不进分组**，直接漏检 —— V8_1..V8_4 有 7 张表正是这种，db-audit 报绿、
# SchemaIT 报红。改为从 tables 左连 columns，缺列数 0 也能查出来。
check "缺审计四列的表的数量 = 0" "0" \
    "SELECT COUNT(*) FROM (
       SELECT t.table_name, COUNT(c.column_name) AS have
       FROM information_schema.tables t
       LEFT JOIN information_schema.columns c
         ON c.table_schema = t.table_schema AND c.table_name = t.table_name
        AND c.column_name IN ('created_at','created_by','updated_at','updated_by')
       WHERE t.table_schema='$DB_NAME' AND t.table_name<>'flyway_schema_history'
         AND t.table_name NOT IN ($APPEND_ONLY_SQL)
       GROUP BY t.table_name HAVING have < 4
     ) x;"
# createdAtRequired 子集必须各有一个 created_at（数量同样来自契约，少一张就报）；
# 其余 append-only 表按各自时间列语义在上一判据豁免。
check "append-only 历史表都有 created_at" "$CREATED_AT_REQUIRED" \
    "SELECT COUNT(DISTINCT table_name) FROM information_schema.columns
     WHERE table_schema='$DB_NAME'
       AND table_name IN ($CREATED_AT_REQUIRED_SQL)
       AND column_name='created_at';"

# 口径修正（2026-11-06）：原写法先筛 `column_name='id'` 再判它是否主键，
# **连 id 列都没有的表直接逃检**（同上一项的漏法）。改为从 tables 左连主键分组，
# 主键缺失、或主键不是「单列 id」，都算违规。
check "主键不是单列 id 的表数量 = 0" "0" \
    "SELECT COUNT(*) FROM (
       SELECT t.table_name
       FROM information_schema.tables t
       LEFT JOIN (
         SELECT k.table_name,
                GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position) AS pk
         FROM information_schema.key_column_usage k
         WHERE k.table_schema='$DB_NAME' AND k.constraint_name='PRIMARY'
         GROUP BY k.table_name
       ) p ON p.table_name = t.table_name
       WHERE t.table_schema='$DB_NAME' AND t.table_name<>'flyway_schema_history'
         AND (p.pk IS NULL OR p.pk <> 'id')
     ) x;"

check "缺主键的表数量 = 0" "0" \
    "SELECT COUNT(*) FROM (
       SELECT t.table_name FROM information_schema.tables t
       LEFT JOIN information_schema.key_column_usage k
         ON k.table_schema=t.table_schema AND k.table_name=t.table_name
        AND k.constraint_name='PRIMARY'
       WHERE t.table_schema='$DB_NAME' AND t.table_name<>'flyway_schema_history'
         AND k.column_name IS NULL
       GROUP BY t.table_name
     ) x;"

# 口径修正（2026-11-06）：原判据只看 data_type='datetime'，**精度不查** ——
# datetime(0) 也能过检。改为比对 column_type，与 SchemaIT 的判据对齐。
check "时间列不是 datetime(3) 的表数量 = 0" "0" \
    "SELECT COUNT(*) FROM (
       SELECT table_name FROM information_schema.columns
       WHERE table_schema='$DB_NAME' AND column_name IN ('created_at','updated_at')
         AND column_type <> 'datetime(3)'
       GROUP BY table_name
     ) t;"

check "非 utf8mb4 的表数量 = 0" "0" \
    "SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema='$DB_NAME' AND table_name<>'flyway_schema_history'
       AND table_collation NOT LIKE 'utf8mb4%';"

echo "--------------------------------------------------------------"
if [[ $fail -gt 0 ]]; then
    echo "db-audit: FAIL（$fail 项不符）"
    exit 1
fi
echo "db-audit: PASS"
