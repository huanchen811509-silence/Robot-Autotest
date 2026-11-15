# v5.11.0 需求调研（00-RESEARCH）：列表与详情之间的信息断层

> 流程位置：① 需求调研。每条结论标注**证据来源**；推断与实测分开写。
> 上游：`docs/plan/重构评估-20261115/02-列表与工作区交互评估.md` §2、§5。
> 本文件不改评估结论，只补 **v5.10.0 交付后**（commit `664e120` 前一轮）的实测事实。

## 1. 实测事实（2026-11-15，仓库 `0521b3f` 起）

> 标注口径：**[已核实]** = 本轮读过代码 / 查过库；**[推断]** = 由事实推出未实测；**[假设]** = 待验证前提。

| # | 事实（三档标注） | 证据 |
|---|---|---|
| R-1 | [已核实] `/cases/board` 是空壳页：`CaseBoardView.vue` 的 `rows` 声明后**全文无赋值**，页面永远「0 条」 | `frontend/src/views/CaseBoardView.vue:27`，`grep -n "rows"` 仅出现声明与模板 |
| R-2 | [已核实] `/g/cases/:id`、`/g/case-sets/:id` 是孤儿路由：全仓无 `router.push` / `<RouterLink>` 指向；`GlobalCaseSetsView.vue` 全文无 `useRoute` | `grep -rn "/g/cases/\|/g/case-sets/" frontend/src` 仅命中 `router/index.ts` 与 `navigation.ts` 的 `alsoMatch` |
| R-3 | [已核实] 用例详情把 `precondition` / `steps` / `expected` 硬编码成「源表无此列」，而 `test_case.precondition`、`test_case.expected`、`test_case_step` 一直都在库里 | `CaseDetailPanel.vue`（v5.11 前）；`V1__baseline.sql` 的 `test_case` / `test_case_step` |
| R-4 | [已核实] 项目用例表的三列 `review_status` / `domain_tag` / `capability` 在 `test_case` 里是真实列，但共用详情 DTO `CaseDetailView` 从未带它们 | `db/migration/V2_3__case_fields.sql:11-20`；`GlobalCaseService.CaseDetailView`（v5.11 前 24 字段） |
| R-5 | [已核实] `case_script_binding` 只有 pytest 链路写入（`PytestBindingSync`），RF 用例没有任何「用例 ↔ 脚本」关联列 | `V11_47__case_script_binding.sql`；`PytestBindingSyncTest` 7 条 |
| R-6 | [已核实] `TestCaseRepositoryJdbc.writeImportMetadata` / `insertImportedSteps` 的 UPDATE 只按 `id`，**不带 `project_id`** | 该方法 v5.11 前的 SQL 原文 |
| R-7 | [已核实] 导航叶数基线 v5.10.0 后为 **45**（`check_nav_kind.py` 的 `EXPECTED_LEAVES`），下架空壳页会改这个常量 | `tools/check_nav_kind.py:46` |
| R-8 | [已核实] 删除用例已有「被引用就 409 点名」的守卫（v5.9.0 / W59-20），但**删除前**看不到影响面 | `TestCaseService#delete` 的 `caseReferences` 调用点在删除路径里 |
| R-9 | [已核实] `@Audited(action="case:delete")` 贴在 `delete` 上；本轮插入只读 `impact()` 时注解被顶走，`AuditAspectTest` 判红 | 门禁 `05-backend_test` 日志：`缺少 @Audited：delete` |

## 2. 由事实推出的三条结论（**[推断]**，未实测）

1. **「信息幂等」的缺口不在前端，在契约**：前端把三列硬编码成占位文案，是因为 DTO 里没有这三项；
   先把 DTO 补齐，前端才谈得上「有值可显示」。所以本版的顺序必须是 DTO → 组件 → 单测，不能反过来。
2. **脚本路径只能由后端算**：前端各自 `split('/')` 一次，列表、详情、用例集就会出现三个字符串。
   文件名必须随响应下发（`scriptName`），前端只负责显示与复制。
3. **删路由的收益是「少一个坑」而不是「少一屏」**：空壳页与孤儿路由本身不报错，
   但它们让「点进去白屏」成为可能；下架的真实价值是让导航叶数、`alsoMatch`、单测三者重新对齐。

## 3. 未核实项（不写成结论）

| # | 待核 | 为什么没核 |
|---|---|---|
| U-1 | [假设] 3000 个分类下中栏展开的真实耗时（判据 ≤500ms） | 本版无浏览器性能采样环境；只做了视图级单测 |
| U-2 | [假设] 平台集副本徽章与 `source_asset_id` 的真实一致性 | 同步动作属 5.12（本版只留位置与只读数据） |
| U-3 | [假设] 用户实际看到的 409 文案逐字内容 | 只做了单测断言；未在真实库构造引用后取响应 |

## 4. 与评估报告的差异

- 评估 02 §2 把「详情用弹窗/抽屉」列为**交互问题**；实测发现更根本的是**字段来源问题**（R-3/R-4）。
  本版据此把「幂等」提到 P0，把「零弹窗」降为实现手段而非目标本身。
- 评估 02 §5 把 `/cases/board` 列为「要么补数据、要么下架」的二选一；本次按**下架**处理——
  补数据需要先定义「历史 / 当前双计数」的口径，而那属于报表域，不是本版的收敛范围。
