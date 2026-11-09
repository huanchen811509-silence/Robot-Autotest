# v3.9.0 测试报告（RA-TR-39）

| 项 | 内容 |
|---|---|
| 文档 | `05-TEST-REPORT.md` ｜ v3.9.0 ｜ 实测回写 |
| 上游 | `TEST-PLAN.md`（22 条 TC）／`PRD.md`（7 条 FR）／`DEV-PLAN.md`（7 个工作包） |
| 结论口径 | `PASS` 才算通过；`PARTIAL` 与 `BLOCKED` 均不得读作通过 |
| 证据根 | `docs/evidence/v3.9.0/`（索引见同目录 `README.md`） |
| 门禁批次 | `bash scripts/gates.sh` 28 步；门禁结论以 `docs/evidence/gates/logs/00-full-run.log` 为准 |

## 1. 逐条结果

| TC | 用例 | 状态 | 结论 / 实测读数 | 证据 |
|---|---|---|---|---|
| TC-v3.9-01 | 暗色主题全站下拉可读 | PASS | 12 个路由 27 个 `select`，`flagged=0`；控件底色 `rgb(26,38,52)` 不透明，前景对比 12.97，`option` 最低 4.67（判据 ≥4.5） | `EW39-01/dark-select-probe.json`，改前 `dark-select-probe-before.json`（4 页 10/10 flagged） |
| TC-v3.9-02 | 亮色主题不回归 | PASS | 同 12 路由 27 个控件 `flagged=0`，对比 15.68 | `EW39-01/light-select-probe.json` |
| TC-v3.9-03 | 注入白底浅字必须判红 | PASS | 注入 `bg=#fff / fg=#9aa7b4` → `contrast=2.45 判红=true`，`DARK_SELECT_SELFCHECK PASS` | 复跑命令见 §4 |
| TC-v3.9-04 | RF 命令提取（含子关键字 Log） | PASS | `CaseCommandExtractorTest` 16 条（RobotFramework 9 / Pytest 4 / Sanitize 3），含「命令写在子关键字 `Log` 消息里也能取到」 | surefire 报告 |
| TC-v3.9-05 | pytest 命令提取（stage stdout） | PASS | `PytestKernelTest` 9 条，`commandExtractedFromStageStdout` 取到 `$ iperf3 …` 与 `exit=0`；无命令用例得 `NONE` / `NONE` / `null` | surefire 报告 |
| TC-v3.9-06 | 三列贯通到读取链 | PASS | `ReportTreeServiceTest` 2 + `ReportIngestServiceTest` 5 + `ReportLogServiceTest` 6 + `DocxExporterTest` 8 全绿 | surefire 报告 |
| TC-v3.9-07 | 双内核真实 run 覆盖率 100% | PASS | RF `RUN-20261109133212-b70000` 7/7 `RF_KEYWORD`；pytest `RUN-20261109133212-b70001` 7/7 `STDOUT_EXTRACT`；两内核 7 条命令逐字相同 | `EW39-02/case-command-coverage.json` |
| TC-v3.9-08 | 门禁第 28 步判据可判红 | PASS | 干净数据 PASS；覆盖率不足 / 非法来源 / 缺内核 / 历史 NULL / 抽样为空 / 静态缺片段 六类脏数据全 RED，`SELFCHECK_RESULT FAILS=0` | `EW39-02/gate-28-case-command.log` |
| TC-v3.9-09 | 报告页命令行三态 | PASS | `SuiteTree.spec` 16 条：present 文案、历史行 `absent` 且无复制按钮、`NONE` 与 `null` 文案不同、超长折叠展开收回复位、复制成功/失败两态 | vitest 输出 |
| TC-v3.9-10 | 字段从接口贯穿到组件 | PASS | `ReportDetailView.spec` 10 条：`TC-001`→原文、`TC-002`→`NONE` | vitest 输出 |
| TC-v3.9-11 | 双内核截图可见命令 | PASS | RF 图注「RF 关键字」、pytest 图注「stdout 提取」，退出码 0/3 与库一致；`CMD-007` 超长命令显示「收起」 | `EW39-03/rf-report-command.png`、`EW39-03/pytest-report-command.png` |
| TC-v3.9-12 | 命令与源日志逐字一致（10 条） | PASS | RF 7 条 + pytest 3 条，`mismatches=0` | `EW39-03/verbatim-samples.json` |
| TC-v3.9-13 | 导出件含命令列 | PASS | 两份 docx 解包后 `下发命令` / `uname -r` / `ip -o link show` / `[exit=0]` / `[exit=3]` 全命中（exportId 29/30） | `EW39-03/export-command-column.log` |
| TC-v3.9-14 | 列表空白 ≤120px、页大小可控、计划页接分页 | PASS | `/cases` 337px→**36px**、`/runs` 231px→**36px**、`/assets` →**0px**；首屏行数按视口自适应（`/cases` 12 行、`/runs` 6 行）；计划页分页由单测钉住：60 条夹具 → 单页 **50 行**（`min(档位, 50)`）、翻页首行变 `计划-51` | `EW39-04/density-probe-before.json` / `density-probe-after.json`；`PlansView.spec` 2 条 |
| TC-v3.9-15 | 页大小档位与估算纯逻辑 | PASS | `useListPageSize.spec` 11 条（四档、记忆、非法值回落） | vitest 输出 |
| TC-v3.9-16 | 21 字段 4 卡 + 预置 + 必填锁定 | PASS | 实测 `fieldCount=21` / `cardCount=4` / `presetCount=4`（简版·标准·完整·审计）/ `lockedFields=21` | `EW39-04/density-probe-after.json` 的 `template` 段；`ReportTemplatesView.spec` 9 条 |
| TC-v3.9-17 | 模板页 ≤1 屏 | PASS | `scrollScreens=0.94`（改前 1.00），`mainScrollHeight=847 < viewportH=900` | 同上 |
| TC-v3.9-18 | 侧栏折叠可逆、命中自身 | PASS | 7 条判据全 PASS：按钮 `hit=svg`（命中自身）、首列 `56px`、`scrollW=56 ≤ clientW=55+1`、文字 `0/35` 隐藏而图标 `16/42` 仍在、再点还原 `200px`、文字 `9/9` 回来 | `EW39-06/side-toggle-probe.json` |
| TC-v3.9-19 | 删/改折叠逻辑必须判红 | PASS | 注入劣化后 `FAILS=5`（品牌区未藏、文字 `9/35` 仍在、不能还原），`[selfcheck] PASS` | 复跑命令见 §4 |
| TC-v3.9-20 | 关于页版本与 CHANGELOG 一致 | PASS | 关于页 `version=3.9.0`、「最近更新」4 条、首条 `v3.9.0-traceability（2026-11-09）`；版本迭代页 15 个版本、首条同版本、正文 880 字符 | `EW39-07/about-changelog-probe.json` |
| TC-v3.9-21 | 版本四副本一致 | PASS | `VERSION` / 运行期 `build-info`（`source=file`）/ 根 `CHANGELOG` / 入口托管 `CHANGELOG` / `openapi.json` 全 3.9.0 | `python3 tools/check_version_consistency.py` 输出 |
| TC-v3.9-22 | 迁移可回滚（down→up 真实演练） | PASS | `robot_it` 上执行 down → 三列全部消失；再执行 up → 三列回来且类型/可空性一致（`text/YES`、`varchar(24)/YES`、`int/YES`） | `EW39-02/down-up-rehearsal.log` |

## 2. 汇总

| 状态 | 条数 | 说明 |
|---|---:|---|
| PASS | 22 | 全部 TC 均有机检或真实链路证据 |
| PARTIAL | 0 | 无 |
| BLOCKED | 0 | 本版工作全部可在本机链路完成（命令演示 run 走 workstation-local，不需要 DUT） |
| FAIL | 0 | 无 |

横向回归：

| 层 | 读数 | 命令 |
|---|---|---|
| 后端全量单测 | **1262 条 / 0 失败 / 4 跳过** | `cd backend && mvn -o -B test` |
| 前端全量单测 | **76 文件 / 526 条全绿** | `cd frontend && npx vitest run` |
| 类型检查 | `vue-tsc --noEmit` 无输出 | `cd frontend && npx vue-tsc --noEmit` |
| 构建 | `vite build` 成功（10.93s） | `cd frontend && npm run build` |
| 门禁 | **28 步全 PASS（`FAILS=0`）** | `bash scripts/gates.sh` |

## 3. 差异与遗留

### 3.1 与 TEST-PLAN 的差异

| 项 | 差异 | 原因 |
|---|---|---|
| 新增 TC-v3.9-22 | 原计划 21 条，实跑 22 条 | 新增 `V11_24` 后 `DownScriptGuardTest` 报「up 58 / down 57」，补回滚脚本时顺手做了真实 down→up 演练；只做静态配对等于没验过 SQL，故立 TC 固化 |
| TC-v3.9-01 采集面扩大 | 原计划 4 页，实跑 12 路由 | 只测 4 页会漏掉 `/imports` 的 `.input` select（该页确有第 3 个控件） |
| TC-v3.9-16 判据来源 | 由 vitest 单测扩为「单测 + 真实探针读数」 | 单测只能证明渲染逻辑，字段数与锁定状态要以真实页面 computed 为准 |
| TC-v3.9-14 计划页判据 | 探针读到 `/plans` 的 `pager=false rows=0`，判据④改由单测证明 | 演示项目没有计划，分页条是 `v-if="plans.length > planRowsPerPage"`——「没计划所以没分页」与「没实现分页」在 DOM 上无法区分，探针在这里测不出结论 |

### 3.2 遗留（不阻塞本版，已排去向）

| 编号 | 内容 | 处置 |
|---|---|---|
| L-39-01 | pytest run 的 `run_artifact` 里 `kind=output_xml` 实际指向 `pytest-report.json` | 不阻塞（`locateCaseLog` 已按文件名判定并只对 `.xml` 兜底）；修 kind 属夹具清理，排 v3.10 |
| L-39-02 | RF 侧 `caseKey` 缺 `suitePath` 前缀时，每条用例自成一层套件，树上出现「套件名 = 用例名」的重复层级 | 观感问题，不影响命令/日志；排 v3.10 目录规则统一 |
| L-39-03 | `EvidenceLogWriter.SECRET` 只覆盖 `password=` / `token:` 这类键值写法，`-u user:pass` 位置式写法不脱敏 | 命令列扩大了暴露面；v3.12「命令复跑」上线前必须补规则，先记安全遗留 |
| L-39-04 | v3.5.0–v3.8.0 有完整版本文档，但 `CHANGELOG.md` / `VERSION` 一直停在 3.4.0 | 本版收口时一并补齐四条版本记录并把 `VERSION` 提到 3.9.0；口径来源为各版本 `05-TEST-REPORT.md` 的 TC 清单，未新增未发生的事实 |

### 3.3 门禁失败项

| 步骤 | 状态 | 说明 |
|---|---|---|
| 第 06 步 lint | 已修 | 首次全量跑报 4 处 px 不在白名单（`ReportTemplatesView.vue` 的 280/620/230/26），按「优先复用已有档位」收敛为 240/640/240/28，复跑 `check_tokens.py` 全 PASS |
| 其余 27 步 | PASS | 含第 13 步 Chrome CDP 浏览器巡检（本轮 CDP 通道正常，不再计入已知失败项） |

**结论：28 步 `FAILS=0`。**（同批次日志：`docs/evidence/gates/logs/00-full-run.log`）

## 4. 复跑命令

```bash
cd ~/robot-autotest
set -a; . ~/.config/robot-autotest/env; set +a

# 前端探针（需 :8001 入口 + :9222 CDP）
node scripts/dark-select-probe.mjs                 # 暗色
PROBE_THEME=light node scripts/dark-select-probe.mjs
node scripts/dark-select-probe.mjs --selfcheck     # 必须 DARK_SELECT_SELFCHECK PASS
node scripts/side-toggle-probe.mjs
node scripts/side-toggle-probe.mjs --selfcheck     # 必须 FAILS>0 且 [selfcheck] PASS
node scripts/about-changelog-probe.mjs
node scripts/about-changelog-probe.mjs --selfcheck
node scripts/density-probe.mjs

# 后端 / 契约
cd backend && mvn -o -B test -Dtest="CaseCommandExtractorTest*,PytestKernelTest,ReportTreeServiceTest,DocxExporterTest,PytestLogPersisterTest,ReportLogServiceTest,ReportIngestServiceTest"
cd .. && python3 tools/check_case_command.py && python3 tools/check_case_command.py --self-check
python3 tools/check_version_consistency.py

# 迁移回滚演练（只允许 _it 库）
bash scripts/migration-down-up-drill.sh V11_24__v3_9_case_command
```

## 5. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-09 | 初版：21 条 TC 实测回写 + 横向回归读数 |
| v1.1 | 2026-11-09 | 补 TC-v3.9-22（迁移 down→up 真实演练）；补 L-39-04（v3.5–v3.8 版本号未落）；补 §3.1 三条与计划差异；TC-v3.9-01 采集面由 4 页更正为 12 路由 |
| v1.2 | 2026-11-09 | 扩密度探针采集计划页 `pager/visibleRows`；实测 `pager=false rows=0`（演示项目无计划）后改为单测钉住判据④，补 `PlansView.spec` 2 条，前端全量 524 → 526 条 |
