# v2.5 AI 助手配置与体验修复 · ⑤ TEST-PLAN

**文档编号** RA-REL-2026-025-T ｜ **版本** v2.5-ai-config ｜ **批次** B13（待落号） ｜ **门禁** G12（未判）
**日期** 2026-11-08 ｜ **状态** 18 条 TC 已执行（15 PASS / 1 PARTIAL / 2 NOT-RUN）

> 原则：能机检的不靠人眼；能真链路的不用单测代替。
> 拿不到证据的判据**记 NOT-RUN**，不写「应该没问题」。

---

## 1. 用例与覆盖

| TC | 覆盖 FR | 判据 | 证据形态 |
|---|---|---|---|
| TC-v2.5-01 | FR-v2.5-01 | 撤销请求永不 resolve 时 1s 内完成并跳登录页 | vitest 断言 `router.replace` 被调用 |
| TC-v2.5-02 | FR-v2.5-01 | 撤销请求抛错时不向调用方抛 | vitest `expect(...).resolves` |
| TC-v2.5-03 | FR-v2.5-01 | 请求发出时 token 已从 localStorage 移除 | vitest localStorage 读值 |
| TC-v2.5-04 | FR-v2.5-02 | 迁移三态：旧键 true→归位 false；迁移键存在→不覆盖；无旧键→默认展开 | vitest `vi.resetModules()` + 动态 import |
| TC-v2.5-05 | FR-v2.5-02 | 每个导航项渲染非空文字，`title` 与文字一致 | vitest DOM 断言 |
| TC-v2.5-06 | FR-v2.5-03 | 760px 下轨道 56px 且 `.app-brand` / 导航文字 `display:none` | 代码审阅 + `check_tokens`（**像素未验**） |
| TC-v2.5-07 | FR-v2.5-03 | 顶栏 `flex-wrap: wrap`，账户区不被推出 | 同上（**点击未验**） |
| TC-v2.5-08 | FR-v2.5-04 | 中英均为 测试用例 / 测试资产；键集相等 | `check_i18n` + vitest |
| TC-v2.5-09 | FR-v2.5-05 | `VERSION` 为真源，三档优先级正确 | 真实 `build-info` 响应 `versionSource=file` |
| TC-v2.5-10 | FR-v2.5-05 | `APP_VERSION` 环境变量覆盖生效 | 后端单测 + 代码路径审阅 |
| TC-v2.5-11 | FR-v2.5-05 | 关于页 30s 刷新且仅可见时刷 | 代码审阅（`visibilityState` 分支） |
| TC-v2.5-12 | FR-v2.5-06 | 列表 7 条，字段齐全，不可注入项标 `injectable=false` | 真实 HTTP JSON |
| TC-v2.5-13 | FR-v2.5-07 | 库内为密文（长度 > 明文、非明文子串） | SQL 抽查长度与前缀 |
| TC-v2.5-14 | FR-v2.5-07 | `keyMasked` 以 `****` 开头且不含明文片段；空值不改、`clearKey` 才清 | 单测 7 条 + HTTP 响应 |
| TC-v2.5-15 | FR-v2.5-08 | `appliedEnv` / `appliedArgs` 与真实 CLI `ok=true` | 真实 HTTP + `pi-test.json` |
| TC-v2.5-16 | FR-v2.5-09 | engineer 三角色接口 403 | 真实 HTTP 403 |
| TC-v2.5-17 | FR-v2.5-10 | db-audit 七项全 OK | `scripts/db-audit.sh` 输出 |
| TC-v2.5-18 | FR-v2.5-11 | CHANGELOG 四类齐全 + 锚点在 v2.5；README 读数一致；三机检不红 | 机检输出 |

## 2. 负向用例

| 编号 | 注入 | 期望 |
|---|---|---|
| N-v2.5-01 | 撤销请求永不 resolve | 仍跳登录页（不得挂住） |
| N-v2.5-02 | 撤销请求 reject | 不抛错 |
| N-v2.5-03 | 保存时不填密钥 | 原密文保留（不得被空值覆盖） |
| N-v2.5-04 | 密钥轮换后旧密文解不开 | 页面不 500，标 `keyUnreadable` 提示重填 |
| N-v2.5-05 | engineer 调 PUT / POST | 403，且不得写入 |

## 3. 证据清单

| 编号 | 路径 |
|---|---|
| TC-v2.5-09 / 12 / 13 / 15 / 16 | `docs/evidence/v3.0.1/v25/raw/ai-settings-verify.log` |
| TC-v2.5-12 | `docs/evidence/v3.0.1/v25/raw/ai-assistants.json` |
| TC-v2.5-09 | `docs/evidence/v3.0.1/v25/raw/build-info.json` |
| TC-v2.5-15 | `docs/evidence/v3.0.1/v25/raw/pi-test.json` |
| TC-v2.5-16 | `docs/evidence/v3.0.1/v25/raw/engineer-forbidden.json` |
| TC-v2.5-01..05 / 08 / 14 | 仓库内 vitest / mvn 用例，见 ⑥ §2 |

## 4. 放行口径

本批单列判据三条，全部成立方可视为「本批做完」：

1. `AiAssistantConfigServiceTest` 7 条 + 前端 4 个 spec（47 条）全绿；
2. 真实 HTTP 五项（列表 / 版本 / 越权 / 密文 / pi 连通）全部符合预期；
3. `db-audit` 七项全 OK。

整仓 `FAILS=0` **未达成**，原因是第 13 步 Chrome CDP 不可用（v2.3 遗留），
**与本批改动无关，也不计入本批通过**。FR-v2.5-02 / 03 的视觉判据因此只能记 NOT-RUN。

## 修订记录

| 版本 | 日期 | 修订内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版：18 条 TC + 5 条负向 | 大师本轮 7 条指令 |
| v1.1 | 2026-11-08 | ⑦ 回写：TC-06 / 07 由「计划用浏览器取证」改为「记 NOT-RUN」，因 CDP 不可用 | 同上 |
