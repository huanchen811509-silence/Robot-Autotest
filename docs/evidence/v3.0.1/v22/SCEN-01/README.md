# SCEN-01 证据 · 场景「一层编排」补强（v2.2 / 2026-11-08）

判据：外键存在；非空 `parent_scenario_id` 返回明确错误。
证据目录 `docs/evidence/v3.0.1/v22/SCEN-01/`（P-1 定案：不新建 release 目录、不 bump 版本、B/G 号挂起）。

## 1. 落地内容（真实编码，非占位）

- `backend/src/main/java/com/qa/autotest/application/group/ScenarioService.java`
  - 新增纯校验入口 `validateNoParent(Long parentScenarioId)`：非空抛 `BusinessException(ErrorCode.INVALID_ARGUMENT, "INVALID_ARGUMENT(40001)：一层编排不允许嵌套父场景，parent_scenario_id 必须为空，收到 …")`，空值放行。
  - 新增 `createScenario(..., int stepOrder)` 与 `createScenario(..., int stepOrder, Long parentScenarioId)` 重载；完整入口先调 `validateNoParent(parentScenarioId)`。
  - INSERT 语句保持 `VALUES (?, ?, ?, NULL)`（父场景永远固定 NULL）；step_order 由随后的 `UPDATE test_scenario SET step_order = ? WHERE id = ?` 落库，不改 INSERT 形态以兼容旧单测断言。
  - 保留原 3 参重载；`scenarios()` 改为 `SELECT id, name, case_set_ids, step_order, parent_scenario_id … ORDER BY step_order, id`。
- `backend/src/test/java/com/qa/autotest/application/group/ScenarioSchemaTest.java`（新增 5 条纯静态断言）：断言 `V11_11__test_scenario_order.sql` 含 `fk_scenario_project` / `step_order` / `FOREIGN KEY` / `idx_test_scenario_order`；`validateNoParent(1L)` 抛异常且 `validateNoParent(null)` 不抛；源码仍含 `VALUES (?, ?, ?, NULL)` 且不含 `parent_scenario_id = ?`；排序与 step_order 查询形态。
- 迁移 `db/migration/V11_11__test_scenario_order.sql` 已由 Flyway 应用，本包不重复建、不改字节；单测只做静态断言（不连库）。

## 2. 真实执行的命令

```
cd /home/evergreen/robot-autotest/backend
mvn -o -B test -Dtest=ScenarioSchemaTest,ExecutionSelectionFreezeTest,Ed202OrganizationTest
mvn -o -B test -Dtest=ScenarioServiceTest
```

首轮另执行过 `mvn -o -B test -Dtest=ScenarioSchemaTest,ExecutionSelectionFreezeTest,ScenarioServiceTest`（BUILD SUCCESS：ScenarioServiceTest 3/0、ScenarioSchemaTest 5/0、ExecutionSelectionFreezeTest 5/0）。本轮又在定向单次 mvn（14 个类）中同批复跑，逐类读数一致。

## 3. 真实输出摘要（摘自上述命令 stdout）

```
[INFO] Running com.qa.autotest.application.group.ScenarioSchemaTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.076 s -- in com.qa.autotest.application.group.ScenarioSchemaTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Running com.qa.autotest.application.group.ScenarioServiceTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.087 s -- in com.qa.autotest.application.group.ScenarioServiceTest
[INFO] BUILD SUCCESS
```

本轮定向单批复核行（同一日志文件内）：

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.041 s -- in com.qa.autotest.application.group.ScenarioServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.040 s -- in com.qa.autotest.application.group.ScenarioSchemaTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 4. 迁移编号事实

DEV-PLAN 的 D6 实际落号为 `V11_11__test_scenario_order.sql`（不是 `V11_6`）。原因：`V11_9__ai_runtime.sql` 已应用且 Flyway 未开 out-of-order，只能用更高号顺延。`test_scenario` 新增 `step_order`（默认 0，排序锚点 `idx_test_scenario_order(project_id, step_order, id)`）与外键 `fk_scenario_project(project_id → project.id)`；up / down 成对（`db/down/V11_11__test_scenario_order.down.sql` 先删外键再删索引与列），已实测应用成功。同类顺延：D4 → `V11_10`、D5 → `V11_12`、D8 → `V11_13`。

## 5. 结论

- 外键 `fk_scenario_project` 由 V11_11 建立并被单测静态锁死，满足「外键存在」。
- 非空父场景归一到 INVALID_ARGUMENT，消息带稳定错误码 `INVALID_ARGUMENT` 与中文说明；空值放行；创建链路确已调用校验入口，满足「非空 parent_scenario_id 返回明确错误」。
- 旧单测 `ScenarioServiceTest` 的两条源码断言仍成立，回归 3/0 无倒退。
- 未建 TRIGGER、未改迁移字节、未动 `scripts/gates.sh` / `scripts/db-audit.sh` / `frontend/` / `RunExecutionService.java`。

## 6. 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 场景校验单测 | ScenarioServiceTest：Tests run 3、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 模式静态单测 | ScenarioSchemaTest：Tests run 5、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 冻结旁证单测 | ExecutionSelectionFreezeTest：Tests run 5、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 定向 mvn 汇总 | Tests run 49、Failures 0、Errors 0，BUILD SUCCESS（14 个类同批） | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 迁移落号 | V11_11__test_scenario_order.sql（11 行 DDL，含 step_order 与 fk_scenario_project） | db/migration/V11_11__test_scenario_order.sql |
| 回滚成对 | db/down/V11_11__test_scenario_order.down.sql 存在，先删外键再删索引与列 | db/down/V11_11__test_scenario_order.down.sql |
| 门禁全量 | 19 步全 PASS，GATES_RESULT FAILS=0（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=0 errors=0 | docs/evidence/gates/logs/05-backend_test.log、02-frontend_test.log、11-editor_test.log |
| 基线 floor | 1016、325、219（只增不减，实测均不低于 floor） | docs/evidence/gates/logs/19-evidence_integrity.log |
| Markdown 机检 | 全量机检 PASS（本 README 的 3 列表格列数一致，单元格内无裸竖线） | docs/evidence/v1.0/policy-20261106/check_md_tables.py 实跑输出 |
