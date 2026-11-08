OUT=/home/evergreen/robot-autotest/docs/evidence/v3.0.1/v23/UI-02/raw
python3 - "$OUT" <<'PY'
import json, pathlib, sys
out = pathlib.Path(sys.argv[1])
raw = json.loads((out / "cards-admin-7d-current77.json").read_text(encoding="utf-8"))
(out / "cards-admin-7d-current77.pretty.json").write_text(
    json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8")
data = raw["data"]
print("full_json_chars:", len(json.dumps(raw, ensure_ascii=False)))
print("first600:", json.dumps(raw, ensure_ascii=False)[:600])
print()
for c in data:
    print(f"id={c['id']:>3} code={c['code']:<26} enabled={str(c['enabled']):<5} vis={c['visibility']:<8} "
          f"case={c['caseCount']:>4} run={c['runCount']:>3} bp={str(c['passRateBp']):>6} "
          f"last={str(c['lastRunAt']):<26} dev={c['deviceCount']} mem={c['memberCount']} role={c['myRole']} cur={c['isCurrent']}")
print()
print("keys:", list(data[0].keys()))
PY
