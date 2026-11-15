# v5.12.0 需求调研（00-RESEARCH）：合并资产工作台之前，先核掉四个过期假设

> 流程位置：① 需求调研。每条结论标注**证据来源**；[已核实] / [推断] / [假设] 三档分开写。
> 上游：`docs/plan/重构评估-20261115/03-资产与采集闭环评估.md` §一~§三、
> `02-列表与工作区交互评估.md` §四。
> 本文件不改评估结论，只补 **v5.11.0 收口后（commit `72a9adc`）到 v5.12.0 W5（`b0a6362`）** 的实测事实。

## 1. 实测事实（2026-11-15，WSL 权威副本）

| # | 事实（三档标注） | 证据 |
|---|---|---|
| R-1 | [已核实] 成员查询**不需要再补 `LEFT JOIN`**：5.11 已有 `CaseScriptLookup.of(projectId, caseIds)` 一次批量解析，`TestSetController.items()` 已返回 `CaseListView`（含 `scriptPath`/`scriptName`/`scriptNodeId`/`bindingState`） | `backend/src/main/java/com/qa/autotest/application/caseasset/CaseScriptLookup.java`；`TestSetController.java#items`；`TestSetMemberQueryTest` 5 条 |
| R-2 | [已核实] **全局资产 `PUT` 早已存在**：`PUT /api/global/assets/{type}/{id}` 是 5.9.0 / W59-20 的改名端点，`GlobalAssetService.rename()` 只写 `name`，`requireAdmin()` 已成立；真实缺口是「管理 IP 不透传」 | `GlobalAssetController.java` 的 `@PutMapping("/{type}/{id}")`；`GlobalAssetRenameTest`；v5.9.0 交付记录 |
| R-3 | [已核实] **端口编辑/删除后端早有**：`PUT/DELETE /api/projects/{projectId}/assets/ports/{portId}` 在 `DeviceController` 里；但前端 `deviceApi` 只接了 `addPort`，页面只有新增表单 | `DeviceController.java:82-94`；`frontend/src/api/asset.ts`（W5 前）；`AssetsView.vue` 端口面板 |
| R-4 | [已核实] 删除被链路引用的端口会落到外键异常（500 口径）：`DeviceService.deletePort()` 在 W5 前直接 `devices.deletePort(...)`，`link` 表的 `a_port_id`/`b_port_id` 会挡住 | `DeviceService.java#deletePort`（W5 前）；`link` 表结构 |
| R-5 | [已核实] 项目资产页**没有「来源」列**，但 `isPlatformAsset(row)`（按 `projectId !== 当前项目`）早已存在；矩阵 `col.source` 标 `project=true / global=false` | `AssetsView.vue:289`；`frontend/src/config/assetWorkspaceMatrix.ts` |
| R-6 | [已核实] 全局资产单条删的 409 **只给引用数量、不点名项目**；批量删端点不存在；矩阵 `action.batchDelete` 两 scope 均为 `true` | `GlobalAssetService.java#delete`（W5 前）；`openapi.json`（W5 前 450 endpoints） |
| R-7 | [已核实] 合并前两页资产组件合计 **2557 行**；W4 合并后 `AssetsView.vue` 1459 + `GlobalAssetsView.vue` 1187 + `AssetWorkspace.vue` 79 = **2725 行**，超过 NFR-03 的 ≤1530 目标 | `wc -l` 实测；W4 提交 `6146484` 登记 |
| R-8 | [已核实] 用例集两页的 `window.confirm` 字面量命中 0；成员幂等真值表三向对齐（列 / DTO / 详情），注入假列名判红 | `grep -rn "window.confirm" frontend/src`；`caseSetIdempotency.spec.ts` 10 条 |
| R-9 | [已核实] 差异矩阵 **16 行**与 PRD §3 表格数据行数一致；`filterColumnsByMatrix` 是唯一列过滤入口，`AssetWorkspace.vue` 源码不含 `v-if="scope` 整块分支 | `assetWorkspaceMatrix.spec.ts` 12 条；`AssetWorkspace.spec.ts` 5 条 |
| R-10 | [已核实] 契约快照 W3 后为 **450 endpoints / 369 paths**；W5 增批量删端点后为 **451 / 370**，`gen_api_contract.py --check` PASS | `tools/gen_api_contract.py` 两次输出 |
| R-11 | [已核实] 后端全量 **2095 条 / 0 失败 / 0 错误 / 4 跳过**；前端全量 **143 文件 / 1139 例**；`vue-tsc --noEmit` 0 error；`check_i18n.py` 5/5 | `backend/target/surefire-reports/TEST-*.xml`；`npx vitest run`；`npx vue-tsc`；`tools/check_i18n.py` |

## 2. 由事实推出的结论（[推断]，未逐条实测）

1. **计划里的「补 join」「补 PUT」都是过期假设**：它们写于 5.11 交付前，而 5.11 实际已把脚本批量解析做掉；5.9 已把改名端点做掉。若不核就照做，会出现两份同源 SQL / 两个同名端点，属于典型的「按计划不看代码」。
2. **409 的价值在「点名」而不是「拦住」**：外键本来就拦得住，但运维看到 500 只会重试；点名链路 / 项目才能指导下一步动作。
3. **行数收敛与零功能回退在本版冲突**：两页各自保留导入弹窗、凭据表单、链路拓扑、租约、平台引用等独有块；抽走的只有共享表格骨架。要压到 ≤1530 必须动这些独有块，风险大于收益 —— 这一条不是「没做完」，是**取舍**，需按未达成登记。
4. **前端不复制后端授权过滤**：`AssetScopeSql` 是唯一真源，「来源」列只做标注，不能拿它当可见性依据。

## 3. 未核实项（不写成结论）

| # | 待核 | 为什么没核 |
|---|---|---|
| U-1 | [假设] 1000 成员 / 1000 设备列表滚动 fps ≥ 50（NFR-04） | 本轮无真实浏览器性能采样；`perf-longlist.mjs` 的产品口径此前实测 `rows=0`，不能当 1000 行 fps |
| U-2 | [假设] 200+ 集合搜索 ≤300ms（TC-v5.12-05） | 同上，只有视图级单测，无真库 200 集合样本 |
| U-3 | [假设] 真实视口下 1440×900 三栏三态截图 | 无截图环境，UI 判据降级为 DOM 断言 |
| U-4 | **[已核实]** 跨项目按 id 直访返回 404「设备不存在」（owner=95 / other=126 / device=21） | `docs/evidence/v5.12.0/w6-http-probe.txt` |
| U-5 | [假设] 中栏 3000 分类展开耗时 ≤500ms | 沿用 5.11 的未采样项，本版未新增采样 |

## 4. 与上游评估的差异

- 评估 03 §3.3 建议「先接采集再合组件」；本版按顺序调整为先合组件（PRD §1.1 已留痕），实际只写一次表格实现，避免 5.13 两遍返工。
- 评估 03 把「全局资产缺 CRUD」列为阻塞；实测 R-2/R-3 表明缺的是**前端入口与事实列透传**，不是后端端点。评估报告据此改写可行性结论。
- 评估 02 §四的「行数收敛」按 R-7 判定为**未达成**，不因合并完成而自动视为达成。

## 5. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：11 条实测事实 + 5 条未核实项；核掉 W62-01 / W62-12 的过期假设 | commit `72a9adc` → `b0a6362` 的代码与测试输出 |
