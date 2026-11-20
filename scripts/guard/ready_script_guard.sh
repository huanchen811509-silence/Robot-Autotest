#!/usr/bin/env bash
# FR-READY 机检：用例脚本绑定可见性/筛选/预检/批量入集的真链路门禁。
#
# 判据（对应 TEST-PLAN TC-READY-15）：
#   A1 四态筛选：逐态 200 且四态 total 之和 = 全量
#   A2 非法 bindingState → 400
#   A3 预检清单结构完整 boundCount/unbound/notApplicableCount
#   A4 platform 项目 sync → 400（kind 守卫）
#   A5 binding-filter 端点已注册（对不存在集返回 404 而非 405）
#
# --self-test：注入一条假判据（把 A2 的期望 400 改成期望 200），必须判红；
#              用于证明机检不是"永远 PASS"的空壳。注入只改内存变量，不改文件。
set -uo pipefail
set -a; . "$HOME/.config/robot-autotest/env"; set +a

BASE="${APP_BASE:-http://127.0.0.1:8001/api}"
USER="qa-e2e-admin"
SELF_TEST=false
[ "${1:-}" = "--self-test" ] && SELF_TEST=true

FAILS=0
pass() { printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s :: %s\n' "$1" "$2"; FAILS=$((FAILS+1)); }

LOGIN=$(curl -s --noproxy '*' -X POST "$BASE/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$QA_E2E_PASSWORD\"}")
TOKEN=$(printf '%s' "$LOGIN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])' 2>/dev/null)
if [ -z "$TOKEN" ]; then echo "FAIL 登录取 token"; exit 1; fi
auth() { curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" "$@"; }

PID=$(auth "$BASE/projects" | python3 -c '
import sys,json
d=json.load(sys.stdin)["data"]
print(next(p["id"] for p in d if p.get("kind")=="pytest-suite"))' 2>/dev/null)
if [ -z "$PID" ]; then echo "FAIL 找 pytest-suite 项目"; exit 1; fi

# A1 四态之和=全量
ALL=$(auth "$BASE/projects/$PID/cases?page=1&size=1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["total"])')
SUM=0
for s in BOUND_VERIFIED BOUND_UNVERIFIED UNBOUND NOT_APPLICABLE; do
  n=$(auth "$BASE/projects/$PID/cases?page=1&size=1&bindingState=$s" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["total"])' 2>/dev/null)
  [ -n "$n" ] && SUM=$((SUM+n))
done
if [ "$SUM" = "$ALL" ]; then pass "A1 四态之和=全量 ($SUM=$ALL)"; else fail A1 "sum=$SUM all=$ALL"; fi

# A2 非法 bindingState 400（self-test 注入：期望改成 200，必判红）
EXPECT_BAD=400
if [ "$SELF_TEST" = true ]; then EXPECT_BAD=200; fi
BAD=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
  "$BASE/projects/$PID/cases?bindingState=BOGUS")
if [ "$BAD" = "$EXPECT_BAD" ]; then pass "A2 非法 bindingState=$BAD"; else fail A2 "got=$BAD expect=$EXPECT_BAD"; fi

# A3 预检清单结构
IDS=$(auth "$BASE/projects/$PID/cases?page=1&size=3" | python3 -c '
import sys,json
print(json.dumps([c["id"] for c in json.load(sys.stdin)["data"]["items"]]))')
PRE=$(curl -s --noproxy '*' -X POST -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"mode\":\"CASE\",\"caseIds\":$IDS}" \
  "$BASE/projects/$PID/runs/precheck-scripts")
if printf '%s' "$PRE" | python3 -c '
import sys,json
d=json.load(sys.stdin)["data"]
assert all(k in d for k in ("boundCount","unbound","notApplicableCount"))' 2>/dev/null; then
  pass "A3 预检清单结构完整"
else fail A3 "$PRE"; fi

# A4 platform kind 守卫
PLAT=$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" "-p$DB_PASSWORD" -N \
  -e "SELECT id FROM $DB_NAME.project WHERE kind='platform' LIMIT 1" 2>/dev/null | head -1)
if [ -n "$PLAT" ]; then
  PC=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $TOKEN" "$BASE/projects/$PLAT/pytest/bindings/sync" -d '{}')
  if [ "$PC" = "400" ]; then pass "A4 platform sync kind守卫 400"; else fail A4 "got=$PC"; fi
else fail A4 "查不到 platform id"; fi

# A5 binding-filter 端点已注册（不存在集 → 404 非 405）
BC=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"bindingState":"BOUND_VERIFIED"}' \
  "$BASE/projects/$PID/case-sets/99999999/items/binding-filter")
if [ "$BC" != "405" ]; then pass "A5 binding-filter 端点已注册 ($BC)"; else fail A5 "405 端点未注册"; fi

echo "----"
echo "FAILS=$FAILS"
if [ "$SELF_TEST" = true ]; then
  if [ "$FAILS" -gt 0 ]; then echo "SELF-TEST OK (注入判红生效)"; exit 0
  else echo "SELF-TEST BAD (注入未判红，机检是空壳)"; exit 1; fi
fi
[ "$FAILS" -eq 0 ] && exit 0 || exit 1
