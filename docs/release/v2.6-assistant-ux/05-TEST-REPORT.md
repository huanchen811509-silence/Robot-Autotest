# v2.6 工作助手体验 · ⑥ 测试报告

**文档编号** RA-REL-2026-026-R6 ｜ **版本** v2.6-assistant-ux ｜ **批次** B14（待落号） ｜ **门禁** G13（未判）
**日期** 2026-11-08 ｜ **执行** 学徒 ｜ **结论** 24 条 TC：**22 PASS / 2 NOT-RUN**，失败 0

---

## 1. 总览

| 维度 | 读数 |
|---|---|
| 后端单测 | **1092 tests / 0 failure**（本版累计新增 12：配置服务 6 + 绑定 2 + 内核归一 4） |
| 前端单测 | **55 files / 397 tests 全绿**（本版累计新增 18：抽屉 10 + 状态层 4 + 退出 3 + 预设 1） |
| 编辑器单测 | 24 files / 219 tests 全绿 |
| `vue-tsc` | 0 error |
| 整仓门禁 | 19 步 **18 PASS / `FAILS=1`**（唯一失败 = 第 13 步 CDP，v2.3 遗留） |
| 静态机检 | `check_tokens` 1122 处 px 全白名单 / `check_css_vars` 115 定义 0 悬空 / `check_colors` 0 硬编码色 / `check_i18n` 全绿 |
| 数据库 | `V11_16` 加 `project.test_kernel`（默认 robot，存量无损）；`db-audit` 口径不变（仍 71 表） |

> 门禁首跑 `FAILS=2`：第 07 步 `release_docs` 因本目录六份产物尚未写完而红，落盘后复检通过。

## 2. 用例结果

| TC | 覆盖 | 结果 | 实测读数 |
|---|---|---|---|
| TC-v2.6-01 | FR-v2.6-01 | **PASS** | 100→340、340→340、500→500、760→760、9999→760、NaN→420、420.6→421 |
| TC-v2.6-02 | FR-v2.6-01 | **PASS** | 存量 `280` 读成 `340` |
| TC-v2.6-03 | FR-v2.6-01 | **PASS** | `setWidth(5000)` → 760 并写盘；`setWidth(10)` → 340 |
| TC-v2.6-04 | FR-v2.6-01 | **PASS** | 340 起点点一下 → emit 420 |
| TC-v2.6-05 | FR-v2.6-01 | **PASS** | 500 起点：← 520 / → 480 / Home 340 / End 760（四次 emit 全对） |
| TC-v2.6-06 | FR-v2.6-01 | **PASS** | `role=separator` / `tabindex=0` / `aria-valuemin=340` / `aria-valuemax=760` |
| TC-v2.6-07 | FR-v2.6-02 | **NOT-RUN** | `≤999px` 实际渲染需浏览器取证，CDP 不可用；CSS 侧已由 `check_css_vars` 验证 |
| TC-v2.6-08 | FR-v2.6-03 | **PASS** | `GET /api/ai/agents → HTTP 200`，4 条（hermes / dsh / codex / pi），`realCliEnabled=true` |
| TC-v2.6-09 | FR-v2.6-03 | **PASS** | 泄漏端点/密钥字段的条目 = **无**；单测另锁「不得含 baseUrl/apiKeyEnc/keyMasked/hasKey」 |
| TC-v2.6-10 | FR-v2.6-03 | **PASS** | `cli=pi requestedCli=pi cliFallback=false degraded=false durationMs=2025 exitCode=0 answer=已连通` |
| TC-v2.6-11 | FR-v2.6-03 | **PASS** | 请求体 `cli=pi`、`model=ark/deepseek-v4-flash` |
| TC-v2.6-12 | FR-v2.6-03 | **PASS** | 未选时 payload 里 `cli` / `model` 均为 undefined |
| TC-v2.6-13 | FR-v2.6-03 | **PASS** | 换 `ark/definitely-not-a-model` → `exitCode=1`，stderr `Model "definitely-not-a-model" not found for provider "ark"` + `404 UnsupportedModel` |
| TC-v2.6-14 | FR-v2.6-05 | **PASS** | 选 claude（未安装）→ `cli=hermes cliFallback=true`；前端显示回落提示 |
| TC-v2.6-15 | FR-v2.6-04 | **PASS** | 输入为 `TEXTAREA`；Shift+Enter 不调用、Enter 调用 1 次 |
| TC-v2.6-16 | FR-v2.6-04 | **PASS** | 历史区无 inline `max-height`；`flex:1 1 auto; min-height:0; overflow-y:auto` |
| TC-v2.6-17 | FR-v2.6-06 | **PASS** | `performLogout` 后 `sessionStorage[login_logout_intent]='1'`（useLogout.spec 第 4 条） |
| TC-v2.6-18 | FR-v2.6-06 | **PASS** | 带标记挂载：`login` 未被调用、密码输入框空串、账号名预填 `evergreen` |
| TC-v2.6-19 | FR-v2.6-06 | **PASS** | 首次挂载消费标记后，再次挂载自动登录照常触发并按 redirect 跳转 |
| TC-v2.6-20 | FR-v2.6-07 | **PASS** | 4 档渲染 340/420/520/760；点 520 → emit 520 |
| TC-v2.6-21 | FR-v2.6-07 | **PASS** | `aria-pressed=['false','true','false','false']`（宽 420）；拖拽/键盘用例不回归 |
| TC-v2.6-22 | FR-v2.6-08 | **PASS** | 卡牌接口 9 项目 `testKernel` 全部 `robot`（`V11_16` 默认，存量无损） |
| TC-v2.6-23 | FR-v2.6-08 | **PASS** | 建 `kernel-verify-*` pytest 回读一致 → 改回 robot 一致 → `junit` **400** → 删除清理 200 |
| TC-v2.6-24 | FR-v2.6-08 | **PASS** | 归一单测 4 条绿；agents 接口回归 200 / 4 条 / 无泄漏字段 |

### 负向用例

| 编号 | 注入 | 结果 |
|---|---|---|
| N-v2.6-01 | `clampWidth(NaN)` | PASS（回落 420） |
| N-v2.6-02 | `cli=claude`（未安装） | PASS（回落 + 明示，无 500） |
| N-v2.6-03 | 不存在的模型 | PASS（失败并回显，反证 `--model` 真进 argv） |
| N-v2.6-04 | 老请求体 | PASS（`cli`/`model` 绑定为 null） |
| N-v2.6-05 | 偏好指向已消失 agent | PASS（加载清单后清偏好） |

## 3. 真实链路证据

| 项 | 读数 | 文件 |
|---|---|---|
| agent 清单 | 4 条、无凭据字段、`defaultKey=hermes` | `agents.json` |
| 选 pi 回答 | `已连通` / 2025 ms / exit 0 / 无回落 | `chat-pi.json` |
| 选未安装 claude | 回落 `hermes` + `cliFallback=true` | `chat-fallback.json` |
| 模型注入负向 | `404 UnsupportedModel` | `chat-bad-model.log` |
| 汇总 | 五段全在 | `assistant-verify.log` |

## 4. 测试中暴露的问题（不是计划内）

| # | 问题 | 怎么暴露 | 处置 |
|---|---|---|---|
| 1 | `var(--ai-drawer-w)` 被判悬空 | 门禁 `check_css_vars` / `check_tokens` 双报 | 在 `theme.css :root` 声明默认值 `420px` |
| 2 | Home/End 只改状态不 emit，父组件宽度不变 | 单测 emit 捕获为空 | 统一改 `emit('update:width', ...)` |
| 3 | 本机对话一直 DEGRADED | 首轮验收 `realCliEnabled=False` | 查得 `FEATURE_AI_REAL_CLI` 从未开启（先于本轮）；以 systemd drop-in 临时打开 |

## 5. 遗留

| 编号 | 项 | 原因 | 状态 |
|---|---|---|---|
| L-V26-01 | 像素级走查（拖拽后主区宽度、<999px 悬浮宽） | Chrome CDP `:9222` 被取消且不许重启 | NOT-RUN |
| L-V26-02 | 非管理员可读 `/api/ai/agents` 的实测 | 本机已知账号全是 admin | 只做代码审阅，未出实测证据 |
| L-V26-03 | `FEATURE_AI_REAL_CLI` 的产品默认值 | 涉及费用与外部依赖，属发版口径 | 本机 drop-in 临时打开，默认值未改，待大师拍板 |
| L-V26-04 | dsh 真实推理 | 上一版遗留：所给 key 对两端点均 401 | 未验（与 v2.5 同因） |

## 6. 放行口径

**本批判据三条全部成立**：单测全绿（1088 / 393 / 219）、真实 HTTP 三项符合预期、负向注入成立。
**整仓 `FAILS=0` 未达成**，唯一原因是第 13 步 Chrome CDP，与本批改动无关，
**因此本报告不构成任何门禁判定，G13 状态仍为「未判」**。

## 修订记录

| 版本 | 日期 | 修订内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版：16 条 TC 结果 + 5 条负向 + 3 条测试中修掉的问题 + 4 条遗留 | 大师本轮 3 条指令 |
| v1.1 | 2026-11-08 | 补 TC-v2.6-17..19：大师实测「点退出仍在登录状态」，退出意图标记修复（FR-v2.6-06 / V26-06） | 大师追加指令 |
| v1.2 | 2026-11-08 | 补 TC-v2.6-20..24：宽度预设陈列（FR-v2.6-07 / V26-07）+ 项目测试内核（FR-v2.6-08 / V26-08，含 `V11_16` 迁移） | 大师追加指令 |
