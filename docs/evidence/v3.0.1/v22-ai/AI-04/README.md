# AI-04 · `ai.enabled` 开关接线、chat 端点与全动作审计

> 层 N 独立证据包（`docs/evidence/v3.0.1/v22-ai/AI-04/README.md`）
> 关联：FR-AI-07 / FR-AI-08 ｜ TC-AI-07 / TC-AI-08
> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。假 CLI 链路已通；真实 CLI 未授权、未执行。

## 三栏证据表

| 命令原文 | 判据 | 真实输出（2026-11-08） |
|---|---|---|
| `mvn -o -B test -Dtest=AiEnabledFlagTest` | `ai.enabled=false` 时 `open` / `suggest` / `chat` 均抛 `BusinessException` 且文案含「AI 助手已禁用」；`/api/ai/state` 仍返回 `enabled=false` 供前端隐藏入口；`FEATURE_AI_REAL_CLI` 未显式开启时 `executed=false`、不起子进程 | AiEnabledFlagTest Tests run 5、Failures 0、Errors 0（0.007 s） |
| `mvn -o -B test -Dtest=AiPanelTest` | 既有 5 个端点签名与 4 参 mock 构造方式保持兼容；每个动作仍写 1 条审计；二次确认闸门不回退 | AiPanelTest Tests run 6、Failures 0、Errors 0（0.006 s） |
| `grep -n requireEnabled backend/src/main/java/com/qa/autotest/api/ai/AiPanelController.java` | 新增 `POST /api/ai/chat`（入参 projectId / sessionId / prompt，返回模型回答 + sessionId）；所有端点先过 `ai.enabled` 闸门；terminate 传真实句柄而非 null | 命中 5 行：38 / 45 / 52 / 62 / 70 行各一处 `panel.requireEnabled();`；`@PostMapping("/chat")` 定义在 67 行，其闸门调用即 70 行 |

## 说明

- 双闸门语义：`ai.enabled`（`SystemSettingService.platform()`，默认 false）控制「能不能用」；`FEATURE_AI_REAL_CLI`（环境/系统属性，默认关闭）控制「是否真起子进程」。
- `/api/ai/state` 是发现端点，禁用时也返回 `enabled=false` 而不抛错，否则前端无法据此隐藏入口；其余端点由 `requireEnabled()` 拦截。
- `terminate` 从 `AiPanelService.handleFor(sessionId)` 取真实 `ProcessHandle`，交给 `AiProcessManager.terminate()` 做进程树终止。

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 开关单测 | AiEnabledFlagTest Tests run 5、Failures 0、Errors 0 | docs/evidence/gates/logs/05-backend_test.log |
| 端点与审计单测 | AiPanelTest Tests run 6、Failures 0、Errors 0 | docs/evidence/gates/logs/05-backend_test.log |
| 闸门覆盖 | `requireEnabled()` 5 处（38 / 45 / 52 / 62 / 70 行），`/chat` 端点 67 行、闸门在 70 行 | 仓库内 `AiPanelController.java` 实测 grep |
| 前端开关联动 | `frontend/src/App.vue` 6 处 `aiEnabled`（75 / 209 / 214 / 216 / 317 / 390 行），含 `v-if="aiEnabled"` 与抽屉挂载 | 仓库内 `App.vue` 实测 grep，详见 AI-05 |
| 后端全量 | `Tests run: 1050, Failures: 0, Errors: 0`（门禁第 05 步） | docs/evidence/gates/logs/05-backend_test.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |

未覆盖项（如实登记）：两条单测读数取自门禁第 05 步的全量 `mvn -o -B test`，未单独执行 `-Dtest` 定向形式；`/api/ai/chat` 的 HTTP 级联调（真实起服务打请求）本轮未单独做，端点契约由 AiPanelTest 与源码 grep 共同覆盖；真实 CLI 未授权、未执行。
