# AI-01 · 真实 CLI 子进程调用与 argv 契约

> 层 N 独立证据包（`docs/evidence/v3.0.1/v22-ai/AI-01/README.md`）
> 关联：FR-AI-01 / FR-AI-02 / FR-AI-08 ｜ TC-AI-01 / TC-AI-02 / TC-AI-08
> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。假 CLI 链路已通；真实 CLI 未授权、未执行。

## 三栏证据表

| 命令原文 | 判据 | 真实输出（2026-11-08） |
|---|---|---|
| `mvn -o -B test -Dtest=AiRealCliChainTest,AiCliArgvContractTest` | 假 CLI 真起 `ProcessBuilder` 子进程；`suggest` / `execute` 各产生 1 次真实子进程；`stdout` 进入 `AiActionResult.stdout` 与 `/api/ai/chat` 返回体；4 个候选 CLI 的 argv 均零 prompt 文件开关 | 门禁第 05 步全量 `mvn -o -B test` 内：AiRealCliChainTest Tests run 4、AiCliArgvContractTest Tests run 5，两者 Failures 0 / Errors 0（共 9 条断言全绿）。本轮未再单独跑 `-Dtest` 定向形式 |
| `mvn -o -B -Pit test -Dtest=AiProcessTerminationIT` | 假 CLI `FAKE_CLI_SLEEP=5` + `AI_CLI_TIMEOUT_SECONDS=1` 超时后被杀；`timedOut=true`；无存活后代进程；terminate 传真实 `ProcessHandle` | AiProcessTerminationIT Tests run 2、Failures 0、Errors 0（1.218 s）；同批 `Tests run: 4, Failures: 0`，`BUILD SUCCESS`，库 robot_it |
| `python3 tools/check_ai_contract.py` | AI 主源码零 prompt 文件开关、零硬编码凭据、零 `git commit` 等 L4-L5 调用；`ProcessBuilder` 真实链路存在且受 `FEATURE_AI_REAL_CLI` 闸门；假 CLI 夹具与 V11_9 up/down 齐全 | `[OK] AI 契约机检通过（扫描 9 个 AI 主源码文件）`，退出码 0 |

## 说明

- 测试一律注入 `fixtures/ai/fake-cli.sh`；`FEATURE_AI_REAL_CLI` / `ai.enabled` 默认关闭，只有显式开启才起子进程。假 CLI 链路已通，真实 CLI 未授权、未执行。
- `AiPanelTest.java` 的 4 参 mock 构造方式保持不变，其 `buildCommand()` 断言继续兼容（AiPanelTest 6 条全绿，见 AI-04）。
- TC-AI-11（真实本机 CLI 调用）仍为 **BLOCKED**，待大师书面 `G-授权`，本轮未执行，不在本证据包结论内。

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 本包后端单测 | AiRealCliChainTest 4 + AiCliArgvContractTest 5 = 9 条，Failures 0 / Errors 0 | docs/evidence/gates/logs/05-backend_test.log |
| 进程终止 IT | AiProcessTerminationIT 2 条全过（-Pit，库 robot_it），同批 `Tests run: 4, Failures: 0` + `BUILD SUCCESS` | docs/evidence/v3.0.1/v22/raw-20261108/ai-it-robot_it.log |
| AI 契约机检 | `[OK] AI 契约机检通过（扫描 9 个 AI 主源码文件）` | docs/evidence/v3.0.1/v22/raw-20261108/ai-contract.log |
| 假 CLI 默认链路 | `FAKE_CLI_OK:hello`，退出码 0 | docs/evidence/v3.0.1/v22/raw-20261108/fake-cli-default.log |
| 假 CLI 注入链路 | stdout `INJECTED_OK`、stderr `INJECTED_ERR`、退出码 7（实测与预期一致） | docs/evidence/v3.0.1/v22/raw-20261108/fake-cli-injected.log |
| 后端全量 | `Tests run: 1050, Failures: 0, Errors: 0`（门禁第 05 步） | docs/evidence/gates/logs/05-backend_test.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |

未覆盖项（如实登记）：TC-AI-11 真实本机 CLI 调用需书面 `G-授权`，本轮未执行，仍为 BLOCKED；上表两行后端类读数取自门禁第 05 步的全量 `mvn -o -B test`（类名与断言数一致），未再单独执行 `-Dtest=AiRealCliChainTest,AiCliArgvContractTest` 的定向形式。
