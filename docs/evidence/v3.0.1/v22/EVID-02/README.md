# EVID-02 · 证据 LOG 五要素 / 真实退出码 / 缺要素 missing / 脱敏

| 项 | 值 |
|---|---|
| 版本 / 波次 | v3.0.1 · v2.2 · M3 |
| 日期 | 2026-11-08 |
| 判据出处 | `docs/plan/v2.2-DEV-PLAN-增补草案-待评审-20261107.md:86` |
| 新增生产类 | `EvidenceLogWriter.java`（`capture` 真实进程 / `write` 五要素 / `scrub` 脱敏） |
| 新增测试 | `EvidenceLogFormatTest.java`（5 条，纯逻辑、不连库、无 `@Tag("it")`） |

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
[INFO] Running com.qa.autotest.application.execution.EvidenceLogFormatTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.122 s -- in com.qa.autotest.application.execution.EvidenceLogFormatTest
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
[INFO] Running com.qa.autotest.application.execution.EvidenceLogFormatTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in com.qa.autotest.application.execution.EvidenceLogFormatTest
[INFO] Tests run: 1050, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 3. 判据逐条命中

| 判据 | 命中它的测试方法 | 断言要点 | 结果 |
|---|---|---|---|
| 五要素齐全 | `一轮证据五要素齐全` | `[hostname]` + `#步骤N说明` + 命令原文 + stdout/stderr + `exit_code` 全部出现在输出 | PASS |
| 退出码来自真实进程 | `真实退出码来自ProcessBuilder_随命令变化而非写死常量` | `ProcessBuilder` 跑 `sh -c exit 0` / `exit 7` 分别得 0 / 7 且两者不等；`write` 输出 `exit_code=7` | PASS |
| 缺要素显式标 missing | `缺任一要素时输出显式标missing_不静默补齐` | hostname / 说明 / 命令 / stdout / stderr / 退出码缺失处均为 `missing`，且不含 `exit_code=0` | PASS |
| LOG 不写入凭据 | `LOG不写入凭据_脱敏生效` | 测试夹具示例口令与 token 值不出现在 LOG，替换为 `password=<redacted>` / `token=<redacted>` | PASS |
| 生产实现真起进程 | `生产源码用ProcessBuilder且取exitValue_不写死退出码` | 源码含 `new ProcessBuilder`、`exitValue()`、`waitFor()`、`MISSING`、`scrub` | PASS |

## 4. 结论

EVID-02 五要素、真实退出码、缺要素 `missing`、凭据脱敏四条判据全部被上述 5 条单测命中；定向 19 条、回归 12 条、全量 1050 条均为 `BUILD SUCCESS`、`Failures: 0`。退出码经 `ProcessBuilder` 起真实 `sh` 子进程后由 `exitValue()` 取得，非写死常量。

## 5. 边界（如实说明）

- 退出码来自本机 WSL 的无害 `sh` 子进程（`exit 0` / `exit 7` / `printf ...; exit 3`），未接入真实 pabot / SSH 设备命令；判据要求「真实进程」而非「真实被测命令」，此处以真实子进程满足。
- 脱敏针对 `password / token / secret / api_key / credential` 等键值形态，测试用显式标假的夹具字符串；真实环境凭据明文未写入任何证据文件。
- 文档中 `sh -c exit 0` 为简写，测试内实际传入的是 `List.of("sh", "-c", "exit 0")` 形式。

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
