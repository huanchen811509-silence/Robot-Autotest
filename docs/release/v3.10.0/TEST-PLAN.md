# v3.10.0 测试计划（RA-TP-310）

> 判据来自 `PRD.md`（RA-PRD-310）。**无测试计划不许进入开发后期**；每条 TC 必须写明证据形态与判定命令。
> 放行条件：§2 全部 PASS + §4 横向回归全绿。

## 1. 取证策略（为什么不是「全都用探针」）

| 判据类型 | 取证手段 | 理由 |
|---|---|---|
| 控件计数 / 布局 | CDP 探针（真浏览器） | 只有真浏览器能量 `getBoundingClientRect` 与视口比 |
| 纯逻辑（排序/过滤/边界） | Vitest 单测 | 探针里 naive-ui 虚拟列表不渲染选项，注入不了边界时刻 |
| 组件接线（选中→回填→提交） | Vitest + 桩组件 | 真实下拉点不到，但「选中之后做什么」必须验 |
| 数据模型 / 快照自包含 | JUnit 契约测试 | 要能删源数据再看快照，UI 层做不到 |

**缺口必须写明**：哪条判据探针测不出、由什么补上，写在 `docs/evidence/v3.10.0/README.md`，不留在「应该没问题」里。

## 2. 用例清单

| TC | 判据 | 内容 | 证据形态 | 判定命令 |
|---|---|---|---|---|
| TC-v3.10-01 | FR-v3.10-01① | 计划页首屏主操作 ≤ 8 | `EW310-01/controls-after.json` 的 `primaryCount` | `node scripts/plan-controls-probe.mjs` |
| TC-v3.10-02 | FR-v3.10-01② | 每行控件 = 1，行内菜单项 ≥ 4（动作没丢） | 同上 `rowControls` / `rowMenuItems` | 同上 |
| TC-v3.10-03 | FR-v3.10-01③ | 冲突区、跳过事件区默认收起；展开后内容与改前一致 | 同上 `conflictCollapsed` / `skippedCollapsed` + 单测反向断言 | 同上 + `npx vitest run src/views/PlansView.spec.ts` |
| TC-v3.10-04 | FR-v3.10-01④ | 工具条「更多」含计划组 / 依赖图 / 返回运行列表，点击路由正确 | 单测（`plan-toolbar-more` + `router.currentRoute`） | `npx vitest run src/views/PlansView.spec.ts` |
| TC-v3.10-05 | FR-v3.10-01④⑤ | 16 项原功能逐项可达（对照表）+ 旧用例全绿 | `EW310-01/feature-map.md` + `PlansView.spec` 21 条 | `npx vitest run src/views/PlansView.spec.ts` |
| TC-v3.10-06 | FR-v3.10-02① | 下拉列出本项目 run，文案四段 `runId · 时间 · 结论 · 用例数` | `EW310-02/report-picker.json` 的 `criteria.①` + 单测 | `node scripts/report-picker-probe.mjs`、`npx vitest run src/utils/runPicker.spec.ts` |
| TC-v3.10-07 | FR-v3.10-02② | 关键词过滤生效（匹配 → 1 条；不匹配 → 空态） | 探针 `criteria.②` + `RunPicker.spec` | 同上 + `npx vitest run src/components/RunPicker.spec.ts` |
| TC-v3.10-08 | FR-v3.10-02② | 时间档边界：恰好 7 天前保留、早 1 ms 剔除；30 天同理 | 单测（注入 `now`） | `npx vitest run src/utils/runPicker.spec.ts` |
| TC-v3.10-09 | FR-v3.10-02③ | 选中执行记录后标题自动带出 runId | 探针 `criteria.③` + `TestReportsView.spec` | 探针 + `npx vitest run src/views/TestReportsView.spec.ts` |
| TC-v3.10-10 | FR-v3.10-02④ | 主路径无手打框；「高级」可切出手打框并可切回 | 探针 `criteria.④` + 视图单测 | 同上 |
| TC-v3.10-11 | FR-v3.10-02（健壮性） | 执行记录加载失败显示失败标记，且**不**同时显示空态 | 组件单测 | `npx vitest run src/components/RunPicker.spec.ts` |
| TC-v3.10-12 | FR-v3.10-03④ | 旧「按 run」路径零回归（建草稿请求体与改前一致） | 视图单测 | `npx vitest run src/views/TestReportsView.spec.ts` |
| TC-v3.10-13 | FR-v3.10-03①② | 按用例集建草稿：取该集最近一次 run；也可指定 run | JUnit 契约测试 | `mvn -o -B test -Dtest=TestReportServiceTest` |
| TC-v3.10-14 | FR-v3.10-03③ | 冻结快照自包含：删源 run 后快照仍渲染同样内容 | JUnit 契约测试 + `EW310-03/snapshot-case-set.json` | 同上 |
| TC-v3.10-15 | FR-v3.10-03（列表） | 列表展示来源类型（按 run / 按用例集） | 视图单测 | `npx vitest run src/views/TestReportsView.spec.ts` |
| TC-v3.10-16 | 数据模型 | `V11_25` down → up 真实演练：列消失再回来、类型/可空性一致、行数不变 | `EW310-03/down-up.log` | `bash scripts/migration-down-up-drill.sh V11_25__v3_10_report_source` |
| TC-v3.10-17 | 横向回归 | 前端全量、后端全量、`vue-tsc --noEmit`、`vite build` | 命令输出 | 见 §4 |
| TC-v3.10-18 | 门禁 | `scripts/gates.sh` 28 步全 PASS | `docs/evidence/gates/` 日志 | `bash scripts/gates.sh` |

## 3. 已知取证缺口（不掩盖）

| 判据 | 探针能不能判 | 由什么补 | 说明 |
|---|---|---|---|
| 时间档 7d/30d 真实生效 | **不能** | TC-v3.10-08 单测 | 本库全部 run 在最近 5 天内（调研 #19），真实数据切不出差异 |
| 下拉选项总数 | 只能在小结果集上判 | 探针读「过滤后剩几条」 | naive-ui 虚拟列表只渲染可见项（调研 #21） |
| 「选中」这个动作本身 | **不能**（点不到真实选项） | 桩组件 + 探针真点 | 组件单测用桩覆盖接线；真点由探针覆盖，两者都不替代对方 |

## 4. 横向回归与放行

```bash
# 前端
cd frontend && npx vue-tsc --noEmit && npx vitest run && npm run build
# 后端
cd backend && mvn -o -B test
# 迁移演练
cd .. && bash scripts/migration-down-up-drill.sh V11_25__v3_10_report_source
# 门禁
bash scripts/gates.sh
```

放行判据：`vitest` 全绿、`mvn test` 0 失败、`vue-tsc` 无输出、`build` 成功、演练 PASS、`gates.sh` 末行 `GATES_RESULT FAILS=0`。

## 5. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-09 | 初版，18 条 TC |
| v1.1 | 2026-11-09 | 新增 TC-v3.10-11（失败态不得伪装空态）：失败文案与空态文案是**相反结论**，靠模板 `v-if` 的先后顺序决定谁生效，顺序写反就会互相覆盖且不报错，因此单独钉一条 |
