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
# 本脚本共 32 步：01–17 为 run 步，18 为末尾 structure_selfcheck 内联块，19 为证据完整性 run 步，
# 20–23 为 v3.2.0 的契约 / 包体 / 性能预算 / 版本一致性门禁。
# 24–27 为 v3.3.0+v3.4.0 的 AI 评测 / 安全扫描 / 容量+备份演练 / i18n+对比度门禁。
# 28 为 v3.9.0 的用例级命令契约（FR-v3.9-02：三列贯通 + 双内核真实 run 覆盖率 100%）。
# 29 为 v3.11.0 的主机清单（FR-v3.11-01：10 类齐全 + 只读命令白名单 + 未采集必有原因）。
# 30 为 v3.11.0 交付能力（FR-v3.11-D05）：安装/拆卸/运维脚本 + 交付包 + CI workflow 静态契约，
#     并校验真实 tarball 或入库的 bundle-manifest.json 证据，二者皆无判红。
# 31 为 v4.0.0 更新机制（FR-v4.0-05）：update.sh 安全默认态 / 降级护栏 / 自动回滚 / 演练版本化。
# 32 为 v4.1.0 工作区目录（FR-v4.1-01/07）：包契约 + 生命周期属主（KEEP_DIRS / backup / 只读报错）。
# 结构自检：若步骤 13 / 14 / 17 / 19 / 20 / 21 / 22 / 23 / 24 / 25 / 26 / 27 / 28 / 29 / 30 / 31 / 32 被删除，
# check_structure 必须变红（`--selfcheck` 会实证 13/14、20/23、24/27 与新增的 28、29、30、31、32）。
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
  grep -qE '^run 20 api_contract ' "$file" || { echo "STRUCT_FAIL 缺少步骤 20 api_contract"; bad=1; }
  grep -qE '^run 21 bundle_budget ' "$file" || { echo "STRUCT_FAIL 缺少步骤 21 bundle_budget"; bad=1; }
  grep -qE '^run 22 perf_budget ' "$file" || { echo "STRUCT_FAIL 缺少步骤 22 perf_budget"; bad=1; }
  grep -qE '^run 23 version_consistency ' "$file" || { echo "STRUCT_FAIL 缺少步骤 23 version_consistency"; bad=1; }
  grep -qE '^run 24 ai_eval ' "$file" || { echo "STRUCT_FAIL 缺少步骤 24 ai_eval"; bad=1; }
  grep -qE '^run 25 security_scan ' "$file" || { echo "STRUCT_FAIL 缺少步骤 25 security_scan"; bad=1; }
  grep -qE '^run 26 capacity_backup ' "$file" || { echo "STRUCT_FAIL 缺少步骤 26 capacity_backup"; bad=1; }
  grep -qE '^run 27 i18n_contrast ' "$file" || { echo "STRUCT_FAIL 缺少步骤 27 i18n_contrast"; bad=1; }
  grep -qE '^run 28 case_command ' "$file" || { echo "STRUCT_FAIL 缺少步骤 28 case_command"; bad=1; }
  grep -qE '^run 29 host_inventory ' "$file" || { echo "STRUCT_FAIL 缺少步骤 29 host_inventory"; bad=1; }
  grep -qE '^run 30 delivery_bundle ' "$file" || { echo "STRUCT_FAIL 缺少步骤 30 delivery_bundle"; bad=1; }
  grep -qE '^run 31 update_mechanism ' "$file" || { echo "STRUCT_FAIL 缺少步骤 31 update_mechanism"; bad=1; }
  grep -qE '^run 32 workspace_bundle ' "$file" || { echo "STRUCT_FAIL 缺少步骤 32 workspace_bundle"; bad=1; }
  return "$bad"
}

if [ "${1:-}" = "--selfcheck" ]; then
  echo '== 13/14/28/29 负向自检 =='
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
  # 28 的判据负向自证：干净数据必须绿、五类脏数据（覆盖率不足 / 非法来源 / 缺内核 /
  # 历史 NULL 行 / 抽样为空）必须红；v4.0 起再加脱敏四类（残留口令 / 缺类样本 /
  # 误脱敏 / 干净证据）。只看「能判红」不够，恒定报错也会"看起来在守门"。
  if python3 tools/check_case_command.py --self-check; then
    echo 'SELFCHECK 28 PASS（命令契约五类 + 脱敏四类脏数据均判红、干净数据判绿）'
  else
    echo 'SELFCHECK 28 FAIL（命令契约判据失灵）'; FAILS=$((FAILS + 1))
  fi
  # 29 的判据负向自证：10 类齐全 / 顺序对 / 每项带命令与时间 / 未采集必有原因 /
  # 不填占位值 / status 与项数一致 / 只读命令白名单，七类脏数据必须红、干净数据必须绿。
  if python3 tools/check_host_inventory.py --self-check; then
    echo 'SELFCHECK 29 PASS（七类脏数据均判红、干净数据判绿）'
  else
    echo 'SELFCHECK 29 FAIL（主机清单判据失灵）'; FAILS=$((FAILS + 1))
  fi
  # 30 的判据负向自证：删脚本 / 去 --apply 护栏 / 拆卸出现 DROP / CI 换回 self-hosted /
  # 凭据模板塞明文 / 入口 unit 丢 ENTRY_DIST / ctl 丢托管标记 / 产物与证据都缺失，
  # 八类脏数据必须红、干净骨架必须绿。
  if python3 tools/check_delivery_bundle.py --self-check; then
    echo 'SELFCHECK 30 PASS（八类脏数据均判红、干净骨架判绿）'
  else
    echo 'SELFCHECK 30 FAIL（交付包判据失灵）'; FAILS=$((FAILS + 1))
  fi
  # 31 的判据负向自证：去 dry-run 默认 / 去二次确认 / 去 DB 备份 / 去自动回滚 /
  # 删演练脚本 / 演练不留临时 prefix / status 丢上一可用版本 / 文档脱钩，八类脏数据必须红。
  if python3 tools/check_update_mechanism.py --self-check; then
    echo 'SELFCHECK 31 PASS（八类脏数据均判红、干净骨架判绿）'
  else
    echo 'SELFCHECK 31 FAIL（更新机制判据失灵）'; FAILS=$((FAILS + 1))
  fi
  # 32 的判据负向自证：删目录 / 改文件 / 多文件 / 去签名 / 错密钥 / 明文凭据 /
  # 版本越界 / 仓库缺 ADR / 缺 down / 缺服务类 / KEEP_DIRS 丢 workspace /
  # backup 丢 workspace / 只读报错被抹平，十三类脏数据必须红。
  if python3 tools/check_workspace_bundle.py --self-check; then
    echo 'SELFCHECK 32 PASS（十三类脏数据均判红、干净骨架判绿）'
  else
    echo 'SELFCHECK 32 FAIL（工作区判据失灵）'; FAILS=$((FAILS + 1))
  fi
  tmp="$(mktemp -d)"
  sed '/^run 13 /d' "$SELF" > "$tmp/no13.sh"
  sed '/^run 14 /d' "$SELF" > "$tmp/no14.sh"
  sed '/^run 19 /d' "$SELF" > "$tmp/no19.sh"
  sed '/^run 20 /d' "$SELF" > "$tmp/no20.sh"
  sed '/^run 23 /d' "$SELF" > "$tmp/no23.sh"
  sed '/^run 24 /d' "$SELF" > "$tmp/no24.sh"
  sed '/^run 27 /d' "$SELF" > "$tmp/no27.sh"
  sed '/^run 28 /d' "$SELF" > "$tmp/no28.sh"
  sed '/^run 29 /d' "$SELF" > "$tmp/no29.sh"
  sed '/^run 30 /d' "$SELF" > "$tmp/no30.sh"
  sed '/^run 31 /d' "$SELF" > "$tmp/no31.sh"
  sed '/^run 32 /d' "$SELF" > "$tmp/no32.sh"
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
  if check_structure "$tmp/no20.sh"; then
    echo 'SELFCHECK 删步骤 20 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 20 PASS（删了变红）'
  fi
  if check_structure "$tmp/no23.sh"; then
    echo 'SELFCHECK 删步骤 23 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 23 PASS（删了变红）'
  fi
  if check_structure "$tmp/no24.sh"; then
    echo 'SELFCHECK 删步骤 24 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 24 PASS（删了变红）'
  fi
  if check_structure "$tmp/no27.sh"; then
    echo 'SELFCHECK 删步骤 27 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 27 PASS（删了变红）'
  fi
  if check_structure "$tmp/no28.sh"; then
    echo 'SELFCHECK 删步骤 28 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 28 PASS（删了变红）'
  fi
  if check_structure "$tmp/no29.sh"; then
    echo 'SELFCHECK 删步骤 29 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 29 PASS（删了变红）'
  fi
  if check_structure "$tmp/no30.sh"; then
    echo 'SELFCHECK 删步骤 30 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 30 PASS（删了变红）'
  fi
  if check_structure "$tmp/no31.sh"; then
    echo 'SELFCHECK 删步骤 31 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 31 PASS（删了变红）'
  fi
  if check_structure "$tmp/no32.sh"; then
    echo 'SELFCHECK 删步骤 32 FAIL（删了仍然绿）'; FAILS=$((FAILS + 1))
  else
    echo 'SELFCHECK 删步骤 32 PASS（删了变红）'
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

# v3.2.0 新增三步（编号续在 19 之后，不重排既有判据）：
# 20 OpenAPI 快照漂移：控制器改动必须同步 docs/contracts/openapi.json，否则契约门禁失效。
#    与 CoreApiContractTest 分工：这里判「端点/参数集合」漂移，单测判字段语义。
run 20 api_contract "python3 tools/gen_api_contract.py --check"

# 21 前端包体：主入口 ≤300 KiB / 单路由块 ≤150 KiB / 单路由加载总量 ≤300 KiB。
#    必须在 04 frontend_build 之后，否则扫的是旧 dist。
run 21 bundle_budget "python3 tools/check_bundle_budget.py"

# 22 性能预算：API p95/p99、长列表 fps、运行链时延/吞吐对账 tools/perf-budget.json。
#    --require：报告缺失直接失败，避免「忘了采样」被当成通过（EW32-01 报告已入库）。
run 22 perf_budget "python3 tools/check_perf_budget.py --report docs/evidence/v3.2.0/EW32-01/perf/perf-report.json --require"

# 23 版本一致性：VERSION / 后端构建产物 / CHANGELOG 两份副本 / openapi 快照 / 运行期关于页 /
#    入口托管的 /CHANGELOG.md 必须同版本。v3.2.0 真实发生过「VERSION 升了、关于页仍报 3.0.1」，
#    而 22 步全绿——本步把「部署了但没重建」变成红。--static 只做静态四副本对账。
run 23 version_consistency "python3 tools/check_version_consistency.py"

# 24 AI 黄金集评测（v3.3.0 / FR-3.3-08）：直接打后端真实端点，事实一致性 ≥95%、
#    严重幻觉 0、拒答必须明确、流式首字 ≤3s；默认 fake CLI 契约，不依赖外部模型。
run 24 ai_eval "python3 tools/ai_eval.py"

# 25 安全扫描（v3.4.0 / FR-3.4-03）：密钥 / SAST / 危险依赖，高危必须为 0。
run 25 security_scan "python3 tools/security_scan.py"

# 26 容量与灾备（v3.4.0 / FR-3.4-01、05）：实测行数/体积/索引与归档候选 EXPLAIN，
#    并校验 backup-drill.sh 写出的 RTO/RPO 证据。证据缺失直接红，不静默放行。
run 26 capacity_backup "python3 tools/capacity_report.py && python3 tools/check_backup_status.py"

# 27 无障碍 AA 与 i18n（v3.4.0 / FR-3.4-04）：中英键集相等、无占位缺失；
#    主题×强调色的正文/大字对比度全部达标。沿用两台已有检查器，避免重复判据。
run 27 i18n_contrast "python3 tools/check_i18n.py && python3 tools/check_contrast.py"

# 28 用例级命令契约（v3.9.0 / FR-v3.9-02）：四层三列贯通 + 双内核真实 run 覆盖率 100%。
#    判据分两段：静态契约（迁移/提取器/读取链/前端/导出任一层漏改即红）
#    + 证据 coverage.json（RF 与 pytest 各一条真实 run，覆盖率 100% 且无历史 NULL 行）。
#    证据缺失判红而不是放行：没有真实 run 支撑的覆盖率不算数。
run 28 case_command "python3 tools/check_case_command.py"

# 29 主机清单（v3.11.0 / FR-v3.11-01）：10 类采集齐全 + 命令白名单只读 + 未采集必有原因。
#    判据分三段：静态（采集器里的 COMMANDS 是唯一事实来源，含 10 类顺序与只读性质）
#    + 接口自述一致（GET /commands 的响应必须与源码逐字相同）
#    + 真实采集证据（<当版证据目录>/host-inventory.json）。
#    证据由 bash scripts/host-inventory-contract.sh 产出；没有一次真实采集就判红，
#    因为「10 类齐全」这句话只能由真机输出证明，不能由单测夹具代替。
#    证据目录按版本走（v4.12.0 起）：v4.12.0 给 10 类命令统一加了 `export LC_ALL=C;`
#    前缀，这是有意的行为变更——若继续比对 v3.11.0 的冻结快照，命令表一改就必然报
#    「接口命令与源码不一致」，把「命令表按设计演进」误判成「证据漂移」。
#    取当前版本目录；当前版本还没产出证据时回落到脚本默认目录（v3.11.0 那份）。
HI_VER="$(tr -d '[:space:]' < VERSION)"
HI_EVID="$(ls -d "docs/evidence/v${HI_VER}"/EW* 2>/dev/null | head -1)"
run 29 host_inventory "python3 tools/check_host_inventory.py ${HI_EVID:+--evidence-dir $HI_EVID}"

# 30 交付包（v3.11.0 / FR-v3.11-D05）：安装/拆卸/运维脚本 + CI workflow 静态契约，
#    并以真实 tarball 或入库的 bundle-manifest.json 作证据；两者皆无判红，
#    避免「脚本写了、门禁没接线、包根本没打出来」的假绿。
run 30 delivery_bundle "python3 tools/check_delivery_bundle.py"

# 31 更新机制（v4.0.0 / FR-v4.0-05）：update.sh 的安全默认态（dry-run / 二次确认 /
#    同版本与降级护栏 / 先备份后写盘 / 失败自动回滚 / 健康打后端 actuator / UPDATE-HISTORY
#    表头模板 / 保留 3 版 / RTO）、版本化演练脚本、status 的上一可用版本与最近结论。
run 31 update_mechanism "python3 tools/check_update_mechanism.py"

# 32 工作区目录（v4.1.0 / FR-v4.1-01/07）：契约文档 + ADR-4X-12..15 + V11_28 up/down 成对 +
#    TenantGuard 登记 workspace_job + 工作区服务类 + 生命周期属主（.gitignore / KEEP_DIRS /
#    backup 覆盖 / 只读文件系统明确报错）。--bundle 由 workspace-contract.sh 另跑真包。
run 32 workspace_bundle "python3 tools/check_workspace_bundle.py"

printf '\nGATES_RESULT FAILS=%s\n' "$FAILS"
exit "$FAILS"
