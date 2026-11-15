# v5.9.0 全站列表 CRUD 台账（v5.8.0 续版 · W59-20/21）

## 口径

- **枚举来源**：`frontend/src/config/navigation.ts`（全局 `GLOBAL_NAVIGATION` 5 组 16 叶 + 项目侧 `NAVIGATION` 7 组）与 `frontend/src/router/index.ts`（列表 / 数据型路由，非详情 / 编辑器 / 打印 / 403 / 登录）。
- **分类**：
  - **A** —— 用户可创建，C/R/U/D 全；
  - **B** —— 系统产物，R + 元数据编辑 + 软删 + 恢复 + 导出，**C（新增）不适用**；
  - **C** —— 只读派生，白名单 + 理由。
- **现状列怎么写**：按端点与页面代码核实，**不按目标倒推**。本次实际打开核对过的真源是
  `api/reports.ts`、`api/testReports.ts`、`api/execution.ts`、`api/globalAssets.ts`、
  `api/listCrud.ts`、`api/credentialOptions.ts`、`api/cases.ts`，以及
  `RunsView.vue` / `TestReportsView.vue` / `CaseSetsView.vue` / `GlobalAssetsView.vue` /
  `ReportsView.vue` / `global/GlobalTemplatesView.vue` 的页面结构。
  只读派生页按 `router/index.ts` 的组件注册与 `navigation.ts` 的元数据登记，
  **未逐一打开其页面源码**：这一点如实保留，不伪装成「已核实」。
- **缺口只看 C/R/U/D 四项能力**（v5.9.0 口径订正）。`ListScaffold` 外壳、`ConfirmAction`
  弹窗、批量勾选这些是**交互形式**，不是「页面缺了 CRUD」：把「没换外壳」记成缺口，
  会让台账永远有 6 条清不掉的假缺口，真正缺的能力反而被淹掉。
- **缺口列**：`无` / `不适用` 表示按目标已闭合；其余为具体未闭合项，
  **必须列入 `## 缺口白名单`**，否则 `tools/check_list_crud.py` 判红（白名单外缺口即 FAIL）。

## 列表 CRUD 台账

| 路由 | 页面/模块 | 分类 | 现状 | 目标 | 缺口 | 对应 FR | 白名单理由 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `/cases` | 项目用例列表 `CasesView.vue` | A | `caseApi.page` + create/update/remove 端点齐备（api/cases.ts 已核对）；页面既有 C/R/U/D | A：C/R/U/D 全 | 无 | W57-32 | - |
| `/cases/sets` | 用例集 `CaseSetsView.vue` | A | `caseSetApi.page/create/update/remove/items/reorder` 齐备；本次接 URL 化筛选 + `useServerSelection` 全选 | A：C/R/U/D 全 + 列表三件套 | 无 | W57-15/16/17 | - |
| `/assets` | 项目资产 `AssetsView.vue` | A | `asset.ts` 的 device/credential 分页与增删改端点齐备（api/asset.ts 已核对） | A：C/R/U/D 全 | 无 | W57-32 | - |
| `/plans` | 测试计划 `ExecutionPlansView.vue` | A | 计划编排端点齐备；页面既有增删改查 | A：C/R/U/D 全 | 无 | W57-32 | - |
| `/g/projects` | 全局项目 `GlobalProjectsView.vue` | A | `/api/projects/cards` + 项目 CRUD 端点；由另一代理维护 | A：C/R/U/D 全 | 无 | W57-09 | - |
| `/g/cases` | 公共用例 `GlobalCasesView.vue` | A | 公共用例分页/导入端点；由另一代理维护，本次未改 | A：C/R/U/D 全 | 无 | W57-32 | - |
| `/g/case-sets` | 全局用例集 `GlobalCaseSetsView.vue` | A | 平台用例集端点（`api/platformCaseSets.ts`）；由另一代理维护 | A：C/R/U/D 全 | 无 | W57-06 | - |
| `/g/iam/roles` | 全局角色 `iam/IamRolesView.vue` | A | `api/iam.ts` 角色 CRUD + `api/iamRelations.ts` 关系聚合；v5.9.0 新增页 | A：C/R/U/D 全 | 无 | W59-14~19 | - |
| `/g/iam/groups` | 用户组 `iam/IamGroupsView.vue` | A | `groups.ts` 组 CRUD + 成员增删 + 关系聚合；v5.9.0 新增页（组授权参与三源并集判权） | A：C/R/U/D 全 + 成员增删 | 无 | W59-14~19 | - |
| `/g/iam/users` | 全局用户 `iam/IamUsersView.vue` | A | `userApi.page/update/remove` + 关系聚合；v5.9.0 新增页（删用户同事务清 `project_member_role`/`user_group_member`） | A：C/R/U/D 全 | 无 | W59-14~19 | - |
| `/g/iam/projects` | 项目关联 `iam/IamProjectLinksView.vue` | A | 项目 ↔ 用户/组关联查询与解除；`user_group_member`/`project_group_role` 双向可见 | A：C/R/U/D 全（关联的解绑与重建） | 无 | W59-14~19 | - |
| `/g/assets` | 公共资产 `GlobalAssetsView.vue` | A | `globalAssetApi.list/createDevice/createCredential/remove/rename` 齐备；**W59-20 补 `PUT /api/global/assets/{type}/{id}` 改名**（仅改显示名：型号/序列号/管理 IP 由采集与导入回写，手工改会在下次采集被覆盖）。资产信息回写仍走 `POST /{id}/collect`、引用关系走 `reference/unreference` | A：C/R/U/D 全（U = 改名；技术参数由采集回写，不走表单） | 无 | W57-41/42 + **W59-20** | - |
| `/g/templates` | 公共模板 `GlobalTemplatesView.vue` | A | 模板列表端点齐备；页面既有内置/自定义页签与增删改；W59-20 起删除改走统一确认弹窗（含「已建项目不受影响、模板不可恢复」） | A：C/R/U/D 全 | 无 | W57-32/43 + **W59-20** | - |
| `/reports/templates` | 报告模板 `ReportTemplatesView.vue` | A | 模板端点齐备；页面既有 CRUD | A：C/R/U/D 全 | 无 | W57-32/43 | - |
| `/settings/users` | 用户管理 `SettingsUsersView.vue` | A | `api/iam.ts` 的 userApi.page/update/remove 齐备 | A：C/R/U/D 全 | 无 | W57-32 | - |
| `/reports` | 执行报告 `ReportsView.vue` | B | `reportsApi.page` 读端点 + `ReportCrudController` 的 `PUT /api/reports/{runId}` 与 `POST /api/reports/batch-delete`；**W59-20 补批量软删**（勾选 → 确认带条数 → 逐条软删 → 回执报真删条数与导出/产物影响面，单条失败不整体回滚） | B：R + 元数据编辑 + 软删 + 批量软删 + 导出，无「新增」 | 无 | W57-32/35~37/43 + W58-08 + **W59-20** | - |
| `/runs` | 运行记录 `RunsView.vue` | B | `executionApi.page` 读 + `listCrudApi.patchRun/deleteRun/restoreRun/runImpact`；本次接 URL 筛选（不接全选）、软删/恢复/备注编辑、`ConfirmAction`（影响面来自 `/impact`）、`ReadOnlyNotice` | B：R + 元数据编辑 + 软删 + 恢复，无「新增」 | 无 | W57-15/32/35~37/43 | - |
| `/test-reports` | 对外测试报告 `TestReportsView.vue` | B | `testReportsApi.list` 读 + `listCrudApi.updateTestReport/deleteTestReport/restoreTestReport`；本次接 `ListScaffold` 六槽位 + 行内编辑/软删/恢复/批量 + 冻结态「请先撤销冻结」 | B：R + 元数据编辑 + 软删 + 恢复 + 导出，无「新增」 | 无 | W57-32/35~37/43 | - |
| `/workspace` | 项目工作区 `WorkspaceView.vue` | B | 工作区任务/导出记录由系统产生；端点见 `api/workspace` | B：R + 元数据编辑 + 导出 | 不适用（软删/恢复不适用：任务与导出记录是执行留痕，删掉就断链，故只提供查看与重新导出） | W57-43 | 见下方「不适用项的判定」 |
| `/sync` | 存量同步 `SyncView.vue` | B | 同步批次为系统产物；端点见 `api/sync` | B：R + 元数据编辑 + 导出 | 不适用（软删/恢复不适用：批次是同步作业的审计留痕，页面只提供预览/执行/回滚三个动作） | W57-43 | 见下方「不适用项的判定」 |
| `/imports` | 导入批次 `ImportsView.vue` | B | 导入批次为系统产物；公共用例导入记录端点见 `api/platformCaseSets.ts` | B：R + 元数据编辑 + 导出 | 不适用（软删/恢复不适用：导入批次是数据来源凭证，删除会让「这批用例从哪来」无法追溯） | W57-43 | 见下方「不适用项的判定」 |
| `/admin/audit` | 审计日志 `AuditView.vue` | B | 审计记录系统产生、只可查；`auditApi.exportCsv` 走 `exportCsv()`，失败时由 `localizeError` 给出可读原因（W59-20 核对：导出失效说明已随错误提示落地，不再是缺口） | B：R + 导出 | 无 | W57-35 | - |
| `/workbench` | 工作台 `WorkbenchView.vue` | C | 跨项目聚合看板，只读派生；`router/index.ts:67` 注册，无写端点 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 跨项目聚合只读看板：数据来自多个项目卡片接口汇总，无独立实体、无主键，创建/编辑/删除均无处落脚 |
| `/overview` | 项目看板 `OverviewView.vue` | C | 单项目指标聚合，只读派生；W59-20 起「最近完成报告」表可直接软删单条（走与 `/reports` 同一个 `deleteReport` 端点，不是第二套删除逻辑）；记录本身仍归属 `/reports` | C：只读派生（报告行删除是转发到报告域的动作，不是本页实体的 CRUD） | 不适用（只读派生） | W57-32 + **W59-20** | 指标聚合视图：数值由运行/用例统计实时算出，改动必须回到源记录，页面上不存在可编辑实体 |
| `/cases/board` | 测试总表 `CasesBoardView.vue` | C | 历史/当前双计数看板，只读派生；`router/index.ts:337` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 双计数统计视图：展示用例库历史与当前计数对比，写操作归属用例列表页，不属于本页职责 |
| `/runs/failure-clusters` | 失败聚类 `FailureClustersView.vue` | C | 运行下钻派生，只读；`router/index.ts:153` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 运行失败聚类是 `/runs` 的下钻聚合，筛选某次运行的失败用例，本身不持有记录 |
| `/plans/groups` | 计划组 `PlanGroupsView.vue` | A | 原登记「C 类只读」**有误**：页面实有 `planGroupApi.create / update / remove / addMembers / removeMember / reorder / apply`（`PlanGroupsView.vue:74,92,106,119,138,149`；api 见 `api/planGroups.ts:42-90`），是完整可写页 | A：C/R/U/D 全 + 成员增删 / 排序 / 应用 | 无 | W57-32 | - |
| `/plans/graph` | 依赖图 `PlanGraphView.vue` | C | 计划依赖图，只读派生；`router/index.ts:146` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 依赖图是计划关系的可视化投影，无独立实体，增删改必须回到 `/plans` |
| `/nodes` | 执行节点 `NodesView.vue` | C | 节点健康只读；`router/index.ts:251` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 节点是执行器上报的运行时状态，页面只读展示在线/负载，节点注册由部署链路管理 |
| `/kernels` | 内核自检 `KernelsView.vue` | C | RF/pytest 内核可用性只读；`router/index.ts:281` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 内核自检是部署环境的探测结果，页面只回答「能不能跑」，不持有可编辑记录 |
| `/environment/overview` | 环境总览 `EnvOverviewView.vue` | C | 只读派生；`router/index.ts:342` 注册，导航注明「5 个只读二级页」 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 环境域按 MENU-01 定义为只读二级页，数据来自 `/asset-info` 采集结果，编辑入口在资产链路 |
| `/environment/collect` | 环境采集 `EnvCollectView.vue` | C | 只读派生；`router/index.ts:347` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 采集结果展示页，数据由采集任务产生，页面不负责创建或删除采集记录 |
| `/environment/topology` | 环境拓扑 `EnvTopologyView.vue` | C | 只读派生；`router/index.ts:352` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 拓扑是链路定义的可视化投影，连接编辑归属「测试资产 → 链路」，此处只读 |
| `/environment/prepare` | 环境准备 `EnvPrepareView.vue` | C | 只读派生；`router/index.ts:357` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 环境准备检查项由探针回读，页面只呈现就绪状态，不持有可写实体 |
| `/environment/analysis` | 环境分析 `EnvAnalysisView.vue` | C | 只读派生；`router/index.ts:362` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 分析页是环境指标的聚合视图，数值由采集数据实时计算，无独立 CRUD |
| `/environment/connections` | 连接与拓扑 `EnvConnectionsView.vue` | C | 只读派生（FR-v4.3-07 注明「连接由链路定义，编辑入口在链路」）；`router/index.ts:369` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 连接项来自 `/asset-info`，本页明确不做连接编辑，编辑入口在资产链路页 |
| `/maintenance/principles` | 维护原则 `MntPrinciplesView.vue` | C | 只读派生；`router/index.ts:375` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 原则页是静态说明文档，非数据网格，不存在 CRUD 对象 |
| `/maintenance/records` | 维护记录 `MntRecordsView.vue` | C | 只读派生；`router/index.ts:380` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 维护记录由维护链路写入，导航将其归入「维护与恢复」只读二级页，页面只做检索 |
| `/maintenance/recover` | 恢复 `MntRecoverView.vue` | C | 只读派生；`router/index.ts:385` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 恢复页呈现备份可恢复点，恢复动作由后端演练脚本执行，页面不持有可编辑记录 |
| `/baselines/iperf` | 基线 `BaselineIperfView.vue` | C | 只读派生；`router/index.ts:391` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 性能基线是采集结果的冻结快照，页面只读对比，写入归属采集链路 |
| `/baselines/perftest` | 基线 `BaselinePerftestView.vue` | C | 只读派生；`router/index.ts:396` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 同上：冻结快照只读，不提供页面级增删改 |
| `/baselines/ccl` | 基线 `BaselineCclView.vue` | C | 只读派生；`router/index.ts:401` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 同上：冻结快照只读，不提供页面级增删改 |
| `/eht/cases` | EHT 用例 `EhtCasesView.vue` | C | 只读派生（EHT 八页由 `check_eht_case_ui.py` 守）；`router/index.ts:295` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | EHT 用例页绑定真实用例库快照，只读展示与筛选，写入由用例库工作树外链路管理 |
| `/admin/ops` | 运维 `OpsView.vue` | C | 只读派生；`router/index.ts:203` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 运维页是运行时状态汇总，无独立实体，操作由运维脚本执行 |
| `/g/settings` | 全局设置 `GlobalSettingsView.vue` | C | 平台配置只读入口；`router/index.ts:453` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 设置项由服务端偏好与配置中心管理，页面是配置入口的容器，不持有可 CRUD 的列表实体 |
| `/scenarios` | 场景 `ScenariosView.vue` | A | 原登记「C 类只读」**有误**：页面实有 `scenarioApi.create / regroup / remove`（`ScenariosView.vue:82,106,118`；api 见 `api/cases.ts:275-303`），U 由 `regroup`（编组一层用例集）表达 | A：C/R/U/D 全（U = `regroup` 编组） | 无 | W57-32 | - |
## 读数（与上表逐行对应；`tools/check_list_crud.py` 按同一份表统计）

| 分类 | 行数 | 含义 |
| --- | --- | --- |
| A | 17 | 用户可创建，C/R/U/D 全（本版新增 `/g/iam/*` 四页） |
| B | 7 | 系统产物：R + 元数据编辑 + 导出，C 不适用 |
| C | 22 | 只读派生：逐条写理由 |
| 合计 | 46 | 全站列表 / 数据型路由总数（`navigation.ts` + `router/index.ts` 实读） |

> v5.9.0 更新：**缺口条数 6 → 0**。两条是补齐（`/reports` 批量软删、`/g/assets` 改名），
> 三条是判定「本页不适用」并写明理由（`/workspace`、`/sync`、`/imports` 的软删/恢复），
> 一条是口径订正（`/g/templates`、`/reports/templates` 的 `ListScaffold` 属外壳而非 CRUD 能力，
> 见上方口径第 4 条）。另 `/admin/audit` 的「导出失效说明」经核对已随 `localizeError` 落地。
> 新增 `/g/iam/*` 四页（A 类），合计 42 → 46。

> 口径订正记录：发布说明与 CHANGELOG 曾写「37 页」，与本表 42 行不符且找不到出处，
> 已按本表读数改为 **42 页**（A 13 / B 7 / C 22）。`/g/assets` 由「C/R/U/D 全」订正为
> 「C/R/D 全 + 采集回写」，`/plans/groups` 与 `/scenarios` 由 C 类订正为 A 类。
> 这三条都不是格式问题，是**机检只看形状不看事实**留下的假绿（见 `06-ACCEPTANCE-AUDIT.md` §四）。


## 本轮判定（v5.9.0 / W59-20）

> DEV-PLAN W59-20 点名五处，逐处给结论。判据是**行为变化**：接上之后用户真的多能做什么。

| 位置 | 判定 | 变化 | 证据 |
| --- | --- | --- | --- |
| 项目看板报告列表（`OverviewView.vue`） | **接** | 「最近完成报告」每行可直接删除，走与 `/reports` 同一个软删端点 | `OverviewView.spec.ts`；`digest-delete-{runId}` |
| `/reports` | **接** | 新增勾选列 + 批量软删：确认框带已选条数，后端逐条删并回报真删条数与导出/产物影响面 | `ReportsView.spec.ts` 5 例；`DataTable.spec.ts` 勾选 2 例；`ReportLifecycleServiceTest` 批量 3 例 |
| `/g/assets` | **接** | 新增「改名」：`PUT /api/global/assets/{type}/{id}`，只改显示名（技术参数由采集回写） | `GlobalAssetsView.spec.ts`；`GlobalAssetRenameTest` 3 例 |
| 用例删除（`TestCaseService.delete`） | **接** | 被 `case_script_binding` 引用时 409，并**点名**「脚本绑定 N 条」，不再只报「删除失败」 | `TestCaseDeleteGuardTest` 3 例 |
| 全局用例集删除（`GlobalCaseSetsView.vue`） | **接** | 删除前二次确认，文案含「{count} 条用例关联会一并解除、用例本身不受影响」 | `GlobalCaseSetsView.spec.ts` 2 例（确认 / 取消） |

**全站确认弹窗**：`window.confirm(` 的**代码调用**为 0 处（`src/composables/useConfirm.ts`
的注释里仍保留该字样，用于说明替换原因；机检口径按「调用」而非「字符串」判定，见 TEST-PLAN §8.3）。

## 缺口白名单

> 只有列在这里的路由允许「缺口」列写具体未闭合项；每条理由必须写清归属 / 端点就绪状态 /
> 未逐一核源码的局限，**不接受「暂时不做」「待定」「后续再说」这类占位**。
>
> **v5.9.0 本表为空（0 条）**：所有行的缺口列要么是 `无`，要么是 `不适用（…理由…)`。
> 空表不是「把缺口删掉装绿」—— 原先 6 条的去处逐条写在下一节「不适用项的判定」里。

## 不适用项的判定（v5.9.0 / W59-20）

> 「不适用」是本版正式引入的第三态：不是「还没做」，而是**这一页按设计就不该有这个动作**。
> 每条必须说清「为什么不该有」，否则退回缺口。

| 路由 | 动作 | 为什么这里不该有 |
| --- | --- | --- |
| `/workspace` | 软删 / 恢复 | 工作区任务是执行链路的运行态记录，导出记录是对外交付凭证。删掉它们等于把「这次跑过什么、交付了什么」从系统里抹掉；需要隐藏时用状态与时间筛选，不用删除。 |
| `/sync` | 软删 / 恢复 | 同步批次本身就能回滚（`projectSyncApi.rollback`），回滚是业务动作、有补偿语义；再叠一层软删会让「已回滚」与「已删除」两种终态混淆。 |
| `/imports` | 软删 / 恢复 | 导入批次回答的是「这批数据从哪来」，是数据血缘凭证。删除批次会让引用它的用例失去来源，属于破坏追溯，界面不提供该动作。 |

## 本次落地范围与降级

- **v5.9.0 新增落地（W59-20~22）**：`ReportsView.vue` 批量软删（`selectable` + `POST
  /api/reports/batch-delete`）、`OverviewView.vue` 看板行删除、`GlobalAssetsView.vue` 改名
  （`PUT /api/global/assets/{type}/{id}`）、`TestCaseService.delete` 引用守卫（409 点名引用方）、
  `GlobalCaseSetsView.vue` 删除二次确认；`DataTable.vue` 新增可选勾选列（默认关闭，既有页面 DOM 不变）。
- **v5.8.0 已落地**：`ReportsView.vue` 行内编辑（`getRun` 回填 → `updateReport`）与软删
  （确认后 `deleteReport`，回执展示后端返回的影响面）；对应 spec 3 例。
- **v5.7.0 已落地**：`RunsView.vue`（URL 筛选 + 软删/恢复/备注编辑 + 后端影响面确认）、
  `TestReportsView.vue`（ListScaffold 六槽位 + 行内编辑/软删/恢复/批量 + 冻结提示）、
  `CaseSetsView.vue`（成员/集合 URL 筛选 + `useServerSelection`）、
  `GlobalAssetsView.vue`（`CredentialPicker` 三处接入）。
- **组件层存在但按口径不计缺口**：`ListScaffold.vue` / `ConfirmAction.vue` / `ReadOnlyNotice.vue`
  是交互外壳，与「页面有没有 CRUD」无关；`listCrud.ts` 的能力已全部有页面在用。
- **只读派生页（C 类）**：不做 CRUD，逐条理由写在台账末列；现状按路由注册与导航元数据登记，
  未逐一打开页面源码 —— 这一局限保留，不伪装成「已核实」。
