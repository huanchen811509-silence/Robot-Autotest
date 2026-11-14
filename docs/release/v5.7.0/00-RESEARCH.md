# v5.7.0 需求调研：白泽对标与两轮补充需求的现状盘点

> 流程位置：① 需求调研（`docs/process/版本开发流程.md` §2.1）。
> 证据分级：**[核实]** 有代码/表结构/接口输出为证；**[推断]** 由已核实事实推导，未实测；**[未知]** 需下一阶段确认。
> 版本号 v5.7.0 为拟用号；本文件**不修改 `VERSION`**，落号以 G 门判定与大师授权为准。

---

## 1. 本轮输入

| 来源 | 内容 |
|---|---|
| 大师 2026-11-14 指示 | ① 充分借鉴白泽优点，基于主程序完善；② 三条补充需求（向导建项目 / 分类归组与用例集 / 全局用例集菜单）；③ 编写 PRD、开发计划、测试计划 |
| 白泽调研评估报告 | `E:\workspace\自动化测试\调研报告-白泽\白泽平台调研评估报告-20261114.md`（20 页菜单实操 + 324 个 Java 文件 + 96 张表核对） |
| 大师三项范围决策 | v5.7.0 全量四主题；样例集首次自动播种 + 可手动重建；全局加叶 + 页头主按钮 |
| 大师 2026-11-14 第二轮补充 | ④ 手工发起执行可选全部 / 用例集 / 用例 / 组合；⑤ 前端全站操作逻辑与布局优化；⑥ 全局与项目两侧列表都要 CRUD（点名执行报告 / 用户报告 / 项目 / 模板 / 用例集）；⑦ 设备选凭证自动关联已验证可用项 + 支持手工输入 |

---

## 2. 三条补充需求的拆解

| 编号 | 大师原话要点 | 本质问题 | 本文件对应章节 |
|---|---|---|---|
| Q1 | 全局要支持向导式创建项目，可选模板 / 用例集 / 用例 / 测试资产 | 创建项目时**素材来源**是否可组合 | §4.3 |
| Q2 | 全局公共用例按表格一二级分类归类；用例集按一级分类做样例；支持手工建集并基于分类单个用例勾选（类似选择树） | 分类从**筛选条件**升级为**组织与选择手段** | §4.1、§4.2 |
| Q3 | 全局模式也需要「用例集」二级菜单 | 全局缺少**集合视角**，只有清单视角 | §4.2 |
| Q4 | 手工发起执行可选全部 / 用例集 / 用例 / 组合 | 发起台的**选择范围模型**是否能表达集合与单用例的并集 | §3.7 |
| Q5 | 前端全站按操作顺畅 / 布局合理 / 便捷 / 直观优化 | 体验要求如何变成**可机检判据**而非主观评价 | §3.9 |
| Q6 | 所有列表支持增删改查（尤其执行报告 / 用户报告 / 项目 / 模板 / 用例集） | 哪些列表**真的缺** CRUD，哪些只是缺统一契约 | §3.8 |
| Q7 | 设备选凭证自动关联已验证可用项，并支持手工输入 | 「可用」是否有**证据来源**；手工材料如何不落地 | §3.10 |

Q2 是核心：它同时要求「看得到分类」（归类）、「用得上分类」（建集）、「自动有样例」（播种），三件事共用同一套分类数据。

---

## 3. 现状事实盘点

### 3.1 全局菜单

| 事实 | 级别 | 证据 |
|---|---|---|
| 全局导航为 5 组 14 叶 | [核实] | `frontend/src/config/navigation.ts` → `GLOBAL_NAVIGATION` |
| 「公共用例」组仅 2 叶（用例清单 / 导入记录） | [核实] | 同上，`global-cases.children.length === 2` |
| 全局无「用例集」入口 | [核实] | 14 个叶 key 中无 `case-set` 相关项 |
| 全局无「新建项目」入口 | [核实] | `global-projects` 仅 `-all` / `-mine` |
| 项目模式有「新建项目」叶（`project:create`） | [核实] | `NAVIGATION` → `workbench-create` → `/projects/new` |
| 菜单唯一真源，全局与项目共用同一套过滤逻辑 | [核实] | `navTreeFor(mode, kind)`、`filterNavByCapabilityPredicate` |

### 3.2 分类体系

| 事实 | 级别 | 证据 |
|---|---|---|
| 分类字典表两级（level 1/2 + parent_id + sort_no） | [核实] | `db/migration/V11_32__global_case_library.sql` |
| 分类作用域含平台与项目 | [核实] | `V11_41__project_case_category.sql` |
| 用例表有一级/二级分类文本列与 id 列 | [核实] | `category_l1`（V11_32）、`category_l2`（V11_3）、`category_l1_id`/`category_l2_id`（V11_41） |
| 全局用例页已有一/二级**筛选** | [核实] | `views/global/GlobalCasesView.vue` L49–L100 |
| 分类查询接口已存在 | [核实] | `api/globalCases.ts` L102 `/global/cases/categories`、L106 `categoriesInScope` |
| **无**按分类归组的树形浏览 | [核实] | 该页无树组件，只有 `category-item` 平铺列表 |
| 历史数据存在「有文本无 id」的行 | [推断] | V11_42 只回填 `COUNT(*)=1` 的唯一匹配组，非唯一的仍为空 |
| 分类字典无平台级种子数据 | [核实] | 迁移中 `case_category` 无 INSERT 语句 |

### 3.3 用例集

| 事实 | 级别 | 证据 |
|---|---|---|
| 项目用例集完整 CRUD + 成员 + 排序 + 立即执行 | [核实] | `api/caseasset/TestSetController.java`（11 个端点） |
| 按分类批量加用例的端点**已存在** | [核实] | 同上 L131 `POST /{id}/items/category-filter`；前端 `api/cases.ts` L257 |
| 平台集作用域模型已完整 | [核实] | `V9_3__case_source_version.sql` L16 `project_id` 改为 NULL；`owner_scope='PLATFORM'` |
| 平台集接口只有 2 个 GET 且仅管理员 | [核实] | `api/caseasset/PlatformCaseSetController.java`；`TestSetService.requirePlatformAdmin()` |
| 项目集加成员是**单条下拉**，无树形勾选 | [核实] | `views/CaseSetsView.vue` L278–L290 |
| 无样例集概念（无 sample 标记） | [核实] | `test_set` 无 `sample` 相关列 |

### 3.4 项目向导

| 事实 | 级别 | 证据 |
|---|---|---|
| 六步向导已存在 | [核实] | `views/ProjectWizardView.vue` L29 `ALL_STEPS` |
| 已有模板选择与内容选择 | [核实] | L156 `selectTemplate`；L63–L80 加载公共用例与资产 |
| 内容步为平铺列表，`size: 100` 硬上限 | [核实] | L69–L71 |
| 内容步**无**集合维度 | [核实] | 只有 `selectedCaseIds` / `selectedAssetIds` |
| 内容应用端点存在 | [核实] | `api/iam/ProjectContentController.java` → `POST /api/projects/{projectId}/content` |
| 从全局进入向导后**无**返回目标区分 | [核实] | 页面无 `from` 参数处理 |

### 3.5 执行链与证据

| 事实 | 级别 | 证据 |
|---|---|---|
| 计划摘要 sha256 已存在 | [核实] | `FrozenPlanService.computePlanSha256` |
| 执行选择快照有幂等键 | [核实] | `V11_12__execution_selection.sql` → `uk_execution_selection_sha` |
| 资源租约已实现 | [核实] | `LeaseService` / `LeaseAdminService` / `LeaseWaitRegistry` |
| `execution_plan` 无确认摘要列 | [核实] | `V6_1__execution_plan.sql` 全字段核对 |
| `test_result` 只有 verdict + failure_msg | [核实] | `V1__baseline.sql` L473–L489 |
| 全库无 criteria 相关表/列 | [核实] | `grep -r "criteria" db/migration` 无命中 |
| sha256 逻辑散落 ≥ 10 个文件 | [核实] | `grep -rln "sha256" backend/src/main/java` |
| 无统一 `EvidenceBundleService` | [核实] | 全库无该类 |

### 3.6 列表交互

| 事实 | 级别 | 证据 |
|---|---|---|
| `DataTable.vue` 无列级筛选 | [核实] | `grep filter` 无筛选实现 |
| 无 `useTableFilters` | [核实] | `composables/` 下无该文件 |
| 选择逻辑为 id 列表 | [核实] | `composables/useSelection.ts` |

---

### 3.7 手工发起执行的选择范围（Q4）

| 事实 | 分级 | 证据 |
|---|---|---|
| 唯一真源是扁平 `selectedCaseIds: number[]`，用例集勾选只是「批量加/减 id」 | [核实] | `frontend/src/views/ExecutionPlansView.vue` L39-45、L86-105 |
| 发起载荷只带 `caseIds`，后端不知道选了哪些集、也没有 `mode=ALL` | [核实] | 同文件 L226-233；`frontend/src/api/execution.ts` `runCases` 签名 |
| **没有「执行全部用例」模式** | [核实] | 全文件无 `ALL` / 全选用例入口；选择区只有「选用例集」「选用例」两卡 |
| 用例列表每页 20 条，用例集列表 `size:100` 硬上限 | [核实] | 同文件 `CASE_PAGE_SIZE = 20`、L76 `caseSetApi.page(..., {page:1,size:100})` |
| **组合语义有真实缺陷**：取消集合时用「剩余集合并集」重建已选，手工单独勾选的用例被误删 | [核实] | 同文件 L99-101：`keep = new Set(selectedSetIds.flatMap(...))` 不含 `MANUAL` 来源 |
| W4 计划中的 `selection`（条件对象）可复用作 `ALL` 模式的载荷 | [推断] | DEV-PLAN W57-16 设计；尚未实现，开工前需复核空号与接口 |

### 3.8 列表 CRUD 现状（Q6）

| 事实 | 分级 | 证据 |
|---|---|---|
| 项目：`PUT/DELETE /api/projects/{id}` 齐全，页面有 `projectApi.update` / `remove` | [核实] | `api/iam/ProjectController.java` L107/L122；`views/WorkbenchView.vue` L144/L177 |
| 模板：`PUT /content`、`PUT /{id}`、`POST /status`、`DELETE /{id}`、`POST /copy` 齐全 | [核实] | `api/iam/TemplateController.java`；`views/global/GlobalTemplatesView.vue` 5 处写调用 |
| 用例集 / 资产 / 计划 / 报告模板有完整写端点与页面动作 | [核实] | `CaseSetsView.vue`、`AssetsView.vue`、`PlansView.vue`、`ReportTemplatesView.vue` |
| **执行报告只有 GET + POST parse，无 PUT / DELETE** | [核实] | `api/report/ReportController.java`；`views/ReportsView.vue` 写调用 0，仅跳转与导出 |
| **用户报告有 GET / POST / freeze / revoke / archive / export，无 PUT / DELETE** | [核实] | `api/report/TestReportController.java`；`api/testReports.ts` 无 `update/remove` |
| 运行记录页写调用 0（取消等动作可能以别的 API 命名，需 DEV 阶段逐个核） | [推断] | `views/RunsView.vue` 粗筛；**不得据此断言「完全没有写操作」** |
| 全站无统一的列表 CRUD 覆盖度台账与机检 | [核实] | 全库无 `check_list_crud.py`、`list-crud-matrix.md` |

**结论**：Q6 不是「所有列表都没有 CRUD」，而是「**B 类系统产物列表缺编辑与删除 + 全站缺可机检的覆盖度契约**」。
A 类（项目 / 模板 / 用例集等）基本齐全，本轮重点是两侧对称与统一骨架。

### 3.9 前端体验基线（Q5）

| 事实 | 分级 | 证据 |
|---|---|---|
| 已有统一表格壳（空态 / 骨架 / 粘性表头 / `maxHeight`） | [核实] | `components/DataTable.vue`（253 行） |
| 已有语义变量体系与机检 | [核实] | `views/StyleguideView.vue` 读 `SEMANTIC_CSS_VARS`；`tools/check_styleguide_tokens.py` |
| 已有浏览器视觉回归脚本 | [核实] | `scripts/browser-regression.mjs` |
| 缺全站列表工具条 / 批量条 / 分页位的一致性骨架 | [核实] | 各页自建工具条，列头筛选尚未实现（DEV-PLAN W4 计划中） |
| 无「1440 / 1280 无横向溢出、无文字竖排」断言 | [核实] | `browser-regression.mjs` 现有断言未覆盖；白泽调优页在 1440 宽下三列挤压、字样竖排（见白泽报告截图） |

### 3.10 设备凭证现状（Q7）

| 事实 | 分级 | 证据 |
|---|---|---|
| 设备已有管理 / BMC 凭证绑定列 | [核实] | `V11_45__asset_credential_binding.sql`；`GlobalAssetsView.vue` 表单 `managementCredentialId` / `bmcCredentialId` |
| 下拉是全量平铺，**不区分可用性** | [核实] | `GlobalAssetsView.vue` `loadCredentialPicker()` → `globalAssetApi.list({type:'CREDENTIAL'})`；`AssetDetailPanel.vue` 采集弹层同理 |
| `credential` 表无验证状态；`device_check` 有 `check_result` 但**不记 credential_id** | [核实] | `V1__baseline.sql` L201-215、L217-232；`domain/asset/Credential.java` 字段 |
| `host_inventory` 采集成功也不记用了哪条凭证 | [推断] | `HostInventoryService.collect` 审计 detail 只含 host / channel / status |
| **手工输入在设备采集链路不通**：`credentialId == null` 且非本机直接抛错 | [核实] | `application/host/HostInventoryService.java` L129-141；`GlobalAssetController.collect` 只接 `credentialId` + `username` |
| 「请求体直传口令且不回显」在系统里已有先例可复用 | [核实] | `api/execution/DutController.java` L22-52（`password` 优先 `credentialId` 时取请求体） |
| 反查「凭证绑定了几台设备」已有端点 | [核实] | `GlobalAssetService.devicesOfCredential()`、`globalAssets.ts#credentialDevices` |

---

## 4. 白泽对照：哪些前提在我方成立

### 4.1 名单与前提核验

| 白泽优点 | 我方是否有前提 | 结论 |
|---|---|---|
| 执行前命令摘要承诺 | 有（`computePlanSha256` + `execution_selection` 幂等键） | **可借**，挂在既有链路而非另起一套 |
| 判据层（status vs conclusion 分离） | 有（`verdict` 已是「事实」字段） | **可借**，加列 + 机检即可 |
| 证据包内容寻址 + 原子发布 | 部分有（sha256 工具散落，无统一服务） | **可借**，属收口 |
| 列表三件套（列头筛选 / URL 化） | 有（`DataTable` + router） | **可借**，但全选要改服务端语义 |
| 设备快照 + 拓扑 confidence | 无快照版本化 | 本版**不借**（前置未做） |
| 同步执行接口 | 我方已是异步 + WebSocket 实时日志 | **不借**（我方差） |
| 租约无 TTL | 我方 `LeaseService` 已有点 | **不借**（白泽是缺陷） |
| 手写 OOXML/SVG | 我方已有成熟导出链 | **不借**（重造成本） |

### 4.2 白泽的缺陷不能一起搬进来

白泽实测到的 13 条缺陷里，有 3 条是**设计层面的**，若照搬会直接污染我方：

| 白泽缺陷 | 若照搬的后果 | 我方对策 |
|---|---|---|
| 租约无 TTL，一次重启积压 18 条 `claim-unresolved` | 执行永久挂起，只能人工解锁 | 不引入新租约机制，复用既有 `LeaseService` |
| 非 GET 必须精确 `Origin`，否则 403 | CI/脚本集成被挡 | 不改既有校验 |
| 无 SSE，运行中看不了日志 | 体验倒退 | 保留我方 WebSocket |

---

## 5. 未知项（需 DEV 阶段确认）

| 编号 | 未知 | 为什么现在定不了 | 确认方式 |
|---|---|---|---|
| U-01 | 一级分类是否都有二级分类 | 取决于现场分类字典维护情况 | 查生产/测试库 `case_category` 分布 |
| U-02 | 历史「有文本无 id」的用例有多少 | V11_42 只回填唯一匹配组 | `SELECT COUNT(*) FROM test_case WHERE category_l1 IS NOT NULL AND category_l1_id IS NULL` |
| U-03 | pytest 侧能否稳定产出判据 | 需改用例写法或加 fixture，涉及 469 条台账 | W57-22 结束时看采集率 |
| U-04 | RF 侧 argv 能否不依赖 dryrun 稳定产出 | 历史上 cwd 漂移踩过坑 | W57-18 单测 + 真实用例验证 |
| U-05 | 平台集是否有存量数据 | 只读期是否有人建过 | `SELECT COUNT(*) FROM test_set WHERE project_id IS NULL` |
| U-06 | 1000 条勾选上限是否够 | 取决于最大分类规模 | 查最大一级分类的用例数 |
| U-07 | 报告展示层的真实表名（`test_run` / `test_report` 还是派生视图） | 执行报告由 run 派生，未逐表核 | W57-31 开工前 `SHOW TABLES` + 查 `ReportController` 查询 SQL |
| U-08 | 通用请求日志是否打印 POST body | 决定一次性凭证会不会被日志切面泄漏 | 查日志切面 / filter，W57-40 前必须核 |
| U-09 | 现场是否存在用例数 > 10 万的项目 | 决定 `ALL` 模式是否需要渐进解析 | 查 `test_case` 按项目分组 COUNT |
| U-10 | 测试环境是否有可连通的真实设备 | 决定凭证验证是实测还是 mock | 有真机则实测；无真机用 mock SSH，**报告须写明未接真机** |

**U-01 与 U-02 影响 FR-v5.7-02 的「未分类」兜底口径**，需在开工前查一次库；若「有文本无 id」比例高，归组接口必须双轨分组（PRD AC-02-5 已预留）。

**U-07 / U-08 是第二轮的两个阻断型未知项**：前者决定 `V11_63` 迁移写在哪张表（写错整波返工），
后者决定一次性凭证会不会从日志切面泄漏（泄漏即安全缺陷）。两者都必须在 W8 / W9 开工前核实并留记录。

---

## 6. 结论

1. **三条补充需求全部落在「已有能力缺 UI / 缺组织」上**，不是从零造：分类数据齐、按分类批量加用例的端点齐、向导骨架齐、平台集模型齐。
2. **白泽四项可借里，两项是轻活（证据包、列表），两项是重活（命令摘要、判据层）**；重活必须按波次做，且判据层有明确降级预案。
3. **白泽的缺陷不能连带搬入**（租约无 TTL、Origin 强校验、无实时日志），这三条我方已有更好的实现。
4. 六个未知项都有确定的确认方式，其中 U-01/U-02 建议开工前查库确认，避免归组口径返工。
5. **第二轮四条补充里，三条是「已有能力缺契约」、一条是「已实现但有缺陷」**：发起台组合选择会误删手工勾选用例（§3.7），
   列表 CRUD 的缺口集中在 B 类系统产物（§3.8），凭证缺的是「可用性证据」与「手工通道」（§3.10），
   前端缺的是可机检判据而非组件能力（§3.9）。
6. **不要把「所有列表都要 CRUD」机械理解成「所有列表都要新增按钮」**：系统产物类列表的「新增」不适用，
   只读派生类不应有写动作；PRD §11 用 A/B/C 三类 + 白名单把这条边界写成可判定契约。

---

## 7. 修订记录

| 日期 | 修订 | 原因 |
|---|---|---|
| 2026-11-14 | 初稿 | 基于白泽报告实测结论 + 本轮 6 项代码核查（导航 / 分类 / 用例集 / 向导 / 执行链 / 列表） |
| 2026-11-14 | 第二轮补充 | 新增 Q4~Q7 与 §3.7~3.10 十项代码核查；补 U-07~U-10 四个未知项；结论补两条（缺陷定位与 CRUD 边界） |
