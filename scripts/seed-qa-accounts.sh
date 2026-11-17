#!/usr/bin/env bash
# 角色夹具账号（v5.21.0 / NFR-v5.21-04 / W71-02）。
#
# 目的：v5.21 之前 `role` 表只有 admin 一行，界面上**建不出** pm/engineer/viewer 账号，
# 于是「角色边界」这件事从来没有被真实账号验过（v5.21 调研 F-21-01）。本脚本幂等地
# 备好 4 个夹具账号并绑好角色，供 role-matrix / role-buttons / role-routes 三个探针使用。
#
# 幂等做法：先按用户名查（`GET /api/users?keyword=`），存在就只校正角色，不存在才 POST。
# 不删用户、不改口令——口令只在创建时给（取自环境变量），已存在账号的口令由 DBA 侧维护。
#
# 用法：
#   set -a; . ~/.config/robot-autotest/env; set +a
#   bash scripts/seed-qa-accounts.sh                 # 建/校正 4 个账号
#   bash scripts/seed-qa-accounts.sh --dry-run       # 只打印将要做的事
#
# 依赖环境变量：
#   APP_BASE           默认 http://127.0.0.1:8001
#   ADMIN_USERNAME     默认 admin
#   ADMIN_PASSWORD     管理员口令（**必填**；不写死默认口令，`scripts/secret-scan.sh` 会判明文）
#   QA_E2E_PASSWORD    夹具账号口令（创建新账号时使用；未设置则跳过创建并告警）
#   QA_SEED_PROJECT    夹具工程号（显式指定；不写则按 QA_SEED_PROJECT_CODE 解析）
#   QA_SEED_PROJECT_CODE  夹具工程 code，默认 demo
set -euo pipefail

BASE="${APP_BASE:-http://127.0.0.1:8001}"
ADMIN_USER="${ADMIN_USERNAME:-admin}"
ADMIN_PASS="${ADMIN_PASSWORD:-${QA_E2E_PASSWORD:-}}"
FIXTURE_PASS="${QA_E2E_PASSWORD:-}"
DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

if [[ -z "$ADMIN_PASS" ]]; then
    echo "[seed] 缺少 ADMIN_PASSWORD（管理员口令）——本脚本不写死默认口令" >&2
    exit 2
fi

api() { # api <method> <path> [body]
    local method="$1" path="$2" body="${3:-}"
    if [[ -n "$body" ]]; then
        curl -sS --noproxy '*' -X "$method" "$BASE$path" \
            -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body"
    else
        curl -sS --noproxy '*' -X "$method" "$BASE$path" -H "Authorization: Bearer $TOKEN"
    fi
}

TOKEN=$(curl -sS --noproxy '*' -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" | jq -r '.data.token // empty')
if [[ -z "$TOKEN" ]]; then
    echo "[seed] 管理员登录失败（$ADMIN_USER）" >&2
    exit 2
fi

# 角色 id 表：code → id
ROLES_JSON=$(api GET /api/roles)
declare -A ROLE_ID=()
while IFS=$'\t' read -r code id; do
    ROLE_ID["$code"]="$id"
done < <(echo "$ROLES_JSON" | jq -r '.data[] | "\(.code)\t\(.id)"')

for code in pm engineer viewer; do
    if [[ -z "${ROLE_ID[$code]:-}" ]]; then
        echo "[seed] 后端缺少内置角色 $code —— 先跑迁移 V11_80" >&2
        exit 1
    fi
done

ensure_account() { # ensure_account <username> <roleCode|-> <displayName>
    local username="$1" role_code="$2" display="$3"
    local found_id
    found_id=$(api GET "/api/users?keyword=$username&page=1&size=50" \
        | jq -r --arg u "$username" '.data.items[] | select(.username == $u) | .id' | head -1)

    local role_ids='[]'
    if [[ "$role_code" != "-" ]]; then
        role_ids="[${ROLE_ID[$role_code]}]"
    fi

    if [[ -n "$found_id" ]]; then
        if [[ $DRY_RUN -eq 1 ]]; then
            echo "[dry-run] 已存在 $username(id=$found_id) → 校正角色为 ${role_code}"
            return
        fi
        # 只发角色的 PUT：displayName/email 传 null 会被后端当作"清空"，
        # 这里显式回读原值再提交，避免校正角色时把显示名抹掉。
        local current
        current=$(api GET "/api/users/$found_id")
        local body
        body=$(echo "$current" | jq -c --argjson ids "$role_ids" \
            '{displayName: .data.displayName, email: .data.email, orgId: .data.orgId,
              enabled: .data.enabled, roleIds: $ids}')
        api PUT "/api/users/$found_id" "$body" > /dev/null
        echo "[seed] 已存在 $username(id=$found_id)，角色已校正为 ${role_code}"
        return
    fi

    if [[ -z "$FIXTURE_PASS" ]]; then
        echo "[seed][WARN] 缺少 QA_E2E_PASSWORD，跳过创建 $username（不写死默认口令）" >&2
        return
    fi
    if [[ $DRY_RUN -eq 1 ]]; then
        echo "[dry-run] 将创建 $username（角色 ${role_code}）"
        return
    fi
    local body
    body=$(jq -cn --arg u "$username" --arg d "$display" --arg p "$FIXTURE_PASS" \
        --argjson ids "$role_ids" \
        '{username: $u, displayName: $d, email: null, orgId: null, password: $p, roleIds: $ids}')
    api POST /api/users "$body" > /dev/null
    echo "[seed] 已创建 $username（角色 ${role_code}）"
}

ensure_account "qa-e2e-pm"        "pm"       "E2E 项目经理"
ensure_account "qa-e2e-engineer"  "engineer" "E2E 工程师"
ensure_account "qa-e2e-viewer"    "viewer"   "E2E 只读访客"
# 无全局角色账号：用来验「最小能力集」口径（FR-v5.21-02 判据 1/2）。
ensure_account "qa-e2e-nobody"    "-"        "E2E 无角色"

# ---------------------------------------------------------------- 工程成员身份
#
# v5.21 调研 L-21-03：夹具账号只有全局角色、**不是任何工程的成员**，于是
# 项目级接口（用例/用例集/资产）对 pm/engineer 也一律 403——「角色 + 真实工程」
# 这个组合从来没被验过（FR-v5.21-04 的 Q-3）。这里把三个夹具账号加进指定工程，
# 让任务级权限真的能被区分出来。
#
# 只加成员，不删不改：`POST /members` 命中已存在成员会 409，按"已就绪"处理。
#
# v5.23 收口：原先默认写死 29，而项目 29 已随演示工程瘦身被清除——写死 id 会静默把成员
# 加到不存在的工程上（`POST /members` 404，看起来像"加成员失败"，其实是工程没了）。
# 改为按 code 解析（`QA_SEED_PROJECT_CODE`，默认 demo）；`QA_SEED_PROJECT` 仍可显式指定。
SEED_PROJECT_CODE="${QA_SEED_PROJECT_CODE:-demo}"
resolve_seed_project() {
    if [[ -n "${QA_SEED_PROJECT:-}" ]]; then echo "$QA_SEED_PROJECT"; return 0; fi
    local out pid
    # `set -euo pipefail` 下：api 失败或 jq 无匹配都会让赋值语句返回非 0 → 整个脚本退出，
    # 这里显式兜底（曾因 `$(... | head -1)` 无匹配而中途终止）。
    out=$(api GET "/api/projects?page=1&size=100" 2>/dev/null || echo '{"data":[]}')
    pid=$(printf '%s' "$out" | jq -r --arg c "$SEED_PROJECT_CODE" \
        '.data[]? | select(.code == $c) | .id' 2>/dev/null | head -1 || true)
    if [[ -z "$pid" || "$pid" == "null" ]]; then
        pid=$(printf '%s' "$out" | jq -r '.data[0].id // empty' 2>/dev/null | head -1 || true)
    fi
    echo "${pid:-}"
    return 0
}
SEED_PROJECT="$(resolve_seed_project)"
if [[ -z "$SEED_PROJECT" || "$SEED_PROJECT" == "null" ]]; then
    echo "[seed][WARN] 找不到可用工程，跳过成员绑定（只建账号）" >&2
    SEED_PROJECT=""
else
    echo "[seed] 夹具工程 projectId=$SEED_PROJECT（code=$SEED_PROJECT_CODE）"
fi
seed_member() { # seed_member <username> <projectRole>
    local username="$1" role_code="$2"
    if [[ -z "$SEED_PROJECT" ]]; then
        echo "[seed][WARN] 无可用工程，跳过 $username 的成员绑定" >&2
        return 0
    fi
    if [[ $DRY_RUN -eq 1 ]]; then
        echo "[dry-run] 将把 $username 加入工程 $SEED_PROJECT（$role_code）"
        return
    fi
    local user_id
    user_id=$(api GET "/api/users?keyword=$username&page=1&size=50" \
        | jq -r --arg u "$username" '.data.items[] | select(.username == $u) | .id' | head -1)
    [[ -z "$user_id" ]] && return
    local resp
    resp=$(api POST "/api/projects/$SEED_PROJECT/members" \
        "{\"userId\":$user_id,\"roleCode\":\"$role_code\"}")
    if echo "$resp" | jq -e '.success == true' > /dev/null 2>&1; then
        echo "[seed] $username 已加入工程 $SEED_PROJECT（$role_code）"
    elif echo "$resp" | jq -e '.codeName == "CONFLICT"' > /dev/null 2>&1; then
        echo "[seed] $username 已是工程 $SEED_PROJECT 成员（跳过）"
    else
        echo "[seed][WARN] $username 加成员失败：$(echo "$resp" | jq -c '.message' 2>/dev/null)" >&2
    fi
}
seed_member "qa-e2e-pm" "pm"
seed_member "qa-e2e-engineer" "engineer"
seed_member "qa-e2e-viewer" "viewer"

echo "[seed] 完成；复核："
api GET "/api/users?page=1&size=50" \
    | jq -r '.data.items[] | select(.username | startswith("qa-e2e-")) | "  \(.username)\t\(.roles | join(","))"'
