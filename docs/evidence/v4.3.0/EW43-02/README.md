# EW43-02 · 导航叶子与能力码（W43-02）

**日期** 2026-11-10 ｜ **对应** `TC-v4.3-01`、`TC-v4.3-02`、`TC-v4.3-03`、`TC-v4.3-08`、`FR-v4.3-01`

## 1. 改动

| 位置 | 文件 | 改动 |
|---|---|---|
| 导航树 | `frontend/src/config/navigation.ts` | `workbench` 组新增 `workbench-create`（`/projects/new`，`labelKey=nav.workbenchCreate`）；新增 `environment-connections`（`/environment/connections`） |
| 能力码 | 同上 | `ROLE_CAPABILITIES` 四角色补 `asset:read`；ADMIN / PM 补 `project:create` |
| 路由 | `frontend/src/router/index.ts` | `/projects/new` 的 `meta.capability` 改为 `project:create`；新增 `/environment/connections`（`asset:read`） |
| 账户菜单 | `frontend/src/components/AccountMenu.vue` | 新增 `canCreateProject`，按 `project:create` 显隐「新建项目」 |
| 词条 | `frontend/src/locales/zh-CN.ts`、`en-US.ts` | 新增 `nav.workbenchCreate`、`nav.environmentConnections` 等键，中英同步 |
| 单测 | `frontend/src/config/navigation.spec.ts` | 新增 2 例：workbench 子路径恰 4 条、`workbench-create` 与 `workbench-overview` 的 `labelKey` 不同 |

## 2. 命令与读数

```bash
# WSL ~/robot-autotest/frontend
./node_modules/.bin/vitest run
```

| 项 | 读数 |
|---|---|
| 前端全量 | **592 例通过**（含 `navigation.spec.ts` 35 例） |
| `vue-tsc --noEmit` | 0 错误 |
| `eslint`（变更 8 文件） | 0 问题 |

## 3. 说明

- 隔离「新建项目」独立入口，不再依赖项目上下文；叶子为静态常量，选项目不裁剪菜单（`TC-v4.3-02` 的静态依据）。
- 导航叶子不挂 `capability`，但路由 `meta` 挂 `asset:read` / `project:create`，页面可见性由守卫兜底。
- **缺口**：DOM 双菜单并列、四角色截图与「0 写请求」清单需 CDP `:9222`，本机不可用，见 `05-TEST-REPORT.md` §3。
