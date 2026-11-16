#!/usr/bin/env bash
# 开发期临时对象清理（v5.18.0 / FR-v5.18-07 / W68-15）
#
# 用法：
#   bash scripts/ops/purge-temp.sh                  # dry-run（默认，零写入）
#   PURGE_TEMP_CONFIRM=YES bash scripts/ops/purge-temp.sh --apply
#
# 五条硬规矩（缺一条就是事故）：
#   1) 清单驱动：候选写死在 CANDIDATE_* 常量里，不按名字模糊匹配；
#   2) 保留清单写死：admin / qa-e2e-* / 内容资产 / demo 样例，删除前二次校验 id 与 code；
#   3) 执行前必须有当日备份且 sha256 校验通过（无备份不删）；
#   4) dry-run 与 --apply 双闸（--apply 还要求 PURGE_TEMP_CONFIRM=YES）；
#   5) 真对象零变化：保留对象的计数与名称逐字比对。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../db-common.sh
source "$ROOT/scripts/db-common.sh"

APPLY=0
for arg in "$@"; do
    case "$arg" in
        --apply) APPLY=1 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "[purge-temp] 未知参数：$arg（仅支持 --apply）" >&2; exit 2 ;;
    esac
done

# ---------------------------------------------------------------- 清单（写死）
# 候选账号（盘点见 docs/evidence/v5.18.0/purge/inventory-temp.md）
CANDIDATE_USERS=("qa-ui" "prj01-demo" "ast05")
# 候选项目（8 个；TEST-V59-* 两个 is_demo 已在 v5.17.0 W67-01 清掉）
CANDIDATE_PROJECTS=(
    "PRJ-RF-202611-001"
    "PRJ-RF-202611-002"
    "UIAUTO-141455"
    "UIAUTO-489082"
    "UIAUTO-558458"
    "UIAUTO-730437"
    "UIAUTO-936762"
    "PERF-LONGLIST"
)
# 保留账号（真实 / 探针 / 门禁在用）
KEEP_USERS=("admin" "qa-e2e-admin" "qa-e2e-viewer" "qa-e2e-engineer" "qa-e2e-pm" "qa-e2e-nobody")
# 保留项目（内容资产 / 演示样例 / 夹具）——含大师 2026-11-16 拍板保留的 demo / demo-pytest
KEEP_PROJECTS=("PLATFORM-LIB" "ED202-COMMON" "EHT-PYTEST-LIB" "qa-e2e-fixture" "demo" "demo-pytest")

q() { db_exec "$DB_NAME" -N -B -e "$1" 2>/dev/null || true; }

in_list() {
    local needle="$1"; shift
    for item in "$@"; do
        [ "$item" = "$needle" ] && return 0
    done
    return 1
}

snapshot() {
    echo "  app_user=$(q "SELECT COUNT(*) FROM app_user") project=$(q "SELECT COUNT(*) FROM project") \
test_case=$(q "SELECT COUNT(*) FROM test_case") test_set=$(q "SELECT COUNT(*) FROM test_set") \
run=$(q "SELECT COUNT(*) FROM run") device=$(q "SELECT COUNT(*) FROM device") \
credential=$(q "SELECT COUNT(*) FROM credential")"
}

echo "[purge-temp] 目标库：${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "[purge-temp] 候选：${#CANDIDATE_USERS[@]} 个账号 / ${#CANDIDATE_PROJECTS[@]} 个项目"
echo "[purge-temp] 基线："
snapshot

# ---------------------------------------------------------------- 0) 保留清单二次校验
problems=0
for code in "${CANDIDATE_PROJECTS[@]}"; do
    if in_list "$code" "${KEEP_PROJECTS[@]}"; then
        echo "[purge-temp] 拒绝：$code 同时在保留清单里（清单写错）" >&2
        problems=$((problems + 1))
    fi
done
for name in "${CANDIDATE_USERS[@]}"; do
    if in_list "$name" "${KEEP_USERS[@]}"; then
        echo "[purge-temp] 拒绝：$name 同时在保留清单里（清单写错）" >&2
        problems=$((problems + 1))
    fi
done
[ "$problems" -eq 0 ] || exit 3

# ---------------------------------------------------------------- 1) 逐对象建档
echo
echo "[purge-temp] 待清理对象："
project_ids=()
for code in "${CANDIDATE_PROJECTS[@]}"; do
    id=$(q "SELECT id FROM project WHERE code = '$code'")
    if [ -z "$id" ]; then
        echo "  project $code  → 不存在（跳过）"
        continue
    fi
    cases=$(q "SELECT COUNT(*) FROM test_case WHERE project_id=$id")
    sets=$(q "SELECT COUNT(*) FROM test_set WHERE project_id=$id")
    devs=$(q "SELECT COUNT(*) FROM device WHERE project_id=$id")
    echo "  project $code (id=$id)  cases=$cases sets=$sets devices=$devs"
    project_ids+=("$id")
done

user_ids=()
for name in "${CANDIDATE_USERS[@]}"; do
    id=$(q "SELECT id FROM app_user WHERE username = '$name'")
    if [ -z "$id" ]; then
        echo "  user $name  → 不存在（跳过）"
        continue
    fi
    owned=$(q "SELECT COUNT(*) FROM project WHERE owner_id=$id")
    echo "  user $name (id=$id)  owned_projects=$owned"
    if [ "$owned" != "0" ]; then
        echo "    注意：该账号名下有 $owned 个项目，删除账号前需先处理项目归属" >&2
    fi
    user_ids+=("$id")
done

# ---------------------------------------------------------------- 2) 备份门禁
BACKUP_DIR="$ROOT/backups"
latest=$(ls -t "$BACKUP_DIR"/robot_dev-*.sql.gz 2>/dev/null | head -1 || true)
if [ -z "$latest" ]; then
    echo "[purge-temp] 拒绝：backups/ 下没有备份。先跑 bash scripts/ops/backup.sh --apply" >&2
    exit 4
fi
# 只认当天备份：昨天的备份可能不包含今天造的数
if [ "$(date -r "$latest" +%Y-%m-%d)" != "$(date +%Y-%m-%d)" ]; then
    echo "[purge-temp] 拒绝：最新备份不是今天的（$latest）。先重新备份。" >&2
    exit 4
fi
# 备份脚本写的 .sha256 只存 hash（不含文件名），`sha256sum -c` 用不了：
# 直接比两边的 hash 值，避免「格式不对就当成校验失败」或反过来「跳过校验」。
expected=$(awk '{print $1}' "$latest.sha256" 2>/dev/null || true)
actual=$(sha256sum "$latest" | awk '{print $1}')
if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
    echo "[purge-temp] 拒绝：备份 sha256 校验失败（$latest）" >&2
    echo "  期望：${expected:-<空>}" >&2
    echo "  实际：$actual" >&2
    exit 4
fi
echo
echo "[purge-temp] 备份校验通过：$(basename "$latest")"

# ---------------------------------------------------------------- 3) dry-run 出口
if [ "$APPLY" -eq 0 ]; then
    echo
    echo "[purge-temp] dry-run：未删除任何行。"
    echo "[purge-temp] 已校验：候选 ${#project_ids[@]} 项目 / ${#user_ids[@]} 账号；备份 hash 通过。"
    echo "[purge-temp] 确认无误后执行：PURGE_TEMP_CONFIRM=YES bash scripts/ops/purge-temp.sh --apply"
    exit 0
fi

if [ "${PURGE_TEMP_CONFIRM:-}" != "YES" ]; then
    echo "[purge-temp] 拒绝执行：--apply 需要同时设置 PURGE_TEMP_CONFIRM=YES" >&2
    echo "[purge-temp] 这是为了防止「手滑多打一个 --apply」把真实数据一起删掉。" >&2
    exit 5
fi

# ---------------------------------------------------------------- 4) 记录保留侧指纹
keep_before=$(for code in "${KEEP_PROJECTS[@]}"; do
    printf '%s=%s\n' "$code" "$(q "SELECT COUNT(*) FROM test_case WHERE project_id IN (SELECT id FROM project WHERE code='$code')")"
done)

# ---------------------------------------------------------------- 5) 删除项目内容
# 关键：有一批子表**没有 project_id**，只能经由 test_case / test_set 间接定位
# （test_case_step、case_requirement、case_script_binding、manual_execution…）。
# 第一版只按 project_id 多轮删，结果 5 轮都卡在同一处、7 个项目一个也没删掉——
# 这是 5.17 那次 1451 的同源问题，只是换了一层。所以先显式清间接子表，再走通用多轮。
#
# 顺序依据 information_schema 的引用关系（实测）：
#   test_case ← case_requirement / case_requirement_version / case_script_binding(case_id)
#              / manual_execution(case_id) / test_case_step(test_case_id) / test_set_item(test_case_id)
#   test_set  ← execution_plan / run / test_set_item
for id in "${project_ids[@]}"; do
    echo "[purge-temp] 清理项目 id=$id"

    # 5.1 间接子表：按 test_case / test_set 定位
    db_exec "$DB_NAME" -e "DELETE FROM test_case_step WHERE test_case_id IN
        (SELECT id FROM test_case WHERE project_id=$id)" >/dev/null 2>&1 || true
    for table in case_requirement case_requirement_version; do
        db_exec "$DB_NAME" -e "DELETE FROM $table WHERE test_case_id IN
            (SELECT id FROM test_case WHERE project_id=$id)" >/dev/null 2>&1 || true
    done
    for table in case_script_binding manual_execution; do
        db_exec "$DB_NAME" -e "DELETE FROM $table WHERE case_id IN
            (SELECT id FROM test_case WHERE project_id=$id)" >/dev/null 2>&1 || true
    done
    db_exec "$DB_NAME" -e "DELETE FROM test_set_item WHERE test_set_id IN
        (SELECT id FROM test_set WHERE project_id=$id) OR test_case_id IN
        (SELECT id FROM test_case WHERE project_id=$id)" >/dev/null 2>&1 || true

    # 5.2 其余有 project_id 的表：多轮删（run ↔ test_set 的相互引用靠重试解开）
    ref_tables=$(q "SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA='$DB_NAME' AND COLUMN_NAME='project_id'
                       AND TABLE_NAME NOT IN ('project')")
    for round in 1 2 3 4 5; do
        left=0
        for table in $ref_tables; do
            db_exec "$DB_NAME" -e "DELETE FROM $table WHERE project_id=$id" >/dev/null 2>&1 || left=$((left + 1))
        done
        if [ "$left" -eq 0 ]; then
            break
        fi
        echo "  第 $round 轮：$left 张表仍有引用，继续"
    done
    db_exec "$DB_NAME" -e "DELETE FROM project WHERE id=$id" >/dev/null 2>&1 \
        && echo "  项目 $id 已删除" \
        || { echo "  项目 $id 删除失败（仍有外键引用）" >&2; }
done

# ---------------------------------------------------------------- 6) 删除账号
# app_user 被 6 张表引用（实测）：api_token / project_member / project_member_role
# / user_group_member / user_preference / user_role。漏一张就是「删除失败但看不到原因」。
for id in "${user_ids[@]}"; do
    db_exec "$DB_NAME" -e "UPDATE project SET owner_id=NULL WHERE owner_id=$id" >/dev/null 2>&1 || true
    db_exec "$DB_NAME" -e "UPDATE project SET created_by=NULL WHERE created_by=$id" >/dev/null 2>&1 || true
    for table in api_token project_member project_member_role user_group_member user_preference user_role; do
        db_exec "$DB_NAME" -e "DELETE FROM $table WHERE user_id=$id" >/dev/null 2>&1 || true
    done
    db_exec "$DB_NAME" -e "DELETE FROM app_user WHERE id=$id" >/dev/null 2>&1 \
        && echo "[purge-temp] 账号 $id 已删除" \
        || echo "[purge-temp] 账号 $id 删除失败（仍被引用）" >&2
done

# ---------------------------------------------------------------- 7) 事后断言
echo
echo "[purge-temp] 事后核对："
snapshot

echo
echo "[purge-temp] 候选残留（应全为 0）："
for code in "${CANDIDATE_PROJECTS[@]}"; do
    printf '  project %-22s %s\n' "$code" "$(q "SELECT COUNT(*) FROM project WHERE code='$code'")"
done
for name in "${CANDIDATE_USERS[@]}"; do
    printf '  user    %-22s %s\n' "$name" "$(q "SELECT COUNT(*) FROM app_user WHERE username='$name'")"
done

echo
echo "[purge-temp] 保留对象指纹（前后必须一致）："
keep_after=$(for code in "${KEEP_PROJECTS[@]}"; do
    printf '%s=%s\n' "$code" "$(q "SELECT COUNT(*) FROM test_case WHERE project_id IN (SELECT id FROM project WHERE code='$code')")"
done)
if [ "$keep_before" = "$keep_after" ]; then
    echo "  KEEP_UNCHANGED=PASS"
else
    echo "  KEEP_UNCHANGED=FAIL"
    diff <(echo "$keep_before") <(echo "$keep_after") || true
    exit 6
fi
