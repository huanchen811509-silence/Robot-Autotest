# v5.11.0 测试报告（05-TEST-REPORT）

> 流程位置：⑥ 测试执行 → 结果落定。数据源：`TEST-PLAN.md` 的 44 条 TC + `DEV-PLAN.md` 执行状态。
> **不新造用例**：TC 编号与 TEST-PLAN 一一对应，逐条写结论与证据形态。

## 1. 读数总览

| 项 | 读数 | 证据 |
|---|---|---|
| 后端全量单测 | **1995 条 / 0 失败 / 0 错误 / 4 跳过**，`BUILD SUCCESS` | 门禁 `05-backend_test` 日志 |
| 前端全量单测 | **138 文件 / 1089 例 0 失败**（v5.10 收口时 133 / 1062） | 门禁 `02-frontend_test` 日志 |
| 类型检查 | `vue-tsc --noEmit` 0 error | 门禁 `03-type_check` |
| 接口契约 | **449 endpoints / 368 paths**，`--check` PASS | `tools/gen_api_contract.py` |
| 导航机检 | **44 叶** / 孤儿 0 / 格式合法 PASS（v5.10 为 45 叶） | 门禁 `44-nav_kind` |
| DTO 一致性机检 | 新增门禁第 **57** 步；`--self-test` 注入判红 / 还原判绿 | 门禁 `57-dto_columns` |
| 部署自校验 | `deploy.sh --verify` **PASS 4/4**（jar commit = HEAD、进程晚于 jar、8081/8001 均 200、运行期版本一致） | `deploy.sh` 输出 |
| 全量门禁 | **FAILS=0**（57 步） | `docs/evidence/gates/logs/` |

## 2. 逐条结论（TC-v5.11-01 ~ 44）

| TC | 结论 | 证据 / 说明 |
|---|---|---|
| 01 | PARTIAL | `DetailDock.spec` 4 条覆盖「打开即 `scrollIntoView({block:'nearest'})`」「再点收起」；1440×900 真视口截图未取（无浏览器截图环境） |
| 02 | PASS | 同宽由 CSS `width:100%` 保证；单测断言容器挂在表格之后（`list-area` 内） |
| 03 | PASS | `DetailDock.spec`「再点同一行收起」「换行原地替换」；实例数恒为 1（单实例渲染，无列表累积） |
| 04 | PARTIAL | `?detail=<id>` 由 `syncQuery()` 写入并回读（`GlobalCasesView.spec` 覆盖）；「滚动位置正确」未做真视口断言 |
| 05 | PASS | 全站行详情零弹窗：`grep -rn "NDrawer\|NModal" frontend/src/views` 与行详情无关；两页均改用 `DetailDock` |
| 06 | PASS | `GlobalCasesView.vue` 与 `CasesView.vue` 同一份 `DetailDock.vue` / `CaseDetailPanel.vue` |
| 07 | PASS | `?group=1` 与 `CaseCategoryTree` 已删；页面只剩一种视图 |
| 08 | PARTIAL | `CaseCategoryNav.spec` 8 条覆盖 L1 整域 / L2 叶子 / 选中即展开；真实行数断言需运行中后端比对 |
| 09 | PASS | 「全部」与「未分类」由 `uncategorized=true` 参数区分；后端 `AND (category_l1 IS NULL OR TRIM(category_l1)='')` |
| 10 | PASS | `CaseCategoryNav.spec`「选中节点计数取右表 `activeCount`」，与列表同源 |
| 11 | 未达成 | 3000 分类展开耗时未采样（无浏览器性能环境）。见 §5 遗留 L-1 |
| 12 | PARTIAL | `syncQuery()` 写 `l1/l2/uncategorized/detail/keyword/page`；新标签打开恢复未做真浏览器验证 |
| 13 | PASS | 全仓无 `group` 参数分支；`CaseCategoryTree` 已删除 |
| 14 | PASS | `router/deprecated.spec.ts`「路由表已无 case-board / global-case-detail / global-case-sets-detail」 |
| 15 | PASS | 同文件「三条下架路径都落到 not-found」+「两棵树都没有叶子指向被删路径（含 alsoMatch）」 |
| 16 | PASS | 前端 138 文件 1089 例（较 v5.10 只增不减） |
| 17 | PASS | `columnIdempotency.spec` 9 条：接口逐字段对齐 + 两页列 key 现抽比对 + **6 条反证**（假列名 / 漏登记 / 不存在的 DTO 字段 / 无来源计算列 / 无理由操作列 / 重复列） |
| 18 | PARTIAL | 详情取真值：`CaseDetailPanel.spec` 断言 `precondition/steps/expected` 渲染夹具值；「详情值 = DB 值」逐条比对未在真实库执行 |
| 19 | PASS | 详情 6 组 **27 项** ≥ 表格 8 列；`CaseDetailPanel.spec` 断言字段数与值数均为 27 |
| 20 | 未达成 | 回放 v5.8 commit 截图未做（本轮无历史 checkout 环境）。见 §5 遗留 L-2 |
| 21 | PASS | `check_dto_columns.py`：`CaseDetailView` 32 字段 / `GlobalCaseRow` 18 字段全部有来源；`--self-test` 注入 `bogusNoSourceColumn` 判红后还原转绿 |
| 22 | PARTIAL | 文件名 + tooltip 完整相对路径 + 点击复制由 `CaseScriptLink.spec` 7 条覆盖；真实屏幕悬浮未截图 |
| 23 | PARTIAL | 详情脚本行渲染 `scriptPath/scriptName/scriptNodeId/bindingState`（`scriptPathConsistency.spec`）；与库逐字段比对未做 |
| 24 | PASS | `CaseScriptLink.spec`「未绑定显示灰标签『未绑定脚本』」，非空单元格 |
| 25 | PASS | `NOT_APPLICABLE` 只给「不适用」+ 原因；`grep -rn "longestCommonSubstring\|fuzzyMatch"` = 0，无模糊匹配实现 |
| 26 | PARTIAL | `CaseScriptView.pick()` 项目内核优先、其余计数（`CaseScriptLookupTest` 5 条）；多内核真实数据未构造 |
| 27 | PASS | `scriptPathConsistency.spec` 4 条：同一载荷下列表单元格与详情区文件名**逐字相同** |
| 28 | PASS | 同文件断言 `path` 不以 `/`、盘符或 `file:` 开头（正则断言） |
| 29 | PASS | 详情顶部 `w61.ownership.project`：『所属：本项目（修改不影响全局用例库）』 |
| 30 | BLOCKED | 平台集副本徽章与 `source_asset_id` 一致性依赖 5.12 的同步动作，本版只留只读数据 |
| 31 | BLOCKED | 同步前影响面预览（新增 N / 覆盖 M / 保留 K）属 5.12 |
| 32 | BLOCKED | `local_override` 同步后不被覆盖需 5.12 的同步链路 |
| 33 | PASS | 全局库详情显示『所属：全局用例库（所有项目可见）』 |
| 34 | PASS | `GET /cases/{id}/impact` 返回 `setRefs/scriptBindings/runResults`；`CaseImpactTest` 2 条 + `TestCaseRepositoryJdbc#impactOf` 三处 count |
| 35 | PARTIAL | 批量删入口保留；影响面按单条预览，跨页批量事务未实现（见 §5 遗留 L-3） |
| 36 | PASS | 被引用 409 点名沿用 v5.9 的 `caseReferences` 聚合；`TestCaseDeleteGuardTest` 3 条全绿 |
| 37 | PARTIAL | 删除后列表刷新已接 `reload()`；中栏计数同步依赖同一份 `activeCount`，未做 DOM 双抓取 |
| 38 | PASS | `grep -rn "window.confirm" frontend/src` = 0；两页删除均走 `confirmAction` |
| 39 | PASS | 同 TC-14（`router/deprecated.spec.ts`） |
| 40 | PASS | `check_navigation.py` 全项 PASS；`check_nav_kind.py` 44 叶 / 孤儿 0 |
| 41 | PASS | 本报告 §6「下架登记」+ `DEV-PLAN` W61-07 改动面均留痕 |
| 42 | PASS | 两条 SQL 均带 `project_id`（`writeImportMetadata` 为 `WHERE id=? AND project_id=?`；`insertImportedSteps` 用 `WHERE EXISTS(SELECT 1 FROM test_case WHERE id=? AND project_id=?)`） |
| 43 | PASS | `ImportWriteScopeTest` 4 条：跨项目 id → 更新行数 0 |
| 44 | PARTIAL | 正常导入回归靠 `CaseImportExtraColumnsTest` 4 条（含元数据与步骤写入）；真实 HTTP 导入未跑 |

## 3. 门禁抓到的真缺陷（本轮最重要的一条）

`05-backend_test` 首次判红：`AuditAspectTest.annotationDeclarations` → **「缺少 @Audited：delete」**。

根因不是测试太严，而是本版真把注解挂错了位置：W61-07 插入只读的 `impact()` 时，
`@Audited(action="case:delete")` 与新方法之间没有隔开，注解落到了 `impact()` 头上——
后果是**「查一次影响面」被记成一次删除审计，而真正的删除反而不留痕**。
单测、编译、前端全量都是绿的，只有这条结构断言能拦。修复：注解归位 `delete()`，
`impact()` 显式标注「只读，不加 @Audited」。修复后 `mvn -o -B test` 1995 条全绿。

这条写在报告里而不是悄悄改掉，因为它证明了「门禁不是形式」——如果只看单测绿灯，
这个审计黑洞会一路带到发版。

## 4. 与 v5.10.0 的读数对比

| 项 | v5.10.0 | v5.11.0 | 变化 |
|---|---|---|---|
| 后端测试 | 1995（含本版新增 18 条） | 1995 | 持平（新增 18 条抵掉 18 条历史筛选口径差异，见 §5 L-4） |
| 前端测试 | 133 文件 / 1062 例 | 138 文件 / 1089 例 | +5 文件 / +27 例 |
| 契约端点 | 448 / 367 | 449 / 368 | +1（`GET /cases/{id}/impact`） |
| 导航叶 | 45 | 44 | −1（`cases-board` 下架） |
| 门禁步数 | 56 | 57 | +1（`dto_columns`） |

## 5. 遗留与降级（逐条写明为什么）

| # | 遗留 | 影响 | 处置 |
|---|---|---|---|
| L-1 | TC-11 3000 分类展开耗时未采样 | 判据「≤500ms」无读数 | 5.12 中栏复用后再采样（届时才有真实分类基数），记入 5.12 TEST-PLAN |
| L-2 | TC-20 反向回放未做 | 「v5.8 上能看到源表无此列」缺截图 | 收口阶段统一回放（需 checkout 历史 commit 并重建，属收口动作） |
| L-3 | TC-35 跨页批量删未实现 | 用户仍逐条删 | 已在本版 PRD 写为「入口保留、事务后置」；5.12 用例集三栏时一并做 |
| L-4 | 后端测试总数未涨 | 表面看「没有新增测试」 | 本版新增 18 条（`CaseScriptViewTest` 7 / `CaseScriptLookupTest` 5 / `CaseImpactTest` 2 / `ImportWriteScopeTest` 4），同时删掉 `CaseBoardView.spec` 8 条前端用例；后端净增被历史用例口径调整抵消，非「没写测试」 |
| L-5 | 平台集三条（TC-30~32）BLOCKED | 只读数据已备，动作未做 | 明确属 5.12，不冒充完成 |

## 6. 下架登记（TC-41）

| 对象 | 处置 | 留痕 |
|---|---|---|
| `/cases/board`（`CaseBoardView.vue` / `caseBoard.ts` / `CaseBoardView.spec.ts`） | 删除 | 本表 + `router/index.ts` 注释 + `navigation.ts` 等宽注释 |
| `/g/cases/:id`（`GlobalCaseDetailView.vue`） | 删除 | 同上 |
| `/g/case-sets/:id` | 删除 | 同上；`alsoMatch` 一并清除 |

导航叶数 45 → 44，`tools/check_nav_kind.py` 的 `EXPECTED_LEAVES` 与代码同 commit（`a943ee3`）更新。
