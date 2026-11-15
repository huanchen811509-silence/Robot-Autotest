# v5.11.0 开发计划（DEV-PLAN）：用例工作区 —— 先立范式，再改布局

> 流程位置：④ 开发计划。**无开发计划不许开工编码**。
> 上游：`PRD.md`（9 FR + 7 NFR）、`02-列表与工作区交互评估.md` §一~§三、§五。
> 本文件给 WBS 编号、改动面与**可复制的验收命令**；不给「大约几天」，只给依赖关系与可独立验收的边界。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | `DetailDock` 范式 | W61-01 ~ W61-02 | 是 | 必须最先（后面三个波都要用它） |
| W2 | 三栏布局 | W61-03 ~ W61-05 | 是 | 依赖 W1 |
| W3 | 视图收敛 | W61-06 ~ W61-07 | 是 | 依赖 W2（新视图就位才删旧的） |
| W4 | 信息幂等 | W61-08 ~ W61-10 | 是 | 依赖 W1（详情组件存在）；可与 W5 并行 |
| W5 | 脚本路径 | W61-11 ~ W61-13 | 是 | 后端 join 可先行；组件接入依赖 W1 |
| W6 | 影响面与 CRUD | W61-14 ~ W61-15 | 是 | 依赖 W1/W2 |
| W7 | 契约漏洞 | W61-16 | 是 | 独立（一行 SQL + 单测） |
| W8 | 收口 | W61-17 ~ W61-18 | 否（最终波） | 依赖 W1 ~ W7 全部出口判据 |

**并行纪律**：W1 是硬前置；W2 与 W5 的后端部分（三处 join）可并行；W3 必须在 W2 之后（否则用户无视图可用）；
W7 与任何波不冲突，可随时插入。**本版预期零迁移**：若 join 需要索引才追加 `V11_70`。

---

## 2. W1 `DetailDock` 范式（W61-01 ~ W61-02）

### W61-01 `DetailDock` 组件

| 项 | 内容 |
|---|---|
| 目标 | 列表容器内、表格之后的详情区：单选渲染 / 再点收起 / 换行原地替换 / 与表同宽 / 两列结构 / `?detail=<id>` 可恢复 |
| 改动面 | 新增 `components/DetailDock.vue` + `DetailDock.spec.ts`；`composables/useDetailDock.ts`（选中态与 URL 同步） |
| 出口判据 | ① 1440×900 下点开行首屏可见且表格仍可见；② `clientWidth` 差 ≤2px；③ 详情区实例数恒为 1；④ `?detail=` 刷新恢复；⑤ 滚动用 `block:'nearest'`（代码检查，无 `scrollTo(bottom)`） |
| 验证命令 | `cd frontend && npx vitest run src/components/DetailDock.spec.ts` |
| 依赖 | — |

### W61-02 项目用例清单接入（替换 expand 行）

| 项 | 内容 |
|---|---|
| 目标 | `CasesView.vue:622-629` 的行内 `expand` 迁到 `DetailDock`，删除旧展开逻辑，保证全站只有一种详情形态 |
| 改动面 | `views/CasesView.vue` + spec；删除 `expandedRowKeys` 相关代码 |
| 出口判据 | ① 旧 `expand` 代码零残留（grep `expandedRowKeys` 无命中）；② 行为与 `DetailDock` 一致（截图 3 张）；③ 全量前端单测例数不减 |
| 验证命令 | `cd frontend && npx vitest run src/views/CasesView.spec.ts` |
| 依赖 | W61-01 |

---

## 3. W2 三栏布局（W61-03 ~ W61-05）

### W61-03 中栏两级分类树

| 项 | 内容 |
|---|---|
| 目标 | 240px 中栏：全部 / 未分类 / L1 域（可折叠）/ L2 分类；数据复用 `GET /api/global/cases/grouped` |
| 改动面 | 新增 `components/CaseCategoryNav.vue`（与 5.10 `WizardContentPicker` 预留的接口对齐）+ spec |
| 出口判据 | ① 点 L1 按整域过滤、点 L2 按叶子过滤；② 「全部」「未分类」行为正确；③ >200 条时懒加载且滚动 fps ≥ 50；④ 3000 分类展开 ≤ 500ms |
| 验证命令 | `cd frontend && npx vitest run src/components/CaseCategoryNav.spec.ts` |
| 依赖 | W61-01 |

### W61-04 `GlobalCasesView` 三栏重排

| 项 | 内容 |
|---|---|
| 目标 | 页头 + 中栏 + 右工作区（筛选/列设置/分页/表格） + 表格下方 `DetailDock` |
| 改动面 | `views/GlobalCasesView.vue`（重排，非重写业务逻辑）+ spec |
| 出口判据 | ① 页面无视图切换按钮；② 中栏统计数 = 右表筛选结果数（同分类下两处相等）；③ 空态自解释 |
| 验证命令 | `cd frontend && npx vitest run src/views/GlobalCasesView.spec.ts` |
| 依赖 | W61-03 |

### W61-05 URL 三态可恢复

| 项 | 内容 |
|---|---|
| 目标 | URL 同时表达「选中分类 + 筛选条件 + `?detail=<id>`」，刷新可恢复 |
| 改动面 | `composables/useListQueryState.ts`（或等价）、两个用例页接线 |
| 出口判据 | ① 复制 URL 到新标签打开得到同一屏（截图 + URL 文本）；② 分类/筛选/详情三者互不覆盖 |
| 验证命令 | `cd frontend && npx vitest run -t URL` |
| 依赖 | W61-04 |

---

## 4. W3 视图收敛（W61-06 ~ W61-07）

### W61-06 删「分类归组」与孤儿路由

| 项 | 内容 |
|---|---|
| 目标 | 删 `?group=1` 切换与 `CaseCategoryTree` 单栏形态；删 `/g/cases/:id`、`/g/case-sets/:id` 两条路由 |
| 改动面 | `GlobalCasesView.vue:42-47`、`:459-462`、`router/index.ts:434-439`、`:494-499`、相关 spec |
| 出口判据 | ① `getRoutes()` 不含被删路由（单测）；② 全仓无 `router.push`/`RouterLink` 指向它们；③ 前端全量单测全绿且例数不减 |
| 验证命令 | `cd frontend && npx vitest run src/router && npx vue-tsc --noEmit` |
| 依赖 | W61-04 |

### W61-07 空壳页下架

| 项 | 内容 |
|---|---|
| 目标 | `/cases/board`（`rows` 恒空）下架；导航与 README 同步登记 |
| 改动面 | `CaseBoardView.vue` 删除、`navigation.ts`、`check_navigation.py` 叶数、README |
| 出口判据 | ① 路由不存在（单测）；② 导航无入口（机检）；③ 叶数常量同 commit 更新 |
| 验证命令 | `python3 tools/check_navigation.py` |
| 依赖 | W61-06 |

---

## 5. W4 信息幂等（W61-08 ~ W61-10）

### W61-08 详情取真值

| 项 | 内容 |
|---|---|
| 目标 | `precondition`/`steps`/`expected` 从 `test_case` 列与 `test_case_step` 表取真值，删掉 `CaseDetailPanel.vue:77-79` 的「源表无此列」硬编码 |
| 改动面 | `CaseDetailPanel.vue`、后端用例详情 DTO（补三字段与步骤列表）、`TestCaseQueryRepository` |
| 出口判据 | ① 详情三字段值与 DB 同值（逐条比对）；② 全仓 `grep "源表无此列"` = 0；③ 详情字段数 ≥ 表格列数 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=TestCaseDetailTest test`；`cd frontend && grep -rn "源表无此列" src \| wc -l` |
| 依赖 | W61-01 |

### W61-09 幂等不变量单测

| 项 | 内容 |
|---|---|
| 目标 | 遍历两页的列配置，断言每个 `key` ∈ 详情 DTO 字段集合（或映射表）；注入一个假列名必须判红 |
| 改动面 | `frontend/src/views/__tests__/columnIdempotency.spec.ts` + 列配置与 DTO 的映射表 |
| 出口判据 | ① 正常全绿；② 注入假列名 → 单测红（自检记录）；③ 映射表含计算字段的来源声明 |
| 验证命令 | `cd frontend && npx vitest run src/views/__tests__/columnIdempotency.spec.ts` |
| 依赖 | W61-08 |

### W61-10 DTO 与表结构一致性机检

| 项 | 内容 |
|---|---|
| 目标 | DTO 字段都能在表/视图找到来源（计算字段须在映射表声明）；带 `--self-test` |
| 改动面 | `tools/check_dto_columns.py`（新增）+ 门禁步注册 |
| 出口判据 | ① 全仓 PASS；② `--self-test` 注入一个无来源字段判红；③ 门禁步数 +1 且 FAILS 不增 |
| 验证命令 | `python3 tools/check_dto_columns.py --self-test` |
| 依赖 | W61-08 |

---

## 6. W5 脚本路径（W61-11 ~ W61-13）

### W61-11 三处 join（后端）

| 项 | 内容 |
|---|---|
| 目标 | 用例列表、用例详情、用例集成员三处响应补 `scriptPath` / `scriptName` / `bindingState`；文件名由后端算（复用 `fileStemOfPath`） |
| 改动面 | `LEFT JOIN case_script_binding b ON b.project_id=c.project_id AND b.case_id=c.id AND b.kernel=c.kernel` 抽成共享片段；三个 Repository + 三个 DTO + 单测 |
| 出口判据 | ① 三处响应三字段齐全；② 文件名与后端算法一致（不前端切）；③ 多内核用例按当前项目内核优先，其余计数；④ 绝对路径脏数据被截断（见 PRD 降级预案） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=CaseScriptJoinTest test` |
| 依赖 | — |

### W61-12 `CaseScriptLink` 组件

| 项 | 内容 |
|---|---|
| 目标 | 显示文件名（等宽）+ tooltip 完整相对路径 + 点击复制；未绑定灰标签「未绑定脚本」；RF 显示「不适用」+ 原因 |
| 改动面 | `components/CaseScriptLink.vue` + spec |
| 出口判据 | ① 四种 `bindingState` 各一张截图；② 复制成功（剪贴板断言）；③ 无模糊匹配代码（grep 无 `longestCommonSubstring` 类实现） |
| 验证命令 | `cd frontend && npx vitest run src/components/CaseScriptLink.spec.ts` |
| 依赖 | W61-11 |

### W61-13 三处接入

| 项 | 内容 |
|---|---|
| 目标 | 用例表格「脚本」列（默认显示可隐藏）、详情顶部脚本行、用例集成员行（5.12 复用） |
| 改动面 | `GlobalCasesView` / `CasesView` 列配置、`DetailDock` 详情模板、`CaseSetsView` 成员行 |
| 出口判据 | ① 三处同一用例路径字符串完全相同（DOM 抓取比对）；② 列可隐藏且设置持久化；③ 路径均为相对路径（单测正则断言） |
| 验证命令 | `cd frontend && npx vitest run -t 脚本` |
| 依赖 | W61-12 |

---

## 7. W6 影响面与 CRUD（W61-14 ~ W61-15）

### W61-14 三处「所属」提示与平台集徽章

| 项 | 内容 |
|---|---|
| 目标 | 项目自建用例显示「所属：本项目（修改不影响全局用例库）」；全局库显示「全局用例库（所有项目可见）」；平台集副本显示来源徽章；同步前显示影响面预览（新增 N / 覆盖 M / 保留 K） |
| 改动面 | `DetailDock` 详情模板、集合头徽章位置（5.12 实现同步动作，本版只留位置与只读数据）、`AssetVersionService` 的预览接口调用 |
| 出口判据 | ① 三种所属各一张截图；② 徽章状态与 `source_asset_id` 一致；③ `local_override` 用例同步后未被覆盖且状态变「已分叉」 |
| 验证命令 | `cd frontend && npx vitest run -t 所属` |
| 依赖 | W61-01 |

### W61-15 删除/批量删与影响面

| 项 | 内容 |
|---|---|
| 目标 | 行删除 + 批量删 + 影响面（用例集引用数 / 脚本绑定数 / 执行结果数）；被引用时 409 点名 |
| 改动面 | `GlobalCasesView` / `CasesView` 操作列、`TestCaseService.delete` 影响面聚合端点、spec |
| 出口判据 | ① 三个数字真实（与三处 count 一致）；② 删除后列表与中栏计数同步更新（无需手刷）；③ `confirmAction` 无 `window.confirm` |
| 验证命令 | `cd backend && mvn -o -B -Dtest=TestCaseDeleteImpactTest test` |
| 依赖 | W61-04 |

---

## 8. W7 契约漏洞（W61-16）

### W61-16 导入元数据与步骤补 `project_id`

| 项 | 内容 |
|---|---|
| 目标 | `writeImportMetadata`（`:243-256`）与 `insertImportedSteps`（`:228-238`）的 UPDATE 加 `project_id` 条件 |
| 改动面 | `TestCaseRepositoryJdbc` 两处 SQL + 单测 |
| 出口判据 | ① 构造跨项目 id 调用 → 更新行数 0；② 正常导入元数据与步骤仍正确；③ 两处 SQL 均带 `project_id`（代码检查） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ImportContractTest test` |
| 依赖 | — |

---

## 9. W8 收口（W61-17 ~ W61-18）

### W61-17 契约与性能

| 项 | 内容 |
|---|---|
| 目标 | `openapi.json` 重生成（三处 join 影响响应体）；长列表 fps 按 v5.8 口径复测 |
| 改动面 | 契约快照（WSL 内重生成）；`docs/evidence/v5.11.0/perf/` |
| 出口判据 | ① `--check` PASS；② 1000 行滚动 fps ≥ 50（含机器信息）；③ 中栏 3000 分类展开 ≤ 500ms |
| 验证命令 | `cd backend && python3 tools/gen_api_contract.py --check`；`node scripts/perf-longlist.mjs` |
| 依赖 | W61-11、W61-04 |

### W61-18 门禁全量与文档回写

| 项 | 内容 |
|---|---|
| 目标 | `GATES_RESULT FAILS=0`；六份齐全；总纲状态回写 |
| 改动面 | `docs/release/v5.11.0/*`（从 `_draft/` 迁入）、总纲交付表 |
| 出口判据 | ① 门禁 `FAILS=0`；② `check_release_docs.py v5.11.0` PASS；③ 表格机检全 PASS |
| 验证命令 | `python3 tools/check_gates.py 2>&1 \| tail -3` |
| 依赖 | W1 ~ W7 全部 |

---

## 10. 接口与数据变更清单

| 变更 | 类型 | 影响 | 处理 |
|---|---|---|---|
| 用例列表/详情/集合成员三处响应加 `scriptPath`/`scriptName`/`bindingState` | 响应新字段 | 前端类型同步；老前端忽略 | 契约重生成 |
| 用例详情 DTO 补 `precondition`/`steps`/`expected` | 响应新字段 | 详情组件取真值 | 契约重生成 |
| 用例删除影响面端点 | 新端点 | 删除确认消费 | 契约重生成 |
| 数据库 | **预期零迁移** | 若 join 需索引则 `V11_70` | 配 `.down.sql` |

---

## 11. 风险与应对

| # | 风险 | 应对 |
|---|---|---|
| R1 | 三栏重排导致既有筛选/分页/列设置回归 | 重排只动模板结构，业务逻辑（query 组装）不动；先跑既有 spec 再改，改完再跑一遍 |
| R2 | 删路由造成死链 | W61-06 判据 3 要求全仓 grep 入口；`getRoutes()` 单测兜底 |
| R3 | `test_case_step` 历史数据缺步骤 | 详情显示「无步骤记录」而不是「源表无此列」；空值也须区分「没有」与「没取到」 |
| R4 | 脚本路径 join 让列表查询变慢 | join 走既有唯一键（`project_id,case_id,kernel`）；NFR-03 用 v5.8 同一套 fps 与查询耗时口径 |
| R5 | 幂等单测形同虚设（映射表写成「万能表」） | 注入假列名必须判红（W61-09 出口判据 2）；映射表禁止通配 |

---

## 12. 与相邻版本的边界

1. 本版交付 `DetailDock` 与 `CaseScriptLink` 两个可复用组件，5.12 用例集与项目资产**直接复用**，不写第二套。
2. 本版**不做**用例集三栏化、**不做**资产工作台合并、**不做** RF 脚本路径猜测。
3. `?group=1` 与 `/cases/board` 的下架会改导航叶数，常量须与代码同 commit（与 5.10 改的是同一处常量，别把 v5.10 的读数当基线）。

---

## 13. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：8 波 18 个 WBS；`DetailDock` 先行；W7 契约漏洞独立可插队 | `PRD.md` v5.11.0 + `02-列表与工作区交互评估.md` |
| 2026-11-15 | 补 §14「执行状态与偏差」（⑦ 回写）：逐波结论、三处偏差、两条未取读数 | `05-TEST-REPORT.md` §2、门禁日志 |

---

## 14. 执行状态与偏差（⑦ 回写）

### 14.1 逐波结论

| 波 | 内容 | 结论 | 证据 |
|---|---|---|---|
| W1 | `DetailDock` 范式 | 完成 | `DetailDock.spec` 4 条；两页接入 |
| W2 | 三栏布局（分类中栏） | 完成 | `CaseCategoryNav.spec` 8 条；`GlobalCasesView.spec` 8 条仍全绿 |
| W3 | 视图收敛 | 完成 | 删 3 路由 + 1 空壳页；叶数 45 → 44；`router/deprecated.spec` 3 条 |
| W4 | 信息幂等 | 完成（**含追加改动**，见偏差 D-1） | `columnIdempotency.spec` 9 条；`check_dto_columns` PASS + self-test |
| W5 | 脚本路径 | 完成（用例集成员行按 §12 推后） | `CaseScriptLookupTest` 5 条；`scriptPathConsistency.spec` 4 条 |
| W6 | 影响面与 CRUD | 完成（平台集徽章/同步预览按 §12 推后） | `CaseImpactTest` 2 条；详情所属提示 + 删除前 `impact` 预览 |
| W7 | 契约漏洞 | 完成 | `ImportWriteScopeTest` 4 条；两处 SQL 均带 `project_id` |
| W8 | 收口 | 完成 | 契约 449/368 PASS；门禁 57 步 `FAILS=0`；六份文档归档 |

### 14.2 偏差（逐条写清为什么）

| # | 原计划 | 实际 | 原因 |
|---|---|---|---|
| D-1 | W4 只补「前端列配置 ↔ 详情字段」单测 | 追加后端 DTO + SQL + 面板 3 行（`domainTag` / `reviewStatus` / `capability`） | 实测发现详情 DTO 从未带这三列（`00-RESEARCH.md` R-4）。只写前端单测的话，映射表只能给这三项「通配」或「无来源」，正是 R5 禁止的假幂等 |
| D-2 | W61-10 只新增机检脚本 | 同时注册为门禁第 **57** 步并进 `check_structure` | 不进 `gates.sh` 的机检等于没人跑；步数 56 → 57 已在本文件 §9 与 `scripts/gates.sh` 头注释同步 |
| D-3 | 无（计划外） | 修复 `@Audited(action="case:delete")` 被新插入的只读 `impact()` 顶走 | 插入新方法导致的注解错位，`05-backend_test` 判红。**不属于任何 WBS**，但必须在本版修：否则「查影响面」被记成删除审计，真删除不留痕 |

### 14.3 未取得合规读数的两项（不冒充达成）

| # | 判据 | 现状 | 处置 |
|---|---|---|---|
| N-1 | NFR-v5.11-03 列表 1000 行滚动 fps ≥ 50 | **未取得**。`perf-longlist.mjs` 产品口径 `rows=0`（PERF 数据集未由 `tools/perf_seed.py` 导入）；harness 口径是独立 HTML 10000 行，读数 8.6 / 10.8 / 10.6 fps，与「1000 行表格」不是同一口径，不能拿来判定 | 收口阶段导入 PERF 数据集后按 v5.8 口径复测；本版不以 harness 读数冒充 PASS |
| N-2 | NFR-v5.11-04 中栏 3000 分类展开 ≤ 500ms | **未测**。无该页面的浏览器性能采样脚本 | 5.12 用例集三栏复用中栏后再采样（届时分类基数才真实） |

> 口径纪律：这两项在 `05-TEST-REPORT.md` §5 记为 L-1 / L-6，在 TC 表里对应 TC-11「未达成」。
> 门禁第 22 步 `perf_budget` 的 PASS **不能**替代它们——那个门禁守的是既有预算，不含本版新增的中栏。
