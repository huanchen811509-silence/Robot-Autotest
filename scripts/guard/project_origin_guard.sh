#!/usr/bin/env bash
# FR-ORIGIN 机检：项目来源标识与差异化删除权限的真链路门禁。
#
# 判据（对应 TEST-PLAN TC-ORIGIN-13）：
#   A1 6 项目 origin 值与 D3 一致（157=USER，其余=BUILTIN）
#   A2 BUILTIN 项目 purge → 409（self-test 注入期望改成 200，必判红）
#   A3 platform 项目 purge → 409
#   A4 USER 项目 purge-preview 只读（返回逐表清单且项目仍在）
#   A5 切换来源代码不匹配 → 400
#
# --self-test：注入一条假判据（把 A2 期望 409 改成期望 200），必须判红；
#              注入只改内存变量，不改文件/库。
set -uo pipefail
set -a; . "$HOME/.config/robot-autotest/env"; set +a

BASE="${APP_BASE:-http://127.0.0.1:8001/api}"
USER_NAME="qa-e2e-admin"
SELF_TEST=false
[ "${1:-}" = "--self-test" ] && SELF_TEST=true

FAILS=0
pass() { printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s :: %s\n' "$1" "$2"; FAILS=$((FAILS+1)); }

LOGIN=$(curl -s --noproxy '*' -X POST "$BASE/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$QA_E2E_PASSWORD\"}")
TOKEN=$(printf '%s' "$LOGIN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])' 2>/dev/null)
if [ -z "$TOKEN" ]; then echo "FAIL 登录取 token"; exit 1; fi
auth() { curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" "$@"; }

# A1 origin 值（卡片接口；platform 单例不进普通列表，只验可见的 5 个）
CARDS=$(auth "$BASE/projects/cards?window=7d")
if printf '%s' "$CARDS" | python3 -c '
import sys,json
d=json.load(sys.stdin)["data"]
m={c["code"]:c.get("origin") for c in d}
assert m.get("PRJ-PYT-202611-003")=="USER", m
assert all(v=="BUILTIN" for k,v in m.items() if k!="PRJ-PYT-202611-003"), m
' 2>/dev/null; then pass "A1 origin 值与 D3 一致"; else fail A1 "$CARDS"; fi

# A2 BUILTIN purge 409（self-test 注入）
EXPECT_BUILTIN=409
if [ "$SELF_TEST" = true ]; then EXPECT_BUILTIN=200; fi
BC=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X DELETE \
  -H "Authorization: Bearer $TOKEN" "$BASE/projects/77/purge")
if [ "$BC" = "$EXPECT_BUILTIN" ]; then pass "A2 BUILTIN purge=$BC"; else fail A2 "got=$BC expect=$EXPECT_BUILTIN"; fi

# A3 platform purge 409
PC=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X DELETE \
  -H "Authorization: Bearer $TOKEN" "$BASE/projects/88/purge")
if [ "$PC" = "409" ]; then pass "A3 platform purge=409"; else fail A3 "got=$PC"; fi

# A4 USER preview 只读：返回清单且项目仍在
PV=$(curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" \
  "$BASE/projects/157/purge-preview")
if printf '%s' "$PV" | python3 -c '
import sys,json
d=json.load(sys.stdin)["data"]
assert d.get("test_case")==69, d
' 2>/dev/null; then
  STILL=$(auth "$BASE/projects/cards?window=7d" | python3 -c 'import sys,json;print(any(c["id"]==157 for c in json.load(sys.stdin)["data"]))')
  if [ "$STILL" = "True" ]; then pass "A4 preview 只读且项目仍在"; else fail A4 "preview 后项目消失"; fi
else fail A4 "$PV"; fi

# A5 切换代码不匹配 400
CC=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X PUT \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"origin":"BUILTIN","confirmCode":"WRONG"}' "$BASE/projects/157/origin")
if [ "$CC" = "400" ]; then pass "A5 切换代码错=400"; else fail A5 "got=$CC"; fi

echo "----"
echo "FAILS=$FAILS"
if [ "$SELF_TEST" = true ]; then
  if [ "$FAILS" -gt 0 ]; then echo "SELF-TEST OK (注入判红生效)"; exit 0
  else echo "SELF-TEST BAD (注入未判红，机检是空壳)"; exit 1; fi
fi
[ "$FAILS" -eq 0 ] && exit 0 || exit 1
