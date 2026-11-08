# MENU-01 证据（v2.2 / v3.0.1）

一级菜单 6 → 8 组；业务能力域不建一级菜单。

> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。本文件读数全部来自真实日志，未编造。

## 命令原文

```
cd frontend && npx vitest run src/config/navigation.spec.ts
```

（方案 §3.2 验收命令：`npx vitest run src/config/navigation.spec.ts`）

## 判据

- `NAVIGATION` 一级分组数 = 8（原 6 组原样保留，新增「环境与拓扑」「维护与恢复」）。
- 8 个一级项各至少 1 个二级入口；新增两组的二级项路径全部命中路由表。
- 6 个业务能力域名（以太网 / RDMA / QoS与拥塞 / 虚拟化与容器 / 卸载与用户态网络 / GPU与存储）不出现在一级项；`navigation.spec.ts` 含「RDMA 假配置」反例，机检谓词应判失败。
- 8 组路由 200、无控制台错误。

## 真实复跑输出（2026-11-08 08:23:34 +08:00）

同批 6 个前端 spec 一起执行，退出码 0（`VITEST_RC=0`），原始日志 `docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log`：

```
 ✓ src/config/navigation.spec.ts (20 tests) 24ms
 ✓ src/views/CaseBoardView.spec.ts (8 tests) 12ms
 ✓ src/views/environment/pages.spec.ts (4 tests) 13ms
 ✓ src/views/maintenance/pages.spec.ts (5 tests) 11ms
 ✓ src/views/baselines/pages.spec.ts (4 tests) 11ms
 ✓ src/components/AiPanelDrawer.spec.ts (6 tests) 183ms

 Test Files  6 passed (6)
      Tests  47 passed (47)
   Start at  08:23:34
```

## 判据逐条命中

| 判据 | 命中它的用例（navigation.spec.ts） | 结果 |
|---|---|---|
| 一级分组数 = 8 | 「v2.2 / MENU-01 八组菜单与业务域隔离」组内：一级分组数 = 8；一级恰好 8 组，层级不超过 2 | PASS |
| 8 个一级项各至少 1 个二级入口 | 8 个一级项各至少 1 个二级入口（叶子自身即一个可导航入口）；分组至少两个子项 | PASS |
| 新增两组二级项路径命中路由表 | 每个叶子的 path 都能被路由表解析到真实页面（菜单不指向 404） | PASS |
| 6 个业务能力域名不入一级项 | 6 个业务能力域名不出现在任何一级项（key / labelKey 字面量） | PASS |
| 反例机检应判失败 | 反例：含「RDMA」一级项的假配置，机检应判失败 | PASS |
| 8 组路由 200、无控制台错误 | 路由可达由 `router.resolve` 断言覆盖；门禁第 13 步浏览器回归 checks=48 fails=0（不含 8 组逐条 200 探针，见下「未覆盖项」） | 部分覆盖 |

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 本包定向 spec | navigation.spec.ts 20 tests 全过；同批 Test Files 6 passed、Tests 47 passed、`VITEST_RC=0` | docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log |
| 全量前端单测 | Test Files 49 passed、Tests 330 passed（门禁第 02 步） | docs/evidence/gates/logs/02-frontend_test.log |
| 类型检查 | `npx vue-tsc --noEmit` 退出码 0，stdout 与 stderr 均为空（门禁第 03 步） | docs/evidence/gates/logs/03-type_check.log |
| 浏览器回归 | `BROWSER_REGRESSION checks=48 fails=0`（门禁第 13 步） | docs/evidence/gates/logs/13-browser_regression.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=errors=0；基线 floor 1016 / 325 / 219（只增不减） | docs/evidence/gates/logs/19-evidence_integrity.log |

未覆盖项（如实登记）：判据第 4 条里的「8 组路由逐条 200 + 控制台零错误」本轮没有单独做逐条 HTTP 探针，现有证据是 spec 的 `router.resolve` 断言（菜单不指向 404）加门禁第 13 步浏览器回归（48 项检查全过，覆盖报告详情路由与 JS 异常 0）。逐条 200 读数属于未采集项，不据此写成已通过。
