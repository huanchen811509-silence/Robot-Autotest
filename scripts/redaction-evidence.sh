#!/usr/bin/env bash
# 重新生成全表面脱敏证据（FR-v4.0-01 / TC-v4.0-03）。
#
# 证据由测试产出而不是手写 JSON：手写只能证明「我以为它会这样」，
# 测试里出的是「实现当下确实这样」。改了规则没重跑，门禁读到的是过期事实。
#
# 用法：bash scripts/redaction-evidence.sh
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT="$ROOT/docs/evidence/v4.0.0/EW40-01/redaction-cases.json"

cd "$ROOT/backend"
mvn -o -B -q -Dtest=SecretRedactorTest,SecretRedactorEvidenceTest \
  -DfailIfNoTests=false test

mkdir -p "$(dirname "$OUT")"
cp target/evidence/redaction-cases.json "$OUT"

python3 "$ROOT/tools/check_case_command.py" >/dev/null
echo "REDACTION_EVIDENCE $OUT"
