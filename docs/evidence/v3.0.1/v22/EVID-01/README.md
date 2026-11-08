# EVID-01 · 冻结计划 SHA / 幂等提交 / 未知状态只读找回

| 项 | 值 |
|---|---|
| 版本 / 波次 | v3.0.1 · v2.2 · M3 |
| 日期 | 2026-11-08 |
| 判据出处 | `docs/plan/v2.2-DEV-PLAN-增补草案-待评审-20261107.md:85` |
| 迁移 | `db/migration/V11_13__run_frozen_round.sql`（已落地，本包未改一个字节） |
| 新增生产类 | `FrozenPlanService.java`；`RunExecutionService.java` 追加 `computePlanSha256` 与 `recoverUnknownRun` |
| 新增测试 | `FrozenPlanIdempotencyIT.java`（6 条，纯逻辑、不连库、无 `@Tag("it")`） |

## 1. 真实执行的命令

环境按项目约定加载 `set -a; . /home/evergreen/.config/robot-autotest/env; set +a`（只引用路径，证据文件不落任何凭据明文）。外层实际调用：

```bash
wsl.exe -d Ubuntu-24.04 bash -lc 'set -a; . /home/evergreen/.config/robot-autotest/env; set +a; cd /home/evergreen/robot-autotest/backend && mvn -o -B test -Dtest=FrozenPlanIdempotencyIT,EvidenceLogFormatTest,ThreeRoundVerdictTest'
```

等价的 WSL 内命令（cwd 为 `/home/evergreen/robot-autotest/backend`）：

```bash
mvn -o -B test -Dtest=FrozenPlanIdempotencyIT,EvidenceLogFormatTest,ThreeRoundVerdictTest
```

回归与全量：

```bash
mvn -o -B test -Dtest=RunExecutionServiceTest,IdempotencyTest
mvn -o -B test
```

## 2. 真实输出摘要

定向三包（2026-11-08 03:21:20 +08:00）：

```
[INFO] Running com.qa.autotest.application.execution.FrozenPlanIdempotencyIT
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.266 s -- in com.qa.autotest.application.execution.FrozenPlanIdempotencyIT
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

回归（03:21:38 +08:00）：

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

全量（证明 `FrozenPlanIdempotencyIT` 在默认 profile、`excluded.groups=it` 下仍被执行）：

```
[INFO] Running com.qa.autotest.application.execution.FrozenPlanIdempotencyIT
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.007 s -- in com.qa.autotest.application.execution.FrozenPlanIdempotencyIT
[INFO] Tests run: 1050, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 3. 判据逐条命中

| 判据 | 命中它的测试方法 | 断言要点 | 结果 |
|---|---|---|---|
| 同 key 两次提交只 1 个 run | `同key两次提交只产生1个run_第二次不新增不重发命令` | 内存 fake `insertCount=1`、`byKey` 1 条、两次 `runId` 相同 | PASS |
| SHA 不符被明确拒绝且不落 run | `冻结计划SHA不符被明确拒绝且不落run` | 抛 `BusinessException`、消息含「SHA / 不符 / 拒绝 / 不落 run」、`insertCount=0` | PASS |
| 服务端按同一规则复算 | `计划材料被篡改时按同一规则复算的SHA不符被拒` | 篡改 `suitePaths` 后复算值变化即拒收；`RunExecutionService.computePlanSha256` 委托值一致 | PASS |
| 未知状态只读找回不新增 run | `未知状态只读找回_不新增run_不重发命令` | `recover` 前后 `insertCount` 与 `sent` 不变、未知 RUN_ID 返回 empty | PASS |
| 找回分支不得有 INSERT / 执行命令 | `找回分支源码不含INSERT与执行命令` | 静态断言 `recover` 与 `recoverUnknownRun` 方法子串无 `insert / execute / dispatch / launch / upsert / processbuilder / transition / .create( / commandSender` | PASS |
| 幂等靠唯一键 | `迁移含幂等唯一键与冻结列` | 静态断言迁移含 `uk_run_idempotency`、`project_id + idempotency_key`、`freeze_sha256`、`VARCHAR(64)` | PASS |

## 4. 结论

EVID-01 三条判据（同 key 两次提交只 1 个 run、SHA 不符被拒、找回不新增 run）全部被上述 6 条单测命中；定向 19 条、回归 12 条、全量 1050 条均为 `BUILD SUCCESS`、`Failures: 0`。既有 `RunExecutionServiceTest` / `IdempotencyTest` 未倒退。

## 5. 边界（如实说明）

- 数据库唯一键 `uk_run_idempotency` 与四个新列在 `V11_13` 已落地，本包未改迁移字节；幂等语义用「先查后写 + 内存 fake 冲突抛错」验证，未连真实数据库执行 `INSERT`（判据明确允许纯单测用内存 fake repository）。
- 找回的「不重发命令」由 `commandSender` 调用计数（行为）+ 找回分支源码静态断言（结构）双重锁定，未在真实 HTTP / SSH 链路上复现。

## 6. Markdown 表格机检

写完 Markdown 后在仓库根真实执行：

```bash
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/evidence/v3.0.1/v22/EVID-01/README.md docs/evidence/v3.0.1/v22/EVID-02/README.md docs/evidence/v3.0.1/v22/EVID-03/README.md
```

真实输出：

```
扫描 7 份文档 / 157 个表格块 / 1252 个数据行
[PASS] 表格列数一致性：全部表格块内列数一致（转义竖线已按非分隔符处理）
扫描 3 份文档 / 6 个表格块 / 42 个数据行
[PASS] 表格列数一致性：全部表格块内列数一致（转义竖线已按非分隔符处理）
```

结论：全量默认文件集与三份新 README 的表格列数一致性均 PASS。
