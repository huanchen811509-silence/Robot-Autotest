# SCEN-03 证据（v2.2 / v3.0.1）

补齐 `POST /api/scenarios/{id}/execute`（原实测 404），连续两次触发返回 2 个不同 `runId`。

## 命令原文

```
mvn -o -B test -Dtest=ScenarioExecuteIT
```

已执行。结果行：

```
[INFO] Running com.qa.autotest.api.group.ScenarioExecuteIT
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.711 s -- in com.qa.autotest.api.group.ScenarioExecuteIT
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 形态事实（必须如实读）

- `ScenarioExecuteIT` 原本**不存在**：DEV-PLAN §3.2 只给出上述验收命令，未给测试类。本轮新建为 `backend/src/test/java/com/qa/autotest/api/group/ScenarioExecuteIT.java`。
- 实现方式为 **standalone MockMvc + mock `RunExecutionService`，不连库**：`MockMvcBuilders.standaloneSetup(new GroupController(...))` 装配真实 `GlobalExceptionHandler`，`RunExecutionService` 用 Mockito 替身。
- 「不再 404」的机检原理：standalone MockMvc 在路由缺失时返回 404，因此断言 `status().isOk()` 本身就是「路由已注册、不再 404」的机检，无需真库。
- 两次调用的 `runId` 不同，是 **mock 依次返回**（`RUN-SCEN-A` / `RUN-SCEN-B`）后由断言比对得到，证明控制器每次调用都取用新的返回体，不缓存首个结果。
- **`runId` 唯一性生成**不由本 IT 证明，由既有 `RunIdGeneratorTest`（7 个用例，同秒单调序列）覆盖，并随门禁第 05 步全量 `mvn -o -B test` 执行（全量 failures=0、errors=0）。
- 负向用例：不传 `projectId` 且无租户上下文时返回 **400**，并 `verify(executions, times(0))` 断言未调用执行服务（不会落一个 `projectId=0` 的 run）。

## 判据

- `POST /api/scenarios/{id}/execute` 不再 404，返回含 `runId` 的 `ApiResponse`。
- 连续两次触发拿到 2 个不同的 `runId`（本 IT 以 mock 两次返回不同值验证控制层透传；唯一性生成见 `RunIdGeneratorTest`）。
- 缺项目上下文时明确 400 且不触发执行，不产生脏 run。
- 按现有风格最小改动：仅经既有执行服务入口建 run，不派发 pabot、不重写执行引擎；场景 → 用例集解析留待后续包。

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 接口验收单测 | ScenarioExecuteIT：Tests run 2、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 定向 mvn 汇总 | Tests run 49、Failures 0、Errors 0，BUILD SUCCESS（14 个类同批） | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 正向用例 | 两次 POST 均 status 200，runId 依次为 RUN-SCEN-A、RUN-SCEN-B，scenarioId=5、projectId=7 | ScenarioExecuteIT 源码断言（standalone MockMvc，不连库） |
| 负向用例 | 不传 projectId 且无租户上下文：status 400，且 verify times(0) 未调用执行服务 | ScenarioExecuteIT 源码断言 |
| RUN_ID 唯一性 | 既有 RunIdGeneratorTest 含 7 个用例（源码 @Test 计数），随门禁 05 步全量执行、全量 failures=0 | backend/src/test/java/com/qa/autotest/application/execution/RunIdGeneratorTest.java |
| 门禁全量 | 19 步全 PASS，GATES_RESULT FAILS=0（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=0 errors=0 | docs/evidence/gates/logs/05-backend_test.log、02-frontend_test.log、11-editor_test.log |
| 基线 floor | 1016、325、219（只增不减，实测均不低于 floor） | docs/evidence/gates/logs/19-evidence_integrity.log |
