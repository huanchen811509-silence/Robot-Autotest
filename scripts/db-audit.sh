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

# 口径：v0.1 设计为 38 表；后续版本扩到 47（V7_6）；v2.0 的 V8_1..V8_4 再增 13 张
# （test_report / _export / _audit、import_batch / case_source、user_group / _member /
# project_group_role / project_template / test_scenario、ai_session / ai_action /
# ai_cli_probe），实测 60（2026-11-06，V8_5 只加列不加表）。
# v2.1 的 V9_1 加 2 张（plan_group / plan_group_item），V9_2 再加 2 张
# （report_template / report_template_field），V9_3 加 1 张（asset_version），
# V9_4 加 1 张（execution_node），V9_6 加 1 张（execution_shard），实测 67。
# v2.2 / 2026-11-08：V11_1 加 1 张（import_batch_sheet），V11_2 加 1 张
# （case_requirement_version），实测 69；V11_3 / V11_4 / V11_9 只增列与索引，不加表。
# 2026-11-08 续：V11_10 / V11_11 / V11_13 只增列与索引，V11_12 加 1 张
# （execution_selection），故 69 → 70。V11_10..13 顺延落号是因为 V11_9 已应用，
# Flyway 未开 out-of-order，插回 11.5 会被拒。
# 2026-11-08 再续：V11_14 加 1 张（ai_assistant_config，AI 助手端点与凭据），故 70 → 71。
# v3.1 V11_18 加 4 张（saved_view / batch_undo / notification_preference /
# notification_read；已读按用户隔离，故单列一表），故 71 → 75。
# v3.3 V11_21 加 8 张（ai_project_config / ai_prompt_template / ai_message /
# alert_channel / alert_rule / alert_event / slo_daily / release_record），
# v3.4 V11_22 加 4 张（archive_policy / archived_run / archived_test_result /
# archived_keyword_result），故 75 → 87；V11_23 只补审计列，不加表。
# V11_2 首版曾建 TRIGGER，因 binlog + 无 SUPER 权限被实测推翻（Error 1419），
# 已改为纯建表 + 应用层不可变约束，见该迁移文件头修订说明。
# v3.9.0 V11_24 只加列；v3.10.0 V11_25 只加列；v3.11.0 V11_26 加 1 张
# （device_host_spec：每次采集插一行、只增不改），V11_27 只改索引，故 87 → 88。
# 此处期望值随迁移演进而更新，不是「表越多越好」的口径。
check "业务表数 = 88" "88" \
    "SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema='$DB_NAME' AND table_name<>'flyway_schema_history';"

# append-only 历史表只要求 created_at：这类表按设计只增不改，没有「更新人」这回事。
# 要求它们也有 updated_at/updated_by 会逼着业务代码写假值，比缺列更糟。
# 2026-11-06 补入 V8 的三张：test_report_export（每次导出插一行）、test_report_audit（审计）、
# ai_action（AI 动作留痕）。它们与 resource_lease_history / run_attempt 同性质。
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
         AND t.table_name NOT IN ('resource_lease_history','run_attempt',
                                  'test_report_export','test_report_audit','ai_action',
                                  -- v3.3 / v3.4 新增的 append-only 表：只增不改，
                                  -- 各自的时间语义见列注释（fired_at / archived_at / day）
                                  'ai_message','alert_event','slo_daily',
                                  'release_record','archived_run',
                                  -- v3.11.0：采集结果只插不改，缺 updated_* 是设计而非漏列
                                  'device_host_spec')
       GROUP BY t.table_name HAVING have < 4
     ) x;"
# 这六张 append-only 表（含 v3.11.0 的 device_host_spec）必须各有一个 created_at
# （6 张 → 期望 6，少一张就报）；v3.3/v3.4 那五张按各自时间列语义在上一判据豁免。
check "append-only 历史表都有 created_at" "6" \
    "SELECT COUNT(DISTINCT table_name) FROM information_schema.columns
     WHERE table_schema='$DB_NAME'
       AND table_name IN ('resource_lease_history','run_attempt',
                          'test_report_export','test_report_audit','ai_action',
                          'device_host_spec')
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
