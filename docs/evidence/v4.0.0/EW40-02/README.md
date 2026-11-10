# EW40-02 · 统一浮层范式与顶栏账户菜单（FR-v4.0-02）

| 项 | 内容 |
|---|---|
| 工作包 | `W40-02` 统一工作区浮层范式 |
| 需求 | `FR-v4.0-02`：层级变量 / 焦点陷阱 / `aria-modal` / 滚动锁 / reduced-motion |
| 日期 | 2026-11-09 |
| 环境 | Chrome 9222 · 前端 `:8001`（4.2.0 构建）· qa-e2e-admin |
| 结论 | 单元 11/11 + 导航 33/33（共 44）绿；CDP 10 项判据全绿，`ACCOUNT_PROBE PASS` |

---

## 1. 真实读数

| 判据 | 命令 | 读数 |
|---|---|---|
| 面板语义 | CDP 探针 | `role=menu`、`aria-label=账户菜单（状态 / 外观 / 管理 / 会话）`、`aria-expanded=true` |
| 四分区 | CDP 探针 | `sections=4`；标题 `外观 / 管理与帮助 / 会话`（状态区无标题，只读） |
| 状态区只读 | CDP 探针 | 状态分区内 `input/select/textarea = 0` |
| 层级 | CDP 探针 + `theme.css` | 计算值 `z-index=100` ← `--z-overlay: 100`（模态另用 `--z-modal: 200`） |
| 滚动锁 | CSS | 面板 `max-height: 70vh; overflow-y: auto`，页面滚动不被锁死 |
| 焦点 | CDP 探针 | 打开后焦点在面板内；连按 Tab 12 次未逃逸（`focus-trap=true`）；`Esc` 关闭 |
| 减动效 | CDP 探针 | 正常态 `transitionDuration=0s`；模拟 `prefers-reduced-motion: reduce` 后为 `1e-05s`（全局 0.01ms 降级） |
| 账户菜单行为 | `npx vitest run src/components/AccountMenu.spec.ts src/config/navigation.spec.ts` | `Test Files 2 passed`、`Tests 44 passed` |

证据文件：

| 文件 | 内容 |
|---|---|
| `account-menu-probe.json` | CDP 探针完整读数（含登录用户名、菜单项、z-index、焦点、减动效） |
| `account-menu-vitest.log` | 账户菜单 11 条 + 导航 33 条单测输出 |

## 2. 实现口径

- 浮层语义未单独抽 `OverlaySurface.vue`，而是收敛为：`theme.css` 的 `--z-overlay/--z-modal` 令牌 +
  `useFocusTrap` 组合式 + 各浮层组件（`AccountMenu` / `AboutDialog` / `AppearanceDialog` / `CommandPalette` / `SidePanel`）。
  计划中的组件名与实现方式存在偏差，已记入计划 §5 偏差表；语义判据（层级 / 焦点 / Esc / 减动效）全部由实跑覆盖。
- 账户菜单打开时并行拉取 `/_ping`（版本）与 `/api/health`（服务状态）；后端不可达降级为「降级」，菜单仍可用（单测第 4 条）。

## 3. 判不了的部分

| # | 项 | 原因 | 补齐 |
|---|---|---|---|
| 1 | 探针未接入 `scripts/env-pages-probe.mjs` / 门禁 | 该探针依赖真实 Chrome 与登录态，纳入门禁会引入环境性假红；本轮以一次性探针归档 | 后续可将判据并入第 13 步浏览器回归 |
| 2 | 真机 125%/150% 缩放下的浮层定位 | 属 W40-04 折叠态量测范围，本包只看 1440×900 | 见 EW40-04 |
