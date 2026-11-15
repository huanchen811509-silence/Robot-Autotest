# v5.14.0 需求调研（00-RESEARCH）：草案里的「需新增」，多数已经在库里

> 流程位置：① 需求调研。每条结论标注**证据来源**；[已核实] / [推断] / [假设] 三档分开写。
> 上游：`docs/plan/重构评估-20261115/04-报告体系评估.md`、`00-总纲` §3.2。
> 核的是 **v5.13.0 收口（commit `efbcf37`）** 时的代码事实，只读取证，未运行构建 / 测试。
> 本文件在 `_draft/` 草案区，W7 收口时随三份计划一起迁入 `docs/release/v5.14.0/`。

## 1. 实测事实

| # | 事实（三档标注） | 证据 |
|---|---|---|
| R-1 | [已核实] 最新迁移是 **V11_68**（`V11_68__code_prefix_registry.sql`），`V11_69`~`V11_72` 从未落文件；5.11/5.12/5.13 的「预留到 V11_7x」都是编号约定，不是已建迁移 | `db/migration/` 目录；`flyway_schema_history` 排序；`docs/release/v5.13.0/05-TEST-REPORT.md` §1 |
| R-2 | [已核实] `suite_count` / `suiteCount` **全库零命中**；`run` 表无套件维度；`ReportListItem` / `ReportCounts` 只有 pass/fail/skip | `V1__baseline.sql:452-471`；`api/report/ReportController.java:133-139,177-190`；`frontend/src/api/reports.ts:25-36` |
| R-3 | [已核实] `report_template` **无 `kernel` 列**（表 / 实体 / View / Controller 四层均无） | `V9_2__report_template.sql:19-35`；`domain/report/ReportTemplate.java:18-20`；`api/report/ReportTemplateController.java:77-78` |
| R-4 | [已核实] `kernel` 列在**别的对象**上已存在，极易误读：`project_template.kernel`（`V11_36:6`）、`project.test_kernel`（`V11_16:6`）、`run.kernel`（`V11_17:5-6`）、`case_script_binding.kernel`（`V11_47:24`） | 对应迁移文件 |
| R-5 | [已核实] 数据层早已分家：执行报告 = `run` 表，测试报告 = `test_report` 表 | `V8_1__test_report.sql:1`「测试报告（对外）与执行报告（对内）分家」；`V11_63__report_crud.sql:3-5` |
| R-6 | [已核实] 界面口径三套：菜单 `menu.externalReports='对外报告'`、页面标题 `testReports.title='测试报告'`、跳转按钮 `testReports.open='测试报告'` | `frontend/src/locales/zh-CN.ts:396,1874-1881`；`views/ReportsView.vue:333-335`；`config/navigation.ts:434,451` |
| R-7 | [已核实] 报告批量删与影响面**全链路已交付**（5.9 W59-20），不是开发项 | `api/report/ReportCrudController.java:51-68`；`api/report/RunRecordController.java:72-79`；`views/ReportsView.vue:263-280,339-360,431-447`；`openapi.json:21453,22777` |
| R-8 | [已核实] 打印页**已完整存在**：`views/ReportPrintView.vue`（token 取数 + metadata 十项表 + 打印样式），路由 `/reports/:runId/print`；缺的只是详情页按钮与 `issuePrintToken` 前端封装 | `router/index.ts:171-174`；`ReportPrintView.vue:89-108,115-226,390-405`；`api/reports.ts:265-270`（只有 `IssuedPrintToken` 类型）；`ReportExportController.java:56-63` |
| R-9 | [已核实] `GET /api/test-reports/{id}` **已存在**且已进契约，但返回 `Map<String,Object>` 字段视图，**不返回 HTML**；`snapshot_html` 落库却无对外通道 | `api/report/TestReportController.java:66-72`；`application/report/TestReportService.java:138-147`；`openapi.json:23361`；`api/testReports.ts:69-73`（`render` 零消费者） |
| R-10 | [已核实] **创建 DRAFT 时就物化快照**：`createInternal` 调 `snapshots.build(...)` 并 insert `snapshot_json/snapshot_html`；「尚未生成快照 + 生成按钮」分支在正常链路不存在，只是 `snapshotOf` 对历史脏数据的防御 | `TestReportService.java:94-102,185-191`；`ReportSnapshotService.java:68-69,117-125` |
| R-11 | [已核实] 后端导出是 **html/pdf/docx/xlsx 四格式**，前端三处口径不一致（3 种 / 2 种） | `application/report/ReportExportService.java:107-113,163-178`；`api/testReports.ts:31-34`；`api/reports.ts:210` |
| R-12 | [已核实] 模板已有**两级默认回退**（项目默认 → 平台 DEFAULT），并有 `TemplateRef` 回指元信息；白名单 `ReportFieldWhitelist` 是单一 21 项集合，无 kernel 分组 | `infrastructure/ReportTemplateRepositoryJdbc.java:147-156`；`TestReportService.java:194-196`；`api/report/ReportViewRenderer.java:48`；`application/report/ReportFieldWhitelist.java:30-47,58-81` |
| R-13 | [已核实] 套件树切分是**唯一规则**：`case_key` 最后一个 `/` 之后的第一个 `.` 分 `suitePath` / `caseName`，再按 `/` 分段；**不读 `run.kernel`** | `application/report/ReportTreeService.java:100-125`（关键 `:107-114`） |
| R-14 | [已核实] `snapshot_html` 形态是 `<h1>Test Report</h1><dl><dt>key</dt><dd>value</dd>…</dl>` 字段键值对，**没有**结论/证据/附录分节与锚点 | `ReportSnapshotService.java:117-125` |
| R-15 | [已核实] 自动生成开关后端已有 `report.auto_generate`（`ReportAutoGenerator.java:47`），设置页只有 `report.retentionDays`；「复制平台模板为项目模板」在 `report_template` 域**确实没有** | `views/SettingsView.vue:44`；`ReportTemplateController.java:26-72`；`api/reportTemplates.ts:44-77` |
| R-16 | [已核实] `test_report` 表**无 `template_id` 列**，模板绑定走快照内 `TemplateRef` | `domain/report/TestReport.java:12-25`；`ReportAutoGenerator.java:34-39` |

## 2. 由事实推出的结论（[推断]，未逐条实测）

1. **R-2 / R-3 说明草案两支迁移假设成立**：`V11_73`（`run.suite_count`）与 `V11_74`（`report_template.kernel`）确需新增；但 R-1 提示这是**跳号追加**，须确认 `db-migrate.sh` / `db-audit.sh` 不要求编号连续。
2. **R-7 / R-8 / R-9 / R-10 说明本版不是「补后端能力」，而是「接线 + 改口径」**：把「批量删」「打印页」「草稿态」写成开发波次会重复造轮子，应降级为回归验证 + 文案统一，腾出的容量给预览 HTML 通道与模板 kernel 维度。
3. **R-13 决定套件数只能抽同一个切分函数**：若按草案「RF 按 `/`、pytest 按 `.`」另写一套，恰好制造草案 R1 想避免的「列表数字与详情树打架」。
4. **R-14 说明三段式不是前端接线**：要改快照生成器或新增渲染层，否则「分节锚点 / 结论-证据-附录」无处落地。
5. **R-16 说明 TC 断言 `templateId` 不可直接落到 DB 列**：应改断言 `GET /{id}/template` 的 `TemplateRef`，否则与「只加 1 列」的 NFR 冲突。

## 3. 未核实项（不写成结论）

| # | 待核 | 为什么没核 |
|---|---|---|
| U-1 | [假设] RF 多套件 / pytest 参数化各 1 个真实 run 的 `case_key` 实际形态 | 未取真库样本；「pytest 按 `.` 分隔」目前只是草案假设 |
| U-2 | [假设] `V11_68 → V11_73` 跳号被 Flyway 与 `db-audit.sh` 接受 | 未执行迁移演练（会改库，留到 W1） |
| U-3 | [假设] 10MB `snapshot_html` 首屏 ≤3s、导出 ≥50MB 内存曲线 | 无大样本与计时环境 |
| U-4 | [假设] 「全站 grep 对外报告 = 0」的扫描范围是否含注释 / spec / docs / CHANGELOG | 判据边界未拍板；`frontend/src` 生产代码命中 1 处、注释 2 处、spec 另有命中 |
| U-5 | [假设] 预览是读 `snapshot_html`（需新增 HTML 通道 + XSS 清洗规则）还是读 `render` 字段 Map 由前端渲染 | 需产品拍板，见 §4 |

## 4. 与上游评估 / 草案的差异

- 草案 §1.3「测试报告能建不能看」结论成立，但归因写成「后端能力缺前端接线」不准确：R-9 表明**详情端点只给字段 Map、不给 HTML**，这是真实工作量。
- 草案 W64-06「草稿态 + 生成快照」与 R-10 冲突：正常链路 DRAFT 即物化，按钮语义需 redefine（映射 `POST /{id}/freeze` 还是新端点）。
- 草案 W64-16「报告批量删」与 R-7 冲突：5.9 已全链路交付。
- 草案引用行号有三处漂移：`ReportsView.vue:296-300` 实为 `:333-335`；`zh-CN.ts:1716` 实为 `:1881`；`ReportExportController.java:70-77` 实为 `:56-63`。验收按错位置取证会白跑。
- 草案「5.13 预留到 V11_72」措辞与 R-1 冲突：那些编号从未落文件。

## 5. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：16 条实测事实 + 5 条推断 + 5 条未核实项；核掉「批量删 / 打印页 / 草稿态 / 详情端点需新增」四类过期假设 | commit `efbcf37` 源码与契约快照，只读核实 |


---

## 6. W3 补充实测事实（开工后取证）

| # | 事实（三档标注） | 证据 |
|---|---|---|
| R-17 | [已核实] `ReportAutoGenerator.KEY_ENABLED="report.auto_generate"` 读取走 `ProjectSettingRepository.findAll(projectId,"report.")`（剥前缀得到 `auto_generate`），而设置页 PUT 走 `SystemSettingService.putProject`；该类的 `PROJECT_KEYS` 白名单**不含**此键，写入必被 `validateProject` 以 400 拒绝 | `ReportAutoGenerator.java:47,163`；`SystemSettingService.java:34-50,146-149`；W3 `SystemSettingAutoGenerateTest` |
| R-18 | [已核实] 后端导出四格式与前端旧口径不一致的确切位置：`ReportExportService.extension()` 白名单为 html/pdf/docx/xlsx；`api/testReports.ts:32` 原写死三种；`api/reports.ts` 的 `ReportExportFormat` 类型只有 pdf/docx（那是**执行报告导出**，与测试报告导出是两个对象，不要混为一谈） | `ReportExportService.java:163-178`；`api/reports.ts:210`；W3 补丁 |
| R-19 | [已核实] 打印页 `ReportPrintView.vue` 通过 `route.query.token` 调 `reportsApi.printData(runId, token)`，路由 `/reports/:runId/print` 标 `public`；缺的只是 `POST /api/reports/{runId}/print-token` 的调用方 | `ReportPrintView.vue:89-108`；`ReportExportController.java:56-63`；`api/reports.ts:408` |

## 7. 修订记录（补充）

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 补 R-17 ~ R-19：W3 开工后读到的写入侧白名单、四格式确切位置、打印页 token 链路 | commit `4cfc94c` 源码 + W3 单测 |


---

## 附：W4 实测补记（R-20 ~ R-22，2026-11-15）

| # | 事实 | 证据来源 |
|---|---|---|
| R-20 | `V11_74` 真实迁移可执行：`kernel varchar(32) NOT NULL DEFAULT 'robot'`；`flyway_schema_history` 最新 `11.74/11.73/11.68`；存量行无 NULL；EHT-PYTEST 9 字段且 `INSERT IGNORE` 重放幂等；平台 DEFAULT 仍 21 字段；业务表数 109 | `w4-verify.txt` [4]（`bash scripts/db-migrate.sh` + mysql CLI） |
| R-21 | `run` 表真实内核分布 `robot:496 / pytest:47`；四级回退 SELECT+CASE 在真实库上按 kernel 正确分流（robot→DEFAULT、pytest→EHT-PYTEST），项目 7 与平台项目 88 各一组 | `w4-verify.txt` [5]（`w4_sql_probe.py`） |
| R-22 | `TestReportService.template()` 原本只从快照读 `TemplateRef(id,version,name,fieldKeys)`；快照 JSON 里**没有** run kernel，也没有「选择落在哪一级」。故 `templateSource` 只能作查询期元信息，不能从快照复原 | `ReportSnapshotService.templateNode` / `ReportRecord` 字段清单 |


---

## 附：W5 开工前事实清单（R-23 ~ R-30，2026-11-15，只读核实）

| # | 事实 | 证据来源 |
|---|---|---|
| R-23 | 快照 HTML 是 `ReportSnapshotService.html()` 生成的平铺 `<dl>`，无分节 / 锚点；`ReportHtmlSanitizer` **保留** `<a>` / `<section id>` / `href="#..."`，但会整块删 `<style>`、删 `on*` 属性 | `ReportSnapshotService.java:117-125`、`ReportHtmlSanitizer.java:28-42` |
| R-24 | 仓库没有「通过 / 不通过 / 缺证据 / 未执行」四态枚举；现成的是三态 `CaseConclusionResolver.Conclusion{PASS,FAIL,UNDETERMINED}` 与判据列 `criteria_verdict` 的 `PASS / FAIL / MISSING / NULL` **四值互斥且穷尽** | `V11_59__test_result_criteria.sql`、`CaseConclusionResolver.java:25-27` |
| R-25 | `test_result` 没有 `node_id` / 证据路径列；真实证据在 `pytest_case_step.node_id / content / stdout_path`，run 级产物在 `run_artifact.path / availability` | `V11_48__pytest_case_step.sql`、`V1__baseline.sql:509-524` |
| R-26 | 快照 metadata 只有 `safeMetadata()` 过滤后的 **5 项**脱敏值；十项门禁在 `MetadataGate.REQUIRED`；内核版本 / 插件版本 / 仓库 commit / 快照引用原本**无载体**（`COMMIT_SHA` 会被 5 键白名单滤掉） | `ReportViewRenderer.java:262-274`、`MetadataGate.java:22-24` |
| R-27 | 跨 run 差异已有 `CompareService`，但基线按 `projectId + DUT/LINK + 时间` 选、**无 kernel 条件**，且属于执行报告链路；无基线时 `newFail` 会把所有当前 FAIL 计成「新增失败」 | `CompareService.java:91-147`、`ReportReadRepositoryJdbc.java:80-101` |
| R-28 | 5.11 的 `CaseScriptLink.vue` 是默认导出组件，`bound` 只在 `bindingState` 为 `BOUND_VERIFIED` / `BOUND_UNVERIFIED` 时为真；报告链路此前**零引用** | `CaseScriptLink.vue:1-48` |
| R-29 | `ReportSnapshotService.build` 已有 2 参 / 3 参重载，`ReportSnapshotTest` 断言平铺 HTML 与 hash 稳定；新增三段式必须保留旧重载，否则历史报告与单测一起变 | `ReportSnapshotTest.java:31-41` |
| R-30 | 真实库探针（2 个 run 样本）：`run_artifact.path` / `pytest_case_step.stdout_path` 存的是**绝对路径**（`/home/evergreen/robot-test/artifacts/...`、`/tmp/eht-fake-runs/...`），且部分 run 的 `case_key` 为空 → `node_ref` 会是 NULL | `w5-verify.txt` [3]（`w5_sql_probe.py`） |


## 附：W6 开工前事实补录（R-31 ~ R-34）

| 编号 | 事实 | 证据 |
|---|---|---|
| R-31 | `POST /api/reports/batch-delete` 与影响面回算在 5.9 已全链路交付；`ReportsView.vue` 有多选、批量删按钮、确认弹窗、影响面回执，`ReportsView.spec.ts` 有 2 条批量删断言 | `frontend/src/views/ReportsView.vue`、`ReportsView.spec.ts:189/224` |
| R-32 | `ReportLifecycleService.deleteTestReport` 对 `state=FROZEN` 抛 `409 CONFLICT`「请先撤销冻结」，`ReportLifecycleServiceTest:166/179` 钉住；后端冻结保护不是缺口 | `ReportLifecycleService.java:240`、`ReportLifecycleServiceTest.java` |
| R-33 | `TestReportsView.vue` 勾选列对**所有**行渲染 `NCheckbox`，冻结件仍可勾选；`batchDelete()` 原样把选中集合送进删除请求 | `frontend/src/views/TestReportsView.vue:223`、`batchDelete()` |
| R-34 | `ReportTemplateRepositoryJdbc.find` 用 `project_id IN (?, 0)` 让平台级模板可见；`create` 权限是 `EXECUTION:UPDATE`；`requireEditable` 禁改平台级模板；`ReportTemplatesView.select()` 会清空 `notice` | `ReportTemplateRepositoryJdbc.java:79`、`ReportTemplateService.java`、`ReportTemplatesView.vue:268` |
