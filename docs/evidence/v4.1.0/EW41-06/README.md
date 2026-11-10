# EW41-06 · 工作区 UI 与任务中心（FR-v4.1-05）

| 项 | 内容 |
|---|---|
| 工作包 | `W41-06` 工作区 UI 与任务中心 |
| 日期 | 2026-11-09 |
| 环境 | 前端 `http://localhost:8001` · Chrome CDP `:9222` · 后端 `:8081` · 真库 `robot_dev`（项目 29） |
| 结论 | `W41_PROBE PASS`：工作区 `15/15` 检查全绿；三卡、三粒度、三策略、包列表、预检 496 行明细、导入门控、任务 20 行与失败行重试入口均为真实读数 |

---

## 1. 判据与真实读数（`workspace-probe.json`）

### 1.1 布局与表单

| 判据 | 真实读数 |
|---|---|
| 三张卡 | `cards=3`（导出 / 导入 / 任务中心） |
| 导出粒度三项 | `PROJECT / DIR / FILE` |
| 冲突策略三项 | `SKIP / OVERWRITE / RENAME` |
| 包列表 | `bundleRows=1`：`qa-e2e-fixture / project / 11/9/2026 11:19:12 PM / 75.2 KB / 24 文件 / 已签名 / 载入`，徽标 `badge badge-ok` |
| 无裸 i18n key | `rawKeyText=false`（页面未出现 `workspaceUi.*` 之类原文） |
| DIR 显根路径 | `rootInput=true`，placeholder `例如 cases/login.robot` |
| PROJECT 隐根路径 | `rootInput=false` |
| 载入包回填 | 点第一行「载入」→ 导入框值 `qa-e2e-fixture` |
| 预检前门控 | 导入按钮 `disabled=true`（未预检 / 未通过不可点） |
| 预检后放行 | 预检 `ok` 报告后导入按钮 `disabled=false` |
| 表单可及性 | `htmlLang=zh-CN`；`labelledSelects=true`、`labelledInputs=true`（控件均被 `label` 包裹）；三张表表头 `7 / 4 / 7` 列 |

### 1.2 预检（dry-run）真实读数

| 判据 | 真实读数 |
|---|---|
| 报告徽标 | `可导入`（`badge badge-ok`） |
| 计数行 | `0 导入 / 494 跳过 / 2 隔离`、`错误码: OK`、`导入完成` |
| 明细行 | `itemRows=496`（496 个文件逐行给出类别 / 键 / 动作 / 原因） |
| 隔离区 | `quarantine=2`：`examples/01_quick_check.robot`、`examples/03_math.robot`，原因 `命中可疑脚本，已隔离；确认后可重新导入` |
| 隔离确认框 | `confirmCheckbox=true`（隔离非空时出现 `.workspace-check` 勾选框；未勾选时点导入会提示 `quarantineConfirmRequired`，见源码门控） |
| 跳过样例 | `examples/02_list_ops.robot` 等 `同路径已存在（策略 SKIP）` |

> 说明：`importEnabledAfter` 是探针里读取的 `disabled` 值（`false` 即「已放行」），预检 `ok` 后按钮可点。

### 1.3 任务中心

| 判据 | 真实读数 |
|---|---|
| 任务行数 | `jobRows=20` |
| 状态分布 | 失败 9 / SUCCESS 11 |
| 重试按钮 | `retryButtons=9`，失败行 9，**仅失败行**有重试（`failedRowsWithRetry=9`） |
| 重试真实调用 | 点失败任务 `id=84` → 页面出现错误行：`路径含非法段: ../x` + `技术详情 INVALID_ARGUMENT (40001)`；任务表仍 20 行（`retryRowDelta=0`） |

负向任务本身是导入安全用例注入的 `../x` 路径，重试必然 400 —— **入口真实调用了后端并如实回显错误**，但「重试后转成功」未演示（NOT-RUN，见 §3）。

## 2. 实现口径

- 三卡复用 `W40-02` 浮层 / 卡片基座；导出卡粒度切换动态显隐 `rootPath` 输入。
- 导入严格两段：先 `preflight`（dry-run，不写库），报告 `ok` 且勾选隔离确认后才允许 `import`；按钮 `:disabled` 绑定 `!report || !report.ok || reportMode!=='preflight'`。
- 报告按类别渲染明细（`itemRows` = 文件总数），隔离区单列并要求显式勾选（`confirmQuarantine`）。
- 任务中心仅对 `FAILED` 行渲染重试按钮，重试走后端任务重投接口，错误以 `ErrorLine` 组件（`.error-line`）呈现。

## 3. 判不了的部分（NOT-RUN）

| # | 项 | 原因 | 补齐 |
|---|---|---|---|
| 1 | 隔离确认后真正执行导入 | 样本包 2 个隔离项未勾选确认，未实测「勾选 → 导入成功」链路；该链路后端由 EW41-02/03 契约脚本覆盖 | 勾选确认后点导入并复核库内文件数 |
| 2 | 重试转成功 | 现成失败任务均为负向注入（`../x`），重试如实返回 `INVALID_ARGUMENT (40001)`；未构造「瞬时失败可恢复」任务 | 造一次可恢复失败任务后重试至 `SUCCESS` |
| 3 | axe-core 自动扫描 | 仓库未安装 `axe-core`，不冒充通过；以手写判据（`lang`、`label` 包裹、表头列数）降级验证 | 安装 `axe-core` 后跑全规则扫描 |
| 4 | 预检与导入的并发 / 大批量性能 | 仅 496 文件样本，未压测大包与并发 | 需大包样本 + 并发预检 |
| 5 | 导出三粒度的真实产出对照 | 本包只验证 UI 粒度/策略选项与显隐；三级导出产物由 EW41-04 契约脚本以真库验证 | 已由 EW41-04 覆盖 |
