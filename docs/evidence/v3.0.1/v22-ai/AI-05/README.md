# AI-05 · 前端文本输入、多轮上下文与结果呈现

> 层 N 独立证据包（`docs/evidence/v3.0.1/v22-ai/AI-05/README.md`）
> 关联：FR-AI-05 ｜ TC-AI-05
> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。假 CLI 链路已通；真实 CLI 未授权、未执行。

## 三栏证据表

| 命令原文 | 判据 | 真实输出（2026-11-08） |
|---|---|---|
| `npx vitest run src/components/AiPanelDrawer.spec.ts` | 抽屉有可输入提示词的输入框与发送按钮；提交后显示模型回答；历史消息可滚动且保留多轮；加载 / 错误 / DEGRADED / 禁用态均可见 | AiPanelDrawer.spec.ts 6 tests 全过（183ms），同批 `Test Files 6 passed`、`Tests 47 passed`、`VITEST_RC=0`；6 条用例分别命中：输入框与发送按钮、提交后显示回答且多轮保留、加载态、错误态、DEGRADED 态、`enabled=false` 不开会话 |
| `npx vue-tsc --noEmit` | `aiPanel.ts` 新增 `chat()` 与 `AiChatResponse` 类型；`AiPanelDrawer.vue` / `App.vue` 类型检查通过，无未使用变量 | 退出码 0，stdout 与 stderr 均为空（门禁第 03 步） |
| `grep -n aiEnabled frontend/src/App.vue` | 顶栏按钮与抽屉按 `/api/ai/state` 返回的 `enabled` 隐藏/显示；`enabled=false` 时不再开会话 | 命中 6 行：75（`const aiEnabled = ref(false)`）、209 与 216（分支置 false）、214（`s.enabled === true` 取值）、317（顶栏 `v-if="aiEnabled"`）、390（`AiPanelDrawer v-if="aiEnabled"`） |

## 说明

- 新增文案直接写中文常量（`LABEL`），未改 `frontend/src/locales/*`、`config/navigation.ts`、`router/index.ts`（避免与其他代理冲突）。
- 多轮上下文：前端把可见消息历史拼成 `prompt` 传给 `/api/ai/chat`，后端统一经 `ContextPacker` 脱敏后再交给子进程。
- 抽屉里的「模型回答」来自假 CLI 回显链路，**真实 CLI 未授权、未执行**；本包证明的是交互与状态机，不是真实模型输出质量。

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 抽屉定向 spec | AiPanelDrawer.spec.ts 6 tests 全过（183ms）；同批 Tests 47 passed、`VITEST_RC=0` | docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log |
| 类型检查 | `npx vue-tsc --noEmit` 退出码 0，输出为空（门禁第 03 步） | docs/evidence/gates/logs/03-type_check.log |
| 开关联动 | `App.vue` 6 处 `aiEnabled`，含顶栏 317 行与抽屉 390 行的 `v-if` | 仓库内 `App.vue` 实测 grep |
| 全量前端单测 | Test Files 49 passed、Tests 330 passed（门禁第 02 步） | docs/evidence/gates/logs/02-frontend_test.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=errors=0；基线 floor 1016 / 325 / 219（只增不减） | docs/evidence/gates/logs/19-evidence_integrity.log |

未覆盖项（如实登记）：6 条用例是组件级单测，「历史可滚动」以 DOM 结构断言覆盖，未在真实浏览器里做滚动行为回归；真实 CLI 未授权、未执行，多轮上下文对真实模型的传递效果未验证。
