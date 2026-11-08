#!/usr/bin/env bash
# B4 · 唯一门禁脚本（取代 docs/evidence/v1.0/{longhist,policy-20261106}/gates.sh 两份副本）
#
# 步骤编号是**判据的一部分**，不要重排：
#   01–12 沿用八步门禁 + 后补四项（见各步注释）
#   13 浏览器回归（真实 Chrome + :8001，1440 利用率 / 1280 无横滚 / 路由守卫 / JS 异常）
#   14 报告敏感扫描（EXTERNAL 导出件；无样本时只跑扫描器自校验）
#   15 CSS 变量引用体检（v2.1 ORCH-02）
#   16 四格式字段一致性（v2.1 TPLX-03，依赖 05 产物）
#   17 只读接口性能基线（W4/v3.0：P95 劣化 >10% 且 >30ms 即红）
#   18 结构自检（原第 17 步，编号顺延）
#   19 证据完整性（v2.2 CLOS-02：output.xml metadata 六必填 + 三处单测基线只增不减
#      + AI 契约机检（AI-06：零 prompt 文件开关 / 零硬编码凭据 / 零 L4-L5 自动提交））
#
# 本脚本共 19 步：01–17 为 run 步，18 为末尾 structure_selfcheck 内联块，19 为证据完整性 run 步。
# 结构自检：若步骤 13 / 14 / 17 / 19 被删除，脚本末尾的 check_structure 必须变红（`--selfcheck` 会实证 13/14）。
#
# 用法：
#   bash scripts/gates.sh              # 全量门禁，0 = 全 PASS
#   bash scripts/gates.sh --selfcheck  # 13/14 的负向自检（含「删步骤必变红」实证）
#
# 前置：source ~/.config/robot-autotest/env（DB_* / QA_E2E_PASSWORD）；Chrome 9222；:8001 可用。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

SELF="$0"
L=docs/evidence/gates/logs
mkdir -p "$L"
FAILS=0

# 门禁依赖的凭据不落仓库：有 env 文件就加载（已导出的环境变量优先）。
if [ -z "${DB_PASSWORD:-}" ] && [ -f "$HOME/.config/robot-autotest/env" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$HOME/.config/robot-autotest/env"
  set +a
fi

run() {
  local no="$1" name="$2" cmd="$3"
  local log="$L/$no-$name.log" t0=$SECONDS
  if bash -lc "$cmd" > "$log" 2>&1; then
    printf '  PASS  %s (%ss)\n' "$no-$name" "$((SECONDS - t0))"
  else
    printf '  FAIL  %s (%ss) -> %s\n' "$no-$name" "$((SECONDS - t0))" "$log"
    FAILS=$((FAILS + 1))
  fi
}

check_structure() {
  local file="$1" bad=0
  grep -qE '^run 13 browser_regression ' "$file" || { echo "STRUCT_FAIL 缺少步骤 13 browser_regression"; bad=1; }
  grep -qE '^run 14 report_sensitive ' "$file" || { echo "STRUCT_FAIL 缺少步骤 14 report_sensitive"; bad=1; }
  grep -qE '^run 17 perf_baseline ' "$file" || { echo "STRUCT_FAIL 缺少步骤 17 perf_baseline"; bad=1; }
  grep -qE '^run 19 evidence_integrity ' "$file" || { echo "STRUCT_FAIL 缺少步骤 19 evidence_integrity"; bad=1; }
  return "$bad"
}

if [ "${1:-}" = "--selfcheck" ]; then
  echo '== 13/14 负向自检 =='
  if node scripts/browser-regression.mjs --selfcheck; then
    echo 'SELFCHECK 13 PASS（注入横滚被判红）'
  else
    echo 'SELFCHECK 13 FAIL（检测器未判红）'; FAILS=$((FAILS + 1))
  fi
  if python3 tools/check_report_sensitive.py --self-check; then
    echo 'SELFCHECK 14 PASS（黑样本命中、白样本 0 命中）'
  else
    echo 'SELFCHECK 14 FAIL（扫描器判据失灵）'; FAILS=$((FAILS + 1))
  fi
  tmp="$(mktemp -d)"
  sed '/^run 13 /d' "$SELF" > "$tmp/no13.sh"
  sed '/^run 14 /d' "$SELF" > "$tmp/no14.sh"
  sed '/^run 19 /d' "$SELF" > "$tmp/no19.sh"
  if check_structure "$tmp/no13.sh"; then
    echo 'SELFCHECK 删步骤 13 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 13 PASS（删了变红）'
  fi
  if check_structure "$tmp/no14.sh"; then
    echo 'SELFCHECK 删步骤 14 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 14 PASS（删了变红）'
  fi
  if check_structure "$tmp/no19.sh"; then
    echo 'SELFCHECK 删步骤 19 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 19 PASS（删了变红）'
  fi
  rm -rf "$tmp"
  printf '\nSELFCHECK_RESULT FAILS=%s\n' "$FAILS"
  exit "$FAILS"
fi

run 01 subset_font     "python3 tools/subset_font.py"
run 02 frontend_test   "cd frontend && npx vitest run"
run 03 type_check      "cd frontend && npx vue-tsc --noEmit"
run 04 frontend_build  "cd frontend && npx vite build"
run 05 backend_test    "cd backend && mvn -o -B test"
run 06 lint            "bash scripts/lint.sh"
run 07 release_docs    "python3 tools/check_release_docs.py"
run 08 wbs_consistency "python3 tools/check_wbs_consistency.py"
run 09 md_tables       "python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py"
run 10 diff_check      "git diff --check"
# 编辑器包有独立的 vitest 工程（unplugin-vue + jsdom），frontend 的 vitest 扫不到它。
# EDT-03/EDT-05 的回归用例住在这里，不接进来就会出现「门禁全绿但编辑器回归没人跑」。
run 11 editor_test     "cd packages/editor && npx vitest run"
# 库结构体检（TC-v0.1-023/024）。显式指向 robot_dev：其它步骤会把 DB_NAME 覆盖成 robot_it。
run 12 db_audit        "DB_NAME=robot_dev bash scripts/db-audit.sh"
# 浏览器回归（B4 第 13 步）。组件单测测不到真实栅格 / 滚动条 / 路由守卫；
# 该步同时在 1440 断言内容区利用率 ≥ 78%、1280 断言无整页横滚、并收集 JS 异常。
# B8：再挑一个真实 finished run（有 test_result 且有 DUT）深检报告页的「对比 / 趋势」，
# 没有可用 run 时退回路由巡检（不判红），避免空库里门禁假红。
# 选样必须同时取出 project_id 并让浏览器会话绑定到它：报告详情/树/趋势/对比一律按
# projectId 取数，而复用的 Chrome profile 里可能残留上一次夹具的 current_project_id。
# 只传 RUN_ID 会命中「运行不存在」——页面根本没加载出趋势/对比，三项深检假红（2026-11-08 实测）。
# 需要 Chrome 9222 与 :8001（缺任一项就会 FAIL，属预期：门禁必须看得见真实页面）。
run 13 browser_regression "sample=\$(MYSQL_PWD=\"\$DB_PASSWORD\" mysql -h\"\$DB_HOST\" -P\"\$DB_PORT\" -u\"\$DB_USERNAME\" \"\$DB_NAME\" -N -B -e \"select r.run_id, r.project_id from run r join test_result t on t.run_id=r.id join project_member pm on pm.project_id=r.project_id and pm.user_id=(select id from app_user where username='qa-e2e-admin') where r.run_status='finished' and r.dut is not null group by r.id, r.run_id, r.project_id order by r.id desc limit 1\" 2>/dev/null); \
  read -r rid pid <<< \"\$sample\"; \
  if [ -n \"\$rid\" ] && [ -n \"\$pid\" ]; then echo \"[13] 对比/趋势深检 run=\$rid project=\$pid\"; REGRESS_REPORT_RUN=\"\$rid\" REGRESS_REPORT_PROJECT=\"\$pid\" REGRESS_SHOT_DIR=docs/evidence/gates/browser node scripts/browser-regression.mjs; \
  else echo '[13] 无可用 run，跳过对比/趋势深检'; node scripts/browser-regression.mjs; fi"
# 报告敏感扫描（B4 第 14 步）。优先扫 EXTERNAL 导出样本；无样本时至少跑判据自校验，
# 不让「没有样本」变成「没有判据」。EXTERNAL 只做内部扫描，不代表获准对外发送。
run 14 report_sensitive "sample=backend/target/evidence/external; \
  if ls \$sample/*.html \$sample/*.pdf \$sample/*.docx \$sample/*.xlsx >/dev/null 2>&1; then \
    python3 tools/check_report_sensitive.py \$sample/*.html \$sample/*.pdf \$sample/*.docx \$sample/*.xlsx; \
  else \
    echo 'SKIP 无 EXTERNAL 样本，改跑扫描器自校验'; python3 tools/check_report_sensitive.py --self-check; \
  fi"
# CSS 变量引用体检（v2.1 ORCH-02 起）。写错变量名不会报错：声明在计算期失效、
# 属性回落初始值，SVG 的 fill 初始值是黑色 —— 节点变黑块，而 vue-tsc / vitest / build 三关全绿。
run 15 css_vars        "python3 tools/check_css_vars.py"
# 四格式字段一致性（v2.1 TPLX-03 起）。样本由 05 步里的 ReportFourFormatDumpTest 用当前代码
# 现生成，所以本步必须排在 05 之后；样本缺失时脚本返回 2（与「不一致」的 1 区分）。
run 16 report_consistency "python3 tools/check_report_consistency.py \
  backend/target/evidence/four-formats/report.html \
  backend/target/evidence/four-formats/report.docx \
  backend/target/evidence/four-formats/report.xlsx \
  backend/target/evidence/four-formats/report.pdf"
# 只读接口性能基线（W4/v3.0）。五条 GET：审计列表 / 报告列表 / 报告详情 / 趋势 / 失败聚类。
# 与 docs/evidence/perf-baseline.json 的 P95 比对；劣化 >10% 且绝对增量 >30ms 才判红
# （30ms 是这台 WSL 宿主机的调度噪声下限）。基线缺失时本步写入基线并 PASS。
# 依赖 :8001 可用（与 13 同源）；报告数据为空时会明确报错，不会静默放行。
run 17 perf_baseline "python3 tools/perf_baseline.py"

# 结构自检：步骤 13 / 14 / 17 / 19 被删掉时，本检查是唯一会红的环节。
# 它放在 18 位；19 是后加的纯只读证据完整性复核，不覆盖任何既有步骤编号。
if check_structure "$SELF"; then
  printf '  PASS  18 structure_selfcheck\n'
else
  printf '  FAIL  18 structure_selfcheck\n'
  FAILS=$((FAILS + 1))
fi

# 证据完整性（v2.2 CLOS-02，第 19 步）。排在 18 之后：先结构自检，再跑纯标准库检查器。
# 三台检查器都带负向实证：当前版本 0 份 output.xml 时靠判据自校验兜底，绝不静默放行；
# 三处单测基线只增不减（floor 防下调），日志缺失 / 解析失败一律报错判红。
# 第三台 check_ai_contract.py 是 AI-06 的契约机检（零 prompt 文件开关、零硬编码凭据、
# 零 L4-L5 自动提交、假 CLI 夹具齐全）：它此前只在证据 README 里「独立执行」，
# 没进门禁就等于没有——2026-11-08 补接，避免 AI 契约回退无人发现。
run 19 evidence_integrity "python3 tools/check_output_metadata.py && python3 tools/check_test_baseline.py && python3 tools/check_ai_contract.py"

printf '\nGATES_RESULT FAILS=%s\n' "$FAILS"
exit "$FAILS"
