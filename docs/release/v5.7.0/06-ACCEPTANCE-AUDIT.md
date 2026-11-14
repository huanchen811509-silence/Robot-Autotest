# v5.7.0 需求 — 实现独立复核（第⑦道：文档回写 · 复核件）

**复核时间** 2026-11-14 20:00 ~ 20:30 ｜ **复核对象** `1beea30`（发版提交） ｜ **复核人** 学徒（AI）
**复核输入** `PRD.md`（30 FR / 218 AC / 12 NFR）、`DEV-PLAN.md`（46 WBS）、`05-TEST-REPORT.md`
**复核方法** 四组并行**代码走查**（逐 FR 读判据 → 找端点 / 组件 / 迁移 / 单测）+ 本人对 10 处关键断言逐行回验

> 本件不复述测试报告，只回答一个问题：**PRD 说要的，代码里到底有没有、用户能不能用。**
> 结论只依据读到的代码与既有证据；未启浏览器、未连真机，因此「UI 外观」「真机采集」类判据不在复核范围。

## 一、结论摘要

| 口径 | 数量 | 说明 |
|---|---|---|
| PRD §18.1 记「达成」 | 10 | FR-03 / 04 / 12 / 13 / 15 / 17 / 22 / 23 / 24 / 25 |
| 复核后仍成立 | **2** | FR-17（来源并集 + 取消不误删，有硬对照单测）、FR-23（部分成立：机检真跑，但台账有事实错） |
| 复核后应下调为「部分达成」 | **6** | FR-03 / 04 / 12 / 15 / 22 / 25 |
| 复核后**不成立**（判据与代码相反） | **2** | FR-13（报告结论未接判据）、FR-24（执行报告页无 CRUD 入口） |
| **功能断裂**（用户点了没效果） | **4 处** | 见 §二，均为 P0 |
| WBS 交付物 | 46 / 46 有落点 | 42 项按计划路径落地，4 个文件**实现归并**到既有类（见 §五） |

**一句话**：本版的**服务端能力基本齐**（端点、迁移、单测、真实 HTTP 都真跑了），但**前后端拼装存在 4 处断链**，其中 3 处会静默失败（不报错、不生效），导致「执行可信度」这一主题在界面上被整体绕过。测试报告判「达成」的依据是「有单测/有端点」，不是「用户能走通」，这是口径偏乐观的根因。

## 二、P0 功能断裂（四组并行走查 + 本人回验，均有代码行号）

### P0-1 发起执行新链路必失败，且静默回退旧链路

| 环节 | 事实 | 证据 |
|---|---|---|
| 后端预览返回 | 字段是 `snapshotId / selectionSha256 / caseCount / items / sha256`，**无 `token`** | `api/execution/RunLaunchController.java:200-206` |
| 前端读取 | 读的是 `preview.token` | `frontend/src/api/runLaunch.ts:25`、`views/ExecutionPlansView.vue:265` |
| 前端确认提交 | 发 `{...payload, token}`，**不带 `snapshotId`** | `frontend/src/api/runLaunch.ts:45-54` |
| 后端确认入参 | `record ConfirmAndStartRequest(long snapshotId)` | `RunLaunchController.java:211` |
| 后果 | `snapshotId=0` → `recomputeSha(projectId, 0)` 找不到快照 → 报错 | `RunLaunchController.java:125-126` |
| 前端兜底 | 任何异常都 `await executionApi.runCases(...)` **真的发起旧链路** | `ExecutionPlansView.vue:268-281` |

**影响**：选择快照、摘要比对、`409 不启动进程`、命令预览这一整套（FR-11 / 16 / 18 / 10）在界面路径上**从不生效**；用户看到的是「旧接口执行成功」，因此这条链路是**假通过**——测试报告里 `POST /runs/preview` 的 200 是 curl 直连打的，不是界面打通的。

**附带缺陷**：`code === 404 || code === 409` 与信封码对不上（信封是 `40400 / 40900`，见 `common/ErrorCode.java:32,38`），该分支是死代码；单测 `ExecutionPlansView.spec.ts:301-317` 用 `new ApiError(409, ...)` 伪造了不存在的码值，所以测试全绿。

### P0-2 向导「选用例集」被静默丢弃

| 环节 | 事实 | 证据 |
|---|---|---|
| 前端字段 | `setIds` | `views/ProjectWizardView.vue:288` |
| 后端字段 | `caseSetIds`（`List<Long> caseSetIds`） | `application/iam/ProjectTemplateService.java:596` |
| 严格校验 | 后端未开 `FAIL_ON_UNKNOWN_PROPERTIES`，未知字段直接忽略 | 无相关配置，全仓 `grep` 0 命中 |
| 后果 | 勾了用例集 → 建项目后「初始化用例集」为空，**不报错** | — |

前端单测 `ProjectWizardView.spec.ts:321-401` 只断言「发出了 setIds」，断言的是错误契约，所以全绿。

### P0-3 平台集「添加成员」永远 `added=0`

| 环节 | 事实 | 证据 |
|---|---|---|
| 前端请求体 | `{ caseId }`（单个） | `api/platformCaseSets.ts:67-71` |
| 后端入参 | `record AddItemsRequest(List<Long> caseIds)` | `api/caseasset/PlatformCaseSetController.java:37,97-98` |
| 后果 | `caseIds=null` → 循环不执行 → 返回 200 且 `added=0` | `application/caseasset/TestSetService.java:340-366` |

用户点击「添加用例」看到成功提示，但成员没变。后端批量端点本身是对的（真实 HTTP `added=5` 通过），坏在前端这一层。

### P0-4 判据写入无调用点，报告结论仍按 `failCount` 出 PASS/FAIL

| 环节 | 事实 | 证据 |
|---|---|---|
| 写入方法 | `CriteriaIngestService.ingest / ingestInTenant` | `application/execution/CriteriaIngestService.java:82` |
| 生产调用点 | **0 处**（全仓只有定义与 `list()` 读取） | `grep -rn "ingestInTenant(" backend/src/main` 仅命中定义 |
| 读取方 | `/runs/{runId}/criteria` 用 `criteriaIngest.list` | `RunLaunchController.java:170` |
| 报告结论 | 仍是 `failCount == 0 ? "PASS" : "FAIL"` | `application/report/ReportViewRenderer.java:215-217`、`ReportExportService.java:434` |
| 矩阵实现 | `CaseConclusionResolver` 把 `MISSING` 与 `NULL` **都**归 `UNDETERMINED` | `CaseConclusionResolver.java:71-72` |

**影响**：FR-12（判据字段与采集）实际只到「有列、有服务、有单测」，**数据永不产生**；FR-13（通过必须过判据机检）在报告链路上**完全没有落地**。PRD 记 FR-13「达成」与代码相反。

## 三、P1 契约与权限偏差

| # | 偏差 | 事实与证据 | 风险 |
|---|---|---|---|
| P1-1 | 平台集写权限前端判据是 `isAdmin` | `GlobalCaseSetsView.vue:21` `computed(() => auth.isAdmin)`；后端用的是 `access.require(CASE, UPDATE)`（`TestSetService.java:428-429`） | PM 有后端权限却无入口；PRD 要求「按 `case:write` 能力渲染」 |
| P1-2 | `POST /platform/case-sets/seed-samples` 无鉴权 | `PlatformCaseSetController.java:121-125` 直接调 seeder，无 `require` | 任何登录用户可写 `test_set` / `test_set_item` |
| P1-3 | `GET` 列表带写副作用 | `PlatformCaseSetController.java:62-69` 每次列表请求都 `ensureSamples()` | `case:read` 用户也会建集，与「写需写权限」冲突 |
| P1-4 | 软删后报告详情 / 导出仍可访问 | `TestReportRepositoryJdbc.find(id)` 不过滤 `deleted_at`（`:72-75`，注释明说「find 不动」），而 render/template/export 三条链路都走它 | PRD AC-25-6 要求「详情 404、导出失效」，实际 200 |
| P1-5 | 运行记录备注二次编辑会被清空 | `RunsView.vue:349` 依赖从未加载的 `runMetas`；后端 `COALESCE(?, remark)` 下空串会覆盖旧值（`ReportLifecycleService.java:50-56`） | 用户改一次备注，第二次打开再保存就丢数据 |
| P1-6 | 摘要公式与 PRD 不一致 | PRD 写 `sha256("v5.7-run-confirm:" + planId + ":" + selectionSha256 + ":" + commandDigest)`；代码是 `sha256(PREFIX + 规范化命令行)`，**不含 planId 与 selectionSha**（`CommandDigest.java:85-87`） | 同一命令清单在不同计划下摘要相同，区分不了「哪个计划的哪次选择」 |
| P1-7 | 409 比对的是选择摘要，不是确认摘要 | `RunLaunchController.java:126` 用 `snapshots.recomputeSha`；`confirmed_digest` 在派发路径上从未被读 | 「换脚本内容但用例集不变」不触发 409 |
| P1-8 | 三处审计事件名不存在 | PRD 要求 `run.digest-mismatch` / `run.blocked-unconfirmed` / `evidence.hash-mismatch`，Java 侧 `grep` **0 命中** | 事后追不了「谁绕过确认」 |

## 四、P2 覆盖度与「声称 vs 实际」

| 项 | PRD / 发布说明声称 | 代码实际 | 证据 |
|---|---|---|---|
| 列表骨架覆盖 | 全站列表统一 `ListScaffold` | **仅 1 页**（`TestReportsView`） | `grep -rl ListScaffold frontend/src --include=*.vue` 只 1 命中 |
| 服务端跨页全选 | 条件全选落到后端 | `useServerSelection.toDescriptor()` **无生产调用方**；后端批量端点只收 `ids` | `useServerSelection.ts:185`；`GenericBatchService.java:44` |
| 列头筛选 | 服务端按 `f_` 过滤 | 三个页面全是前端 `items.filter(...)` 内存过滤；后端无 `f_` 入参 | `CaseSetsView.vue:121-131,146-156`、`RunsView.vue:315-326` |
| 列表 CRUD 台账 | 发布说明写「37 页」 | 台账实际 **42 行**（A 11 / B 7 / C 24），无缺口 13 行，白名单 7 行；「37」找不到来源 | `docs/evidence/v5.7.0/list-crud-matrix.md:20-80` |
| UI 回归覆盖 | 台账内列表页逐页 | 默认只跑 **5 条路由 × 1440 单视口** | `scripts/browser-regression.mjs:27,280-296` |
| 执行报告页 CRUD | 支持编辑 / 软删 | `ReportsView.vue` **无操作列**（全文无 `listCrudApi`） | `views/ReportsView.vue:47-92` |
| 一次性凭证 | 可用于采集设备 | `ManualCredentialCollectService` 构造完材料即返回 `ACCEPTED`，材料被丢弃；`HostInventoryService.accessOf` 无一次性分支 | `ManualCredentialCollectService.java:71-81`、`HostInventoryService.java:134-137`（= 已登记 L-57-04） |

**台账事实错误 3 条**（机检只校验格式，不校验事实，所以「全绿」是绿在不准确的台账上）：
`/g/assets` 标「C/R/U/D 全」但实际无 U（`GlobalAssetController` 无 PUT）；
`/plans/groups` 标 C 类只读，实际有 create/remove/reorder/apply（`PlanGroupsView.vue:74,92,138,149`）；
`/scenarios` 标 C 类只读，实际有 create/regroup/remove（`ScenariosView.vue:82,106,118`）。

## 五、DEV-PLAN 46 个 WBS 核对

| 判定 | 数量 | 说明 |
|---|---|---|
| 交付文件按计划路径落地 | 42 | 迁移 7 支、后端 20 个类、前端 12 个组件/组合式、机检 3 个工具均存在 |
| 实现归并（功能在、拆分不同） | 4 | `TestCaseCriteria` + `TestResultCriteriaRepository` → 并入 `CriteriaIngestService` + `RunTrustJdbcStore`；`SelectionSnapshot` → 并入 `SelectionSnapshotService`；`SoftDeleteSupport` → 并入 `ReportLifecycleService` |
| 完全没落地 | **0** | — |
| 验收命令可复跑且为真 | 是 | 迁移重放、`db-audit`、单测、机检、门禁均本轮实跑过 |

**结论**：开发计划的**「有没有做」全部兑现**；问题不在 WBS 执行，而在**跨 WBS 的拼装**（P0-1~P0-4 都发生在「前端怎么调后端」这一层，而 DEV-PLAN 把前后端拆成不同 WBS，各自单测都过）。

## 六、根因

1. **单测 mock 了错误契约**：`ExecutionPlansView.spec.ts` 伪造 `token` 与 `ApiError(409)`；`ProjectWizardView.spec.ts` 断言「发出 setIds」；`GlobalCaseSetsView.spec.ts` 只断言「addItem 被调用」不看请求体。**测试断言的是「我发请求了」，不是「后端认这个请求」。**
2. **缺前后端字段对账机检**：现有 55 步门禁有 API 契约快照（endpoints/paths），但没有「前端 `api/*.ts` 的请求体字段 ⊆ 后端 record 字段」这类检查，所以 3 处 P0 全部逃过门禁。
3. **验收判据用「有单测」代替「能走通」**：`05-TEST-REPORT.md` 的「达成（单测/机检）」把「服务层单测绿」当成「功能达成」，导致 §18.1 的 10 条「达成」中 8 条站不住。

## 七、建议（按优先级，供大师裁决）

| 优先级 | 事项 | 验收方式 |
|---|---|---|
| **P0** | 修 P0-1~P0-3 三处字段/契约 | 补「契约对账」单测：前端 api 层请求体 vs 后端 record 字段逐一比对，红转绿；再走一次真实界面发起 |
| **P0** | 删掉 `ExecutionPlansView` 的静默回退（或改为显式按钮「用旧路径执行」+ 二次确认） | 409/404 时**进程数不变**（`ps` 前后对比），并留审计 |
| **P0** | 接上判据写入（`ingest` 挂到报告入库链路），报告结论改读 `CaseConclusionResolver` | 跑一条 pytest 用例，`test_result.criteria_*` 有值；报告结论非 `failCount` 单判 |
| **P1** | 权限：前端按 `CASE:UPDATE` 渲染；`seed-samples` 加写权限；`find(id)` 过滤软删 | 越权用例 403；软删后详情 404 |
| **P1** | 备注回填：列表接口返回 `remark`，或编辑前拉详情 | 二次编辑不丢数据 |
| **P2** | 台账纠错 3 条 + 「37 页」改成台账真实口径；`ListScaffold` 与 `toDescriptor` 要么接上要么从发布说明里删掉 | 台账与代码逐条对齐 |
| **P2** | 新增机检：`check_fe_contract.py`（前端请求体 vs 后端 record 字段）并入 `gates.sh` | `--self-test` 真注入判红 |

## 八、复核边界（必须声明）

- 本件**只做代码走查**：未启浏览器、未连真机、未压测，因此「视觉走查」「真机采集」「10 万量级」三类判据**未复核**，仍维持测试报告的「未执行」。
- `05-TEST-REPORT.md` 里的真实 HTTP 证据（`docs/evidence/v5.7.0/api-smoke-20261114/raw/*.log`）是**用 curl 直连接口**打的，证明「端点按契约工作」，**不证明「界面调得通」**——这是本件与测试报告口径差异的根源。
- 本件不判 G 门，不放行/不放行由大师裁定。

## 九、处置回写（2026-11-14 修复轮，P0/P1/P2 逐条）

> 本节由修复轮补写，**不修改**上文复核结论——上文是「修之前的事实」，本节是「修完之后的事实」。
> 判据：能拿出反向实证（修前会红）才算闭合；拿不出的，如实标「部分」并登记遗留。

| 编号 | 原判定 | 处置 | 反向实证（修前会红 / 修后判绿） |
|---|---|---|---|
| P0-1 | 发起台字段虚构 `token` + 静默回退旧链路 | 字段改 `snapshotId`，删除回退；后端 `confirm-and-start` 接收完整执行参数 | 回退 6 个实现文件到 `1beea30` → 4 个 spec **13 条判红**；恢复后 46 条全绿。真实界面：四模式 `preview` 200 回 `snapshotId` |
| P0-2 | 向导发 `setIds` / `templateKeys`，后端不认 | 前端改 `caseSetIds`，模板从请求体移除改为只读回显 | 真实调用 `POST /projects/122/content` 带 `setIds` → **400**（修前 200 静默丢弃）；正确字段 200 `testSetId=91 cases=8` |
| P0-3 | 平台集加成员发 `{caseId}` 被吞 | 统一发 `{caseIds:[...]}` | 真实界面加成员 → 响应 `{"added":1,"skipped":0}`；回退实现后 `platformCaseSets.spec` 3 条判红 |
| P0-4 | 判据只算不写入，报告结论仍按 `failCount` | 入库时评估并落 `criteria_json/verdict/missing`；报告/导出/快照结论读判据汇总，未声明判据 `UNDETERMINED` | 单测：`CriteriaEvaluationTest`(6) / `PytestCriteriaExtractorTest`(4) / `ReportSnapshotTest`(结论与 failCount 解耦)。**未取得真实入库样本 → L-57-10** |
| P0-5（本件新增） | —（复核时未发现） | `platformCaseSetApi.list()` 把分页信封当数组，`/g/case-sets` 与向导内容步的用例集**永远为空** | 真实界面取证发现；修后 `wizard-content-sets` / `set-list` PASS；`platformCaseSets.spec` 加 2 条信封用例 |
| P1-1 | 前端按 `isAdmin` 判写权限 | 能力码 `case:write` ⇄ `CASE:UPDATE` 单一真源 | `PermissionMatrixTest` 7 能力 28 格；真实界面写入口由能力集控制（`set-add-input-visible` PASS） |
| P1-2 | `seed-samples` 无写权限 | 加 `requirePlatformWrite()` | 代码 + 权限矩阵单测；**未用只读账号实测 403**（TC-265 部分） |
| P1-3 | `GET` 有播种副作用 | `page()` 删除 `ensureSamples()` | 代码；**未做连打 3 次计数取证**（TC-266 部分） |
| P1-4 | `find(id)` 不过滤软删 | 加 `AND deleted_at IS NULL` | 单测；导出共用同一路径（TC-268 部分） |
| P1-5 | 备注从空 `runMetas` 取，二次编辑覆盖成空串 | 新增 `GET /api/runs/{runId}`，打开即取真实值 | 真实界面：保存 A → 关掉重开读回 A（`remark-persisted` PASS） |
| P1-6 | 摘要不含 `planId` 与选择摘要 | `confirmationDigest(planId, selectionSha256, commandDigest)` | 单测 + 代码；真实 409 未跑（TC-262 部分） |
| P1-7 | 派发前不比确认摘要 | 重算比对，不等 → 409 + 审计 | 代码路径 + 审计事件落名；真实派发未跑 |
| P1-8 | 三条审计事件只存在于文档 | 事件名落码：`run.digest-mismatch` / `run.blocked-unconfirmed` / `evidence.hash-mismatch` | 代码；**未逐条触发取证**（TC-263 部分） |
| P2 | 台账 3 条事实错误 + 「37 页」 | `/g/assets` 去 U、`/plans/groups` 与 `/scenarios` 改 A 类、读数改 42 页（A13/B7/C22） | `LIST_CRUD_RESULT PASS=114 FAIL=0` + `--self-test` |
| P2 | `ListScaffold` / `toDescriptor` 声称 vs 实际 | 承认只落 1 页 / 无调用方：发布说明、CHANGELOG、PRD 改为「部分落地」，代码注释写明边界，登记 L-57-05 / L-57-09 | 检索无「全站统一骨架」「服务端跨页全选」残留（需求条文已附落地状态） |
| P2 | 缺前后端字段对账机检 | 新增 `tools/check_fe_contract.py`，入门禁第 56 步 | 在 `1beea30` 上回放判红 3 处；`--self-test` 注入判红 → 还原判绿 |
| **新发现** | Spring Boot 全局关闭 `FAIL_ON_UNKNOWN_PROPERTIES`，record 上的 `@JsonIgnoreProperties(ignoreUnknown=false)` **无效** | `application.yml` 打开开关，并加 `JacksonStrictBodyConfigTest` 钉住配置 | 带 `token` 的 `preview` / 带 `setIds` 的 `content` 修前 **200**、修后 **400** |

**结论**：P0 五条（含新发现一条）全部闭合到「代码 + 反向实证」；P1 八条中五条有真实界面或单测实证、
三条只到代码路径并登记为部分；P2 三条闭合。仍欠真实派发与真实判据入库样本两项（L-57-01 / L-57-10），
以及报告页结论列（L-57-11）。

### 9.1 本件遗留的口径问题（自查）

本件 §五 曾按「WBS 有没有交付文件」判「完全没落地 0」，这个判据本身**太宽**——
文件在 ≠ 拼得上。修复轮把判据改成「真实请求/真实界面能走通」，才抓出 P0-5 与 Jackson 开关这两处。
下一版复核建议直接把「真实浏览器五链路」与「未知字段 400」列进放行门槛，而不是等审计件来发现。
