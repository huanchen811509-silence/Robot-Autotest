# SCEN-02 证据 · 用例集三态与执行选择冻结（v2.2 / 2026-11-08）

判据：同设计两次生成 SHA 不同；来源设计 `updated_at` 与内容不变。
证据目录 `docs/evidence/v3.0.1/v22/SCEN-02/`（P-1 定案：不新建 release 目录、不 bump 版本）。

## 1. 落地内容（真实编码，非占位）

- `backend/src/main/java/com/qa/autotest/application/caseasset/TestSetService.java`
  - 新增 `ALLOWED_KINDS = Set.of("case_set", "scenario", "execution_selection")` 与 `validateKind(String)`：非法或 null 抛 `BusinessException(ErrorCode.INVALID_ARGUMENT, "INVALID_ARGUMENT(40001)：非法用例集三态 kind=…")`。
  - 原 4 参 `create` 委托到新增 6 参 `create(..., String kind, Long designId)`，默认 `case_set` / null；6 参先走白名单再写库。
- `backend/src/main/java/com/qa/autotest/application/repository/TestSetRepository.java` 与 `infrastructure/TestSetRepositoryJdbc.java`
  - 新增 `insertWithKind(...)`，INSERT 显式写入 `kind` / `design_id`，并保留 `set_code` / `name` / `owner_id` / `set_status` / `created_by` / `updated_by` 等既有 NOT NULL 列。
- 新增 `domain/caseasset/FrozenSelection.java`（不可变 record，字段 `frozenAt`）、`application/repository/ExecutionSelectionRepository.java`（只有 `insert` + `find`）、`infrastructure/ExecutionSelectionRepositoryJdbc.java`（`@Repository`，只发 `INSERT INTO execution_selection` 与只读 SELECT）、`application/caseasset/ExecutionSelectionService.java`（`@Service`，`freeze` / `find` / `computeSha256`）。
  - `freeze` 的 SHA-256 输入含 `Instant.now()` 与 `UUID.randomUUID()` nonce，保证同一设计两次生成 SHA 不同；冻结结果以不可变 record 返回。
  - 冻结源码中不出现对 `execution_selection` 或来源 `test_set` 的任何改写语句；不可变靠「只发 INSERT + 唯一键 `uk_execution_selection_sha` + 单测锁死」，不建 TRIGGER（V11_2 已实测 binlog + 无 SUPER 会被 Error 1419 拒）。
- `backend/src/test/java/com/qa/autotest/application/caseasset/ExecutionSelectionFreezeTest.java`（新增 5 条纯单测，内存仓储替身，不连库）：两次 freeze SHA 不同、来源设计内容与 updatedAt 不变（不可变 record 前后比对）、源码无改写、迁移含唯一键、三态白名单拒绝非法值、创建 SQL 显式写 kind/design_id。

## 2. 真实执行的命令

```
cd /home/evergreen/robot-autotest/backend
mvn -o -B test -Dtest=ScenarioSchemaTest,ExecutionSelectionFreezeTest,Ed202OrganizationTest
mvn -o -B test -Dtest=ScenarioServiceTest
```

本轮定向单次 mvn（14 个类）同批复跑，含 `ExecutionSelectionFreezeTest`。

## 3. 真实输出摘要（摘自上述命令 stdout）

```
[INFO] Running com.qa.autotest.application.caseasset.ExecutionSelectionFreezeTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.059 s -- in com.qa.autotest.application.caseasset.ExecutionSelectionFreezeTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

本轮定向单批复核行：

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.060 s -- in com.qa.autotest.application.caseasset.ExecutionSelectionFreezeTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 4. 迁移编号事实

DEV-PLAN 的 D4 / D5 实际落号为 `V11_10__test_set_kind.sql`（D4）与 `V11_12__execution_selection.sql`（D5），不是 `V11_4` / `V11_5`。原因：`V11_9__ai_runtime.sql` 已应用且 Flyway 未开 out-of-order，只能用更高号顺延。

- `V11_10`：`test_set.kind` 三态 `case_set` / `scenario` / `execution_selection`（VARCHAR(24)，默认 `case_set`）＋ `design_id`，配 `idx_test_set_kind` / `idx_test_set_design`；取值由应用层收口，不加 CHECK。
- `V11_12`：新表 `execution_selection`，带唯一键 `uk_execution_selection_sha(project_id, sha256)` 与 `sha256 CHAR(64)`。
- 顺延同批：D6 → `V11_11`、D8 → `V11_13`；四份 up 全部在 `db/down/` 有同名 down，成对且已实测应用成功。

## 5. 结论

- 两次 `freeze(7L, 42L, 同 payload, 同 snapshot)` 得到不同 64 位 sha256，因 Instant + UUID nonce 参与摘要，满足「同设计两次生成 SHA 不同」。
- 冻结只向 `execution_selection` 发 INSERT，不触碰来源 `test_set`；来源设计以不可变 record 表达，冻结前后内容与 updatedAt 比对一致，满足「来源设计 updated_at 与内容不变」。
- 三态白名单对 `bogus` / `CASE_SET` / 空串 / null 均抛 INVALID_ARGUMENT，对三个合法值放行。
- 新增 Java 测试均为纯逻辑 + 读源码/读迁移静态断言，默认 surefire 不启动 Spring 上下文、不依赖数据库。

## 6. 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 冻结单测 | ExecutionSelectionFreezeTest：Tests run 5、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 三态/模式旁证 | ScenarioSchemaTest 5、ScenarioServiceTest 3，均 Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 定向 mvn 汇总 | Tests run 49、Failures 0、Errors 0，BUILD SUCCESS（14 个类同批） | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| D4 落号 | V11_10__test_set_kind.sql（kind 三态＋design_id＋两个索引） | db/migration/V11_10__test_set_kind.sql |
| D5 落号 | V11_12__execution_selection.sql（uk_execution_selection_sha 为 project_id＋sha256） | db/migration/V11_12__execution_selection.sql |
| 回滚成对 | db/down 下 V11_10 / V11_12 同名 down 均存在 | db/down/V11_10__test_set_kind.down.sql、db/down/V11_12__execution_selection.down.sql |
| 门禁全量 | 19 步全 PASS，GATES_RESULT FAILS=0（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=0 errors=0 | docs/evidence/gates/logs/05-backend_test.log、02-frontend_test.log、11-editor_test.log |
| 基线 floor | 1016、325、219（只增不减，实测均不低于 floor） | docs/evidence/gates/logs/19-evidence_integrity.log |
| Markdown 机检 | 全量机检 PASS（3 列表格列数一致，单元格内无裸竖线） | docs/evidence/v1.0/policy-20261106/check_md_tables.py 实跑输出 |
