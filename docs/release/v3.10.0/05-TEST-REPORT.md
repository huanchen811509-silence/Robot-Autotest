# v3.10.0 测试报告（RA-TR-310）

| 项 | 内容 |
|---|---|
| 文档 | `05-TEST-REPORT.md` ｜ v3.10.0 ｜ 实测回写 |
| 上游 | `TEST-PLAN.md`（18 条 TC）／`PRD.md`（3 条 FR）／`DEV-PLAN.md`（3 个工作包） |
| 结论口径 | `PASS` 才算通过；`PARTIAL` 与 `BLOCKED` 均不得读作通过 |
| 证据根 | `docs/evidence/v3.10.0/`（索引见同目录 `README.md`） |
| 门禁批次 | `bash scripts/gates.sh`（B5 全量 29 步；前 28 步与 v3.10.0 收口时一致，第 29 步为 v3.11.0 新增、不重排既有编号） |

## 1. 逐条结果

| TC | 用例 | 状态 | 结论 / 实测读数 | 证据 |
|---|---|---|---|---|
| TC-v3.10-01 | 计划页首屏主操作 ≤ 8 | PASS | 实测 **9 → 5**；整页控件 64 → 30 | `EW310-01/controls-after.json` |
| TC-v3.10-02 | 每行控件 = 1、菜单项 ≥ 4 | PASS | 行内 **4 → 1**，「更多」菜单 4 项 | 同上 `rowControls` / `rowMenuItems` |
| TC-v3.10-03 | 冲突/跳过区默认收起、展开内容不变 | PASS | `conflictCollapsed=true` / `skippedCollapsed=true`；`PlansView.spec` 反向断言 | `EW310-01/controls-after.json` + vitest |
| TC-v3.10-04 | 「更多」含 3 个跳转且路由正确 | PASS | `PlansView.spec` 覆盖计划组 / 依赖图 / 返回运行列表 | vitest 输出 |
| TC-v3.10-05 | 16 项原功能逐项可达 + 旧用例全绿 | PASS | 功能对照表 16/16；`PlansView.spec` **21 条全绿**；首屏 `screens 2.63 → 1.18` | `EW310-01/feature-map.md` + `PLAN_CONTROLS PASS FAILS=0` |
| TC-v3.10-06 | 下拉列出 run、四段文案 | PASS | 100 条选项，首条 `runId · 时间 · 结论 · n 条用例` | `EW310-02/report-picker.json` |
| TC-v3.10-07 | 关键词过滤生效、无匹配空态 | PASS | 过滤到 1 条；`RUN-NOT-EXIST-ZZZ` 出现空态 | 同上 + `RunPicker.spec` |
| TC-v3.10-08 | 7d/30d 时间档边界 | PASS | `runPicker.spec` 注入 `NOW`：恰好 7 天前保留、早 1 ms 剔除；30d 同理 | `npx vitest run src/utils/runPicker.spec.ts`（10 条） |
| TC-v3.10-09 | 选中后标题自动带出 runId | PASS | `测试报告 RUN-…`；视图单测同步覆盖 | 探针 `criteria.③` + `TestReportsView.spec`（16 条） |
| TC-v3.10-10 | 主路径无手打框、高级可切回 | PASS | 默认 `manualInput=false`；切「高级」后出现、切回消失 | 同上 |
| TC-v3.10-11 | 失败态不得同时显示空态 | PASS | `RunPicker.spec` 6 条；`vi.clearAllMocks()` 置于 `beforeEach` 首位后全绿 | vitest 输出 |
| TC-v3.10-12 | 旧「按 run」路径零回归 | PASS | `testReportsApi.create` 三参签名未动；视图单测断言请求体 | `TestReportsView.spec` |
| TC-v3.10-13 | 按用例集取最近/指定 run | PASS | 契约① ② + `TestReportCaseSetIT`（真库）全 PASS | `EW310-03/contract.log` |
| TC-v3.10-14 | 冻结快照自包含 | PASS | 冻结 HTTP 200；删源 run 后渲染逐字不变 **566 字节**；快照含 runId | `EW310-03/snapshot-case-set.json` |
| TC-v3.10-15 | 列表展示来源类型 | PASS | 显示「按执行记录 / 用例集 #id」而非内部枚举名 | `TestReportsView.spec` |
| TC-v3.10-16 | V11_25 down→up 真实演练 | PASS | `test_report` 0→0 行；两列 down 后消失、up 后类型/可空性一致 | `EW310-03/down-up.log`（`RESULT PASS fails=0`） |
| TC-v3.10-17 | 横向回归 | PASS | 前端 **80 文件 / 567 条**；后端 **1262 条 / 0 失败 / 4 跳过**；`vue-tsc` 无输出；`check_i18n.py` 1607 键全通过；`vite build` 成功 | 见 §3 |
| TC-v3.10-18 | 门禁 28 步全 PASS | PASS | v3.10.0 收口时 28 步逐项 PASS；B5 最终 `bash scripts/gates.sh` **29 步全 PASS / `GATES_RESULT FAILS=0`**（第 29 步为 v3.11.0 追加，不重排既有编号） | `docs/evidence/v3.11.0/EW311-02/final-gates.log` |

## 2. 汇总

| 状态 | 条数 | 说明 |
|---|---:|---|
| PASS | 18 | 全部 TC 均有探针、单测、契约或真实演练证据 |
| PARTIAL | 0 | 无 |
| BLOCKED | 0 | 本版不需要真实 DUT |
| FAIL | 0 | 无 |

## 3. 横向回归读数

| 层 | 读数 | 命令 |
|---|---|---|
| 前端全量 | 80 文件 / 567 条全绿 | `cd frontend && npx vitest run` |
| 后端全量 | 1262 条 / 0 失败 / 4 跳过 | `cd backend && mvn -o -B test` |
| 类型检查 | `vue-tsc --noEmit` 无输出 | `cd frontend && npx vue-tsc --noEmit` |
| i18n | 1607 键，中英键集相等 | `python3 tools/check_i18n.py` |
| 构建 | `vite build` 成功 | `cd frontend && npx vite build` |

## 4. 实测推翻的假设（回写）

| 原假设 | 实测 | 处置 |
|---|---|---|
| 冻结版本号用「本报告 version + 1」不会撞号 | 同一 run 两份报告时撞 `uk_report_run_version`，契约⑥ HTTP 500 | 改为按 run 全局 `maxVersion + 1`，并加「同 run 第二份占 v2」断言复现现场（`DEV-PLAN.md` §5） |
| 演练脚本可直接复用到 V11_25 | 原脚本把 `test_result` / `command%` 写死，拿 V11_25 跑会「通过」一个它没看的表 | 重写为从 up 脚本解析目标表/列，加 `MODE=table` 支持 |
| 手打 runId 是「高级功能」 | 主路径上它才是主要失败点 | 降级为「高级」而非删除，保留归档 run 的入口 |

## 5. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-09 | B5 收口实测回写：18 条 TC 全 PASS，补「冻结版本号」真 bug 与两处取证脚本修正 |
| v1.1 | 2026-11-09 | 最终门禁回写：B5 全量门禁为 29 步 `FAILS=0`（第 29 步属 v3.11.0，不影响本版 28 步判据编号）；横向回归读数仍取自本版收口时日志，未用后续版本读数顶账 |
