# v2.6 工作助手体验 · ⑤ TEST-PLAN

**文档编号** RA-REL-2026-026-T ｜ **版本** v2.6-assistant-ux ｜ **批次** B14（待落号） ｜ **门禁** G13（未判）
**日期** 2026-11-08 ｜ **状态** 19 条 TC 已执行（17 PASS / 2 NOT-RUN），失败 0

> 原则：能机检的不靠人眼；能真链路的不用单测代替。
> 拿不到证据判据**记 NOT-RUN**，不写「应该没问题」。

---

## 1. 用例与覆盖

| TC | 覆盖 FR | 判据 | 证据形态 |
|---|---|---|---|
| TC-v2.6-01 | FR-v2.6-01 | `clampWidth` 把 100/340/500/760/9999/NaN/420.6 全部收进边界 | vitest 断言 |
| TC-v2.6-02 | FR-v2.6-01 | 存量越界偏好（280）读成 340 | vitest + localStorage |
| TC-v2.6-03 | FR-v2.6-01 | `setWidth` 钳制后写回 localStorage | vitest + localStorage |
| TC-v2.6-04 | FR-v2.6-01 | 档位按钮 340 → 420 循环并 emit | vitest emit 捕获 |
| TC-v2.6-05 | FR-v2.6-01 | 方向键 ±20、Home/End 直达两端（均 emit 到父组件） | vitest emit 捕获 |
| TC-v2.6-06 | FR-v2.6-01 | 手柄 `role=separator` / `tabindex=0` / `aria-valuenow` 齐全（键盘可达） | vitest DOM 属性 |
| TC-v2.6-07 | FR-v2.6-02 | `≤999px` 悬浮态宽度用 `var(--ai-drawer-w)` 且 `max-width:100%` | 代码审阅 + `check_css_vars` |
| TC-v2.6-08 | FR-v2.6-03 | `GET /api/ai/agents` 返回 200，条目只含已安装且未禁用的 agent | 真实 HTTP |
| TC-v2.6-09 | FR-v2.6-03 | 清单**不含** baseUrl / apiKeyEnc / keyMasked / hasKey | 真实 HTTP + 单测双锁 |
| TC-v2.6-10 | FR-v2.6-03 | 选 pi + `ark/deepseek-v4-flash` → `已连通`、`degraded=false`、无回落 | 真实 HTTP |
| TC-v2.6-11 | FR-v2.6-03 | 前端提交时把 cli/model 放进请求体 | vitest 捕获 payload |
| TC-v2.6-12 | FR-v2.6-03 | 不给 cli/model 时请求体不含这两个字段（老行为不变） | vitest 捕获 payload |
| TC-v2.6-13 | FR-v2.6-03 | 模型名真进 argv：换成不存在的模型必须失败 | 真实 HTTP 负向 |
| TC-v2.6-14 | FR-v2.6-05 | 选未安装的 claude → `cliFallback=true` + `cli=hermes`，前端显示提示 | 真实 HTTP + vitest |
| TC-v2.6-15 | FR-v2.6-04 | 输入是 textarea；Enter 发送、Shift+Enter 不发；中文组合期不误发 | vitest DOM + 事件 |
| TC-v2.6-16 | FR-v2.6-04 | 历史区无硬编码 `max-height`，由弹性布局撑满 | 代码审阅 + vitest 类名断言 |
| TC-v2.6-17 | FR-v2.6-06 | `performLogout` 落一次性退出意图标记（sessionStorage） | vitest |
| TC-v2.6-18 | FR-v2.6-06 | 带标记挂载登录页：不自动提交、不预填密码、账号名预填保留 | vitest + sessionStorage |
| TC-v2.6-19 | FR-v2.6-06 | 标记挂载时消费（一次性）：再进登录页自动登录恢复原行为 | vitest |

## 2. 负向用例

| 编号 | 注入 | 期望 |
|---|---|---|
| N-v2.6-01 | `clampWidth(NaN)` | 回落默认 420，不产生 `NaNpx` 布局 |
| N-v2.6-02 | 请求 `cli=claude`（未安装） | 回落 + `cliFallback=true`，不 500、不静默 |
| N-v2.6-03 | 请求不存在模型 `ark/definitely-not-a-model` | 失败并回显 CLI 报错，反证 `--model` 真进了 argv |
| N-v2.6-04 | 老请求体（只有 projectId/sessionId/prompt） | Jackson 按 null 绑定，行为不变 |
| N-v2.6-05 | 本地偏好指向已消失的 agent | 前端清偏好回「自动」，下拉不空转 |

## 3. 证据清单

| 编号 | 路径 |
|---|---|
| TC-v2.6-08 / 09 / 10 / 14 | `docs/evidence/v3.0.1/v26/raw/assistant-verify.log` |
| TC-v2.6-08 / 09 | `docs/evidence/v3.0.1/v26/raw/agents.json` |
| TC-v2.6-10 | `docs/evidence/v3.0.1/v26/raw/chat-pi.json` |
| TC-v2.6-14 | `docs/evidence/v3.0.1/v26/raw/chat-fallback.json` |
| TC-v2.6-13（N-v2.6-03） | `docs/evidence/v3.0.1/v26/raw/chat-bad-model.log` |
| TC-v2.6-01..06 / 11 / 12 / 15 / 16 | 仓库内 vitest，见 ⑥ §2 |
| TC-v2.6-17 / 18 / 19 | 仓库内 vitest（`LoginView.spec` 12 + `useLogout.spec` 4） |
| TC-v2.6-09（单测侧） | `AiAssistantConfigServiceTest.agentsListNeverExposesEndpointsOrCredentials` |

## 4. 放行口径

本批单列判据三条，全部成立方可视为「本批做完」：

1. 后端 `AiAssistantConfigServiceTest`(13) + `AiChatRequestBindingTest`(2) 全绿；前端两 spec 23 条全绿；
2. 真实 HTTP 三项符合预期：清单 200 且无凭据字段、选 pi 得真实回答、选未安装 agent 回落并明示；
3. 负向「不存在的模型必须失败」成立（反证注入链路有效）。

整仓 `FAILS=0` **未达成**，唯一原因是第 13 步 Chrome CDP 不可用（v2.3 遗留），
**与本批改动无关，也不计入本批通过**；FR-v2.6-01/02/04 的像素与视觉判据因此只能记 NOT-RUN。

## 修订记录

| 版本 | 日期 | 修订内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版：16 条 TC + 5 条负向 | 大师本轮 3 条指令 |
| v1.1 | 2026-11-08 | ⑦ 回写：TC-07 由「浏览器取证」改为「代码 + 机检」，像素部分记 NOT-RUN | 同上 |
| v1.2 | 2026-11-08 | 补 TC-v2.6-17..19（FR-v2.6-06 退出不自动登回），总 19 条 | 大师追加指令 |
