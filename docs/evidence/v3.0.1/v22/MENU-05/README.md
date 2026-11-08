# MENU-05 证据（v2.2 / v3.0.1）

新增「性能基线」3 个只读页：iperf / perftest / CCL。

> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。本文件读数全部来自真实日志，未编造。

## 命令原文

```
cd frontend && npx vitest run src/views/baselines/
```

（方案 §3.2 验收命令：`npx vitest run src/views/baselines/`）

## 判据

- 3 条路由全部可达；每条基线条目显示来源 `run_id` 与 SHA。
- 无数据显式显示「0 条」，不显示历史占位数值（330 / 730 / 813 等一律不出现）。
- 3 页只读、无写请求，不编造 / 硬编码业务数据。

## 真实复跑输出（2026-11-08 08:23:34 +08:00）

同批 6 个前端 spec 一起执行，退出码 0（`VITEST_RC=0`），原始日志 `docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log`：

```
 ✓ src/views/baselines/pages.spec.ts (4 tests) 11ms

 Test Files  6 passed (6)
      Tests  47 passed (47)
```

## 判据逐条命中

| 判据 | 命中它的用例（baselines/pages.spec.ts） | 结果 |
|---|---|---|
| 3 条路由全部可达 | 3 条路由都解析到真实页面（不落到 not-found） | PASS |
| 每条显示来源 run_id 与 SHA | 每条显示来源 run_id 与 SHA，空态显示「0 条」 | PASS |
| 不出现历史占位数值 | 不显示历史占位数值（330 / 730 / 813 等一律不出现） | PASS |
| 3 页只读、无写请求 | 3 个视图均为只读：无写请求 | PASS |

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 本包定向 spec | baselines/pages.spec.ts 4 tests 全过；同批 Test Files 6 passed、Tests 47 passed、`VITEST_RC=0` | docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log |
| 全量前端单测 | Test Files 49 passed、Tests 330 passed（门禁第 02 步） | docs/evidence/gates/logs/02-frontend_test.log |
| 类型检查 | `npx vue-tsc --noEmit` 退出码 0，输出为空（门禁第 03 步） | docs/evidence/gates/logs/03-type_check.log |
| 只读接口性能 | 门禁第 17 步 perf_baseline PASS（P95 未劣化超阈值） | docs/evidence/gates/logs/00-full-run.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=errors=0；基线 floor 1016 / 325 / 219（只增不减） | docs/evidence/gates/logs/19-evidence_integrity.log |

未覆盖项（如实登记）：三条基线页均为空态验证，未接真实 `run_id` 与 SHA 数据做端到端展示验证；「只读」为源码扫描断言，未起后端观察网络请求。
