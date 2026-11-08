# EVID-03 · 三轮独立证据编排与判定

| 项 | 值 |
|---|---|
| 版本 / 波次 | v3.0.1 · v2.2 · M3 |
| 日期 | 2026-11-08 |
| 判据出处 | `docs/plan/v2.2-DEV-PLAN-增补草案-待评审-20261107.md:87`、`§4.5` |
| 迁移 | `db/migration/V11_13__run_frozen_round.sql`（`round_no` 列，历史 run 为 NULL，本包未改字节） |
| 新增生产类 | `RoundVerdictService.java` + 不可变 `RoundResult.java` |
| 新增测试 | `ThreeRoundVerdictTest.java`（8 条，纯逻辑、不连库、无 `@Tag("it")`） |

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
[INFO] Running com.qa.autotest.application.execution.ThreeRoundVerdictTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.047 s -- in com.qa.autotest.application.execution.ThreeRoundVerdictTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

回归（03:21:38 +08:00）：

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

全量：

```
[INFO] Running com.qa.autotest.application.execution.ThreeRoundVerdictTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.012 s -- in com.qa.autotest.application.execution.ThreeRoundVerdictTest
[INFO] Tests run: 1050, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 3. 判据逐条命中

| 判据 | 命中它的测试方法 | 断言要点 | 结果 |
|---|---|---|---|
| 3 个独立 RUN_ID + round 1/2/3 | `编排产出3个独立RUN_ID与round_no_1_2_3` | `orchestrate` 产出 3 条互不复用 runId、roundNo 恰好 1/2/3、evaluate=PASS | PASS |
| 0 轮 INCOMPLETE | `零轮判定不完整` | 空集合判 INCOMPLETE，且 `label()` 含「不完整」 | PASS |
| 只跑 2 轮 INCOMPLETE | `只跑两轮判定不完整` | round 1 / 2 两轮仍不完整，不提前置 PASS | PASS |
| 3 轮全 PASS 才 PASS | `三轮全PASS才PASS` | 1/2/3 全 PASS 得 PASS | PASS |
| 3 轮里有 FAIL | `三轮里有FAIL不判PASS` | 中间轮 FAIL 得 FAIL，且不等于 PASS | PASS |
| 重复 RUN_ID 被拒 | `重复RUN_ID被拒` | `orchestrate` 与 `evaluate` 均抛 `IllegalArgumentException` | PASS |
| 缺轮不拼 / 不跨轮拼 | `缺轮不得用其它轮补齐_不跨轮拼` | 缺 round2 的 1/3/3 与多出 round4 的 1/2/4 均判 INCOMPLETE | PASS |
| 历史 round_no 为 NULL 不回填 | `历史round_no为NULL不回填_迁移与源码静态断言` | 迁移注释含「历史 run 为 NULL / 不回填」、服务源码无 `update run / backfill` | PASS |

## 4. 结论

EVID-03 的 3 个独立 RUN_ID、0/2/3 轮判定、3 轮全 PASS 才 PASS、不跨轮拼、重复 RUN_ID 被拒、历史 NULL 不回填各判据全部被上述 8 条单测命中；定向 19 条、回归 12 条、全量 1050 条均为 `BUILD SUCCESS`、`Failures: 0`。`round_no` 只用于新轮次，未写任何历史回填 SQL。

## 5. 边界（如实说明）

- `RoundVerdictService` 是纯判定 / 编排类，未接真实 `run` 表查询；「3 个独立 RUN_ID」由 `orchestrate` 校验互不复用 + 测试构造三条不同 RUN_ID 验证，未连数据库读真实 run 记录。
- `run.round_no` 的「历史为 NULL、不回填」由迁移注释静态断言 + 服务源码无 `UPDATE / backfill` 锁定；本包未对任何历史数据做回填动作（判据亦禁止回填）。

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
