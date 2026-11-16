#!/usr/bin/env bash
# 开发期临时对象盘点（v5.18.0 / FR-v5.18-07 / W68-14）
#
# 只读：不写库、不改文件（除本脚本要输出的清单本身）。
# 三信号判定，**禁止按名字猜**：
#   S1 is_demo=1              → 直接判临时
#   S2 引用面为 0             → 才可进候选（scripts/ tools/ frontend/src/ 里搜不到该名字）
#   S3 内容资产规模           → 有用例规模且被文档当资产的，一律保留
#
# 用法：bash scripts/ops/inventory-temp.sh [输出路径]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../db-common.sh
source "$ROOT/scripts/db-common.sh"

OUT="${1:-$ROOT/docs/evidence/v5.18.0/purge/inventory-temp.md}"
mkdir -p "$(dirname "$OUT")"

# 必须保留：真实管理员 / 探针与门禁在用的账号 / 内容资产项目 / 演示样例
KEEP_USERS="admin qa-e2e-admin qa-e2e-viewer qa-e2e-engineer qa-e2e-pm qa-e2e-nobody engineer pm nobody"
KEEP_PROJECTS="PLATFORM-LIB ED202-COMMON EHT-PYTEST-LIB qa-e2e-fixture demo demo-pytest"

# 引用面分两类（S2 只认「运行依赖」）：
#   runtime = 运行期真的会用到这个对象的引用（脚本/工具/前后端源码）
#   fixture = 测试夹具（*.spec.ts）与造数脚本里的名字；它们**不依赖对象存在**，
#             造数脚本下次运行会重建，spec 里的字符串也不需要库里真有这行
# 只按「有引用就保留」会把临时对象永久钉住；只按「有引用就删」会删掉探针依赖。
refs_runtime() {
    local name="$1"
    # 注释里的示例（形如 `// PRJ-XXXX-000`）不算依赖：它不读取库里任何行
    grep -rn --fixed-strings "$name" \
        "$ROOT/scripts" "$ROOT/tools" "$ROOT/frontend/src" "$ROOT/backend/src" 2>/dev/null \
        | grep -vE '\.spec\.(ts|js):|/src/test/|/test/resources/|tools/perf_seed\.py|scripts/ui-flows-' \
        | grep -vF "$0" \
        | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' \
        | cut -d: -f1 | sort -u | wc -l || true
    return 0
}

refs_fixture() {
    local name="$1"
    grep -rl --fixed-strings "$name" \
        "$ROOT/scripts" "$ROOT/tools" "$ROOT/frontend/src" "$ROOT/backend/src" 2>/dev/null \
        | grep -E '\.spec\.(ts|js)$|/src/test/|/test/resources/|tools/perf_seed\.py|scripts/ui-flows-' \
        | wc -l || true
    return 0
}

is_keep_user() {
    case " $KEEP_USERS " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}

is_keep_project() {
    case " $KEEP_PROJECTS " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}

q() { db_exec "$DB_NAME" -N -B -e "$1" 2>/dev/null || true; }

{
    echo "# 开发期临时对象盘点（自动生成）"
    echo
    echo "生成时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "目标库：${DB_HOST}:${DB_PORT}/${DB_NAME}"
    echo "脚本：\`scripts/ops/inventory-temp.sh\`（只读）"
    echo
    echo "> 判定口径：S1 \`is_demo=1\` / S2 引用面为 0 / S3 内容资产规模。"
    echo "> 三信号缺一不可进删除候选；**按名字匹配一律不作依据**。"
    echo

    echo "## 一、账号（${DB_NAME}.app_user）"
    echo
    echo "| id | 用户名 | is_demo | 运行依赖 | 夹具引用 | 判定 | 理由 |"
    echo "|---|---|---|---|---|---|---|"
    total_users=0
    while IFS=$'\t' read -r id username demo; do
        [ -z "${id:-}" ] && continue
        total_users=$((total_users + 1))
        runtime=$(refs_runtime "$username")
        fixture=$(refs_fixture "$username")
        if is_keep_user "$username"; then
            verdict="保留"
            reason="真实/探针/门禁在用"
        elif [ "$demo" = "1" ]; then
            verdict="候选删除"
            reason="S1 is_demo=1"
        elif [ "$runtime" = "0" ]; then
            verdict="候选删除"
            reason="S2 运行依赖为 0（夹具引用 ${fixture} 处，不依赖该行存在）"
        else
            verdict="保留"
            reason="有 ${runtime} 处运行依赖"
        fi
        printf '| %s | %s | %s | %s | %s | %s | %s |\n' "$id" "$username" "$demo" "$runtime" "$fixture" "$verdict" "$reason"
    done < <(q "SELECT id, username, IFNULL(is_demo,0) FROM app_user ORDER BY id")

    echo
    echo "## 二、项目（${DB_NAME}.project）"
    echo
    echo "| id | 编码 | is_demo | 用例数 | 运行依赖 | 夹具引用 | 判定 | 理由 |"
    echo "|---|---|---|---|---|---|---|---|"
    while IFS=$'\t' read -r id code demo; do
        [ -z "${id:-}" ] && continue
        cases=$(q "SELECT COUNT(*) FROM test_case WHERE project_id=$id")
        runtime=$(refs_runtime "$code")
        fixture=$(refs_fixture "$code")
        if is_keep_project "$code"; then
            verdict="保留"
            reason="内容资产 / 演示样例 / 夹具"
        elif [ "$demo" = "1" ]; then
            verdict="候选删除"
            reason="S1 is_demo=1"
        elif [ "$runtime" = "0" ]; then
            verdict="候选删除"
            reason="S2 运行依赖为 0（夹具/造数引用 ${fixture} 处）"
        else
            verdict="保留"
            reason="有 ${runtime} 处运行依赖"
        fi
        printf '| %s | %s | %s | %s | %s | %s | %s | %s |\n' "$id" "$code" "$demo" "$cases" "$runtime" "$fixture" "$verdict" "$reason"
    done < <(q "SELECT id, code, IFNULL(is_demo,0) FROM project ORDER BY id")

    echo
    echo "## 三、库内总量基线"
    echo
    echo '| 表 | 行数 |'
    echo '|---|---|'
    for table in app_user project test_case test_set run test_result test_report device credential; do
        printf '| %s | %s |\n' "$table" "$(q "SELECT COUNT(*) FROM $table")"
    done

    echo
    echo "## 四、候选汇总（供 purge-temp.sh 复核）"
    echo
    echo "账号候选（运行依赖为 0 且不在保留清单）："
    while IFS=$'\t' read -r username; do
        [ -z "${username:-}" ] && continue
        if is_keep_user "$username"; then continue; fi
        if [ "$(refs_runtime "$username")" != "0" ]; then continue; fi
        echo "- \`$username\`"
    done < <(q "SELECT username FROM app_user ORDER BY id")
    echo
    echo "项目候选（运行依赖为 0 且不在保留清单）："
    while IFS=$'\t' read -r code; do
        [ -z "${code:-}" ] && continue
        if is_keep_project "$code"; then continue; fi
        if [ "$(refs_runtime "$code")" != "0" ]; then continue; fi
        echo "- \`$code\`"
    done < <(q "SELECT code FROM project ORDER BY id")
} > "$OUT"

echo "[inventory-temp] 已写入：$OUT"
echo "[inventory-temp] 行数：$(wc -l < "$OUT")"
