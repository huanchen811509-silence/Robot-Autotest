# PRD：用例/用例集脚本绑定可见性、执行软校验与快速组集

> 日期：2026-11-20 ｜ 撰写：学徒 → 交大师评审
> 性质：七道流程第 3 环产物（PRD）。**上游** `00-评估报告.md`（有条件可行，条件 P1–P4）。**本文不含开发、不 bump 版本。**
> 编号规范：FR 采用临时前缀 **`FR-READY-xx`**；正式版本号待大师落号拍板，**进 `docs/release/` 前统一替换为 `FR-vX.Y-xx`**（`tools/check_release_docs.py` 的 `detect_prefix` 只认 `v<数字>`；有 `FR-EHT-xx` 临时前缀先例）。
> 已裁定口径：2026-11-20 向导 4 项（见 §3），与评估 §4 一一对应。

---

## 1. 问题定义（每条带代码级证据）

| # | 问题 | 代码级证据 | 用户可感知后果 |
|---|---|---|---|
| P1 | 有绑定数据，但用户只能逐行看，不能按「有无脚本」筛 | `CasesView.vue:97-103` 仅 5 个筛选项；`CaseController.java:77-89` list 无 bindingState；脚本是分页后 Controller 补的（`:95-106`） | 想从 61 条里挑「已有脚本的」只能肉眼翻，需求 3 无解 |
| P2 | 项目库能看脚本，公共库虽也装配但端点更弱 | `GlobalCaseService.java:266-278` 装配脚本；`GlobalCaseController.java:57-66` 无过滤；`GlobalCaseSetsView.vue:230-240` 公共集只能填 ID 单条加 | 公共用例/集「关注脚本」只做了一半 |
| P3 | 用例集没有脚本覆盖率概览 | `CaseSetsView.vue:119-137` 成员筛选仅 `caseCode/priority`；成员行有脚本列（`:1042-1049`）但不可聚合 | 用户无法判断「这个集能不能整套跑」 |
| P4 | 执行前无统一脚本校验，三套口径并存且打架 | `CaseRunService.java:89-95` 整单 400；`PytestNicRunController.java:456-458` willSkip 不拦；`CommandPreviewService.java:54-64` 只记 skipReasons；快照 confirm 实际走 `RunLaunchController.java:193-196` 的 `CaseRunService`，与预览不同源 | v5.24 UX 评审 P0-2「61 条静默跳过、平台不解释」 |
| P5 | 无脚本被跳过时不落任何结果行，K 无事实来源 | `PytestCommandPlanProvider.java:56-66` 直接 `continue`；`PytestResultNormalizer.java:325-327` 的 SKIP 只来自 junit `<skipped>` | 四元组 N/M/K/T（`FR-v5.23-09`）对不上，报告无法解释 |
| P6 | 用例库页没有「筛选结果 → 用例集」动作 | `CasesView.vue:941-997` 批量动作只有评审/改属性/删除；`:1276` 仅跳转集页；唯一按条件组集是场景页 `CategoryTree.vue:122-147` 且只按分类 | 组一个「可执行集」要逐条勾选，需求 3 无便捷方案 |
| P7 | 筛选条件存不下来 | `useSavedViews.ts:28-37` payload 无脚本字段；执行 filter 白名单 `RunTrustJdbcStore.java:41-46` 无脚本键且前端未传（`:243-247`） | 每次都要重筛；筛完不能直接跑 |
| P8 | 绑定有 verified/sha256 两态，现无「失效」判据 | `V11_47__case_script_binding.sql:27,29`；`CaseScriptView.java:25-29` 四态；`PytestBindingSyncService.java:128-144` sha256 跟随 | 有绑定行的用例可能实际跑不了，组集后才发现 |

---

## 2. 范围

### 2.1 In scope（按组）

| 组 | 主题 | FR |
|---|---|---|
| A | 脚本状态可见与筛选（双端 + 集成员） | FR-READY-01 ~ 03 |
| B | 按筛选结果快速组集（双端） | FR-READY-04 ~ 06 |
| C | 执行前软校验、确认跳过与跳过可追溯 | FR-READY-07 ~ 09 |
| D | 绑定可信度门槛（隐藏需求 H1/H2） | FR-READY-10 ~ 11 |

### 2.2 Out of scope（明确不做）

1. **不改 `case_script_binding` 表结构、不新增绑定表**——绑定是唯一可信源，本 PRD 只消费它。
2. **不做动态/智能用例集**——大师裁定静态快照；不改 `test_set.kind`、不引入条件实时计算。
3. **不碰 G-1 授权与 ADR-0013 real 安全闸**——软校验只决定「是否下发 nodeid」，不绕过授权。
4. **不主动为「无脚本」生成 SKIP 结果行写库**——只在 `run.log` 与 `skipReasons` 留可追溯清单；与 `test_result` 无 NOT_RUN 事实的既有口径（`v5.14.0/PRD.md:505`）保持一致。
5. **不给 RF 补脚本绑定**——RF 继续显示 `NOT_APPLICABLE`，按内核校验（大师裁定 3）。
6. **不在本 PRD 确定版本号 / bump VERSION**——留 O-2 与落号流程。
7. **不重做 v5.24 确认面板、不合并场景页 `addByCategoryFilter`**——只提供数据源与泛化端点，合流列 O-5。

---

## 3. 关键口径汇总（与评估 §4 一一对应）

| # | 口径 | 裁定 | 落地条款 |
|---|---|---|---|
| 1 | 执行前脚本校验 | 软校验 + 确认跳过：清单可见、无脚本默认剔除、用户确认后继续 | FR-READY-07/08/09 |
| 2 | 快速组集形态 | 筛选器 + 按结果组集（静态快照） | FR-READY-01/04/05 |
| 3 | 适用范围 | 双端可见 + 按内核校验，RF 不适用不拦截 | FR-READY-02/06/07 |
| 4 | 版本/编号 | 临时 `FR-READY-xx`，待落号替换 `FR-vX.Y-xx` | 本文件头部 |

---

## 4. 功能需求

### 组 A · 脚本状态可见与筛选

#### FR-READY-01 项目用例库支持按脚本状态筛选（P0）
- **位置**：后端 `CaseController.page` → `TestCaseService.page` → repository；前端 `CasesView.vue` FilterBar + `api/cases.ts:193-205`。
- **现状**：list 参数无 bindingState；脚本字段分页后在 Controller 补（`CaseController.java:95-106`）。
- **要求**：新增 `bindingState` 四态过滤（`BOUND_VERIFIED / BOUND_UNVERIFIED / UNBOUND / NOT_APPLICABLE`），**在分页前 JOIN `case_script_binding` 并按项目内核判定**；不得把过滤做成分页后的内存过滤（会错总数）。内核判定用 `CaseScriptLookup.kernelOf` 同款逻辑，RF 内核下 `UNBOUND` 请求返回 0、`NOT_APPLICABLE` 返回全部。
- **验收判据**：
  1. 构造四态各 ≥1 条样本，逐态筛选结果与 `SELECT ... FROM case_script_binding` 真值逐条一致。
  2. 每态的 `PageResult.total` 与同条件 SQL `COUNT(*)` 相等（分页前后一致性）。
  3. 筛选 + 既有 5 个维度任意组合，结果不重不漏。
  4. 未传 `bindingState` 时行为与现网逐字一致（默认全量）。

#### FR-READY-02 公共用例库同口径筛选与展示（P0）
- **位置**：`GlobalCaseController.page` / `GlobalCaseService` / `GlobalCasesView.vue` / `api/globalCases.ts:110-121`。
- **现状**：公共用例在 platform 单例项目（`GlobalCaseService.java:43`），脚本经 `scriptLookup.of(platform.id(), ids)` 装配（`:266-278`），但无过滤参数。
- **要求**：同 FR-01 的四态过滤，projectId 固定 `platform.id()`，不得串到项目库；公共用例集成员表同样暴露脚本状态。
- **验收判据**：
  1. 同一 `case_code` 在项目库与公共库显示的 bindingState 文案逐字相同（同一 `CaseScriptView` 产出）。
  2. 公共库筛选四态与 platform 项目下绑定表真值一致。
  3. 公共库 `kind='platform'` 项目不被误判为 pytest-suite。

#### FR-READY-03 用例集成员表脚本状态筛选与覆盖率概览（P1）
- **位置**：`CaseSetsView.vue:119-137`（memberFilters）、`:1042-1049`（成员脚本列）；公共集 `GlobalCaseSetsView.vue:389-392`。
- **现状**：成员筛选仅 `caseCode/priority`，无聚合。
- **要求**：成员表支持 `bindingState` 列筛选；集合详情显示「已绑定 X / 共 Y 条」覆盖率（X 含 VERIFIED 与 UNVERIFIED，口径见 FR-10）。
- **验收判据**：
  1. 一个含 3 已绑定 + 2 未绑定的集，概览显示 `3/5`，筛选「未绑定」恰好返回 2 条。
  2. 公共集同样显示概览（无绑定时显示 `0/N`，不是空白）。
  3. 覆盖率与成员表的实际行数一致，不因分页变化。

### 组 B · 按筛选结果快速组集

#### FR-READY-04 用例库勾选 → 加入用例集（P0）
- **位置**：`CasesView.vue:941-997` 批量动作区、`:1276` 跳转按钮；复用 `api/cases.ts:301-308` batchAddItems。
- **现状**：无「加入用例集」动作；`FR-v5.24-01` 已规划但未落号（边界见评估 §8）。
- **要求**：用例库（含筛选结果、含「条件全选」`selectAllMatching`）勾选后，出现「加入用例集」动作，支持「新建集」与「追加到已有集」两个目标；一次提交完成，不逐条请求。
- **验收判据**：
  1. 筛出 20 条勾选后选「加入集 → 新集 EXEC-READY-01」，该集成员数 =20，一次网络请求完成（≤3 个 API 调用）。
  2. 追加到已有集后，原成员保留，新成员 `seq_no` 从 `MAX(seq_no)+1` 连续续排，无唯一键冲突（`V1__baseline.sql:320-334`）。
  3. 条件全选跨页时，加入的是**全部匹配项**而非当前页。

#### FR-READY-05 通用「按筛选条件批量入集」端点（P0）
- **位置**：新增端点，参考 `api/cases.ts:328-338` `addByCategoryFilter` 与 `CategoryTree.vue:122-147`。
- **现状**：唯一按条件组集端点只吃 `categoryL1/L2/intersectWithSetId`（`CategoryTree.vue:52-59`），不接受脚本状态。
- **要求**：端点接受 `CaseSelectionSpec` 式筛选（关键词/优先级/评审状态/域/安全级/**bindingState**）与 `excludeIds`，服务端按同一 filter 真值选中并批量写入；返回 `{ matched, added, itemSource }` 摘要。
- **验收判据**：
  1. 传 `bindingState=BOUND_VERIFIED` 时，入集用例全部为已校验绑定，无一条 UNBOUND。
  2. `matched` 与前端筛选总数一致；`added` ≤ `matched`（去重后）。
  3. 同一筛选重复调用不产生重复成员（幂等，按 `(test_set_id, test_case_id)` 去重）。
  4. 权限与安全级校验沿用既有 `addByCategoryFilter` 口径，不绕过。

#### FR-READY-06 公共用例集支持按筛选批量加成员（P1）
- **位置**：`GlobalCaseSetsView.vue:230-240,367-378`。
- **现状**：只能输入数字 ID 单条 `addItem`。
- **要求**：公共集详情提供「从公共用例库筛选加入」，复用 FR-05 端点（platform projectId），保留单条填 ID 作为高级用法。
- **验收判据**：
  1. 公共库筛 `BOUND_VERIFIED` → 加入公共集，成员数增加且全部为已校验绑定。
  2. 公共集成员表显示脚本状态（FR-02），不是只显示三列。
  3. `owner_scope='PLATFORM'` 与 `project_id IS NULL` 不被写坏（`V9_3__case_source_version.sql:15-27`）。

### 组 C · 执行前软校验、确认跳过与可追溯

#### FR-READY-07 统一执行前脚本预检（P0）
- **位置**：新增/收敛预检服务，供三入口消费：`RunLaunchController`（快照 confirm）、`RunController`（quick-run / test-sets run / cases run）、`PytestNicRunController`（pytest 专用）。
- **现状**：整单 400（`CaseRunService.java:89-95`）、willSkip 数字预测（`PytestNicRunController.java:456-458`）、skipReasons 串（`CommandPreviewService.java:54-64`）三套并存。
- **要求**：预检返回结构化清单：`{ boundCount, unbound: [{caseId, caseCode, reason}], notApplicableCount }`；`reason` 取 `UNBOUND / NODEID_EMPTY / BINDING_STALE`（见 FR-10）。**pytest 内核才计算 unbound；RF 内核全量归入 notApplicableCount，不拦截。**
- **验收判据**：
  1. 用含 3 已绑定 + 2 未绑定的选择集预检，返回 `boundCount=3`、`unbound` 恰 2 条且含 caseCode 与 reason。
  2. RF 项目预检：`unbound=[]`、`notApplicableCount=全部`，不产生拦截。
  3. 三入口调用同一预检，同一次选择的清单逐字一致（解决「预览与实跑不同源」）。

#### FR-READY-08 确认面板展示清单与「确认跳过」（P0）
- **位置**：执行发起页确认面板，沿用 `FR-v5.24-04` 面板，不另造；pytest 台 `PytestRunLaunch.vue:68-79,332-334` 接入。
- **现状**：willSkip 只是提示，`canSubmit` 不因它禁用；无用户确认动作。
- **要求**：面板展示 FR-07 清单（编号 + 原因 + 「如何补齐」链接，复用 v5.23 文案），提供「确认跳过未绑定用例并继续」勾选；**未勾选不得提交**；勾选后真正下发的 nodeid/caseId 清单不含 unbound 项。
- **验收判据**：
  1. 含未绑定用例时提交，面板显示清单且未勾选「确认跳过」→ 提交按钮禁用、后端无新 run。
  2. 勾选后提交 → 成功产生 run，实际下发清单不含 unbound 的 caseCode。
  3. 全部已绑定时面板不显示跳过区，提交路径与现网一致（无回归）。
  4. pytest real 模式仍保留 fail-closed 安全闸（G-1/ADR-0013 不绕过），清单用于解释而非放行未授权项。

#### FR-READY-09 跳过清单可追溯（P0）
- **位置**：`run.log`、`run.skipReasons`（或等价字段）、报告首屏跳过可点区（`FR-v5.23-06/07`）。
- **现状**：`PytestCommandPlanProvider.java:56-66` 直接 continue，不留痕；SKIP 结果行只来自 junit。
- **要求**：每次软校验跳过，写 `run.log` 与 `skipReasons`：`{reason, count, samples:[caseCode]}`；报告首屏「跳过 K」展开后能按 reason 分组看到样例，与 FR-07 清单同源。
- **验收判据**：
  1. 一次含 2 条未绑定的执行，报告 `skipReasons` 中 `UNBOUND` 组 `count=2`，`samples` 含两个 caseCode。
  2. `run.log` 含「跳过无绑定用例」字样与编号（复刻 `PytestNicRunService.java:337-359` 的 retry 日志口径）。
  3. 报告未新增 NOT_RUN/SKIP 结果行（与 `test_result` 口径一致）。
  4. 四元组 N/M/K/T：K = 清单条数，N = 应跑数，M = 有结论项数，T = 参数化展开总数，四处同源且 `N ≥ M`。

### 组 D · 绑定可信度门槛（隐藏需求）

#### FR-READY-10 「有脚本」准入判据与失效标记（P1）
- **位置**：`CaseScriptView` 四态（`:25-31`）、`case_script_binding.verified/script_sha256`（`V11_47__...sql:27,29`）、FR-01/05 的筛选与组集。
- **现状**：有绑定行即算有脚本，未区分 `BOUND_UNVERIFIED` 与 sha256 漂移。
- **要求**：
  1. 「可执行集」准入按 `BOUND_VERIFIED`；`BOUND_UNVERIFIED` 可单独筛出但提示「未校验」。
  2. 绑定行存在但脚本文件缺失或 sha256 与当前文件不符 → 预检 reason 记 `BINDING_STALE`，按未绑定处理（默认剔除）。
  3. `NOT_APPLICABLE` 不计入任何分母（既不算 bound 也不算 unbound）。
- **验收判据**：
  1. 一条 `verified=0` 用例筛 `BOUND_VERIFIED` 不返回；筛 `BOUND_UNVERIFIED` 返回且带「未校验」文案。
  2. 人为删除/改动某脚本文件后预检，该用例 reason=`BINDING_STALE` 且不进入下发清单。
  3. RF 用例不出现在任何 bindingState 筛选的 unbound 结果里。

#### FR-READY-11 绑定查询按内核判定、跨项目不串（P1）
- **位置**：`CaseScriptLookup.java:36-42,72-97`（`kernelOf` 与「项目内核优先，否则 id 最小」）。
- **现状**：唯一键含 kernel，一条用例可多内核绑定；集跨项目时「有无脚本」随内核变。
- **要求**：所有筛选/预检/组集判定都按**当前项目内核**取首选绑定，沿用 `pick` 规则；公共库固定 platform 项目内核，不得串用。
- **验收判据**：
  1. 一条用例同时有 pytest 与 robot 两条绑定，在 pytest 项目筛 `BOUND_VERIFIED` 命中首选 pytest 绑定行。
  2. 跨项目组集时，成员脚本状态按目标项目内核重算，不沿用源项目判定。
  3. 既有 `CaseScriptView` 三处（列表/详情/集成员）显示字符串不变（FR-v5.11-05 判据 6 不回归）。

---

## 5. 数据模型与接口影响

| 对象 | 变更 | 说明 |
|---|---|---|
| `case_script_binding` | **不变** | 唯一可信源，本 PRD 只读消费 |
| `test_set` / `test_set_item` | **不变** | 静态快照；seq 续排沿用现有写入逻辑 |
| `project` / `project_setting` | **不变** | pytest-suite 与 platform 语义沿用 |
| `CaseController.page` / `GlobalCaseController.page` | 加 `bindingState` 入参 | 需分页前 JOIN |
| 新增批量入集端点 | 接 `CaseSelectionSpec + bindingState` | 参考 `addByCategoryFilter` 返回体 |
| 新增统一预检端点/服务 | 返回结构化清单 | 供三入口复用 |
| 前端 api types | `caseApi.page` / `globalCaseApi.page` / `CaseSelectionSpec` / `useSavedViews` payload 加 `bindingState` | 前后端同源 |

---

## 6. 非功能需求（NFR）

1. **向后兼容（写死）**：既有 61 条 pytest 用例的绑定展示路径、三处脚本列字符串、既有 5 个筛选维度默认行为**全部不变**；未传新参数时与现网逐字一致。
2. **内核隔离**：RF 项目不受 bindingState 影响，不误拦、不误标；`NOT_APPLICABLE` 文案不变。
3. **性能**：`bindingState` 过滤在 WSL ext4 上以 61 条样本实测，列表首屏 P95 < 2 s；不取整行大字段（脚本 sha256 只取判定位）。
4. **安全**：软校验不得绕过 G-1 授权、ADR-0013 real 闸、`INVALID_ARGUMENT` 400 的安全级校验；`bindingState` 入参走白名单，非法值 400。
5. **i18n**：新增键中英文齐备；含 `@` 的串按既有坑转义（`{'@'}`）；`check_i18n` PASS。
6. **可追溯**：每次跳过留 `run.log` + `skipReasons`，不写凭据、不写绝对路径（`EhtSections.relativePath` 口径）。

---

## 7. 兼容性与回归

1. **行为变更需在同一批内回归**：执行口径从整单 400 改软校验，是行为变更——须把 v5.1/v5.23 既有执行门禁逐条重跑，差异逐条归因；无法解释的差异当缺陷回退排查。
2. **预览与实跑同源复验**：用同一选择集分别调 `preview-commands` 与 confirm-and-start，断言下发清单一致（修复 `RunLaunchController.java:41-44` 的接线偏差）。
3. **三处脚本列逐字一致**：列表、详情、集成员同一 case_code 的字符串用 `CaseScriptView` 同一函数产出，不因实现细节分歧（`CaseScriptView.java:4-9`）。
4. **门禁**：现有 66/67 步门禁 `FAILS=0 UNJUDGED=0`；新增专项机检（bindingState 白名单、FR 编号无孤儿、四元组一致）。
5. **文档**：本文与评估过 `check_md_tables.py`；进 release 前 FR 号替换后机检无孤儿。

---

## 8. 门禁与验收标准

| 门禁 | 判据 |
|---|---|
| G-doc | 评估 + PRD 两文档成件，表格机检 PASS，FR 每条可直接写 TC |
| G-filter | 四态筛选逐态对真值，分页 total 与 COUNT 一致，默认行为无回归 |
| G-dual | 项目库与公共库脚本状态逐字一致，公共集可批量入成员 |
| G-group | 20 条筛选结果一次入集，seq 连续，幂等无重复 |
| G-exec | 三入口同清单，未确认跳过不放行，real 安全闸保留 |
| G-trace | 跳过清单入 run.log 与报告，四元组 N/M/K/T 四处同源 |
| G-regress | 既有门禁全绿，i18n / 组件导入 / 表格机检 PASS |

---

## 9. 风险与缓解

| ID | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 分页后装配改分页前 JOIN，性能与排序风险 | 中 | WSL ext4 实测；只取判定位；加索引评估列 O-6 |
| R2 | 软校验与 real fail-closed 冲突 | **高** | 清单只决定下发范围，不碰授权闸；G-1/ADR-0013 原样保留 |
| R3 | platform 项目绑定来源不明（评估 H8） | 中 | ~~开工前查清~~ **已查证**：无写入路径、kernel=robot ⇒ 恒 `NOT_APPLICABLE`（非 UNBOUND）；公共端实测降级判定见 TEST-PLAN；sync 无 kind 守卫由 W-READY-01 补断言 |
| R4 | 与 v5.24-01/04 重复建设 | 中 | O-2 大师裁定合流方式；本文只提供筛选维度与数据源 |
| R5 | 四元组分母被混用（61/70/9） | 中 | 沿用 FR-v5.23-09 定义，PRD/DEV/TEST 三文档同源 |
| R6 | 门禁期改被读文件致假红 | 低 | 门禁期间不改工作树，收口前 restore 已跟踪副产物 |

---

## 10. 评审开放项（O 表）

> **修订记录（2026-11-20）**：大师批准本 PRD，O-1/O-2/O-3 按建议口径闭合；O-4 经只读查证闭合，**并修正一处实质认知**——公共库当前恒 `NOT_APPLICABLE`（platform kernel=robot，非 UNBOUND），且无绑定写入路径。详见 `02-DEV-PLAN.md` §0。

| ID | 开放项 | 建议 / 结论 | 状态 |
|---|---|---|---|
| O-1 | 「用例集有脚本」术语澄清为「成员脚本覆盖率」 | 采纳建议写法，避免误解为集本身有脚本字段 | ~~已闭合~~ 大师批准 PRD（2026-11-20） |
| O-2 | 与未落号 v5.24 的合流方式：独立主题 or 并入 UX 迭代 | 按 PRD 独立推进，保留 `FR-READY-xx`；v5.24-01/04 不变 | ~~已闭合~~ 大师批准 PRD（2026-11-20） |
| O-3 | 「有脚本」门槛是否以 BOUND_VERIFIED 为准 | 采纳建议：UNVERIFIED 单独提示但不进「可执行集」 | ~~已闭合~~ 大师批准 PRD（2026-11-20） |
| O-4 | 公共库 platform 项目的绑定由谁同步、同步哪个仓库 | **查证结论**：无自动/人工/迁移写入路径；kernel=robot ⇒ 恒 `NOT_APPLICABLE`。风险口：sync 端点无 kind 守卫，DEV-PLAN W-READY-01 加断言。公共端实测降级判定见 TEST-PLAN | ~~已闭合~~ 只读查证（2026-11-20） |
| O-5 | 是否把场景页 `addByCategoryFilter` 收敛为 FR-05 的形态 | 建议后续版本做，本轮不动，避免范围膨胀 | 留评审 |
| O-6 | 是否需要为 `bindingState` 过滤加数据库索引 | 61 条样本无需；数据量大时评估 | 留评审 |
| O-7 | 正式版本号与 FR 编号落号 | 评审通过后由大师拍板，统一替换 | 待落号 |

---

## 11. 退出标准

本文满足以下条件方可进入 DEV-PLAN：O-1 / O-2 / O-3 / O-4 闭合（大师裁定或查证完成）；FR 每条有可测判据；与 v5.24 边界无重复争议；版本号或临时编号口径确认。**在大师拍板前，本需求不进入开发，不称 GA。**
