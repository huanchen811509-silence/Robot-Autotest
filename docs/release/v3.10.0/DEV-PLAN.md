# v3.10.0 开发计划（RA-DVP-310）

> 上游：`docs/plan/v3.9+版本演进-开发与测试计划-20261109.md`。本文件是**发布口径**的 WBS 与验收命令。
> 执行顺序：B3（EW310-01 → 02 → 03）→ B5 收口（05-TEST-REPORT + 版本四副本 + 门禁）。

## 1. WBS 与验收命令

| WBS | 内容 | 改动面 | 验收命令 | 状态 |
|---|---|---|---|---|
| EW310-01 | 计划页信息架构重构 | `components/MoreMenu.vue`（新）、`views/PlansView.vue`、locales、`styles/main.css`、`PlansView.spec.ts`、`scripts/plan-controls-probe.mjs`（新） | `node scripts/plan-controls-probe.mjs`（`PLAN_CONTROLS PASS FAILS=0`） | 完成 |
| EW310-02 | 报告草稿：执行记录下拉 | `components/RunPicker.vue`（新）、`views/TestReportsView.vue`、`utils/runPicker.ts`（新）、locales、3 个单测、`scripts/report-picker-probe.mjs`（新） | `node scripts/report-picker-probe.mjs`（`REPORT_PICKER PASS FAILS=0`） | 完成 |
| EW310-03 | 报告草稿：用例集来源 | `V11_25` + down、`TestReportService`、`TestSetRepository`（取集最近一次 run）、`TestReportController`/DTO、`TestReportsView.vue`、`components/CaseSetPicker.vue`、`api/testReports.ts`、`TestReportCaseSetIT`、`scripts/report-source-contract.sh`（新） | `bash scripts/report-source-contract.sh`（`RESULT PASS fails=0`）+ `mvn -o -B -Pit test -Dtest=TestReportCaseSetIT` + `bash scripts/migration-down-up-drill.sh V11_25__v3_10_report_source` | 完成 |

## 2. 迁移与部署

| 项 | 内容 |
|---|---|
| 迁移 | `db/migration/V11_25__v3_10_report_source.sql`：`test_report` 增 `source_type VARCHAR(16) NOT NULL DEFAULT 'run'`、`case_set_id BIGINT NULL`；旧行由 DEFAULT 回填为「按 run」（不留 NULL，避免列表出现空白来源） |
| 回滚 | `db/down/V11_25__v3_10_report_source.down.sql`：先删 `case_set_id` 再删 `source_type`（与 up 逆序），中途失败时残留列始终可解释 |
| 部署 | 重建 jar → `systemctl --user restart robot-autotest-backend` → 前端 `vite build`（`:8001` 读 `frontend/dist`） |
| 兼容 | 旧报告 `source_type='run'`，列表展示与改前一致；`case_set_id` 为 NULL 时不展示用例集链接 |

## 3. 门禁与取证

| 项 | 变更 |
|---|---|
| 门禁步数 | **不变（28 步）**。第 28 步 `case_command` 与 v3.9.0 一致 |
| 新增探针 | `plan-controls-probe.mjs` / `report-picker-probe.mjs` 依赖 CDP + 真实项目数据（计划 ≥ 1 条、run ≥ 1 条），**不进 CI 门禁**，产物入库供人工复核；判据由单测兜底（见 `TEST-PLAN.md` §3） |
| 证据 | `docs/evidence/v3.10.0/EW310-01/`（改前/改后读数 + 功能对照表）、`EW310-02/report-picker.json`、`EW310-03/contract.log` + `snapshot-case-set.json` |

## 4. 交付清单

- 代码：前端 4 个新文件（`MoreMenu.vue`、`RunPicker.vue`、`utils/runPicker.ts` + 3 个 spec）、2 个探针、1 份迁移 + 1 份回滚；
- 文档：本目录六份产物 + `docs/evidence/v3.10.0/README.md` 索引；
- 数据：无新增夹具（复用项目 29 的 470 条真实 run 与既有用例集）。

## 5. 偏差记录

| 项 | 计划 | 实际 | 原因 |
|---|---|---|---|
| EW310-01 行内入口 | PRD §7.8 写「行内只留详情，其余进更多」 | 行内只留 1 个「更多」，四动作全在菜单里 | PRD 同节判据是「首屏 ≤ 8 **+ 行内 1**」，「详情 + 更多」是行内 2，与判据冲突；按判据执行（ADR-310-01） |
| EW310-01 表单 | PRD 写「新建/编辑改抽屉或独立页」 | 未改，仍是页内表单卡 | 判据未涉及；改抽屉要重写 3 条既有用例。排 v3.10.1（ADR 待补） |
| EW310-02 过滤位置 | 计划「前端过滤」 | 确认必须前端：后端 `RunPageQuery` 只有 `state/page/size` | 只加载最近 100 条 → 更早的 run 需时间档或高级手输；界面给空态文案，此处不掩盖 |
| EW310-02 时间档取证 | 计划「探针证明 7d/30d 生效」 | 探针只验证控件存在；边界改由单测钉 | 本库全部 run 在最近 5 天内，切不出差异（调研 #19/#20），真跑也证明不了 |
| EW310-02 选项计数 | 计划「数选项条数」 | 只在小结果集上可靠 | naive-ui 用虚拟列表，100 条时只渲染可见部分 |
| EW310-03 冻结版本号 | 沿用「本报告版本 + 1」 | 改为按 run 全局 `maxVersion + 1` | 唯一键 `uk_report_run_version (project_id, run_id, version)` 下，同一 run 有两份报告时先冻结第 1 份会占掉第 2 份用过的号 → 唯一键冲突 → HTTP 500（契约验证首跑实测）。按用例集建报告让这个组合从罕见变成常态，必须修 |
| EW310-03 用例集解析位置 | 计划放 `RunRepository` | 放 `TestSetRepository.latestRunId/containsRun` | 「这个集最近怎么跑的」属于用例集的语义；放 run 仓储会让 run 仓储反向依赖集的概念 |
| 迁移演练脚本 | 直接复用 v3.9.0 的 `migration-down-up-drill.sh` | 先把它参数化（目标表/列从 up 脚本解析） | 原脚本把 `test_result`/`command%` 写死，拿去演练 `V11_25` 会「通过」它根本没看的表——假绿比不跑更危险（实测第一次就是假 PASS） |
| EW310-03 列表展示 | 计划「展示来源类型」 | 展示为「按执行记录 / 用例集 #id」 | 只显示枚举名 `CASE_SET` 等于把内部值漏给用户（违反 FR-v0.2-34 的既有口径） |
