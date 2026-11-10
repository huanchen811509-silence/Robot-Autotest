#!/usr/bin/env bash
# 主机清单契约验证（FR-v3.11-01 判据①②③④，真实 HTTP + 真库 + 真采一次本机）。
#
# 为什么不用单测代替：`HostInventoryCollectorTest` 喂的是**离线夹具**，它证明解析器
# 逻辑对，但证明不了「接口真的会采」「采出来的东西真的带命令和时间」「没采到的项真的
# 有原因」。这三件事只有真打一次接口、真的在本机跑一遍 10 条命令才知道。
#
# 做法：登录后在**开发库**里真实采集一次本机清单，然后逐条核对：
#   ① GET  /commands → 10 类齐全（判据④：命令清单要能被看到）
#   ② POST /collect  → 200，落库拿到 id
#   ③ GET  /{id}     → 10 类齐全、每项带 command + collectedAt（判据②）
#   ④ 未采集项必有原因；value 不许是占位值（判据③：不填假值）
#   ⑤ status 与「有值项数」一一对应（OK / PARTIAL / FAILED 不是随便填的）
#   ⑥ 列表接口不返回 spec_json（列表只给摘要），但摘要列与详情一致
#   ⑦ 采集只能显式 POST：GET /collect 必须 405（不存在「打开页面就采一次」）
#   ⑧ spec_hash 与库里的 spec_json 对得上（搬运过程没被改坏）
#   ⑨ 跨项目访问 403（漏登记租户表就会读出别人项目的机器）
#   ⑩ 远程采集负向（FR-v4.2-03）：远程 host 不带 credentialId / 不带 confirm，
#      必须在**连远端之前**就 400；判据是「三道门没走完，一条命令也不许发出去」
#
# 用法：bash scripts/host-inventory-contract.sh
# 输出：逐条 [PASS]/[FAIL]，末行 RESULT PASS/FAIL fails=N
#      并把真实响应导出到 docs/evidence/v3.11.0/EW311-01/（commands.json / host-inventory.json）
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

set -a
# shellcheck disable=SC1091
. "$HOME/.config/robot-autotest/env" 2>/dev/null || true
set +a
DB_NAME="${DB_NAME:-robot_dev}"
# shellcheck disable=SC1091
source ./scripts/db-common.sh

API="${API_BASE:-http://127.0.0.1:8081}"
USER_NAME="${QA_USER:-qa-e2e-admin}"
PROJECT_ID="${PROJECT_ID:-29}"
OUT_DIR="${OUT_DIR:-docs/evidence/v3.11.0/EW311-01}"
RECORD_ID=""
FAILS=0

pass() { echo "[PASS] $1"; }
fail() { echo "[FAIL] $1"; FAILS=$((FAILS + 1)); }
jq_py() { python3 -c "import json,sys;d=json.load(sys.stdin);print(eval(sys.argv[1],{'d':d}))" "$1"; }

mkdir -p "$OUT_DIR"
echo "[host-inventory] api=$API db=$DB_NAME project=$PROJECT_ID out=$OUT_DIR"

# ---- 0. 登录 ----
TOKEN="$(curl -s --noproxy '*' -X POST "$API/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"${QA_E2E_PASSWORD:-}\"}" \
    | jq_py "d.get('data',{}).get('token') or ''")"
if [[ -z "$TOKEN" ]]; then
    echo "[host-inventory] 登录失败：拿不到 token（检查 QA 账号口令）" >&2
    exit 1
fi
AUTH="Authorization: Bearer $TOKEN"

cleanup() {
    if [[ -n "$RECORD_ID" ]]; then
        db_exec -D "$DB_NAME" -e "DELETE FROM device_host_spec WHERE id=$RECORD_ID;" 2>/dev/null
    fi
}
trap cleanup EXIT

# ---- ① 命令白名单公开 ----
CODE="$(curl -s --noproxy '*' -o /tmp/hi-commands.json -w '%{http_code}' \
    -H "$AUTH" "$API/api/asset-host-inventory/commands?projectId=$PROJECT_ID")"
N_CMD="$(jq_py "len(d.get('data') or {})" < /tmp/hi-commands.json 2>/dev/null || echo 0)"
[[ "$CODE" == "200" && "$N_CMD" == "10" ]] \
    && pass "① 命令白名单公开：HTTP 200，返回 $N_CMD 类" \
    || fail "① 命令白名单返回 $CODE，类数 $N_CMD（期望 200 / 10）"
ORDER="$(jq_py "','.join((d.get('data') or {}).keys())" < /tmp/hi-commands.json 2>/dev/null || echo '')"
[[ "$ORDER" == "OS,KERNEL,CPU,MEMORY,DISK,GPU,NIC,IP,ROCE,ROUTE" ]] \
    && pass "① 分类顺序与 CATEGORIES 一致" \
    || fail "① 分类顺序为 '$ORDER'（期望 OS,KERNEL,...,ROUTE）"
cp /tmp/hi-commands.json "$OUT_DIR/commands.raw.json"
# 注意不能用 jq_py 导出：那是 print(eval(...))，输出的是 Python repr（单引号），
# 机检脚本 json.load 会直接失败。这里必须走 json.dump。
python3 -c "import json;d=json.load(open('/tmp/hi-commands.json',encoding='utf-8'));
json.dump(d.get('data') or {}, open('$OUT_DIR/commands.json','w',encoding='utf-8'),
          ensure_ascii=False, indent=2)" \
    && echo "[host-inventory] 命令白名单 -> $OUT_DIR/commands.json"

# ---- ② 真实采集（显式 POST；host 用 localhost 表示本机） ----
CODE="$(curl -s --noproxy '*' -o /tmp/hi-collect.json -w '%{http_code}' -X POST \
    "$API/api/asset-host-inventory/collect" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"projectId\":$PROJECT_ID,\"host\":\"localhost\"}")"
RECORD_ID="$(jq_py "d.get('data',{}).get('id') or ''" < /tmp/hi-collect.json 2>/dev/null || echo '')"
[[ "$CODE" == "200" && -n "$RECORD_ID" ]] \
    && pass "② 真实采集 HTTP 200，记录 id=$RECORD_ID" \
    || { fail "② 采集返回 $CODE，id='$RECORD_ID'"; cat /tmp/hi-collect.json; }

# ---- ③ 详情：10 类齐全 + 每项带命令与时间 ----
CODE="$(curl -s --noproxy '*' -o /tmp/hi-detail.json -w '%{http_code}' \
    -H "$AUTH" "$API/api/asset-host-inventory/$RECORD_ID?projectId=$PROJECT_ID")"
[[ "$CODE" == "200" ]] && pass "③ 详情 HTTP 200" || fail "③ 详情返回 $CODE"

# spec_json 是 JSON 字符串，解出来才是 10 类清单；导出成证据文件
python3 - <<'PY' > "$OUT_DIR/host-inventory.json" 2>/tmp/hi-export.err
import json, sys
raw = json.load(open('/tmp/hi-detail.json', encoding='utf-8'))
data = raw.get('data') or {}
spec = data.get('specJson') or '{}'
print(json.dumps(json.loads(spec), ensure_ascii=False, indent=2))
PY
if [[ -s "$OUT_DIR/host-inventory.json" ]]; then
    echo "[host-inventory] 清单 -> $OUT_DIR/host-inventory.json（$(wc -c < "$OUT_DIR/host-inventory.json") 字节）"
else
    fail "③ 无法从 specJson 解出清单：$(cat /tmp/hi-export.err)"
fi

DETAIL_CHECK="$(python3 - "$OUT_DIR/host-inventory.json" <<'PY'
import json, sys
spec = json.load(open(sys.argv[1], encoding='utf-8'))
items = spec.get('items') or []
cats = [i.get('category') for i in items]
with_cmd = sum(1 for i in items if (i.get('command') or '').strip())
with_time = sum(1 for i in items if (i.get('collectedAt') or '').strip())
collected = [i for i in items if i.get('value') is not None]
missing = [i for i in items if i.get('value') is None]
with_reason = sum(1 for i in missing if (i.get('reason') or '').strip())
placeholder = [i.get('category') for i in items
               if isinstance(i.get('value'), str) and i['value'].strip() in ('', '-', '未知', 'N/A')]
expected = 'FAILED' if not collected else ('OK' if len(collected) == len(items) else 'PARTIAL')
print(f"n={len(items)} with_cmd={with_cmd} with_time={with_time} "
      f"collected={len(collected)} missing={len(missing)} with_reason={with_reason} "
      f"placeholder={len(placeholder)} status={spec.get('status')} expected={expected} "
      f"cats={','.join(cats)}")
PY
)"
echo "[host-inventory] 详情统计: $DETAIL_CHECK"
getm() { printf '%s' "$DETAIL_CHECK" | grep -oE "(^| )$1=[^ ]*" | sed "s/.*$1=//"; }

[[ "$(getm n)" == "10" ]] && pass "③ 清单 10 类齐全" || fail "③ 清单只有 $(getm n) 类"
[[ "$(getm with_cmd)" == "10" ]] && pass "③ 每项都记了 command（判据②）" \
    || fail "③ 只有 $(getm with_cmd)/10 项记了 command"
[[ "$(getm with_time)" == "10" ]] && pass "③ 每项都记了 collectedAt（判据②）" \
    || fail "③ 只有 $(getm with_time)/10 项记了 collectedAt"
if [[ "$(getm missing)" == "0" ]] || [[ "$(getm with_reason)" == "$(getm missing)" ]]; then
    pass "④ 未采集项全部带原因（$(getm missing) 项未采集，$(getm with_reason) 项有原因）"
else
    fail "④ $(getm missing) 项未采集，只有 $(getm with_reason) 项写了原因"
fi
[[ "$(getm placeholder)" == "0" ]] && pass "④ 无占位值冒充采集结果（判据③）" \
    || fail "④ 出现占位值 $(getm placeholder) 处"
[[ "$(getm status)" == "$(getm expected)" ]] \
    && pass "⑤ status=$(getm status) 与 $(getm collected)/10 项有值一致" \
    || fail "⑤ status=$(getm status)，但按项数应为 $(getm expected)"

# ---- ⑥ 列表只给摘要，不返回 spec_json ----
CODE="$(curl -s --noproxy '*' -o /tmp/hi-list.json -w '%{http_code}' \
    -H "$AUTH" "$API/api/asset-host-inventory?projectId=$PROJECT_ID&limit=20")"
LIST_INFO="$(python3 - <<'PY'
import json
rows = (json.load(open('/tmp/hi-list.json', encoding='utf-8')).get('data') or [])
hit = next((r for r in rows if r.get('id')), None)
# 判「列表有没有把整份清单带出来」要看**值**：字段名一定会出现（null 也会序列化），
# 只看键名会把正常的 null 判成违规。
has_spec = int(any((r.get('specJson') or '').strip() for r in rows))
print(f"rows={len(rows)} has_spec={has_spec} "
      f"nic={hit.get('nicCount') if hit else ''} roce={hit.get('rocePorts') if hit else ''}")
PY
)"
echo "[host-inventory] 列表统计: $LIST_INFO"
lg() { printf '%s' "$LIST_INFO" | grep -oE "(^| )$1=[^ ]*" | sed "s/.*$1=//"; }
[[ "$(lg rows)" -ge 1 ]] && pass "⑥ 列表能查到刚采的记录（$(lg rows) 条）" || fail "⑥ 列表查不到记录"
[[ "$(lg has_spec)" == "0" ]] && pass "⑥ 列表 specJson 为 null（只给摘要，不拖全量清单）" \
    || fail "⑥ 列表把 spec_json 内容也返回了"
# 网口数取「接口条数」不是「NIC 这一项本身」（一项里装着十几个口）
DETAIL_NIC="$(python3 -c "import json;d=json.load(open('$OUT_DIR/host-inventory.json',encoding='utf-8'));print(sum(len(i['value']) for i in d['items'] if i['category']=='NIC' and isinstance(i.get('value'),list)))" 2>/dev/null || echo 0)"
DETAIL_ROCE="$(python3 -c "import json;d=json.load(open('$OUT_DIR/host-inventory.json',encoding='utf-8'));print(sum(len(i['value']) for i in d['items'] if i['category']=='ROCE' and isinstance(i.get('value'),list)))" 2>/dev/null || echo 0)"
[[ "$(lg nic)" == "$DETAIL_NIC" && "$(lg roce)" == "$DETAIL_ROCE" ]] \
    && pass "⑥ 摘要列与详情一致（nic=$DETAIL_NIC roce=$DETAIL_ROCE）" \
    || fail "⑥ 摘要列与详情不一致（列表 nic=$(lg nic) roce=$(lg roce)，详情 nic=$DETAIL_NIC roce=$DETAIL_ROCE）"

# ---- ⑦ 采集只能显式 POST ----
# 详情路由写成 `{id:\d+}` 之后，`/collect` 不再被它吃掉，GET 应稳定回 405。
CODE="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
    -H "$AUTH" "$API/api/asset-host-inventory/collect?projectId=$PROJECT_ID")"
[[ "$CODE" == "405" ]] && pass "⑦ GET /collect 返回 405（采集只能显式触发）" \
    || fail "⑦ GET /collect 返回 $CODE（期望 405；400 说明 {id} 路由把字面路径吃掉了）"

# ---- ⑧ 落库哈希与内容对得上 ----
HASH_INFO="$(db_exec -D "$DB_NAME" -N -B -e "SELECT spec_hash, SHA2(spec_json, 256),
    CHAR_LENGTH(spec_json) FROM device_host_spec WHERE id=$RECORD_ID;")"
H1="$(printf '%s' "$HASH_INFO" | awk '{print $1}')"
H2="$(printf '%s' "$HASH_INFO" | awk '{print $2}')"
[[ -n "$H1" && "$H1" == "$H2" ]] && pass "⑧ spec_hash 与 spec_json 实测 SHA-256 一致（${#H1} 位）" \
    || fail "⑧ spec_hash='$H1' 与实测 '$H2' 不一致"

# ---- ⑨ 跨项目访问必须 403 ----
# 注意：管理员账号跨项目放行是**设计如此**（AccessControlService 有注释），
# 所以这条判据必须用「非成员账号」来打，用管理员打出来的 200 不是漏洞也不是通过。
# qa-e2e-nobody 无任何全局角色、也不在项目 29 里 —— 它 200 才是真问题。
NOBODY_TOKEN="$(curl -s --noproxy '*' -X POST "$API/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"qa-e2e-nobody\",\"password\":\"${QA_E2E_PASSWORD:-}\"}" \
    | jq_py "d.get('data',{}).get('token') or ''")"
if [[ -z "$NOBODY_TOKEN" ]]; then
    fail "⑨ 非成员账号 qa-e2e-nobody 登录失败（拿不到 token，跨项目判据无法验证）"
else
    NB_AUTH="Authorization: Bearer $NOBODY_TOKEN"
    C1="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
        -H "$NB_AUTH" "$API/api/asset-host-inventory?projectId=$PROJECT_ID")"
    C2="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
        -H "$NB_AUTH" "$API/api/asset-host-inventory?projectId=999999")"
    [[ "$C1" == "403" ]] && pass "⑨ 非成员读本项目清单被拒（403）" \
        || fail "⑨ 非成员读项目 $PROJECT_ID 返回 $C1（期望 403）"
    [[ "$C2" == "403" ]] && pass "⑨ 不存在的项目一律 403（不泄露项目是否存在）" \
        || fail "⑨ 不存在的项目返回 $C2（期望 403）"
fi

# 采集记录只留证据文件，库里不堆：每次门禁都采一次的话，表会长得比用的人还快
db_exec -D "$DB_NAME" -e "DELETE FROM device_host_spec WHERE id=$RECORD_ID;" 2>/dev/null
RECORD_ID=""

# ---- ⑩ 远程采集负向（W42-03）：三道门没走完，一条命令也不许发出去 ----
# 这里**刻意不连任何远端**：两条用例都在 HostInventoryService.accessOf 里就被拦下。
# 用 10.0.0.9 这种不存在的地址，对照的是「真发出去会立刻失败」——但正确行为是根本发不出去。
W42_OUT_DIR="docs/evidence/v4.2.0/EW42-03"
mkdir -p "$W42_OUT_DIR"
NEG_LINES=/tmp/hi-neg-lines.txt
: > "$NEG_LINES"
neg() {  # $1=用例说明 $2=请求体 $3=期望 message 片段
    local code msg
    code="$(curl -s --noproxy '*' -o /tmp/hi-neg.json -w '%{http_code}' -X POST \
        "$API/api/asset-host-inventory/collect" -H "$AUTH" -H 'Content-Type: application/json' \
        -d "$2")"
    msg="$(python3 -c "import json;print(json.load(open('/tmp/hi-neg.json',encoding='utf-8')).get('message') or '')" 2>/dev/null || echo '')"
    if [[ "$code" == "400" && "$msg" == *"$3"* ]]; then
        pass "⑩ $1 → 400（$msg）"
    else
        fail "⑩ $1 → HTTP $code message='$msg'（期望 400 且含「$3」）"
    fi
    printf '%s\t%s\t%s\n' "$1" "$code" "$msg" >> "$NEG_LINES"
}
neg "远程 host 不带 credentialId" \
    "{\"projectId\":$PROJECT_ID,\"host\":\"10.0.0.9\"}" "credentialId"
neg "远程 host 不带 confirm=true" \
    "{\"projectId\":$PROJECT_ID,\"host\":\"10.0.0.9\",\"credentialId\":999999}" "confirm=true"

python3 - "$W42_OUT_DIR/remote-negative.json" <<'PY'
import json, sys, datetime
rows = []
for line in open('/tmp/hi-neg-lines.txt', encoding='utf-8'):
    label, code, msg = line.rstrip('\n').split('\t')
    rows.append({"case": label, "http": int(code), "message": msg})
payload = {
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "note": "FR-v4.2-03 远程采集负向：未过凭据/二次确认两道门前不得启动任何远端进程",
    "cases": rows,
}
json.dump(payload, open(sys.argv[1], 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
PY
echo "[host-inventory] 远程负向证据 -> $W42_OUT_DIR/remote-negative.json"

# ---- ⑪ 机检脚本对证据的判定（第 29 步真正的判据） ----
if python3 tools/check_host_inventory.py; then
    pass "⑪ check_host_inventory.py 判定通过（10 类 + 只读命令 + 远程硬约束 + 证据自洽）"
else
    fail "⑪ check_host_inventory.py 判定失败（详见上方输出）"
fi

if [[ "$FAILS" -gt 0 ]]; then
    echo "RESULT FAIL fails=$FAILS"
    exit 1
fi
echo "RESULT PASS fails=0"
