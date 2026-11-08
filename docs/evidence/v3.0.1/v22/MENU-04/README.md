# MENU-04 证据（v2.2 / v3.0.1）

新增「维护与恢复」3 个只读页：执行原则 / 恢复记录 / 只读找回。

> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。本文件读数全部来自真实日志，未编造；未执行项已单列，不写成通过。

## 命令原文

```
# 本包新增前端只读页用例（真实执行）
cd frontend && npx vitest run src/views/maintenance/

# 原方案 §3.2 列的后端定向命令（本轮未执行，原因见「未覆盖项」）
mvn -o -B test -Dtest=ReadOnlyRecoverTest
```

后端定向命令的执行前提不成立：仓库内不存在该测试类（`find backend -iname "*Recover*"` 只命中既有的 `RestartRecoveryTest` 与 `RestartRecoveryService`，与「只读找回」无关；`ReadOnlyRecoverView.vue` 只有前端视图与路由 `/maintenance/recover`）。因此本轮没有伪造这条命令的输出，后端侧只引用门禁第 05 步的全量读数。

## 判据

- 3 条路由全部可达；3 页只读、无写请求；空态显示「0 条」。
- 恢复记录不含恢复命令正文（页面明确声明，且无「恢复命令」列）。
- 只读找回为 GET 且不产生新 run：仅只读查询，不调用执行 / 触发 / 重跑接口。

## 真实复跑输出（2026-11-08 08:23:34 +08:00）

同批 6 个前端 spec 一起执行，退出码 0（`VITEST_RC=0`），原始日志 `docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log`：

```
 ✓ src/views/maintenance/pages.spec.ts (5 tests) 11ms

 Test Files  6 passed (6)
      Tests  47 passed (47)
```

## 判据逐条命中

| 判据 | 命中它的用例（maintenance/pages.spec.ts） | 结果 |
|---|---|---|
| 3 条路由全部可达 | 3 条路由都解析到真实页面（不落到 not-found） | PASS |
| 3 页只读、无写请求、空态「0 条」 | 3 个视图均为只读：无写请求、空态显示「0 条」 | PASS |
| 恢复记录不含恢复命令正文 | 恢复记录不含恢复命令正文 | PASS |
| 只读找回为 GET 且不产生新 run | 只读找回为 GET 且不产生新 run | PASS |
| 分组含 3 个二级项且路径一致 | 「维护与恢复」一级分组含 3 个二级项，路径与路由一致 | PASS |

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 本包定向 spec | maintenance/pages.spec.ts 5 tests 全过；同批 Test Files 6 passed、Tests 47 passed、`VITEST_RC=0` | docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log |
| 全量前端单测 | Test Files 49 passed、Tests 330 passed（门禁第 02 步） | docs/evidence/gates/logs/02-frontend_test.log |
| 全量后端单测 | `Tests run: 1050, Failures: 0, Errors: 0`（门禁第 05 步，`mvn -o -B test`） | docs/evidence/gates/logs/05-backend_test.log |
| 类型检查 | `npx vue-tsc --noEmit` 退出码 0，输出为空（门禁第 03 步） | docs/evidence/gates/logs/03-type_check.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=errors=0；基线 floor 1016 / 325 / 219（只增不减） | docs/evidence/gates/logs/19-evidence_integrity.log |

未覆盖项（如实登记）：`mvn -o -B test -Dtest=ReadOnlyRecoverTest` 本轮未执行——该类在仓库中不存在，跑起来只会得到「No tests were executed」；「只读找回为 GET 且不产生新 run」目前只有前端源码断言，没有后端接口级实测（该只读找回接口在后端无对应测试类）。
