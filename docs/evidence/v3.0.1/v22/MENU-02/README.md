# MENU-02 证据（v2.2 / v3.0.1）

新增「环境与拓扑」5 个只读页：环境总览 / 环境采集 / 拓扑与快照 / 环境准备 / 采集记录分析。

> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。本文件读数全部来自真实日志，未编造。

## 命令原文

```
cd frontend && npx vitest run src/views/environment/
```

（方案 §3.2 验收命令：`npx vitest run src/views/environment/`）

## 判据

- 5 条路由全部可达（`router.resolve` 命中真实组件，不落到 `not-found`）。
- 5 个页面为只读视图，不含任何写请求（POST / PUT / DELETE / PATCH / multipart），只读数据统一 GET。
- 环境准备页不触发真实设备写入（页面明确声明，且无任何写入 / 执行调用）。
- 空态显示「0 条」，不编造 / 硬编码业务数据。

## 真实复跑输出（2026-11-08 08:23:34 +08:00）

同批 6 个前端 spec 一起执行，退出码 0（`VITEST_RC=0`），原始日志 `docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log`：

```
 ✓ src/views/environment/pages.spec.ts (4 tests) 13ms

 Test Files  6 passed (6)
      Tests  47 passed (47)
```

## 判据逐条命中

| 判据 | 命中它的用例（environment/pages.spec.ts） | 结果 |
|---|---|---|
| 5 条路由全部可达 | 5 条路由都解析到真实页面（不落到 not-found）——一条用例遍历 5 条路由断言 | PASS |
| 5 页只读、无写请求 | 5 个视图均为只读：无写请求、空态显示「0 条」 | PASS |
| 环境准备页不真写设备 | 环境准备页明确不触发真实设备写入 | PASS |
| 空态「0 条」、不硬编码数据 | 5 个视图均为只读：无写请求、空态显示「0 条」 | PASS |
| 分组含 5 个二级项且路径一致 | 「环境与拓扑」一级分组含 5 个二级项，路径与路由一致 | PASS |

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 本包定向 spec | environment/pages.spec.ts 4 tests 全过；同批 Test Files 6 passed、Tests 47 passed、`VITEST_RC=0` | docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log |
| 全量前端单测 | Test Files 49 passed、Tests 330 passed（门禁第 02 步） | docs/evidence/gates/logs/02-frontend_test.log |
| 类型检查 | `npx vue-tsc --noEmit` 退出码 0，输出为空（门禁第 03 步） | docs/evidence/gates/logs/03-type_check.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=errors=0；基线 floor 1016 / 325 / 219（只增不减） | docs/evidence/gates/logs/19-evidence_integrity.log |

未覆盖项（如实登记）：4 个用例覆盖 5 页（5 条路由在一条用例里循环断言），未对每页单独计数；「无写请求」是源码扫描式断言，未起真实后端观察网络请求。
