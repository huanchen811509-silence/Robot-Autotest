# MENU-03 证据（v2.2 / v3.0.1）

新增 `CaseBoardView.vue` 测试总表看板，同屏显示 `historical_status` 与 `current_status` 双计数。

> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。本文件读数全部来自真实日志，未编造。

## 命令原文

```
cd frontend && npx vitest run src/views/CaseBoardView.spec.ts
```

（方案 §3.2 验收命令：`npx vitest run src/views/CaseBoardView.spec.ts`）

## 判据

- 同一屏并列两列与各自计数；纯函数 `summarizeBoard` 分别统计，互不串用。
- 导入后 `current_status` 全部为「未测试」（null 计入未测试），历史通过数不为 0。
- 不得把 ED202 历史「通过 330」等历史数值当成本平台当前通过率：当前通过只取 `current_status`，不从历史列推导。
- 空态显示「0 条」，页面不编造 / 硬编码业务数据。

## 真实复跑输出（2026-11-08 08:23:34 +08:00）

同批 6 个前端 spec 一起执行，退出码 0（`VITEST_RC=0`），原始日志 `docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log`：

```
 ✓ src/views/CaseBoardView.spec.ts (8 tests) 12ms

 Test Files  6 passed (6)
      Tests  47 passed (47)
```

8 条用例 = MENU-03 三条（双计数与空态、导入后状态、路由可达）+ EVID-04 五条（四态枚举、双计数隔离锁死、弹窗、下载、无证据态）。

## 判据逐条命中

| 判据 | 命中它的用例（CaseBoardView.spec.ts） | 结果 |
|---|---|---|
| 同屏两列、计数互不串用 | 空态「0 条」，两项计数互不串用；看板路由可达，且同屏并列显示两列 | PASS |
| 导入后当前列全为未测试、历史通过数不为 0 | 导入后：当前列全为未测试、历史通过数不为 0，且当前通过不被历史通过污染 | PASS |
| 历史数值不得当当前通过率 | 历史通过数不写进当前列（EVID-04 新增锁死断言） | PASS |
| 空态「0 条」、不硬编码数据 | 空态「0 条」，两项计数互不串用 | PASS |

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 本包定向 spec | CaseBoardView.spec.ts 8 tests 全过；同批 Test Files 6 passed、Tests 47 passed、`VITEST_RC=0` | docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log |
| 全量前端单测 | Test Files 49 passed、Tests 330 passed（门禁第 02 步） | docs/evidence/gates/logs/02-frontend_test.log |
| 类型检查 | `npx vue-tsc --noEmit` 退出码 0，输出为空（门禁第 03 步） | docs/evidence/gates/logs/03-type_check.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=errors=0；基线 floor 1016 / 325 / 219（只增不减） | docs/evidence/gates/logs/19-evidence_integrity.log |

未覆盖项（如实登记）：前端未装 `@vue/test-utils`，spec 采用「纯函数 + 路由 + 源码字符串断言」范式，不挂载组件，未做真实浏览器点击回归；行集初始为空，未用真实导入件跑端到端。
