#!/usr/bin/env bash
# 环境与拓扑页的真实数据夹具（FR-v3.11-02 / 03）。
#
# 这里不往库里塞假行：两次采集走真实的 POST /collect（在本机跑 10 条只读命令），
# 两份快照走真实的 POST /snapshots，内容取自真实 GET /devices 与 /links，
# 只额外带一个真实采集时间戳——因此两份快照的 diff 必然是「采集时点变了」，
# 不是人为编出来的差异。留下的行是真数据，供页面与探针读到非空态。
#
# 用法：bash scripts/env-fixtures.sh
# 输出：docs/evidence/v3.11.0/EW311-02/fixtures.json，末行 RESULT PASS fails=0
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

set -a
# shellcheck disable=SC1091
. "$HOME/.config/robot-autotest/env" 2>/dev/null || true
set +a

API="${API_BASE:-http://127.0.0.1:8081}"
PROJECT_ID="${PROJECT_ID:-29}"
OUT="${OUT:-docs/evidence/v3.11.0/EW311-02/fixtures.json}"
FAILS=0
pass() { echo "[PASS] $1"; }
fail() { echo "[FAIL] $1"; FAILS=$((FAILS + 1)); }
jq_py() { python3 -c "import json,sys;d=json.load(sys.stdin);print(eval(sys.argv[1],{'d':d}))" "$1"; }

TOKEN="$(curl -s --noproxy '*' -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"${QA_USER:-qa-e2e-admin}\",\"password\":\"${QA_E2E_PASSWORD:-}\"}" \
    | jq_py "d.get('data',{}).get('token') or ''")"
[[ -n "$TOKEN" ]] || { echo "[fixtures] 登录失败" >&2; exit 1; }
AUTH="Authorization: Bearer $TOKEN"
mkdir -p "$(dirname "$OUT")"

# ---- 1. 两次真实采集 ----
COLLECT_IDS=()
for i in 1 2; do
    CODE="$(curl -s --noproxy '*' -o /tmp/env-collect-$i.json -w '%{http_code}' -X POST \
        "$API/api/asset-host-inventory/collect" -H "$AUTH" -H 'Content-Type: application/json' \
        -d "{\"projectId\":$PROJECT_ID,\"host\":\"localhost\"}")"
    ID="$(jq_py "d.get('data',{}).get('id') or ''" < /tmp/env-collect-$i.json 2>/dev/null || echo '')"
    [[ "$CODE" == "200" && -n "$ID" ]] && pass "第 $i 次真实采集 HTTP 200，id=$ID" \
        || fail "第 $i 次采集返回 $CODE id='$ID'"
    COLLECT_IDS+=("$ID")
    sleep 1
done

# ---- 2. 真实资产 → 两份快照 ----
DEVICES="$(curl -s --noproxy '*' -H "$AUTH" "$API/api/projects/$PROJECT_ID/devices?page=1&size=100")"
LINKS="$(curl -s --noproxy '*' -H "$AUTH" "$API/api/projects/$PROJECT_ID/links?page=1&size=100")"
STAMP="$(date +%Y%m%d%H%M%S)"
ENV_NAME="V311-PROBE-${STAMP}"

make_snapshot() { # $1=序号
python3 - "$DEVICES" "$LINKS" "$ENV_NAME" "$1" <<'PY'
import json, sys
devices = json.loads(sys.argv[1]); links = json.loads(sys.argv[2])
name = sys.argv[3]; seq = sys.argv[4]
import datetime
payload = {
    "name": name,
    "profileType": "topology",
    "description": "真实资产快照 · 第 %s 次" % seq,
    "snapshotJson": json.dumps({
        "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "devices": [{"id": d["id"], "name": d["name"], "enabled": d["enabled"]}
                    for d in (devices.get("data") or {}).get("items", [])],
        "links": [{"id": l["id"], "name": l["name"], "enabled": l["enabled"]}
                  for l in (links.get("data") or {}).get("items", [])],
    }, ensure_ascii=False),
}
print(json.dumps(payload, ensure_ascii=False))
PY
}

SNAP_IDS=()
for i in 1 2; do
    BODY="$(make_snapshot "$i")"
    CODE="$(curl -s --noproxy '*' -o /tmp/env-snap-$i.json -w '%{http_code}' -X POST \
        "$API/api/projects/$PROJECT_ID/snapshots" -H "$AUTH" -H 'Content-Type: application/json' \
        -d "$BODY")"
    ID="$(jq_py "d.get('data',{}).get('id') or ''" < /tmp/env-snap-$i.json 2>/dev/null || echo '')"
    [[ "$CODE" == "200" && -n "$ID" ]] && pass "第 $i 份真实快照 HTTP 200，id=$ID（环境 $ENV_NAME）" \
        || { fail "第 $i 份快照返回 $CODE id='$ID'"; cat /tmp/env-snap-$i.json; }
    SNAP_IDS+=("$ID")
    sleep 1
done

# ---- 3. 列表与 diff ----
LIST="$(curl -s --noproxy '*' -H "$AUTH" \
    "$API/api/projects/$PROJECT_ID/snapshots?name=$ENV_NAME")"
N_SNAP="$(printf '%s' "$LIST" | jq_py "len(d.get('data') or [])" 2>/dev/null || echo 0)"
[[ "$N_SNAP" == "2" ]] && pass "快照列表查到 2 份（按环境名）" || fail "快照列表只有 $N_SNAP 份"

DIFF="$(python3 - "$LIST" <<'PY'
import json, sys
snaps = (json.loads(sys.argv[1]).get('data') or [])
snaps = sorted(snaps, key=lambda s: s['id'])
print(json.dumps({'before': snaps[0], 'after': snaps[1]}) if len(snaps) >= 2 else '{}')
PY
)"
DIFF_RESULT="$(curl -s --noproxy '*' -X POST \
    "$API/api/projects/$PROJECT_ID/snapshots/diff" -H "$AUTH" \
    -H 'Content-Type: application/json' -d "$DIFF")"
N_DIFF="$(printf '%s' "$DIFF_RESULT" | jq_py "len(d.get('data') or [])" 2>/dev/null || echo 0)"
PATHS="$(printf '%s' "$DIFF_RESULT" | jq_py "','.join(x.get('path','') for x in (d.get('data') or []))" 2>/dev/null || echo '')"
[[ "$N_DIFF" -ge 1 ]] && pass "两份真实快照 diff 出 $N_DIFF 项变化（$PATHS）" \
    || fail "两份快照 diff 无变化（$DIFF_RESULT）"

# ---- 4. 环境清单接口能看到新环境 ----
PROFILES="$(curl -s --noproxy '*' -H "$AUTH" "$API/api/projects/$PROJECT_ID/snapshots/profiles")"
HIT="$(printf '%s' "$PROFILES" | jq_py "sum(1 for p in (d.get('data') or []) if p.get('name')=='$ENV_NAME')" 2>/dev/null || echo 0)"
[[ "$HIT" == "1" ]] && pass "环境清单接口列出新环境（profiles GET 生效）" \
    || fail "环境清单里找不到 $ENV_NAME"

python3 - "$OUT" "$ENV_NAME" "${COLLECT_IDS[*]}" "${SNAP_IDS[*]}" "$N_DIFF" <<'PY'
import json, sys
out = {
    "generatedAt": __import__('datetime').datetime.now(__import__('datetime').timezone.utc).isoformat(),
    "environment": sys.argv[2],
    "collectIds": [int(x) for x in sys.argv[3].split() if x.isdigit()],
    "snapshotIds": [int(x) for x in sys.argv[4].split() if x.isdigit()],
    "diffItems": int(sys.argv[5]),
    "source": "真实 POST /collect 与 POST /snapshots；快照内容取自真实 GET /devices 与 /links",
}
open(sys.argv[1], 'w', encoding='utf-8').write(json.dumps(out, ensure_ascii=False, indent=2) + '\n')
PY
echo "[fixtures] -> $OUT"

if [[ "$FAILS" -gt 0 ]]; then echo "RESULT FAIL fails=$FAILS"; exit 1; fi
echo "RESULT PASS fails=0"
