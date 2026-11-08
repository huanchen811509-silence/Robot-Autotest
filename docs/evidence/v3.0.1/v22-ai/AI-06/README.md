# AI-06 · 假 CLI 夹具、AI 契约静态机检与回归基线

> 层 N 独立证据包（`docs/evidence/v3.0.1/v22-ai/AI-06/README.md`）
> 关联：CN1 / CN4 / CN5 / VN3 ｜ TC-AI-10
> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。假 CLI 链路已通；真实 CLI 未授权、未执行。

## 三栏证据表

| 命令原文 | 判据 | 真实输出（2026-11-08） |
|---|---|---|
| `python3 tools/check_ai_contract.py` | 独立可运行；断言 AI 主源码零 prompt 文件开关、零硬编码 API key / Bearer、零 `git commit` 等 L4-L5 调用；`ProcessBuilder` 链路存在且受闸门；假 CLI 夹具输出 `FAKE_CLI_OK`；V11_9 up/down 成对 | `[OK] AI 契约机检通过（扫描 9 个 AI 主源码文件）`，退出码 0 |
| `bash fixtures/ai/fake-cli.sh -p hello` | 可执行；环境变量 `FAKE_CLI_STDOUT` / `FAKE_CLI_STDERR` / `FAKE_CLI_EXIT` / `FAKE_CLI_SLEEP` 分别控制 stdout / stderr / 退出码 / 睡眠秒数；默认输出固定可断言字符串 | 默认：stdout `FAKE_CLI_OK:hello`、退出码 0；注入 `FAKE_CLI_STDOUT=INJECTED_OK FAKE_CLI_STDERR=INJECTED_ERR FAKE_CLI_EXIT=7` 时：stdout `INJECTED_OK`、stderr `INJECTED_ERR`、**退出码 7（实测与预期一致）** |
| `bash scripts/gates.sh` | 三处单测基线不倒退、FAILS=0 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19）；三处单测 backend 1050 / frontend 330 / editor 219，failures=errors=0，floor 1016 / 325 / 219 只增不减 |

## 说明

- `tools/check_ai_contract.py` 为本包新增，**当前未接入 `scripts/gates.sh`**（`grep -rn check_ai_contract scripts/` 无命中），只能独立运行；接入属门禁改动，本包按要求不碰 `scripts/`。
- 假 CLI 全程替代真实模型；TC-AI-11（真实 CLI 调用）仍 BLOCKED 待 `G-授权`，本轮未执行。
- 本包不改 `docs/plan/` 四份文档、不改 `WBS-STATUS.md`、不 bump 版本、不 git commit / push。

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| AI 契约机检 | `[OK] AI 契约机检通过（扫描 9 个 AI 主源码文件）` | docs/evidence/v3.0.1/v22/raw-20261108/ai-contract.log |
| 假 CLI 默认 | `FAKE_CLI_OK:hello`，退出码 0 | docs/evidence/v3.0.1/v22/raw-20261108/fake-cli-default.log |
| 假 CLI 注入 | stdout `INJECTED_OK`、stderr `INJECTED_ERR`、退出码 7（与预期一致） | docs/evidence/v3.0.1/v22/raw-20261108/fake-cli-injected.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=errors=0 | docs/evidence/gates/logs/05-backend_test.log、02-frontend_test.log、11-editor_test.log |
| 基线 floor | 1016 / 325 / 219，本轮实测均不低于 floor（只增不减） | docs/evidence/gates/logs/19-evidence_integrity.log |

未覆盖项（如实登记）：`check_ai_contract.py` 尚未接入 `scripts/gates.sh`（脚本内无该调用），本轮为独立执行，19 步门禁 PASS 不含这一步；真实 CLI 未授权、未执行，`FAKE_CLI_SLEEP` 的超时杀进程路径由 AI-01 的 `AiProcessTerminationIT` 覆盖，不在本包重复计数。
