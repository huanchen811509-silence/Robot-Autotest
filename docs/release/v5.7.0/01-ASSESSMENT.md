# v5.7.0 评估报告 · 全局用例资产化、执行可信度与全站操作体验

> 流程位置：② 评估报告。**无评估报告不许开 PRD**（用户级流程纪律）。
> 上游输入：`E:\workspace\自动化测试\调研报告-白泽\白泽平台调研评估报告-20261114.md`（下称「白泽报告」）
> 下游：`PRD.md` / `DEV-PLAN.md` / `TEST-PLAN.md`（同目录）
> 版本号 v5.7.0 为拟用号；本文件**不修改 `VERSION`**，落号以 G 门判定与大师授权为准。
> 2026-11-14 第二轮：并入大师四条补充需求，新增 §2.9~2.12 现状、§3.5~3.8 可行性评估，主题数 4 → 8。

---

## 1. 结论摘要

| 项 | 结论 |
|---|---|
| 做不做 | **做**。八个主题全部具备现成落点，无一项需要推翻既有架构 |
| 主题数 | 8（E 用例资产化 / W 全局向导 / R 执行可信度 / D 证据与列表 / S 执行选择 / U 前端体验 / L 列表 CRUD / C 设备凭证） |
| FR 规模 | 30 条 / 218 条 AC（PRD §5–§12；初稿 15 条，第二轮 +15 条） |
| 波次 | 10 波 / 46 个 WBS（W1–W6 原六波 + W7 执行选择 / W8 列表 CRUD / W9 设备凭证 / W10 体验契约与全量收口） |
| 主要风险 | ① 服务端全选 / `ALL` 模式要改列表与执行选择语义，触及多个既有页面；② 判据层指标来源可能降级；
③ **一次性凭证若被通用日志切面打印即构成泄漏**（最高优先级安全项）；④ 全站列表骨架替换的回归面最大 |
| 是否阻塞发版 | 不阻塞现有 v5.6.0 判定；v5.7.0 独立排期 |
| 需大师授权项 | ① 落号 5.7.0；② 平台用例集写权限放宽（安全口径变更）；③ 迁移新号 `V11_58` 起；
④ **报告 / 运行记录开放软删**（数据保留口径变更）；⑤ **允许一次性凭证以 POST body 传入**（安全口径变更） |

**一句话结论**：白泽值得抄的四件事（摘要承诺、判据层、证据包、列表三件套）中，**前两件是要改执行链的重活、后两件是"把散装收成整装"的轻活**；
大师第一轮三条补充落在「已有能力缺 UI」，第二轮四条里三条是「已有能力缺契约」、一条是**已实现但有缺陷**（组合选择误删手工勾选用例）。
八主题拼在一个版本里成立，但必须按 10 个波次推进：W7/W8/W9 可并行，W10 统一收口，不能并行开工。

---

## 2. 现状核查（代码事实优先，不采信记忆）

> 本节每一条都在仓库里核对过；「路径 + 符号」可复查。**这是 PRD 能写判据的前提**。

### 2.1 全局菜单

| 事实 | 证据 |
|---|---|
| 全局导航 5 组 14 叶 | `frontend/src/config/navigation.ts` → `GLOBAL_NAVIGATION` |
| 「公共用例」组只有 2 叶（用例清单 / 导入记录） | 同上，`global-cases` 的 `children` 长度为 2 |
| 全局无「用例集」入口 | 14 叶 key 中无 `case-set` 字样 |
| 全局无「新建项目」入口 | `global-projects` 仅 `global-projects-all` / `-mine` |
| 项目模式有「新建项目」叶 | `NAVIGATION` → `workbench-create` → `/projects/new`，capability `project:create` |

### 2.2 用例分类体系

| 事实 | 证据 |
|---|---|
| 分类字典表已存在，两级 | `db/migration/V11_32__global_case_library.sql` → `case_category(level 1/2, parent_id, sort_no, status)` |
| 分类作用域含平台与项目 | 同文件 `project_id`；`V11_41__project_case_category.sql` 扩到「平台 + 项目」 |
| 用例带一级/二级分类列 | `V11_3__test_case_ed202_fields.sql` 加 `category_l2`；`V11_32` 加 `category_l1`；`V11_41` 加 `category_l1_id` / `category_l2_id` |
| 全局用例页已有一/二级**筛选** | `frontend/src/views/global/GlobalCasesView.vue` L49–L100（`categoryL1` / `categoryL2` / `categoryL1Options`） |
| 分类查询接口已存在 | `frontend/src/api/globalCases.ts` L102 → `/global/cases/categories`；L106 `categoriesInScope(projectId)` |

**判定**：分类的**数据与筛选**都齐了，缺的是**归组视图**（按树浏览 + 组内计数），以及**把分类用于选择**（勾选树）。

### 2.3 用例集

| 事实 | 证据 |
|---|---|
| 项目用例集完整 CRUD + 成员 + 排序 + 立即执行 | `api/caseasset/TestSetController.java`（`/api/projects/{projectId}/case-sets`，11 个端点） |
| 按分类批量加用例的端点**已有** | 同上 L131 → `POST /{id}/items/category-filter`；前端 `api/cases.ts` L257 |
| 平台用例集模型**已完整** | `V9_3__case_source_version.sql` L15–L16 把 `test_set.project_id` 改为 `NULL`，加 `owner_scope`（`PLATFORM`/`PROJECT`）；`uk_test_set_scope_code(scope_project_id, set_code)` |
| 平台用例集接口**只读且仅管理员** | `api/caseasset/PlatformCaseSetController.java`（只有 2 个 GET）；`TestSetService.requirePlatformAdmin()` 强制 `access.isAdmin()` |
| 项目集页面加成员是**单条下拉**，无树形勾选 | `views/CaseSetsView.vue` L278–L290：`selectedCaseId` 单选后 `addItem` |

**判定**：2.2 的「手工建集 + 分类勾选」**后端零成本**（`category-filter` 已能按 `categoryL1`/`categoryL2` 批量写），只需前端做树。平台侧要支持「全局建集」，必须做三件小事：放宽权限、补写端点、加样例标记位。

### 2.4 全局向导建项目

| 事实 | 证据 |
|---|---|
| 向导已存在，6 步 | `views/ProjectWizardView.vue` L29 `ALL_STEPS = ['template','basics','runtime','members','content','review']` |
| 已有模板选择 | 同文件 L156 `selectTemplate()`；模板源 `config/projectTemplates.ts` + `api/globalTemplates.ts` |
| 内容步**已能**选公共用例与资产 | 同文件 L63–L80：`globalCaseApi.page(...)` + `globalAssetApi.list(...)`，`toggleCase` / `toggleAsset` |
| 内容步**没有**用例集维度 | `selectedCaseIds` 只有用例 id；`caseSetCount` 是以「选了用例就算 1 个集」推算（L326） |
| 内容步**没有**分类维度 | 平铺列表，`size: 100` 硬上限，无分类树、无搜索 |
| 只挂在项目模式菜单 | 路由 `/projects/new` 在 `NAVIGATION` → `workbench-create`；`GLOBAL_NAVIGATION` 无对应项 |
| 应用内容端点 | `api/iam/ProjectContentController.java` → `POST /api/projects/{projectId}/content` |

**判定**：2.1 是「把已有向导提升到全局 + 来源扩到四类 + 加入分类树」，不是从零造。

### 2.5 执行链（A-1 命令摘要承诺）

| 事实 | 证据 |
|---|---|
| 已有计划摘要绑定 | `application/execution/FrozenPlanService.java` L78 `computePlanSha256(projectId, sourceSelectionId, ...)` |
| 已有执行选择快照与幂等键 | `db/migration/V11_12__execution_selection.sql` → `uk_execution_selection_sha(project_id, sha256)` |
| 已有冻结计划记录 | `domain/caseasset/FrozenSelection.java`（`payloadJson` / `resourceSnapshotJson` / `sha256` / `frozenAt`） |
| 已有资源租约 | `application/execution/LeaseService.java` + `LeaseAdminService` + `LeaseWaitRegistry` |
| 即时发起台存在 | `views/ExecutionPlansView.vue`（566 行，`EP-` 前缀标记本页发起） |
| 计划表无「确认摘要」列 | `db/migration/V6_1__execution_plan.sql` → `execution_plan` 全字段无 `digest` / `confirm` |

**判定**：A-1 只需**把命令清单纳入已有 sha256 链路**，并加「确认态」持久化；不需要引入白泽那套「提案文件 + 平台代执行」模型。

### 2.6 判据层（A-2）

| 事实 | 证据 |
|---|---|
| `test_result` 只有 `verdict` + `failure_msg` | `db/migration/V1__baseline.sql` L473–L489 |
| `verdict` 口径为 `PASS/FAIL/SKIP` + 默认 `PENDING` | 同上 L478 |
| 无判据快照列 | 全库 `grep criteria` 无表/列命中 |
| 报告侧读 `test_result` | v5.6.0 已改为「跑完即入库」写 `test_result` |

**判定**：A-2 是**纯增列 + 机检**，代价小；难点不在存储，而在「指标从哪来」——RF 侧靠 listener、pytest 侧靠 fixture 或标记。**这是本版唯一可能降级的项**。

### 2.7 列表交互（B-3）

| 事实 | 证据 |
|---|---|
| 有统一表格组件，但无列头筛选 | `components/DataTable.vue`（`grep filter` 无筛选实现） |
| 无 `useTableFilters` | `frontend/src/composables/` 下无该文件 |
| 选择逻辑为「id 列表」 | `composables/useSelection.ts` |

**判定**：B-3 是**新增**，不是改造。要动 2–3 个主要列表页 + 后端筛选参数，「服务端全选」需要把选择从「id 数组」改成「筛选条件表达式」。

### 2.8 证据（A-3）

| 事实 | 证据 |
|---|---|
| sha256 逻辑散落在多处 | `api/report/ReportExportController.java`、`api/editor/EditorSaveController.java`、`api/kernel/PytestNicRunController.java`、`application/caseasset/CaseImportService.java` 等 ≥ 10 个文件含 `sha256` |
| 有 `ArtifactVerifier` 与 `EvidenceLogWriter` | `application/execution/` 下 |
| 无统一 `EvidenceBundleService` | 全库无该类 |

**判定**：A-3 是「把散装收成整装」，风险低。

---

### 2.9 手工发起执行的选择范围

| 事实 | 证据 |
|---|---|
| 唯一真源为扁平 `selectedCaseIds`，集合勾选只是批量加/减 | `views/ExecutionPlansView.vue` L39-45、L86-105 |
| 发起只带 `caseIds`，无集合维度、无 `ALL` 模式 | 同文件 L226-233 |
| 用例集 `size:100` 硬上限、用例每页 20 条 | 同文件 L76、`CASE_PAGE_SIZE` |
| **取消集合会误删手工勾选用例**（真实缺陷） | 同文件 L99-101，`keep` 只由剩余集合构成 |

**判定**：S 主题不是从零造，是**修一个已核实的缺陷 + 补一种模式 + 把选择变成可冻结快照**。
快照可挂在 W5 已有的 `FrozenPlanService.computePlanSha256` / `execution_selection` 唯一键链路上，不另起一套。

### 2.10 列表 CRUD 缺口

| 事实 | 证据 |
|---|---|
| A 类（项目 / 模板 / 用例集 / 资产 / 计划 / 报告模板）写端点齐全 | `ProjectController`、`TemplateController`、`CaseSetsView.vue` 等 |
| 执行报告无 PUT / DELETE，页面写调用 0 | `ReportController.java`、`ReportsView.vue` |
| 用户报告无 PUT / DELETE（只有 freeze / revoke / archive） | `TestReportController.java`、`api/testReports.ts` |
| 无覆盖度台账与机检 | 全库无 `check_list_crud.py` / `list-crud-matrix.md` |

**判定**：L 主题的真实工作量是「**B 类补 4 个端点 + 全站一份台账与骨架**」。
「所有列表都要新增」不成立——系统产物类列表的 C 不适用、只读派生类不该有写动作，必须用 A/B/C 分类 + 白名单写死边界。

### 2.11 前端体验基线

| 事实 | 证据 |
|---|---|
| 已有表格壳、语义 token、视觉回归脚本 | `DataTable.vue`、`StyleguideView.vue`、`scripts/browser-regression.mjs` |
| 缺全站列表骨架与两档宽度 / 无竖排的机检断言 | 各页自建工具条；白泽调优页 1440 宽下三列挤压 |

**判定**：U 主题应写成**可机检契约**（溢出、行高、对比度、可访问名、点击数），
不写「界面更美观」这类无法判定的话；现有三个组件是落点，不需要引入新依赖。

### 2.12 设备凭证

| 事实 | 证据 |
|---|---|
| 已有 `management_credential_id` / `bmc_credential_id` 绑定 | `V11_45__asset_credential_binding.sql` |
| 下拉全量平铺，无可用性区分 | `GlobalAssetsView.vue#loadCredentialPicker`、`AssetDetailPanel.vue` 采集弹层 |
| 无验证状态字段；`device_check` 不记 credential_id | `V1__baseline.sql` L201-232 |
| 手工输入不通：`credentialId == null` 直接抛错 | `HostInventoryService.java` L129-141 |
| 请求体直传口令的先例已有 | `DutController.java` L22-52 |

**判定**：C 主题要做的是「**补一张验证证据表 + 一个排序端点 + 一条一次性材料通道**」。
一次性材料**故意不落库**，安全性来自「不落地」，因此必须先用探针确认通用日志切面不打印 body（U-08）。

---

## 3. 八主题可行性评估

### 3.1 主题 E：全局用例资产化（大师 2.2 / 2.3）

| 维度 | 评估 |
|---|---|
| 现状 | 分类数据齐、筛选齐、按分类批量加用例的端点齐；缺归组视图、缺树形勾选、平台集只读 |
| 方案 | ① 全局加「用例集」叶；② 全局用例页加分类树归组；③ 平台集开放写并加 `sample` 标记；④ 首次播种 + 手动重建样例集；⑤ 新建集走树形勾选 |
| 代价 | **中低**。后端新增约 4 个写端点 + 1 个播种服务 + 1 个迁移；前端 1 个新页 + 1 个树组件 + 全局用例页改造 |
| 复用 | `category-filter` 端点、`case_category` 表、`TestSetService` 现有私有集逻辑 |
| 风险 | 平台集从只读变可写是**权限口径变更**，需大师授权；播种要防重复（幂等键 `sample + category_l1`） |
| 可判定性 | 高。每条都能用「库里几行 / 页面几个节点 / 勾选后 items 几条」判定 |

### 3.2 主题 W：全局向导建项目（大师 2.1）

| 维度 | 评估 |
|---|---|
| 现状 | 6 步向导已存在且能用，内容步只有平铺用例 + 资产 |
| 方案 | ① 全局加「新建项目」叶 + `/g/projects` 页头主按钮；② 内容步扩为四类来源（模板 / 用例集 / 用例 / 资产）；③ 用例与用例集走分类树挑选，带已选预览与撤销 |
| 代价 | **中**。向导页改造为主（+1 步或改造 content 步），后端 `POST /content` 扩参数（支持 setIds） |
| 复用 | `ProjectWizardView` 六步骨架、`PROJECT_TEMPLATES`、`globalTemplates`、`globalCaseApi`、`globalAssetApi` |
| 风险 | 内容步变复杂后，向导的「单屏可完成」体验会退化；必须给「跳过」与「已选 N 项」摘要 |
| 可判定性 | 高。建完项目后核对：用例数、集合数、资产绑定数 |

### 3.3 主题 R：执行可信度（A-1 + A-2）

| 维度 | 评估 |
|---|---|
| 现状 | 计划 sha256 已有、选择快照已有、租约已有；缺命令清单与确认态；`test_result` 缺判据列 |
| 方案 | ① 发起前生成命令清单（argv 数组）+ `command_digest`；② `confirmation_digest = sha256(前缀:planId:selectionSha:commandDigest)` 持久化；③ 派发前重算比对，不一致 409；④ `test_result` 加 `criteria_json` / `criteria_verdict` / `criteria_missing`；⑤ 判「通过」必须 `criteria_verdict=PASS`，缺指标判 `FAIL(缺证据)` |
| 代价 | **高**。要动执行链（`FrozenPlanService` → 派发 → `CaseRunService` 落库），要定命令来源抽象（RF 从 dryrun 拿 argv，pytest 从用例声明拿），前端加确认面板 |
| 复用 | `computePlanSha256`、`execution_selection` 幂等键、`LeaseService` |
| 风险 | ① RF `--dryrun` 拿 argv 需要真实用例库，历史上曾因 cwd 漂移失败（记忆「EHT pytest 链路」条）；② pytest 侧「显式声明命令」需要改用例写法，会波及 469 条台账；③ 判据指标来源若无法统一，`criteria_verdict` 会大量为「缺指标」，反而制造噪音 |
| 可判定性 | 高（摘要比对）与中（判据覆盖率） |
| **降级预案** | 若 W5 结束时 pytest 侧命令来源无法稳定产出，则**本版只做 RF 侧**，pytest 侧登记为遗留项 L-57-xx，并写明「未覆盖的用例仍按旧口径判通过」。**不允许**用「全部标记 PASS」凑覆盖率 |

### 3.4 主题 D：证据与列表（A-3 + B-3）

| 维度 | 评估 |
|---|---|
| 现状 | sha256 散落 ≥10 处；表格无列筛选、无服务端全选 |
| 方案 | ① `EvidenceBundleService`：文件名=sha256、两级分片 `ab/cd/`、临时目录 → `ATOMIC_MOVE` → 指针替换、读时重算；② `useTableFilters` + `DataTable` 列头筛选；③ 服务端全选（筛选条件即选择条件） |
| 代价 | **中**。A-3 低（收口）；B-3 中（改 2–3 个列表页 + 后端筛选参数） |
| 复用 | 既有 sha256 工具、`DataTable.vue`、`useSelection.ts` |
| 风险 | 「服务端全选」语义变更会影响「批量执行」等既有动作的入参形状，需要兼容旧 `ids` 数组 |
| 可判定性 | 高 |

---

### 3.5 主题 S：执行选择四模式（补充 1）

| 维度 | 评估 |
|---|---|
| 现状 | 有集 / 用例两种勾选但组合语义有缺陷；无 `ALL`；无选择快照 |
| 方案 | 来源映射重构 → 四模式面板 → 快照冻结（复用 W5 摘要链）→ 大范围选择与上限 |
| 代价 | **中**。1 个迁移 + 1 个解析服务 + 2 个端点 + 1 个 composable + 1 个面板组件；前端发起台改动面大 |
| 复用 | W4 `selection` 分支、W5 `FrozenPlanService`、`RunPrecheckService` |
| 风险 | `ALL` 若绕过租户过滤会跨项目执行（R-57-08）；老 `caseIds` 调用方回归 |
| 可判定性 | 高。TC-v5.7-136 的「集 3 条 + 手工 1 条 → 取消集剩 1 条」是硬对照 |

### 3.6 主题 U：全站前端体验契约（补充 2）

| 维度 | 评估 |
|---|---|
| 现状 | 有 `DataTable` / token / 视觉回归，缺一致骨架与机检判据 |
| 方案 | `ListScaffold` 六槽位 + `ConfirmAction` + 两档宽度 × 两主题 DOM 断言 |
| 代价 | **中高**（页面数量多），但零新依赖；分批接入可把回归面摊薄 |
| 复用 | `DataTable.vue`、`SEMANTIC_CSS_VARS`、`browser-regression.mjs` |
| 风险 | 全站替换一次性做会批量单测红（R-57-12） |
| 可判定性 | 高（溢出 / 行高 / 对比度 / 可访问名都能断言），但**像素差异只做人工确认不做阻断** |

### 3.7 主题 L：列表 CRUD 全覆盖（补充 3）

| 维度 | 评估 |
|---|---|
| 现状 | A 类基本齐全；B 类缺 U/D；无台账 |
| 方案 | A/B/C 分类台账 + 机检 → B 类补 4 个端点 → 项目 / 模板两侧对称 → 逐页接入 + 只读白名单 |
| 代价 | **中**。1 个迁移 + 4 个端点 + 1 个机检 + 逐页接入（页面多但机械） |
| 复用 | W4 `useTableFilters` / 服务端全选；`WorkbenchView` 已有删除确认口径 |
| 风险 | 报告展示层真实表名未核（U-07）；页面漏页（R-57-10） |
| 可判定性 | 高。`check_list_crud.py` 真注入判红还原判绿 |

### 3.8 主题 C：设备凭证（补充 4）

| 维度 | 评估 |
|---|---|
| 现状 | 绑定关系齐、无可用性证据、手工输入不通 |
| 方案 | 验证记录表 + 探测端点 + `options` 四级排序 + 一次性材料通道 + 安全探针 |
| 代价 | **中**。1 个迁移 + 3 个端点 + 1 个服务 + 1 个组件 + 1 个探针 |
| 复用 | `HostAccess.channelOf`、`devicesOfCredential`、`SensitiveMask`、`DutController` 的手工口令口径 |
| 风险 | **R-57-11 最高**：通用日志切面可能打印 body；无真机时不得写「真机验证达成」 |
| 可判定性 | 高。六处零泄漏探针可复跑；排序四级可用固定数据断言 |

---

## 4. 关键设计决策（D 系列）

> 标「已拍板」的是大师已明确；标「待确认」的需在 PRD 评审时定。

| 编号 | 决策 | 状态 | 依据 |
|---|---|---|---|
| D-57-01 | 版本范围＝四主题全量，一个版本交付 | **已拍板**（2026-11-14；第二轮大师追加四条补充，主题扩为 8 个，**仍在一个版本内**） | 大师选择「全量四主题一次做完」 |
| D-57-02 | 样例集生成＝首次自动播种 + 可手动重建 | **已拍板** | 大师选择 |
| D-57-03 | 全局「新建项目」＝项目组加叶 + `/g/projects` 页头主按钮 | **已拍板** | 大师选择 |
| D-57-04 | 平台用例集写权限放宽到 `case:write`（原 `isAdmin`） | **待确认** | 安全口径变更，按硬规矩须大师拍板 |
| D-57-05 | 样例集用 `test_set.sample_flag` + `sample_l1` 做幂等键，不用名字匹配 | 提案 | 名字会被用户改，键一旦漂移就会重复播种 |
| D-57-06 | 命令清单的来源抽象为 `CommandPlanProvider`（RF/pytest 两个实现），不在派发处硬编码 | 提案 | 否则 W5 一定写成一堆 if-else |
| D-57-07 | 判据列加在 `test_result`，不新建表 | 提案 | 与白泽报告 §8.2「最小方案」一致；避免 join |
| D-57-08 | 列表「服务端全选」以「选择条件 JSON」表达，同时兼容既有 `ids` 数组 | 提案 | 不回退既有批量执行调用方 |
| D-57-09 | 证据包两级分片 `ab/cd/<sha>`，不做单层扁平 | 提案 | 白泽 2393 个文件单目录已开始难受 |
| D-57-10 | 全局用例「归组视图」与「筛选视图」共存，用同一 URL 的 `group=1` 切换 | 提案 | 老用户习惯筛选，不能强改 |
| D-57-11 | 列表按 A/B/C 三类定 CRUD 契约；C 类登记白名单并在页面明示只读，不渲染灰按钮 | 提案 | 「所有列表都要增删改查」机械落地会造出无意义的新增按钮与假动作 |
| D-57-12 | 所有删除走软删 + 审计，`test_result` 等原始事实表不在删除范围 | 提案（**含对外数据口径，须大师确认**） | 事实与结论分离；硬删会破坏对外报告可追溯性 |
| D-57-13 | 一次性凭证**故意不落库**，只走 POST body，六处零泄漏由探针守 | 提案（**安全口径，须大师确认**） | 落库即失去「一次性」的安全价值，且引入解密与轮转成本 |
| D-57-14 | 全量回归与文档回写统一放 W10，W6 只做阶段性回归 | 提案 | 两个波次都自称收口会造成重复与口径冲突 |

---

## 5. 明确不做的选项（附理由）

| 不做的方案 | 为什么不做 |
|---|---|
| 照抄白泽「pytest 只写提案、平台代执行」 | 我方 pytest 用例自带 assert 且直接执行，整体换提案式要重写 469 条台账与执行链，收益不匹配 |
| 照抄白泽「同编号即覆盖」 | 我方有版本与基线概念，覆盖会破坏审计链；应保留「覆盖＝新版本」 |
| 照抄白泽 57 探针硬编码 | 我方设备类型更多，硬编码会失控；应做可配置探针包（遗留项，本版不做） |
| 新建 `criteria` 独立表 | 一次运行的判据与用例结论是 1:1，独立表只增加 join 成本 |
| 用 `localStorage` 存列表筛选状态 | 不可分享、刷新丢失；必须 URL 化 |
| 把向导做成独立新页面（不复用 `ProjectWizardView`） | 会产生两套创建逻辑，违反单一真源 |
| 给系统产物类列表加「新增」按钮 | 执行报告 / 运行记录由执行产生，人工新增是假数据，破坏事实链 |
| 给只读派生页放灰掉的「编辑 / 删除」 | 白泽与本报告反复出现的体验问题：不做的动作不该出现 |
| 把一次性手工凭证落进 `credential` 表「方便下次用」 | 与「不落地」的安全前提冲突；需要复用时由用户显式勾选「另存为凭据」 |
| 用「像素完全一致」做视觉回归阻断 | 跨机器字体渲染差异会制造假红；只阻断 DOM 断言，像素差异走人工确认 |

---

## 6. 前置条件与风险登记

### 6.1 前置条件（不满足则对应波次不得开工）

| 编号 | 前置条件 | 影响波次 | 现状 | 确认方式 |
|---|---|---|---|---|
| P-01 | 平台集写权限从 `isAdmin` 放宽到 `case:write` 获大师授权（D-57-04） | W2 | **未确认** | 大师书面拍板 |
| P-02 | 迁移空号复核（拟定 `V11_58` ~ `V11_61`） | W1 | 待复核 | `ls db/migration/*.sql \| sort -V \| tail -1` |
| P-03 | 生产/测试库一级分类分布与「有文本无 id」用例数（U-01、U-02） | W2（归组口径） | 未查 | 两条 COUNT 查询 |
| P-04 | pytest 插件侧可配合加判据采集 | W5 | 未确认 | 用例库仓库可改，或退化为后端解析 junit |
| P-05 | 测试环境具备 4 个角色账号（admin / pm / engineer / viewer） | W2 起全部 | 未确认 | 查 `app_user` 与角色绑定 |
| P-06 | 平台集存量数据确认（U-05） | W2（播种幂等） | 未查 | `SELECT COUNT(*) FROM test_set WHERE project_id IS NULL` |
| P-07 | 构建与测试在 WSL `~/robot-autotest` 执行 | 全部 | 已满足 | 既有环境 |
| P-08 | 浏览器回归可用（13 CDP 组依赖有 Chrome） | 收口 | 已满足（有 Chrome 即可跑通） | `agent-browser doctor` 或既有回归 |
| P-09 | 报告展示层真实表名核实（U-07） | W8（W57-31） | **未查** | `SHOW TABLES` + `ReportController` 查询 SQL |
| P-10 | 通用日志切面是否打印 POST body（U-08） | W9（W57-40） | **未查** | 查 filter / 切面；未通过不得实现手工输入 |
| P-11 | 大师批准「报告软删」与「一次性凭证 POST body」两项口径（D-57-12 / 13） | W8、W9 | **未确认** | 大师书面拍板 |
| P-12 | 迁移空号复核扩到 `V11_58` ~ `V11_64` | W1 | 待复核 | `ls db/migration/*.sql \| sort -V \| tail -1` |

### 6.2 风险登记

| 编号 | 风险 | 影响 | 应对 |
|---|---|---|---|
| R-57-01 | 服务端全选改变批量执行入参，既有调用方回归 | 高 | 保留 `ids` 分支，新增 `selection` 分支；两分支各有单测 |
| R-57-02 | 判据指标来源不统一，`criteria_verdict` 大量「缺指标」 | 中高 | 先只在 pytest 侧试点（样本可控），RF 侧按 `tag` 逐步接；覆盖率写进 TEST-PLAN 判据 |
| R-57-03 | 播种样例集与用户手改冲突 | 中 | 幂等键用 `sample_l1` 而非名字；用户改过的集打 `user_modified=1`，重建时跳过并提示 |
| R-57-04 | 平台集开放写后，跨项目副本同步语义不清 | 中 | 本版只做「平台集可写 + 项目引用仍只读取副本」，不做双向同步；同步留遗留项 |
| R-57-05 | 向导内容步加分类树后首屏变慢 | 低 | 分类树懒加载子节点，用例分页取前 200，超出走搜索 |
| R-57-06 | 迁移 `V11_58` 与并行分支撞号 | 低 | 开工前 `ls db/migration/*.sql \| sort -V \| tail -1` 复核；已应用迁移不可改只能追加 |
| R-57-07 | 命令摘要重算在派发时因 argv 顺序不稳定而误报 409 | 中 | digest 对 argv 做规范化（排序环境变量、固定分隔符），并有单测锁住 |
| R-57-08 | `ALL` 模式绕过租户过滤，跨项目执行 | **高** | 复用 `ProjectScope` 查询，两项目数据对照单测；不通过则关闭 `ALL` |
| R-57-09 | 报告展示层表名判断错导致迁移返工 | 中 | W1 出口判据加「表名复核记录」；错了只追加新迁移，不改已应用文件 |
| R-57-10 | 列表台账漏页，收口仍有缺口 | 中 | 用路由表自动生成候选清单 + 机检兜底，逐页勾验 |
| R-57-11 | 一次性凭证被日志切面 / 异常堆栈泄漏 | **最高** | 探针六处零泄漏 6/6 才放行；发现任一处命中即阻断本波 |
| R-57-12 | 全站骨架替换导致批量单测红 | 中 | 分批接入，保留旧工具条直到全绿 |

---

## 7. 波次建议

| 波次 | 内容 | 依赖 | 可独立验收 |
|---|---|---|---|
| W1 | 迁移 `V11_58`（`test_set.sample_*`、`test_result.criteria_*`、`execution_plan.confirm_*`）+ 领域模型 | 无 | 是（迁移执行 + 表结构核对） |
| W2 | 主题 E：全局用例集菜单 + 平台集可写 + 播种 + 树形勾选建集 | W1 | 是 |
| W3 | 主题 W：全局向导入口 + 四类来源 + 分类树 | W1、W2（用到平台集） | 是 |
| W4 | 主题 D 的 B-3：列头筛选 + 服务端全选 | 无 | 是 |
| W5 | 主题 R：命令清单 + 摘要确认 + 判据层 | W1 | 是（RF 侧可独立验收） |
| W6 | 主题 D 的 A-3：证据包接入 + **阶段性**回归 | W2–W5 | 是 |
| W7 | 主题 S：选择快照 + 四模式解析 + 发起台重构 + 大范围选择 | W4、W5 | 是 |
| W8 | 主题 L：软删迁移 + 覆盖度台账 / 机检 + B 类 U/D + 两侧对称 + 逐页补齐 | W1、W4 | 是 |
| W9 | 主题 C：验证记录 + options / verify 端点 + 一次性通道 + 选择器组件 + 探针 | W1 | 是 |
| W10 | 主题 U：`ListScaffold` + 两档宽度机检 + **全量回归与文档回写** | W7–W9 | 否（最终收口波） |

**排期纪律**：W4 与 W5 无相互依赖，可以并行；W2 → W3 有强依赖（向导要引用平台集）；W7 / W8 / W9 改动面不重叠（执行台 / 列表页 / 资产页）可并行；**W10 必须最后**且承担唯一的全量收口。

---

## 8. 授权与待办

| 编号 | 事项 | 归属 |
|---|---|---|
| A-57-01 | 版本号 5.7.0 落号 | 大师 |
| A-57-02 | D-57-04 平台集写权限放宽 | 大师 |
| A-57-03 | `V11_58` 起用迁移号 | 学徒（开工前复核） |
| A-57-04 | 判据指标来源是否接受「pytest 先行、RF 逐步」 | 大师（PRD 评审时定） |
| A-57-05 | D-57-12 报告 / 运行记录开放软删（数据保留口径） | 大师 |
| A-57-06 | D-57-13 允许一次性凭证以 POST body 传入（安全口径） | 大师 |
| A-57-07 | `V11_62` ~ `V11_64` 迁移号开工前复核 | 学徒（W1 前） |
| A-57-08 | P-09 / P-10 两个阻断型未知项核实并留记录 | 学徒（W8 / W9 前） |

---


---

## 9. 被实测推翻的假设（回写）

| # | 原假设（本轮之前写的） | 实测结果 | 影响 |
|---|---|---|---|
| O-57-01 | 「B 类系统产物列表要从零补 CRUD」 | 端点侧只需补 `PUT/DELETE` 与软删列；`ReportController` 的 GET 与 `ReportExportController` 子路径已存在 | 工作量从「重做」降为「补 4 个端点 + 骨架」 |
| O-57-02 | 「平台集写端点会踩唯一键冲突」 | `test_set` 唯一键是**生成列** `scope_project_id`，服务层自查 `set_code` 即可；实测 409 文案正确 | 无需改表结构 |
| O-57-03 | 假定入参校验能挡住超长名（认为会 400） | **实测 500「服务内部错误」**：`varchar(128)` 溢出冒到全局异常 | 已修：入口按列宽校验，返回 400 并点名字段 |
| O-57-04 | 「执行报告展示层是独立表」 | 实为 `run` 表；用户报告是 `test_report`（U-07 已查实） | 迁移只加列，未新表 |
| O-57-05 | 「`test_set_item` 列名是 `set_id/case_id`」 | 实际是 **`test_set_id` / `test_case_id`** | 冒烟脚本首版取数失败（已按真实列名改） |
| O-57-06 | 认为同一选择预览会不断新增快照行 | 实测三次预览同 `selectionSha256`，`execution_selection_snapshot` 只 1 行（唯一键复用） | 幂等成立，无需额外去重 |
| O-57-07 | `TestReportsView` 未接 `ListScaffold` | 实测已接（3 处引用 + 5 处 CRUD 调用），只有 `ReportsView`/`GlobalTemplatesView`/`GlobalAssetsView` 未接 | L-57-05 表述已订正 |
| O-57-08 | 归组接口可能返回空树（担心分类脏数据） | 实测返回真实树，`l1Id/l2s[].count` 均有值，脏数据兜到「未分类」 | 无需补数据清洗迁移 |

---

## 10. 修订记录

| 日期 | 修订 | 原因 |
|---|---|---|
| 2026-11-14 | 第三轮：§9 被实测推翻的假设 8 条逐条改掉（含 O-57-03 超长名 500 缺陷） | 测试执行推翻原判断，按流程第 7 道回写 |
| 2026-11-14 | 初稿 | 基于白泽报告 + 大师 2026-11-14 三条补充需求与三项范围决策 |
| 2026-11-14 | 第二轮补充 | 新增 §2.9~2.12 现状、§3.5~3.8 可行性、D-57-11~14 决策、P-09~12 前置、R-57-08~12 风险、W7~W10 波次；主题 4→8、FR 15→30、WBS 26→46、波次 6→10 |
