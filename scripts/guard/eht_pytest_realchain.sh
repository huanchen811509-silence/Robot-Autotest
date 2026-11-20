#!/usr/bin/env bash
#
# EHT pytest 真链路机检（W-EHT-10 / FR-EHT-06）
#
# 为什么需要它：v5.24.0 之前，"执行成功"只看退出码，证据链可以整段空着
# （pytest_case_step 0 行、判据 481/486 空）却照样 FINISHED。单元测试用假 fixture
# 也能全绿。所以放行判据必须是**真实 run + 真实库**的断言，而不是 mock 断言。
#
# 用法：
#   bash scripts/guard/eht_pytest_realchain.sh                  # 查最近一个有步骤的 run
#   bash scripts/guard/eht_pytest_realchain.sh --run-id 17081   # 查指定 run
#   bash scripts/guard/eht_pytest_realchain.sh --self-test      # 真注入→判红→还原→判绿
#
# 退出码：0 全绿；1 有红项；2 用法/环境错误。
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_ID=""
SELFTEST=0

while [ $# -gt 0 ]; do
  case "$1" in
    --run-id) RUN_ID="${2:-}"; shift 2 ;;
    --self-test) SELFTEST=1; shift ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

ENV_FILE="$HOME/.config/robot-autotest/env"
if [ ! -f "$ENV_FILE" ]; then
  echo "[FAIL] 缺少 $ENV_FILE（数据库连接配置）" >&2
  exit 2
fi
set -a; . "$ENV_FILE"; set +a
# shellcheck disable=SC1091
. "$ROOT/scripts/db-common.sh"

q() {
  "$DB_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USERNAME" \
    --password="$DB_PASSWORD" --database="$DB_NAME" --default-character-set=utf8mb4 \
    --batch --skip-column-names -e "$1" 2>/dev/null
}

PASS=0
FAIL=0
ok()  { echo "[PASS] $1"; PASS=$((PASS + 1)); }
bad() { echo "[FAIL] $1"; FAIL=$((FAIL + 1)); }

# ---------- 选 run ----------
if [ -z "$RUN_ID" ]; then
  RUN_ID="$(q "SELECT run_id FROM pytest_case_step ORDER BY id DESC LIMIT 1")"
fi
if [ -z "$RUN_ID" ] || [ "$RUN_ID" = "NULL" ]; then
  echo "[FAIL] 没有可检查的 run（pytest_case_step 全空 = 证据链从未落库）"
  exit 1
fi

# 注意两个 run_id 不能混：
#   run.id      数字主键，test_result / pytest_case_step 的 run_id 外键指向它；
#   run.run_id  业务串（如 EHT-REAL-HK-1795095861），用例库 artifacts/<run_id>/ 用它做目录名。
RUN_PK="$RUN_ID"
PROJECT_ID="$(q "SELECT project_id FROM run WHERE id=$RUN_PK LIMIT 1")"
RUN_CODE="$(q "SELECT run_id FROM run WHERE id=$RUN_PK LIMIT 1")"
if [ -z "$PROJECT_ID" ]; then
  echo "[FAIL] run 主键 $RUN_PK 不存在或不属于任何项目"
  exit 1
fi

echo "== EHT pytest 真链路机检 =="
echo "run_id=$RUN_PK run_code=$RUN_CODE project_id=$PROJECT_ID"
echo

# ---------- 断言 1：步骤非空 ----------
STEP_COUNT="$(q "SELECT COUNT(*) FROM pytest_case_step WHERE run_id=$RUN_ID")"
STEP_COUNT="${STEP_COUNT:-0}"
if [ "$STEP_COUNT" -gt 0 ]; then
  ok "过程记录非空：pytest_case_step ${STEP_COUNT} 条"
else
  bad "过程记录为空：pytest_case_step 0 条（回调没抽取或产物无语义步骤）"
fi

# ---------- 断言 2：四态之和 = 用例总数 ----------
TOTAL="$(q "SELECT COUNT(*) FROM test_result WHERE run_id=$RUN_ID")"
TOTAL="${TOTAL:-0}"
STATE_SUM="$(q "SELECT COALESCE(SUM(criteria_verdict='PASS'),0)
                      +COALESCE(SUM(criteria_verdict='FAIL'),0)
                      +COALESCE(SUM(criteria_verdict='MISSING'),0)
                      +COALESCE(SUM(criteria_verdict IS NULL),0)
                 FROM test_result WHERE run_id=$RUN_ID")"
STATE_SUM="${STATE_SUM:-0}"
if [ "$TOTAL" -gt 0 ] && [ "$STATE_SUM" = "$TOTAL" ]; then
  ok "四态平衡：$STATE_SUM = 用例总数 $TOTAL"
elif [ "$TOTAL" -eq 0 ]; then
  bad "用例结果为 0 行（空执行不得放行）"
else
  bad "四态不平衡：$STATE_SUM ≠ 用例总数 $TOTAL"
fi

# ---------- 断言 3：日志区间非空 ----------
LOG_OK="$(q "SELECT COUNT(*) FROM test_result
              WHERE run_id=$RUN_ID AND log_span IS NOT NULL AND log_span<>''")"
LOG_OK="${LOG_OK:-0}"
if [ "$LOG_OK" -gt 0 ]; then
  ok "日志区间非空：${LOG_OK}/${TOTAL} 条有用例级 log_span"
else
  bad "日志区间全空：log_span 一条都没有（报告无法定位过程）"
fi

# ---------- 断言 4：判据缺证据必须显性说明（不许静默） ----------
# fake/Plan-only 下没有 [判定] 是事实，允许 criteria_json 为空；
# 但**必须**在 criteria_missing 里写清原因。否则读者看到一个空列，无从判断。
SILENT="$(q "SELECT COUNT(*) FROM test_result
              WHERE run_id=$RUN_ID
                AND (criteria_json IS NULL OR criteria_json='')
                AND (criteria_missing IS NULL OR criteria_missing='')")"
SILENT="${SILENT:-0}"
if [ "$SILENT" -eq 0 ]; then
  ok "判据缺失均有说明：无静默空判据"
else
  bad "存在 $SILENT 条静默空判据（criteria_json 与 criteria_missing 同时为空）"
fi

# ---------- 断言 5：real 模式判据覆盖率必须 100% ----------
REPO_PATH="$(q "SELECT setting_value FROM project_setting
                 WHERE project_id=$PROJECT_ID AND setting_key='pytest.repo_path' LIMIT 1")"
PLAN_FILE=""
if [ -n "$REPO_PATH" ] && [ -n "$RUN_CODE" ]; then
  PLAN_FILE="$REPO_PATH/artifacts/$RUN_CODE/fake_plan.json"
fi

if [ -n "$PLAN_FILE" ] && [ -f "$PLAN_FILE" ]; then
  # fake / Plan-only：产物目录里有计划文件，说明本次没连设备。
  ok "fake 计划文件存在：artifacts/$RUN_CODE/fake_plan.json（本次为 Plan-only）"
  MODE=fake
else
  MODE=real
  CRIT_OK="$(q "SELECT COUNT(*) FROM test_result
                 WHERE run_id=$RUN_ID AND criteria_json IS NOT NULL AND criteria_json<>''")"
  CRIT_OK="${CRIT_OK:-0}"
  if [ "$TOTAL" -gt 0 ] && [ "$CRIT_OK" = "$TOTAL" ]; then
    ok "real 模式判据覆盖 100%：${CRIT_OK}/${TOTAL}"
  else
    bad "real 模式判据覆盖不全：${CRIT_OK}/${TOTAL}（真实执行必须每个用例都有结构化判据）"
  fi
fi

# ---------- 断言 6：报告快照确实装配过（不是查询期临时拼） ----------
SNAP_PLAN="$(q "SELECT COUNT(*) FROM test_report
                 WHERE project_id=$PROJECT_ID
                   AND snapshot_json LIKE '%\"plan\"%'
                   AND snapshot_json LIKE '%$RUN_CODE%'")"
SNAP_PLAN="${SNAP_PLAN:-0}"
if [ "$SNAP_PLAN" -gt 0 ]; then
  ok "报告快照含 plan 段：$SNAP_PLAN 份"
else
  # 还没生成报告时不算硬红，但要显性提示——报告未生成 ≠ 链路有问题。
  echo "[INFO] 尚未找到含 plan 段的 $RUN_CODE 报告快照（如未生成报告可忽略）"
fi

echo
echo "== 汇总：PASS=$PASS FAIL=$FAIL（mode=$MODE）=="

# ---------- self-test：真注入 → 判红 → 还原 ----------
if [ "$SELFTEST" -eq 1 ]; then
  echo
  echo "== self-test：注入一条静默空判据，断言必须变红，然后回滚 =="
  TARGET_ID="$(q "SELECT id FROM test_result WHERE run_id=$RUN_ID
                    AND criteria_json IS NOT NULL LIMIT 1")"
  if [ -z "$TARGET_ID" ]; then
    echo "[FAIL] self-test 无法执行：该 run 没有 criteria_json 非空的行可注入"
    exit 1
  fi
  # 事务内注入 + 统计 + 回滚：真实影响断言，但绝不落库。
  INJECTED="$(q "START TRANSACTION;
                 UPDATE test_result SET criteria_json=NULL, criteria_missing=NULL
                  WHERE id=$TARGET_ID;
                 SELECT COUNT(*) FROM test_result
                  WHERE run_id=$RUN_ID
                    AND (criteria_json IS NULL OR criteria_json='')
                    AND (criteria_missing IS NULL OR criteria_missing='');
                 ROLLBACK;")"

  AFTER="$(q "SELECT COUNT(*) FROM test_result
               WHERE run_id=$RUN_ID
                 AND (criteria_json IS NULL OR criteria_json='')
                 AND (criteria_missing IS NULL OR criteria_missing='')")"
  AFTER="${AFTER:-0}"

  if [ -n "$INJECTED" ] && [ "$INJECTED" -gt 0 ]; then
    echo "[PASS] 注入后断言判红：静默空判据 = $INJECTED"
  else
    echo "[FAIL] 注入后断言未判红——检查逻辑对缺证据不敏感"
    FAIL=$((FAIL + 1))
  fi
  if [ "$AFTER" = "$SILENT" ]; then
    echo "[PASS] 回滚后数据还原：静默空判据回到 $AFTER"
  else
    echo "[FAIL] 回滚后数据不一致：期望 $SILENT，实际 $AFTER"
    FAIL=$((FAIL + 1))
  fi
fi

if [ "$FAIL" -eq 0 ]; then
  echo "RESULT: 全绿"
  exit 0
fi
echo "RESULT: 存在 $FAIL 项红"
exit 1
