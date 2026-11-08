set -a; . /home/evergreen/.config/robot-autotest/env; set +a
BASE=http://127.0.0.1:8001
OUT=/home/evergreen/robot-autotest/docs/evidence/v3.0.1/v23/UI-02/raw
mkdir -p "$OUT"

login() {
  curl -s --noproxy '*' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$QA_E2E_PASSWORD\"}" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("data") or {}).get("token") or "")'
}

T_ADMIN=$(login qa-e2e-admin)
T_ENG=$(login qa-e2e-engineer)
T_NOBODY=$(login qa-e2e-nobody)
echo "ADMIN token_len=${#T_ADMIN} ENG token_len=${#T_ENG} NOBODY token_len=${#T_NOBODY}"

save() { # name url token extra
  curl -s --noproxy '*' -o "$OUT/$1.json" -w '%{http_code}' "$2" -H "Authorization: Bearer $3" > "$OUT/$1.status"
  echo "$1 HTTP=$(cat "$OUT/$1.status") bytes=$(stat -c%s "$OUT/$1.json")"
}

save cards-admin-7d-current77 "$BASE/api/projects/cards?window=7d&current=77" "$T_ADMIN"
save cards-admin-30d         "$BASE/api/projects/cards?window=30d&current=77" "$T_ADMIN"
save cards-admin-no-current  "$BASE/api/projects/cards" "$T_ADMIN"
save projects-admin          "$BASE/api/projects" "$T_ADMIN"
save cards-engineer-7d       "$BASE/api/projects/cards?window=7d&current=29" "$T_ENG"
save projects-engineer       "$BASE/api/projects" "$T_ENG"
save cards-nobody-7d         "$BASE/api/projects/cards?window=7d" "$T_NOBODY"

# 非法窗口
curl -s --noproxy '*' -o "$OUT/cards-bad-window.json" -w 'HTTP=%{http_code}\n' \
  "$BASE/api/projects/cards?window=90d" -H "Authorization: Bearer $T_ADMIN" | tee "$OUT/cards-bad-window.status"

echo "--- summary ---"
python3 - "$OUT" <<'PY'
import json, sys, pathlib
out = pathlib.Path(sys.argv[1])
def load(n):
    return json.loads((out / f"{n}.json").read_text(encoding="utf-8"))

for name in ["cards-admin-7d-current77", "cards-admin-30d", "cards-engineer-7d", "cards-nobody-7d"]:
    d = load(name)["data"]
    ids = [c["id"] for c in d]
    print(f"{name}: n={len(ids)} ids={ids}")

adm_cards = [c["id"] for c in load("cards-admin-7d-current77")["data"]]
adm_projects = [p["id"] for p in load("projects-admin")["data"]]
print("admin set_equal:", sorted(adm_cards) == sorted(adm_projects), "cards=", sorted(adm_cards), "projects=", sorted(adm_projects))

eng_cards = [c["id"] for c in load("cards-engineer-7d")["data"]]
eng_projects = [p["id"] for p in load("projects-engineer")["data"]]
print("engineer set_equal:", sorted(eng_cards) == sorted(eng_projects), "cards=", sorted(eng_cards), "projects=", sorted(eng_projects))
print("engineer sees 77:", 77 in eng_cards)

cur = [c["id"] for c in load("cards-admin-7d-current77")["data"] if c["isCurrent"]]
print("isCurrent ids:", cur)
no_cur = [c["id"] for c in load("cards-admin-no-current")["data"] if c["isCurrent"]]
print("isCurrent ids (current omitted):", no_cur)

for cid in (77, 29, 76, 75):
    row = next(c for c in load("cards-admin-7d-current77")["data"] if c["id"] == cid)
    print(f"card {cid}: caseCount={row['caseCount']} runCount={row['runCount']} passRateBp={row['passRateBp']} lastRunAt={row['lastRunAt']} deviceCount={row['deviceCount']} memberCount={row['memberCount']} myRole={row['myRole']}")

m7 = {c["id"]: c["runCount"] for c in load("cards-admin-7d-current77")["data"]}
m30 = {c["id"]: c["runCount"] for c in load("cards-admin-30d")["data"]}
print("30d vs 7d runCount diff:", {k: (m7.get(k), m30.get(k)) for k in m30 if m7.get(k) != m30.get(k)})

bad = load("cards-bad-window")
print("bad window body:", json.dumps(bad, ensure_ascii=False))
PY
