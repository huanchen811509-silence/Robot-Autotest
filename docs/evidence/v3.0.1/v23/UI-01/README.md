# UI-01 菜单 IA 重构证据（v2.3 / FR-UI-01..08、26..30）

把侧栏从「8 组、两个叶子 + 六个分组、默认全展开」改成「**7 组、全部是分组、默认只展开当前组**」，
并把「执行」与「报告」合并为一个一级组，同时修掉 `/test-reports` 的孤儿页与「同页两名」。

> 状态：已回填（2026-11-08，命令真实执行）。原始日志见 `docs/evidence/v3.0.1/v23/UI-08/raw/`。
> 本文件不含占位、不含「待复跑」。

## 1. 改了什么

| 文件 | 类型 | 说明 |
|---|---|---|
| `frontend/src/config/navigation.ts` | 修改 | 7 组目标态；`NavLeaf` 新增 `exclude`（前缀排除）与 `section`（合并组分区）；新增 `isSectionBreak()` |
| `frontend/src/config/navigation.spec.ts` | 修改 | 旧「8 组」断言改为 7 组；新增 12 条合并组 / 命名唯一性 / 角色矩阵断言 |
| `frontend/src/components/AppNav.vue` | 新增 | 导航渲染从 `App.vue` 抽出：展开态、叶子/分组形态、分区线 |
| `frontend/src/components/AppNav.spec.ts` | 新增 | 6 条：形态可区分、默认只展开当前组、手型优先、分区线恰好 1 条 |
| `frontend/src/App.vue` | 修改 | 只留布局与能力过滤，导航渲染交给 `AppNav` |
| `frontend/src/locales/zh-CN.ts` / `en-US.ts` | 修改 | 新增 `menu.workbenchOverview` `menu.execution` `menu.runsPlans` `menu.reportTemplates` `menu.externalReports`；改名 5 处 |
| `frontend/src/router/index.ts` | 修改 | 新增 `/workbench`；`/` 与登录后落地改指 `/workbench` |
| `frontend/src/views/{Login,Forbidden,NotFound}View.vue` | 修改 | 默认跳转统一到 `/workbench`，不再指向 `/overview` |

## 2. 目标态（与 PRD 附录 A 逐项一致）

| 序 | key | 显示名 | 子项数 | 备注 |
|---|---|---|---|---|
| 1 | workbench | 工作台 | 2 | 项目总览 `/workbench` + 项目看板 `/overview` |
| 2 | execution | 执行与报告 | 6 | 运行记录 / 测试计划 / 执行节点 ｜ 报告列表 / 报告模板 / 对外报告 |
| 3 | cases | 用例 | 3 | 含测试总表看板 |
| 4 | assets | 资产 | 3 | 设备 / 链路 / 凭据（`?tab=` 精确区分） |
| 5 | environment | 环境与拓扑 | 5 | 只读页 |
| 6 | maintenance | 维护与恢复 | 3 | 只读页 |
| 7 | settings | 系统设置 | 5 | 平台设置 / 用户管理 / 审计 / 关于 / 版本迭代 |

## 3. 三条判据的技术要点

| 点 | 做法 | 为什么必须这么做 |
|---|---|---|
| 报告模板归属 | `execution-reports` 加 `exclude: ['/reports/templates']` | `/reports` 按前缀会命中 `/reports/templates`，不排除就会出现「点模板，报告列表也亮」 |
| 合并组权限 | 组上**不设** `capability`，只给 3 个运行态子项设 `execution:read` | 原「报告」叶子本不需要该能力；给组加回会让 `viewer` 静默失去报告入口 |
| 分区线 | `section: 'run' / 'report'` + `isSectionBreak()` | 只影响渲染，不参与选中与能力过滤（单测锁「带/不带 section 结果相同」） |

## 4. 命令原文与真实输出

```bash
cd /home/evergreen/robot-autotest/frontend
npx vitest run src/config/navigation.spec.ts src/components/AppNav.spec.ts
```

```text
✓ src/config/navigation.spec.ts (32 tests) 33ms
✓ src/components/AppNav.spec.ts (6 tests) 164ms
Test Files  2 passed (2)
     Tests  38 passed (38)
```

断言覆盖（逐条对应 PRD）：

| 判据 | 断言 |
|---|---|
| FR-UI-01 | `NAVIGATION` 长度 7，key 序列严格等于 workbench / execution / cases / assets / environment / maintenance / settings |
| FR-UI-02 | 工作台是分组、children 长度 2、两个 path 都能 `router.resolve` 命中真实页面且不是 `not-found` |
| FR-UI-03 | `menu.workspace` 与 `menu.overview` 不相等，且分别挂在分组与 `/overview` 子项上 |
| FR-UI-04 | `execution-plans.labelKey === 'menu.runsPlans'`；navigation 源码不再出现 `plans.title` |
| FR-UI-05/06 | `menu.mntPrinciples` / `menu.adminUsers` / `menu.envOverview` 词条键落位；`/nodes` 叶子恰好 1 个 |
| FR-UI-07 | 分组头有 `aria-expanded` 与 chevron；`.app-nav-sub` 两者都没有 |
| FR-UI-08 | 7 个一级项 icon 集合大小 = 7 |
| FR-UI-26 | 合并组 children 的 path 严格等于 6 元数组；一级不再有 `reports` |
| FR-UI-27 | 组上 `capability` 为 `undefined`；带 `execution:read` 的恰好是 3 个运行态项 |
| FR-UI-28 | `/test-reports` 叶子恰好 1 个 |
| FR-UI-29 | `section` 序列 = run,run,run,report,report,report；分区线恰好 1 条且在第 3 项前 |
| FR-UI-30 | `hasBusinessDomainAsTopLevel(NAVIGATION) === false`（沿用 v2.2 判据，未放松） |

## 5. 角色矩阵（V-UI-11 的机检部分）

```text
viewer    : ['workbench-overview', 'workbench-board', 'execution-reports',
             'execution-report-templates', 'execution-external-reports', 'cases-…', …]
engineer  : 含 execution-runs / execution-plans / execution-nodes 共 6 项执行域
```

`viewer` 仍见 3 个报告项、不见 3 个运行项——与合并前**逐项一致**，说明合并没有把权限收窄。

## 6. 没做到的判据（如实）

| 项 | 状态 | 原因 |
|---|---|---|
| 浏览器里从侧栏点「对外报告」可达 | **未验** | 门禁第 13 步需要 Chrome CDP `:9222`，本轮该后台进程被取消，未重启（按大师指示不重启）。已有的 `docs/evidence/gates/logs/13-browser_regression.log` 是 v2.3 之前的版本，不能当本批证据 |
| 排序「更合理」 | 推演，非实测 | 仓库无访问埋点；PRD 已记为遗留 `L-UI-01` |
