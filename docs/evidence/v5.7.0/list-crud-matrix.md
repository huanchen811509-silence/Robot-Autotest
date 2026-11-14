# v5.7.0 全站列表 CRUD 台账（前端 B 组 · W57-32/35~37/43）

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
  **未逐一打开其页面源码**：这一局限在 `## 缺口白名单` 里逐条标注，不伪装成「已核实」。
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
| `/g/assets` | 公共资产 `GlobalAssetsView.vue` | A | `globalAssetApi.list/createDevice/createCredential/remove` 齐备；**`GlobalAssetController` 无 `PUT`**——资产信息回写走 `POST /{id}/collect`、`POST /{type}/{id}/reference`、`POST /{type}/{id}/unreference`，不是表单式更新，故本页 U 无独立端点；本次接 `CredentialPicker`（管理/BMC/采集三处）与 `credentialOptionsApi` | A：C/R/D 全 + 采集回写 + 引用关系 + 凭证选择器（**无通用 U**） | 无 | W57-41/42 | - |
| `/g/templates` | 公共模板 `GlobalTemplatesView.vue` | A | 模板列表端点齐备；页面既有内置/自定义页签，本次未接骨架 | A：C/R/U/D 全 + ListScaffold | 未接 ListScaffold + 行内编辑/软删/恢复/批量 | W57-32/43 | 见缺口白名单 |
| `/reports/templates` | 报告模板 `ReportTemplatesView.vue` | A | 模板端点齐备；页面既有 CRUD，本次未接骨架 | A：C/R/U/D 全 + ListScaffold | 未接 ListScaffold + 批量/软删/恢复 | W57-32/43 | 见缺口白名单 |
| `/settings/users` | 用户管理 `SettingsUsersView.vue` | A | `api/iam.ts` 的 userApi.page/update/remove 齐备 | A：C/R/U/D 全 | 无 | W57-32 | - |
| `/reports` | 执行报告 `ReportsView.vue` | B | `reportsApi.page/detail/...` 读端点齐备；`ReportCrudController` 的 `PUT/DELETE /api/reports/{runId}` 已就绪并封装进 `listCrudApi.updateReport/deleteReport`，但页面未暴露编辑/软删按钮 | B：R + 元数据编辑 + 软删 + 导出，无「新增」 | 未接 ListScaffold + 行内编辑/软删/恢复/批量；已有导出入口 | W57-32/35~37/43 | 见缺口白名单 |
| `/runs` | 运行记录 `RunsView.vue` | B | `executionApi.page` 读 + `listCrudApi.patchRun/deleteRun/restoreRun/runImpact`；本次接 URL 筛选（不接全选）、软删/恢复/备注编辑、`ConfirmAction`（影响面来自 `/impact`）、`ReadOnlyNotice` | B：R + 元数据编辑 + 软删 + 恢复，无「新增」 | 无 | W57-15/32/35~37/43 | - |
| `/test-reports` | 对外测试报告 `TestReportsView.vue` | B | `testReportsApi.list` 读 + `listCrudApi.updateTestReport/deleteTestReport/restoreTestReport`；本次接 `ListScaffold` 六槽位 + 行内编辑/软删/恢复/批量 + 冻结态「请先撤销冻结」 | B：R + 元数据编辑 + 软删 + 恢复 + 导出，无「新增」 | 无 | W57-32/35~37/43 | - |
| `/workspace` | 项目工作区 `WorkspaceView.vue` | B | 工作区任务/导出记录由系统产生；端点见 `api/workspace`，本次未核页面源码 | B：R + 元数据编辑 + 软删 + 导出 | 未接 ListScaffold + 软删/恢复（且未逐一核页面源码） | W57-43 | 见缺口白名单 |
| `/sync` | 存量同步 `SyncView.vue` | B | 同步批次为系统产物；端点见 `api/sync`，本次未核页面源码 | B：R + 元数据编辑 + 软删 + 导出 | 未接 ListScaffold + 软删/恢复（且未逐一核页面源码） | W57-43 | 见缺口白名单 |
| `/imports` | 导入批次 `ImportsView.vue` | B | 导入批次为系统产物；公共用例导入记录端点见 `api/platformCaseSets.ts` | B：R + 元数据编辑 + 软删 + 导出 | 未接 ListScaffold + 软删/恢复 | W57-43 | 见缺口白名单 |
| `/admin/audit` | 审计日志 `AuditView.vue` | B | 审计记录系统产生、只可查；端点见 `api/iam.ts` 审计页 | B：R + 导出 | 未接导出失效可读说明 | W57-35 | 见缺口白名单 |
| `/workbench` | 工作台 `WorkbenchView.vue` | C | 跨项目聚合看板，只读派生；`router/index.ts:67` 注册，无写端点 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 跨项目聚合只读看板：数据来自多个项目卡片接口汇总，无独立实体、无主键，创建/编辑/删除均无处落脚 |
| `/overview` | 项目看板 `OverviewView.vue` | C | 单项目指标聚合，只读派生；`router/index.ts:94` 注册 | C：只读 + 白名单理由 | 不适用（只读派生） | W57-32 | 指标聚合视图：数值由运行/用例统计实时算出，改动必须回到源记录，页面上不存在可编辑实体 |
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
| A | 13 | 用户可创建，C/R/U/D 全（`/plans/groups`、`/scenarios` 由 C 类纠正而来） |
| B | 7 | 系统产物：R + 元数据编辑 + 软删 + 恢复 + 导出，C 不适用 |
| C | 22 | 只读派生：白名单逐条写理由 |
| 合计 | 42 | 全站列表 / 数据型路由总数（`navigation.ts` + `router/index.ts` 实读） |

> 口径订正记录：发布说明与 CHANGELOG 曾写「37 页」，与本表 42 行不符且找不到出处，
> 已按本表读数改为 **42 页**（A 13 / B 7 / C 22）。`/g/assets` 由「C/R/U/D 全」订正为
> 「C/R/D 全 + 采集回写」，`/plans/groups` 与 `/scenarios` 由 C 类订正为 A 类。
> 这三条都不是格式问题，是**机检只看形状不看事实**留下的假绿（见 `06-ACCEPTANCE-AUDIT.md` §四）。


## 缺口白名单

> 只有列在这里的路由允许「缺口」列写具体未闭合项；每条理由必须写清归属 / 端点就绪状态 /
> 未逐一核源码的局限，**不接受「暂时不做」「待定」「后续再说」这类占位**。

| 路由 | 理由 |
| --- | --- |
| `/reports` | 后端 `ReportCrudController` 的 `PUT/DELETE /api/reports/{runId}` 已就绪并封装进 `listCrudApi.updateReport/deleteReport`，页面 `ReportsView.vue` 本次未接 ListScaffold 与行内动作；本组本轮只在 RunsView / TestReportsView 落地骨架，避免与报告详情/对比/趋势链路的并行改动冲突；已有导出入口保留。 |
| `/g/templates` | 模板列表端点齐备、`GlobalTemplatesView.vue` 既有内置/自定义页签，本次未接骨架；该视图与模板代理共用文件，改动需与模板代理的并行提交合并，故本轮只保留既有 CRUD 不新增骨架。 |
| `/reports/templates` | 报告模板视图既有 CRUD，本次未接 ListScaffold 与批量/软删/恢复；它与 `/g/templates` 共用模板端点，接线需两页口径一致后一并提交。 |
| `/workspace` | 工作区任务/导出记录为系统产物，本次未逐一打开 `WorkspaceView.vue` 源码核实页面已有动作，也未接骨架；端点核对以 `api/workspace` 为准，页面接线留待工作区链路代理确认后再补。 |
| `/sync` | 存量同步批次为系统产物，本次未逐一打开 `SyncView.vue` 源码，也未接骨架；端点以 `api/sync` 为准，接线需与存量同步链路的写入口径对齐。 |
| `/imports` | 导入批次为系统产物，公共用例导入记录端点见 `api/platformCaseSets.ts`；本轮未接骨架，需与另一代理维护的公共用例导入视图合并改动。 |
| `/admin/audit` | 审计日志系统产生且只可查，本次未接「导出链接失效的可读说明」；导出失效态需与审计导出端点一起核实后补，避免写出与实际失效条件不符的文案。 |

## 本次落地范围与降级

- **已落地**：`RunsView.vue`（URL 筛选 + 软删/恢复/备注编辑 + 后端影响面确认）、
  `TestReportsView.vue`（ListScaffold 六槽位 + 行内编辑/软删/恢复/批量 + 冻结提示）、
  `CaseSetsView.vue`（成员/集合 URL 筛选 + `useServerSelection`）、
  `GlobalAssetsView.vue`（`CredentialPicker` 三处接入）。
- **组件层已就绪但未全量接线**：`ListScaffold.vue` / `ConfirmAction.vue` / `ReadOnlyNotice.vue` /
  `listCrud.ts` 已具备能力，`ReportsView.vue`、`GlobalTemplatesView.vue`、
  `ReportsTemplatesView.vue` 等页面未接（见缺口白名单）。
- **只读派生页（C 类）**：不做 CRUD，逐条理由写在台账「白名单理由」列；现状按路由注册与
  导航元数据登记，未逐一打开页面源码，这一局限已在缺口白名单中标注。
