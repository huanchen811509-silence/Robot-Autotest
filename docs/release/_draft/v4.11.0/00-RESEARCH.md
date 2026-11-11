# v4.11.0 需求调研（① 调研）

> **存放位置说明**：本批文档暂放 `docs/release/_draft/v4.11.0/`。
> 原因：`tools/check_release_docs.py` 判「已启动」的判据是目录内出现 `00-RESEARCH.md` 或 `01-ASSESSMENT.md`，
> 一旦判为已启动就要求六份产物齐全，缺一即 FAIL（门禁第 07 步）。
> 本版尚未到 ④ 开发计划 / ⑤ 测试计划 / ⑥ 测试报告 阶段，直接放 `docs/release/v4.11.0/` 会让门禁从「30 PASS / 2 FAIL」退化为 3 红。
> **收口动作**：⑥ 完成后把本目录整体上移一级为 `docs/release/v4.11.0/`，`_draft` 目录删除。

| 项 | 内容 |
|---|---|
| 编号 | `RA-RS-v4.11-001` |
| 版本 | `v4.11.0`（待批准，未开工） |
| 基线 | `v4.10.0` / commit `fd2311e`（2026-11-11） |
| 调研时间 | 2026-11-11 17:40 ~ 18:10（GMT+8） |
| 调研对象 | 前端菜单信息架构（`frontend/src/config/`）、外壳与顶栏（`App.vue` / `AppHeader.vue` / `AppNav.vue`）、全局与项目双域后端端点 |
| 证据口径 | 每条事实标 `[已核实]`（可追到 file:line）/ `[推断]`（由已核实事实推出，未单独验证）/ `[假设]`（无证据，需实测或拍板） |

## 1. 需求原文（2026-11-11 大师补充）

| # | 原文 | 归口 |
|---|---|---|
| N1 | 全局模式的菜单系统也要支持一级 二级菜单（风格 布局 逻辑 都要和项目模式对齐）；甚至需要评估必要的项目模式下的菜单 和功能要移动到全局模式 请先补充这个评估 | 本文件 §3 + `01-ASSESSMENT.md` §2、§3 |
| N2 | 右上角 管理员按钮 要放到最右边 其他的（放大镜 铃铛 AI助手 亮色暗色 中文英文）按合理的建议 重新排序 | 本文件 §4 + `01-ASSESSMENT.md` §4 |
| N3 | 执行与报告菜单下 需要一个 执行计划 的单独二级菜单 用例发起执行 | 本文件 §5 + `01-ASSESSMENT.md` §5 |
| N4 | 结合待拍板事项 一起生成一个 PRD 文档 用例软件的下一步迭代 | 本文件 §6 + `PRD.md` |

## 2. 术语与边界

- **全局模式**：跨项目的治理视角，路由前缀 `/g/*`，当前 5 个一级项全部是叶子。
- **项目模式**：单项目执行闭环，路由无前缀，当前 7 个一级组 / 32 个二级项。
- **一级 / 二级**：一级 = 侧栏可折叠分组头；二级 = 分组内可点击叶子。二者都进 `NavNode` 树。
- 本文只调研**菜单与入口**，不涉及各页面内部信息架构（页面内 tab 不算二级菜单）。

## 3. 事实清单 A：全局模式菜单

| # | 事实 | 分级 | 证据 |
|---|---|---|---|
| A1 | 全局导航 `GLOBAL_NAVIGATION` 共 **5 项，全部是叶子**（无 `children`）：项目 `/g/projects`、公共用例 `/g/cases`、公共资产 `/g/assets`、模板 `/g/templates`、系统设置 `/g/settings` | [已核实] | `frontend/src/config/navigation.ts:69-100` |
| A2 | 全局导航**不做能力过滤**：`App.vue` 里 `uiMode.isGlobal` 分支直接返回 `GLOBAL_NAVIGATION`；项目分支才走 `filterNavByCapabilityPredicate` | [已核实] | `frontend/src/App.vue:84-89` |
| A3 | 全局命名空间共 **12 条路由**：5 个一级页 + `/g/settings/ai` + `/g/admin` + `/g/admin/audit` + `/g/about` + `/g/changelog` + 3 条详情（`/g/cases/:id`、`/g/assets/:id`、`/g/templates/:id`） | [已核实] | `frontend/src/router/index.ts:339-430` |
| A4 | 「系统设置」的 6 个二级目标**只在页面内**用 `RouterLink` 列表渲染，**不进侧栏** | [已核实] | `frontend/src/views/global/GlobalSettingsView.vue:20,32-39` |
| A5 | 两模式设置项由 `SETTINGS_NAV` 单一真源派生（名称/顺序/能力 diff = 0），项目路径由去 `/g` 前缀得到 | [已核实] | `frontend/src/config/settingsNav.ts:24-32,41-55` |
| A6 | 侧栏渲染层 `AppNav.vue` **已经支持分组**：`NavNode` 泛型入参，分组渲染 chevron + `aria-expanded` + 展开态持久化 `localStorage['robot.nav.expanded']` | [已核实] | `frontend/src/components/AppNav.vue:28,76-129` |
| A7 | `activeGroupKey()` **只遍历项目 `NAVIGATION`**，不认全局树 → 全局若改成分组，激活组不会被判定、也不会自动展开 | [已核实] | `frontend/src/config/navigation.ts:410-415` |
| A8 | 全局准入按**角色**在路由守卫拦：只允许 admin / pm 进入 `/g/*`（`canEnterGlobalMode`）；顶栏模式切换按钮同口径 | [已核实] | `frontend/src/router/index.ts:457-468`；`frontend/src/components/AppHeader.vue:45-48` |

## 4. 事实清单 B：项目模式菜单

| # | 事实 | 分级 | 证据 |
|---|---|---|---|
| B1 | 项目导航 **7 个一级 / 32 个二级**：工作台 4、执行与报告 6、用例 3、资产 3、环境与拓扑 6、维护与恢复 4、系统设置 6 | [已核实] | `frontend/src/config/navigation.ts:133-345`（逐组 children 计数） |
| B2 | 「执行与报告」为合并组，6 项按 `section: run / report` 分两段，渲染时只画 1 条细分隔线，不引三级层级 | [已核实] | `navigation.ts:184-239`；`AppNav.vue:110-115` |
| B3 | 项目「系统设置」组 6 项由 `SETTINGS_NAV` 派生，与全局同名同序 | [已核实] | `navigation.ts:111-131` |
| B4 | 项目导航按服务端能力集过滤，`has()` 来自 `stores/menuCapabilities`，未就绪时不渲染导航（防「先全显再收起」） | [已核实] | `App.vue:84-89`；`navigation.ts:457-478` |
| B5 | 菜单可见性真源是服务端能力集，前端不再有硬编码角色→能力表 | [已核实] | `navigation.ts:457-464`；`stores/menuCapabilities.ts` |

## 5. 事实清单 C：顶栏

| # | 事实 | 分级 | 证据 |
|---|---|---|---|
| C1 | 顶栏 DOM 顺序：`brand` → `header-actions`（**模式段**（项目/全局）→ **上下文段**（项目选择）→ 分隔线 → **工具段**（搜索 / 通知 / AI）→ 分隔线 → **账户段**）→ `header-quick`（主题、语言） | [已核实] | `frontend/src/components/AppHeader.vue:69-168` |
| C2 | 主题 / 语言按钮位于 `header-actions` **之外**的 `app-header-quick` 容器，因此**视觉最右是主题与语言，账户按钮不在最右** | [已核实] | `AppHeader.vue:162-167`；样式 `AppHeader.vue:210-216` |
| C3 | 账户菜单内部分四区：状态（只读）→ 外观 → 管理（按能力显隐）→ 会话 | [已核实] | `frontend/src/components/AccountMenu.vue:10,181-187` |
| C4 | 既有断言口径：三段顺序 + `.app-sep` **恰 2 条** + 可聚焦控件 **7（有 AI）/ 6（无 AI）**，且计数范围**只在 `header-actions` 内**（主题/语言当时不在里面，所以没被计入） | [已核实] | `frontend/src/components/AppHeader.spec.ts:5,58-65,93-124` |
| C5 | 把主题/语言并入工具段后，可聚焦控件数将变 **9 / 8**，`.app-sep` 仍为 2 条 → 需同步改这 2 处断言 | [推断] | 由 C1+C4 推出，未实跑 |

## 6. 事实清单 D：执行与报告

| # | 事实 | 分级 | 证据 |
|---|---|---|---|
| D1 | 「执行与报告」现有 6 个二级：运行记录 `/runs`、**测试计划** `/plans`、执行节点 `/nodes` ‖ 报告列表 `/reports`、报告模板 `/reports/templates`、外部报告 `/test-reports` | [已核实] | `navigation.ts:184-239` |
| D2 | 其中「测试计划」是 **cron 定时计划**：`/api/plans` 提供 CRUD + `pause` / `resume` / `trigger-now` / `preview`，列表列含 `cron`、`nextFire`、`lastResult` | [已核实] | `backend/src/main/java/com/qa/autotest/api/plan/PlanController.java:38-78`；`frontend/src/views/PlansView.vue:386-410` |
| D3 | 「用例发起执行」的**能力已存在**：`runCases(projectId, {caseIds, environmentId, concurrency, timeoutSeconds, testTimeoutSeconds, includeTag, runRequestId})`，另有 `safetyPrecheck` 做安全级预检 | [已核实] | `frontend/src/api/execution.ts:181-190,268-273,279-282` |
| D4 | 现有发起入口**只在用例列表**：勾选用例后展开执行表单再提交 | [已核实] | `frontend/src/views/CasesView.vue:796-828` |
| D5 | 「执行计划」若只做「一次性批量发起」，与现有「测试计划」（cron）**职责会重叠**，PRD 必须写清边界，否则用户分不清两个「计划」 | [推断] | 由 D1+D2+D4 推出 |

## 7. 事实清单 E：后端双域能力

| # | 事实 | 分级 | 证据 |
|---|---|---|---|
| E1 | 全局命名空间**只有 4 个域**：`/api/global/projects`、`/api/global/templates`、`/api/global/assets`、`/api/global/cases` | [已核实] | `GlobalProjectController.java:26`、`TemplateController.java:24`、`GlobalAssetController.java:31`、`GlobalCaseController.java:35` |
| E2 | **没有** `/api/global/runs`、`/api/global/reports`、`/api/global/plans` | [已核实] | 全仓 `@RequestMapping("/api/global` 仅 E1 四处 |
| E3 | 用例 / 资产 / 项目 / 模板 四域同时具备项目域与全局域两套端点（例：`CaseController` 类级 `/api/projects/{projectId}` + `/cases/{id}`，`GlobalCaseController` 类级 `/api/global/cases`） | [已核实] | `api/caseasset/CaseController.java:29,88`；`api/caseasset/GlobalCaseController.java:35,150-179` |

## 8. 事实清单 F：待拍板与遗留（v4.10.0 结转）

| # | 事项 | 状态 | 证据 |
|---|---|---|---|
| F1 | L-49-03 同一工作簿二次预览返回首批结果（`replayed=true`），取不到增量 diff | 大师已拍板**排入 v4.11** | `docs/release/v4.10.0/05-TEST-REPORT.md` §7 |
| F2 | L-49-06 `check_tables` 判红：`ProjectSyncView.vue`(2)、`GlobalAssetsView.vue`(1)、`GlobalCasesView.vue`(3) 共 6 处原生 `<table>` | 大师已拍板**单独立项 v4.11** | 同上 §7 |
| F3 | L-49-04 `CategoryTree.vue`、`TemplateWorkbenchView.vue` 无组件单测 | 结转 | 同上 §7 |
| F4 | L-49-05 浏览器级 3 条（04 / 16 / 45）未执行，因 CDP `:9222` 被取消 | 结转（依赖 CDP 恢复） | 同上 §7 |
| F5 | L-49-07 模板 `SCENARIO` 项生成时只建场景壳，`case_set_ids` 恒为空 | 结转 v4.11 | 同上 §7 |
| F6 | 门禁 32 步 = 30 PASS / 2 FAIL（第 13 步 CDP、第 06 步 `check_tables`）；G4 / G5 / G7 / G8 均未判 | 现状 | `docs/evidence/opt-20261111/integration/gates-20261111-1642.log` |

## 9. 需求与事实的缺口对照

| 需求 | 现状差距 | 是否已有可复用件 |
|---|---|---|
| N1 全局两级菜单 | 全局树 5 项全叶子（A1）；渲染层已支持分组（A6）但激活组判定不认全局树（A7）；全局不过能力过滤（A2） | `AppNav.vue` 可原样复用；`SETTINGS_NAV` 已可作设置组数据源 |
| N1 迁移评估 | 全局后端只有 4 个域（E1/E2）；执行/报告/环境/维护无全局端点 | 无，需新建或明确不迁 |
| N2 顶栏重排 | 账户按钮不在最右（C2）；主题/语言在独立容器（C1） | 纯前端改动 + 2 处断言同步（C5） |
| N3 执行计划 | 无该菜单项；发起能力已有（D3），但入口只在用例列表（D4）；与 cron 计划命名冲突（D5） | `runCases` + `safetyPrecheck` 可直接复用，预计 0 迁移 |

## 10. 未确认事项（[假设]，需在 ② 评估或后续实测中收敛）

| # | 假设 | 影响 | 收敛方式 |
|---|---|---|---|
| H1 | 全局模式改为「所有登录用户可进入、侧栏按能力过滤」是可接受的 | 决定「系统设置」能否从项目模式移除；否则 engineer 将失去「关于 / 版本迭代」入口（这两项能力是 `public`） | 大师拍板（PRD P-01） |
| H2 | 「执行计划」定位为「可复用的执行编排 + 一键发起」，与 cron「测试计划」并存不合并 | 决定新增页还是改造 `/plans` | 大师拍板（PRD P-02） |
| H3 | 跨项目「运行记录 / 报告」汇总本版不做 | 决定是否需要新增 `/api/global/runs` 聚合端点与权限模型 | 大师拍板（PRD P-03） |
| H4 | 顶栏主题/语言并入「工具段」而不新起第四段 | 影响 `.app-sep` 数量与既有断言 | 本文件 C5，实施时以单测锁定 |

## 11. 修订记录

| 版本 | 日期 | 变更 | 依据 |
|---|---|---|---|
| v1.0 | 2026-11-11 | 首版：A/B/C/D/E/F 六组事实清单共 34 条，全部标证据位置；给出 N1~N4 缺口对照与 H1~H4 待收敛假设 | 当日代码走查（`navigation.ts` / `App.vue` / `AppHeader.vue` / `AppNav.vue` / `GlobalSettingsView.vue` / `settingsNav.ts` / `router/index.ts` / `execution.ts` / `CasesView.vue` / `PlanController.java` / 4 个全局 Controller） |
