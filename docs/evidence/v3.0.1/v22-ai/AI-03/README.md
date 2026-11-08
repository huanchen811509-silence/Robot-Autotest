# AI-03 · L2 真实建议（模型回显，非固定模板）与上下文脱敏

> 层 N 独立证据包（`docs/evidence/v3.0.1/v22-ai/AI-03/README.md`）
> 关联：FR-AI-06 ｜ TC-AI-06 / TC-AI-09
> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。假 CLI 链路已通；真实 CLI 未授权、未执行。

## 三栏证据表

| 命令原文 | 判据 | 真实输出（2026-11-08） |
|---|---|---|
| `mvn -o -B test -Dtest=AiSuggestContentTest` | `suggest` 返回值含假 CLI 固定回显串；`level=L2`；与 `ContextPacker` 打包长度无关；模板字符串不是唯一输出 | AiSuggestContentTest Tests run 3、Failures 0、Errors 0（门禁第 05 步全量套件内的同类读数，0.016 s） |
| `mvn -o -B test -Dtest=AiContextScrubTest` | 上下文经 `ReportViewRenderer.scrub()` 脱敏；响应 `stdout` / `summary` 不含 IP、口令、token、`apiKey=` 等凭据原文 | AiContextScrubTest Tests run 2、Failures 0、Errors 0（0.010 s） |
| `python3 tools/check_ai_contract.py` | AI 主源码零硬编码 API key / Bearer 令牌；无凭据原文写入产品代码 | `[OK] AI 契约机检通过（扫描 9 个 AI 主源码文件）`，退出码 0 |

## 说明

- `AiPanelService.suggest()` 接真子进程，把 `stdout` 放进 `AiActionResult.stdout` 与 `summary`；无 CLI 时显式 `DEGRADED`（L1 脱敏摘要），不抛异常阻断主功能。
- 上下文脱敏继续复用 `ContextPacker`（内部调用 `ReportViewRenderer.scrub()`），未另造一套判据。
- 本包的「L2 建议」走的是假 CLI 夹具（`fixtures/ai/fake-cli.sh`）的固定回显，证明的是「回显内容来自子进程 stdout、不是模板拼接」；**真实 CLI 未授权、未执行**，TC-AI-11 仍为 BLOCKED。

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 建议内容单测 | AiSuggestContentTest Tests run 3、Failures 0、Errors 0 | docs/evidence/gates/logs/05-backend_test.log |
| 上下文脱敏单测 | AiContextScrubTest Tests run 2、Failures 0、Errors 0 | docs/evidence/gates/logs/05-backend_test.log |
| AI 契约机检 | `[OK] AI 契约机检通过（扫描 9 个 AI 主源码文件）` | docs/evidence/v3.0.1/v22/raw-20261108/ai-contract.log |
| 假 CLI 默认链路 | `FAKE_CLI_OK:hello`，退出码 0（回显来源可断言） | docs/evidence/v3.0.1/v22/raw-20261108/fake-cli-default.log |
| 后端全量 | `Tests run: 1050, Failures: 0, Errors: 0`（门禁第 05 步） | docs/evidence/gates/logs/05-backend_test.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |

未覆盖项（如实登记）：两条单测的读数取自门禁第 05 步的全量 `mvn -o -B test`，未单独执行对应的 `-Dtest` 定向命令；真实模型输出质量未验证（真实 CLI 未授权、未执行），本包只证明链路与脱敏，不宣称真实模型结论。
