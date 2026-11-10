#!/usr/bin/env bash
# 工作区包契约验证（FR-v4.1-01..04，真实 HTTP + 真库 + 真目录）。
#
# 为什么不用单测代替：单测验的是 writer/reader 分支，这里验的是**接口契约**——
# 请求体字段名、错误码、权限口径、真包落盘结构、篡改必拒、幂等、隔离区位置、审计留痕。
#
# 用法：bash scripts/workspace-contract.sh [export|import|levels|all]   # 默认 all
# 前置：后端已启动（含 /workspace 端点）、库可读、$HOME/.config/robot-autotest/env 有 QA 口令。
# 可选环境变量：WORKSPACE_ROOT（默认取 backend/workspace）、PROJECT_ID（默认 29）。
# 输出：逐条 [PASS]/[FAIL]/[SKIP]，末行 RESULT PASS/FAIL fails=N
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
REPO="$(pwd)"

set -a
# shellcheck disable=SC1091
. "$HOME/.config/robot-autotest/env" 2>/dev/null || true
set +a
DB_NAME="${DB_NAME:-robot_dev}"
# shellcheck disable=SC1091
source ./scripts/db-common.sh

API="${API_BASE:-http://127.0.0.1:8081}"
USER_NAME="${QA_USER:-qa-e2e-admin}"
VIEWER_NAME="${QA_VIEWER:-qa-e2e-viewer}"
NOBODY_NAME="${QA_NOBODY:-qa-e2e-nobody}"
PROJECT_ID="${PROJECT_ID:-29}"
MODE="${1:-all}"
WS_ROOT="${WORKSPACE_ROOT:-$REPO/backend/workspace}"
STAMP="$(date +%Y%m%d%H%M%S)"
FAILS=0
PASSES=0
SKIPS=0

pass() { echo "[PASS] $1"; PASSES=$((PASSES + 1)); }
fail() { echo "[FAIL] $1"; FAILS=$((FAILS + 1)); }
skip() { echo "[SKIP] $1"; SKIPS=$((SKIPS + 1)); }
pyjson() { python3 -c "import json,sys;d=json.load(sys.stdin);print(eval(sys.argv[1],{'d':d}))" "$1"; }

CODE="$(db_exec -D "$DB_NAME" -N -B -e "SELECT code FROM project WHERE id=$PROJECT_ID;" | head -n1)"
[[ -z "$CODE" ]] && { echo "[contract] 项目 $PROJECT_ID 不存在或没有 code" >&2; exit 1; }
BUNDLE_DIR="$WS_ROOT/$CODE"

cleanup_paths() { rm -rf "$WS_ROOT/$CODE-ct-tamper" "$WS_ROOT/$CODE-ct-sig" "$WS_ROOT/$CODE-ct-quar" 2>/dev/null || true; }
trap cleanup_paths EXIT

# ---- 登录与鉴权头 ----
login() {
    local name="$1"
    curl -s --noproxy '*' -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
        -d "{\"username\":\"$name\",\"password\":\"${QA_E2E_PASSWORD:-}\"}" \
        | pyjson "d.get('data',{}).get('token') or ''"
}
ADMIN_TOKEN="$(login "$USER_NAME")"
if [[ -z "$ADMIN_TOKEN" ]]; then
    echo "[contract] 登录失败：拿不到 token（检查 QA_E2E_PASSWORD）" >&2
    exit 1
fi
AUTH="Authorization: Bearer $ADMIN_TOKEN"
VIEWER_TOKEN="$(login "$VIEWER_NAME")"
NOBODY_TOKEN="$(login "$NOBODY_NAME")"
VIEWER_AUTH="Authorization: Bearer $VIEWER_TOKEN"
NOBODY_AUTH="Authorization: Bearer $NOBODY_TOKEN"

req() { # $1=method $2=path $3=body(可空) $4=auth(默认 AUTH)
    local method="$1" path="$2" body="${3:-}" auth="${4:-$AUTH}"
    local args=(-s --noproxy '*' -o /tmp/ws-ct-body.json -w '%{http_code}' -X "$method"
                "$API$path" -H "$auth")
    if [[ -n "$body" ]]; then
        args+=(-H 'Content-Type: application/json' -d "$body")
    fi
    curl "${args[@]}" | tee /tmp/ws-ct-code.txt
}
body_field() { pyjson "d.get('data',{}).get('$1')" < /tmp/ws-ct-body.json; }
error_code() { pyjson "d.get('codeName') or ''" < /tmp/ws-ct-body.json; }

export_project() {
    req POST "/api/projects/$PROJECT_ID/workspace/export" '{"granularity":"PROJECT"}'
}
export_level() { # $1=level $2=rootPath
    req POST "/api/projects/$PROJECT_ID/workspace/export" \
        "{\"granularity\":\"$1\",\"rootPath\":\"$2\"}"
}

# 读签名密钥（导出首启会落盘；也可由 WORKSPACE_SIGNING_KEY 注入）。
# 必须在首次导出之后调用：签名密钥由服务端首次签名时生成。
load_sign_key() {
    [[ -n "$SIGN_KEY" ]] && return 0
    if [[ -f "$WS_ROOT/.signing-key" ]]; then
        SIGN_KEY="$(tr -d '[:space:]' < "$WS_ROOT/.signing-key")"
    fi
}
SIGN_KEY="${WORKSPACE_SIGNING_KEY:-}"

refresh_manifest() { # $1=包目录：重算清单并用服务端密钥重签（仅用于本脚本的篡改/隔离样本）
    python3 - "$1" "$SIGN_KEY" <<'PY'
import hashlib, hmac, json, os, sys
root, key = sys.argv[1], sys.argv[2]
if len(key) != 64:
    raise SystemExit("签名密钥不可用（需要 64 位十六进制）")
files = []
for dirpath, _, filenames in os.walk(root):
    for name in filenames:
        p = os.path.join(dirpath, name)
        rel = os.path.relpath(p, root).replace(os.sep, "/")
        if rel == "manifest.json":
            continue
        data = open(p, "rb").read()
        files.append({"path": rel, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
files.sort(key=lambda x: x["path"])
m = json.load(open(os.path.join(root, "manifest.json"), encoding="utf-8"))
m.pop("signature", None)
m["files"] = files
canonical = json.dumps(m, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
sig = hmac.new(bytes.fromhex(key), canonical.encode("utf-8"), hashlib.sha256).hexdigest()
m["signature"] = {"algorithm": "HMAC-SHA256", "keyId": "server-local-v1", "value": sig}
json.dump(m, open(os.path.join(root, "manifest.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=2)
PY
}

preflight() { # $1=包相对路径 $2=strategy
    req POST "/api/projects/$PROJECT_ID/workspace/import/preflight" \
        "{\"bundlePath\":\"$1\",\"strategy\":\"$2\"}"
}

echo "[contract] api=$API db=$DB_NAME project=$PROJECT_ID code=$CODE root=$WS_ROOT mode=$MODE"

# =====================================================================
# export：结构、签名、篡改必拒、半途失败留痕、权限分流
# =====================================================================
run_export() {
    echo "[contract] ---- export ----"
    cleanup_paths

    local code; code="$(export_project)"
    [[ "$code" == "200" ]] && pass "E1 导出 PROJECT 返回 200" || fail "E1 导出返回 $code（$(error_code)）"
    [[ "$(body_field bundlePath)" == "$CODE" ]] && pass "E1 包路径=项目 code（$CODE）" \
        || fail "E1 包路径='$(body_field bundlePath)'（期望 $CODE）"
    [[ "$(body_field filesImported)" -gt 0 ]] && pass "E1 报告含文件数（$(body_field filesImported)）" \
        || fail "E1 报告文件数为 0"
    load_sign_key

    local missing=0 d
    for d in cases case-sets assets environments reports runs files; do
        [[ -d "$BUNDLE_DIR/$d" ]] || { fail "E2 缺目录 $d"; missing=$((missing + 1)); }
    done
    for f in README.txt project.json members.json manifest.json cases/cases.json \
             case-sets/case-sets.json assets/assets.json assets/credentials.json \
             environments/environments.json reports/reports.json runs/runs.json; do
        [[ -f "$BUNDLE_DIR/$f" ]] || { fail "E2 缺文件 $f"; missing=$((missing + 1)); }
    done
    [[ "$missing" -eq 0 ]] && pass "E2 契约目录/文件齐全"

    local gran root sig_kid
    gran="$(pyjson "d.get('granularity')" < "$BUNDLE_DIR/project.json")"
    root="$(pyjson "d.get('rootPath')" < "$BUNDLE_DIR/project.json")"
    sig_kid="$(pyjson "d.get('signature',{}).get('keyId')" < "$BUNDLE_DIR/manifest.json")"
    [[ "$gran" == "project" && "$root" == "None" ]] \
        && pass "E2 project.json granularity=project rootPath=null" \
        || fail "E2 project.json granularity='$gran' rootPath='$root'"
    [[ "$sig_kid" == "server-local-v1" ]] && pass "E2 manifest 有签名（keyId=$sig_kid）" \
        || fail "E2 manifest 缺签名"

    if [[ -n "$SIGN_KEY" ]]; then
        if python3 tools/check_workspace_bundle.py --bundle "$BUNDLE_DIR" --key "$SIGN_KEY" >/tmp/ws-ct-check.log 2>&1; then
            pass "E2 包结构 + HMAC 机检通过"
        else
            fail "E2 包机检失败：$(tail -n3 /tmp/ws-ct-check.log | tr '\n' ' ')"
        fi
    else
        skip "E2 拿不到签名密钥，跳过 HMAC 机检"
    fi

    # 篡改一个字节：清单 sha256 不再匹配
    cp -a "$BUNDLE_DIR" "$WS_ROOT/$CODE-ct-tamper"
    local sample
    sample="$(cd "$WS_ROOT/$CODE-ct-tamper" && find files -type f | head -n1)"
    if [[ -n "$sample" ]]; then
        printf ' ' >> "$WS_ROOT/$CODE-ct-tamper/$sample"
        code="$(preflight "$CODE-ct-tamper" SKIP)"
        [[ "$code" == "400" ]] && pass "E3 改字节 → 预检 400（$(error_code)）" \
            || fail "E3 改字节期望 400，实际 $code"
    else
        skip "E3 包内没有源文件，跳过改字节样本"
    fi

    # 篡改签名：值改一位
    if [[ -n "$SIGN_KEY" ]]; then
        cp -a "$BUNDLE_DIR" "$WS_ROOT/$CODE-ct-sig"
        python3 - "$WS_ROOT/$CODE-ct-sig/manifest.json" <<'PY'
import json, sys
p = sys.argv[1]
m = json.load(open(p, encoding="utf-8"))
v = m["signature"]["value"]
m["signature"]["value"] = ("0" if v[0] != "0" else "1") + v[1:]
json.dump(m, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
        code="$(preflight "$CODE-ct-sig" SKIP)"
        [[ "$code" == "400" ]] && pass "E3 改签名 → 预检 400（$(error_code)）" \
            || fail "E3 改签名期望 400，实际 $code"
    fi

    # 半途失败：任务已建，writer 拒绝越界路径 → 400 + job FAILED + 旧包不被破坏
    local before_sha; before_sha="$(sha256sum "$BUNDLE_DIR/manifest.json" | cut -d' ' -f1)"
    code="$(export_level FILE 'files/../../etc')"
    [[ "$code" == "400" ]] && pass "E4 越界 rootPath → 400" || fail "E4 越界 rootPath 期望 400，实际 $code"
    local job_status; job_status="$(db_exec -D "$DB_NAME" -N -B -e \
        "SELECT status FROM workspace_job WHERE project_id=$PROJECT_ID AND job_type='EXPORT' ORDER BY id DESC LIMIT 1;" | head -n1)"
    [[ "$job_status" == "FAILED" ]] && pass "E4 失败任务落 FAILED" \
        || fail "E4 最新导出任务状态='$job_status'（期望 FAILED）"
    [[ "$(sha256sum "$BUNDLE_DIR/manifest.json" | cut -d' ' -f1)" == "$before_sha" ]] \
        && pass "E4 失败未破坏旧包" || fail "E4 失败后旧包 manifest 变了"
    [[ -z "$(find "$WS_ROOT" -maxdepth 1 \( -name ".$CODE.tmp-*" -o -name ".$CODE.old-*" \) 2>/dev/null)" ]] \
        && pass "E4 无残留临时/回收目录" || fail "E4 残留 .tmp/.old 目录"
    local failed_audit; failed_audit="$(db_exec -D "$DB_NAME" -N -B -e \
        "SELECT COUNT(*) FROM audit_log WHERE project_id=$PROJECT_ID AND action='workspace:export' AND summary LIKE '%failed=true%' AND occurred_at >= NOW(3) - INTERVAL 10 MINUTE;" | head -n1)"
    [[ "${failed_audit:-0}" -ge 1 ]] && pass "E4 失败审计留痕" || fail "E4 缺失败审计"

    # 权限：viewer 可读导出；非成员 403
    if [[ -n "$VIEWER_TOKEN" ]]; then
        code="$(req GET "/api/projects/$PROJECT_ID/workspace/bundles" '' "$VIEWER_AUTH")"
        [[ "$code" == "200" ]] && pass "E5 viewer 可列出包（CASE:READ）" || fail "E5 viewer 列包返回 $code"
    else
        skip "E5 无 viewer 账号 token"
    fi
    if [[ -n "$NOBODY_TOKEN" ]]; then
        code="$(req POST "/api/projects/$PROJECT_ID/workspace/export" '{"granularity":"PROJECT"}' "$NOBODY_AUTH")"
        [[ "$code" == "403" ]] && pass "E5 非成员导出 403" || fail "E5 非成员导出返回 $code（期望 403）"
    else
        skip "E5 无 nobody 账号 token"
    fi
}

# =====================================================================
# import：预检、幂等、隔离区与确认、审计
# =====================================================================
run_import() {
    echo "[contract] ---- import ----"
    cleanup_paths
    export_project >/dev/null
    load_sign_key

    local code
    code="$(preflight "$CODE" SKIP)"
    [[ "$code" == "200" ]] && pass "I1 预检 200" || fail "I1 预检返回 $code（$(error_code)）"
    [[ "$(body_field ok)" == "True" ]] && pass "I1 预检判定可导入" || fail "I1 预检 ok='$(body_field ok)'"

    code="$(req POST "/api/projects/$PROJECT_ID/workspace/import" "{\"bundlePath\":\"$CODE\",\"strategy\":\"SKIP\"}")"
    [[ "$code" == "200" ]] && pass "I2 导入 200" || fail "I2 导入返回 $code（$(error_code)）"
    [[ "$(body_field filesImported)" == "0" && "$(body_field filesSkipped)" -gt 0 ]] \
        && pass "I2 首次重复导入全 SKIP（幂等）" \
        || fail "I2 imported=$(body_field filesImported) skipped=$(body_field filesSkipped)（期望 0 / >0）"

    code="$(req POST "/api/projects/$PROJECT_ID/workspace/import" "{\"bundlePath\":\"$CODE\",\"strategy\":\"SKIP\"}")"
    [[ "$code" == "200" && "$(body_field filesImported)" == "0" ]] \
        && pass "I2 再次导入仍全 SKIP" || fail "I2 再次导入 $code imported=$(body_field filesImported)"

    local job_status; job_status="$(db_exec -D "$DB_NAME" -N -B -e \
        "SELECT status FROM workspace_job WHERE project_id=$PROJECT_ID AND job_type='IMPORT' ORDER BY id DESC LIMIT 1;" | head -n1)"
    [[ "$job_status" == "SUCCESS" ]] && pass "I2 导入任务 SUCCESS" || fail "I2 最新导入任务状态='$job_status'"

    # 隔离区：复制包 + 加一个可疑脚本 + 重签
    cp -a "$BUNDLE_DIR" "$WS_ROOT/$CODE-ct-quar"
    mkdir -p "$WS_ROOT/$CODE-ct-quar/files"
    printf 'import os\nos.system("id")\n' > "$WS_ROOT/$CODE-ct-quar/files/evil_ws_ct.py"
    if ! refresh_manifest "$WS_ROOT/$CODE-ct-quar" 2>/tmp/ws-ct-sign.log; then
        fail "I3 无法重签隔离样本：$(cat /tmp/ws-ct-sign.log)"
        return
    fi
    code="$(preflight "$CODE-ct-quar" SKIP)"
    [[ "$code" == "200" && "$(body_field filesQuarantined)" -ge 1 ]] \
        && pass "I3 预检识别可疑脚本（隔离 $(body_field filesQuarantined)）" \
        || fail "I3 可疑脚本预检 $code quarantined=$(body_field filesQuarantined)"
    local db_rows; db_rows="$(db_exec -D "$DB_NAME" -N -B -e \
        "SELECT COUNT(*) FROM robot_file WHERE project_id=$PROJECT_ID AND path='evil_ws_ct.py';" | head -n1)"
    [[ "${db_rows:-0}" == "0" ]] && pass "I3 预检不写库" || fail "I3 预检写了 robot_file（$db_rows 行）"

    code="$(req POST "/api/projects/$PROJECT_ID/workspace/import" \
        "{\"bundlePath\":\"$CODE-ct-quar\",\"strategy\":\"SKIP\",\"confirmQuarantine\":false}")"
    [[ "$code" == "200" && "$(body_field filesQuarantined)" -ge 1 ]] \
        && pass "I3 导入未确认 → 隔离" \
        || fail "I3 未确认导入 $code quarantined=$(body_field filesQuarantined)"
    local qfile; qfile="$(find "$WS_ROOT/.quarantine/$CODE" -name evil_ws_ct.py 2>/dev/null | head -n1)"
    [[ -n "$qfile" ]] && pass "I3 隔离副本落到 .quarantine/<code>/<job>/" \
        || fail "I3 找不到隔离副本"
    db_rows="$(db_exec -D "$DB_NAME" -N -B -e \
        "SELECT COUNT(*) FROM robot_file WHERE project_id=$PROJECT_ID AND path='evil_ws_ct.py';" | head -n1)"
    [[ "${db_rows:-0}" == "0" ]] && pass "I3 隔离后未写库" || fail "I3 隔离后仍写库（$db_rows 行）"

    code="$(req POST "/api/projects/$PROJECT_ID/workspace/import" \
        "{\"bundlePath\":\"$CODE-ct-quar\",\"strategy\":\"SKIP\",\"confirmQuarantine\":true}")"
    [[ "$code" == "200" && "$(body_field filesImported)" -ge 1 ]] \
        && pass "I3 显式确认 → 写库并留 warning" \
        || fail "I3 确认导入 $code imported=$(body_field filesImported)"
    pyjson "'可疑' in ' '.join(d.get('data',{}).get('warnings') or [])" < /tmp/ws-ct-body.json \
        | grep -q True && pass "I3 确认导入带 warning" || fail "I3 确认导入缺 warning"
    db_rows="$(db_exec -D "$DB_NAME" -N -B -e \
        "SELECT COUNT(*) FROM robot_file WHERE project_id=$PROJECT_ID AND path='evil_ws_ct.py';" | head -n1)"
    [[ "${db_rows:-0}" -ge 1 ]] && pass "I3 确认后写库（$db_rows 行）" || fail "I3 确认后未写库"
    # 清理隔离样本（robot_file_version 引用 robot_file，先删版本再删文件）；
    # 确认导入后可能被用例引用，删不掉不影响契约结论
    db_exec -D "$DB_NAME" -e "DELETE v FROM robot_file_version v JOIN robot_file f ON f.id=v.robot_file_id WHERE f.project_id=$PROJECT_ID AND f.path='evil_ws_ct.py'; DELETE FROM robot_file WHERE project_id=$PROJECT_ID AND path='evil_ws_ct.py';" \
        >/dev/null 2>&1 || true
    rm -rf "$WS_ROOT/$CODE-ct-quar" "$WS_ROOT/.quarantine/$CODE" 2>/dev/null || true

    # 权限：viewer 可预检、不可真导入
    if [[ -n "$VIEWER_TOKEN" ]]; then
        code="$(req POST "/api/projects/$PROJECT_ID/workspace/import/preflight" \
            "{\"bundlePath\":\"$CODE\",\"strategy\":\"SKIP\"}" "$VIEWER_AUTH")"
        [[ "$code" == "200" ]] && pass "I4 viewer 可预检（CASE:READ）" || fail "I4 viewer 预检返回 $code"
        code="$(req POST "/api/projects/$PROJECT_ID/workspace/import" \
            "{\"bundlePath\":\"$CODE\",\"strategy\":\"SKIP\"}" "$VIEWER_AUTH")"
        [[ "$code" == "403" ]] && pass "I4 viewer 导入 403（IMPORT:EXECUTE）" \
            || fail "I4 viewer 导入返回 $code（期望 403）"
    fi

    # 审计（异步写，轮询最多 3s）
    local i=0 n=0
    while [[ "$i" -lt 15 ]]; do
        n="$(db_exec -D "$DB_NAME" -N -B -e \
            "SELECT COUNT(*) FROM audit_log WHERE project_id=$PROJECT_ID AND action='workspace:import' AND occurred_at >= NOW(3) - INTERVAL 10 MINUTE;" | head -n1)"
        [[ "${n:-0}" -ge 2 ]] && break
        sleep 0.2; i=$((i + 1))
    done
    [[ "${n:-0}" -ge 2 ]] && pass "I5 workspace:import 审计（$n 条）" || fail "I5 缺 workspace:import 审计"
    n="$(db_exec -D "$DB_NAME" -N -B -e \
        "SELECT COUNT(*) FROM audit_log WHERE project_id=$PROJECT_ID AND action='workspace:import-quarantine' AND occurred_at >= NOW(3) - INTERVAL 10 MINUTE;" | head -n1)"
    [[ "${n:-0}" -ge 1 ]] && pass "I5 workspace:import-quarantine 审计" || fail "I5 缺隔离审计"
}

# =====================================================================
# levels：PROJECT / DIR / FILE 三级传递与非法范围负向
# =====================================================================
run_levels() {
    echo "[contract] ---- levels ----"
    cleanup_paths
    local code first_file

    code="$(export_level DIR '')"
    [[ "$code" == "400" ]] && pass "L1 DIR 缺 rootPath → 400" || fail "L1 DIR 缺 rootPath 返回 $code"

    code="$(export_level DIR 'cases/cases.json')"
    [[ "$code" == "400" ]] && pass "L1 DIR 传文件路径 → 400" || fail "L1 DIR 传文件路径返回 $code"

    code="$(export_level DIR '/tmp')"
    [[ "$code" == "400" ]] && pass "L2 绝对路径 → 400" || fail "L2 绝对路径返回 $code"

    code="$(export_level DIR '../x')"
    [[ "$code" == "400" ]] && pass "L2 .. 越界 → 400" || fail "L2 .. 越界返回 $code"

    code="$(req POST "/api/projects/$PROJECT_ID/workspace/export" '{"granularity":"FOO"}')"
    [[ "$code" == "400" ]] && pass "L2 非法粒度 → 400" || fail "L2 非法粒度返回 $code"

    code="$(export_level DIR 'files')"
    local gran root
    gran="$(pyjson "d.get('granularity')" < "$BUNDLE_DIR/project.json")"
    root="$(pyjson "d.get('rootPath')" < "$BUNDLE_DIR/project.json")"
    [[ "$code" == "200" && "$gran" == "dir" && "$root" == "files" ]] \
        && pass "L3 DIR files/：granularity=dir rootPath=files" \
        || fail "L3 DIR files/ 返回 $code granularity='$gran' rootPath='$root'"
    local ncases nfiles
    ncases="$(pyjson "len(d.get('cases') or [])" < "$BUNDLE_DIR/cases/cases.json")"
    nfiles="$(find "$BUNDLE_DIR/files" -type f | wc -l | tr -d ' ')"
    [[ "$ncases" == "0" && "$nfiles" -gt 0 ]] && pass "L3 DIR 只带 files/（用例 0、源文件 $nfiles）" \
        || fail "L3 DIR 裁剪不对（用例 $ncases、源文件 $nfiles）"

    first_file="$(db_exec -D "$DB_NAME" -N -B -e \
        "SELECT path FROM robot_file WHERE project_id=$PROJECT_ID ORDER BY id LIMIT 1;" | head -n1)"
    if [[ -z "$first_file" ]]; then
        skip "L4 项目没有源文件，跳过 FILE 粒度"
    else
        code="$(export_level FILE "files/$first_file")"
        gran="$(pyjson "d.get('granularity')" < "$BUNDLE_DIR/project.json")"
        root="$(pyjson "d.get('rootPath')" < "$BUNDLE_DIR/project.json")"
        local mfiles
        mfiles="$(pyjson "len([f for f in d.get('files') if f.get('path','').startswith('files/')])" \
            < "$BUNDLE_DIR/manifest.json")"
        [[ "$code" == "200" && "$gran" == "file" && "$root" == "files/$first_file" && "$mfiles" == "1" ]] \
            && pass "L4 FILE 单文件：manifest 只带 $first_file" \
            || fail "L4 FILE 返回 $code gran='$gran' root='$root' 文件数=$mfiles"
    fi

    # 恢复正式包为 PROJECT 全量
    code="$(export_project)"
    [[ "$code" == "200" ]] && pass "L5 恢复 PROJECT 全量包 200" || fail "L5 恢复导出返回 $code"
}

case "$MODE" in
    export) run_export ;;
    import) run_import ;;
    levels) run_levels ;;
    all) run_export; run_import; run_levels ;;
    *) echo "用法: bash scripts/workspace-contract.sh [export|import|levels|all]" >&2; exit 2 ;;
esac

cleanup_paths
echo "RESULT $( [[ "$FAILS" -eq 0 ]] && echo PASS || echo FAIL ) passes=$PASSES fails=$FAILS skips=$SKIPS"
[[ "$FAILS" -eq 0 ]]
