# v2.5 AI 助手配置与体验修复 · ⑥ 测试报告

**文档编号** RA-REL-2026-025-R6 ｜ **版本** v2.5-ai-config ｜ **批次** B13（待落号） ｜ **门禁** G12（未判）
**日期** 2026-11-08 ｜ **执行** 学徒 ｜ **结论** 18 条 TC：**15 PASS / 1 PARTIAL / 2 NOT-RUN**，失败 0

---

## 1. 总览

| 维度 | 读数 |
|---|---|
| 后端单测 | **1080 tests / 0 failure**（含本版新增 `AiAssistantConfigServiceTest` 7 条） |
| 前端单测 | **55 files / 379 tests 全绿** |
| 编辑器单测 | 24 files / 219 tests 全绿 |
| `vue-tsc` | 0 error |
| 整仓门禁 | 19 步 **18 PASS / `FAILS=1`**（唯一失败 = 第 13 步 CDP，v2.3 遗留） |
| 数据库审计 | 7 项全 OK（业务表数 71） |

> 门禁首跑 `FAILS=2`：第 12 步 `db_audit`（由 `V11_15` 修掉，见 §4）与第 07 步 `release_docs`
> （因本文件尚未落盘而红，落盘后复检 `[PASS] v2.5-ai-config FR=11 TC=18`）。
> 复跑后仅剩第 13 步 Chrome CDP，与本批改动无关。

## 2. 用例结果

| TC | 覆盖 | 结果 | 实测读数 |
|---|---|---|---|
| TC-v2.5-01 | FR-v2.5-01 | **PASS** | 撤销请求永不 resolve，`router.replace('/login')` 仍被调用 |
| TC-v2.5-02 | FR-v2.5-01 | **PASS** | reject 时不抛，`performLogout` resolves |
| TC-v2.5-03 | FR-v2.5-01 | **PASS** | 请求发出瞬间 localStorage 已无 token |
| TC-v2.5-04 | FR-v2.5-02 | **PASS** | 迁移三态全对（归位 / 不覆盖 / 默认展开） |
| TC-v2.5-05 | FR-v2.5-02 | **PASS** | 8 条断言：文字非空、`title` 与文字一致 |
| TC-v2.5-06 | FR-v2.5-03 | **NOT-RUN** | 760px 像素读数需浏览器取证，CDP 不可用 |
| TC-v2.5-07 | FR-v2.5-03 | **NOT-RUN** | 顶栏点击可达性需浏览器取证，CDP 不可用 |
| TC-v2.5-08 | FR-v2.5-04 | **PASS** | 中英均 `测试用例` / `测试资产`；`check_i18n` 键集相等 |
| TC-v2.5-09 | FR-v2.5-05 | **PASS** | `version=3.0.1`、`versionSource=file`、`gitCommit=e4d5587` |
| TC-v2.5-10 | FR-v2.5-05 | **PASS** | `APP_VERSION` 覆盖路径单测 + 代码审阅 |
| TC-v2.5-11 | FR-v2.5-05 | **PASS** | 30s 定时器 + `visibilityState === 'visible'` 分支 |
| TC-v2.5-12 | FR-v2.5-06 | **PASS** | 7 条，掩码格式异常 0 项；`hermes` 为 `injectable=false` |
| TC-v2.5-13 | FR-v2.5-07 | **PASS** | 库内 `api_key_enc` 长度 100、前缀 `jIAkn4`（密文，非明文） |
| TC-v2.5-14 | FR-v2.5-07 | **PARTIAL** | 掩码 `****93b8`、空值不改、`clearKey` 清空三条单测绿；**密钥轮换降级路径**只有单测覆盖，未做真轮换演练 |
| TC-v2.5-15 | FR-v2.5-08 | **PASS** | `ok=true elapsedMs=1617 exit=0`，`appliedEnv=[OPENAI_BASE_URL, OPENAI_API_KEY, ARK_API_KEY]`，`appliedArgs=[--model, ark/deepseek-v4-flash]`，stdout `已连通` |
| TC-v2.5-16 | FR-v2.5-09 | **PASS** | engineer → HTTP 403 / `AI 助手配置仅管理员可访问` |
| TC-v2.5-17 | FR-v2.5-10 | **PASS** | `db-audit: PASS`，7 项全 OK |
| TC-v2.5-18 | FR-v2.5-11 | **PASS** | CHANGELOG 四类齐全、锚点在 v2.5；README 读数已回填；三机检全绿 |

### 负向用例

| 编号 | 注入 | 结果 |
|---|---|---|
| N-v2.5-01 | 撤销永不 resolve | PASS（仍跳转） |
| N-v2.5-02 | 撤销 reject | PASS（不抛） |
| N-v2.5-03 | 保存不填密钥 | PASS（原密文保留，走不写密钥列的 upsert） |
| N-v2.5-04 | 旧密文解不开 | PASS（单测，标 `keyUnreadable`，页面不 500） |
| N-v2.5-05 | engineer 调写接口 | PASS（403，未写入） |

## 3. 真实链路证据

| 项 | 读数 | 文件 |
|---|---|---|
| 列表 | 7 条，`hermes` 安装但不可注入，`claude/gemini/aider` 未安装 | `ai-assistants.json` |
| 版本 | `3.0.1` / `file` / `e4d5587` | `build-info.json` |
| pi 连通 | 1617 ms / exit 0 / `已连通` | `pi-test.json` |
| 越权 | 403 | `engineer-forbidden.json` |
| 汇总日志 | 五段全在 | `ai-settings-verify.log` |

## 4. 本轮修掉的缺陷（测试中暴露，不是计划内）

| # | 缺陷 | 怎么暴露 | 处置 |
|---|---|---|---|
| 1 | `ai_assistant_config` 缺审计四列、`updated_at` 非 `datetime(3)` | 门禁第 12 步 `db_audit` 3 项不符 | 追加 `V11_15` 增量补列 + 提精度（不改已应用的 `V11_14`） |
| 2 | pi 报 `Model "deepseek-v4-flash" is ambiguous across providers` | 设置页点「测试连接」 | 模型须 provider 限定 `ark/deepseek-v4-flash` |
| 3 | pi 报 `No API key found for ark` | 同上 | `credentialEnv` 由单值改列表，同时注入 `OPENAI_API_KEY` + `ARK_API_KEY` |
| 4 | 迁移首次执行静默无输出且表未建 | `mvn flyway:migrate` 内联变量未带入 | 改脚本文件 + `set -a; . env; set +a` |
| 5 | 明文泄漏断言误报 | 对整段 JSON 查 `sk-`，命中描述文本 | 改为逐条校验 `keyMasked` 前缀 |

## 5. 遗留

| 编号 | 项 | 原因 | 状态 |
|---|---|---|---|
| L-V25-01 | dsh 真实推理 | 大师所给 key 对 `api.deepseek.com` 与 ARK 两端点**均判 401**（36 字符 UUID 形态，两家均不认） | 未验，未绕过 |
| L-V25-02 | 窄屏像素 / 点击走查 | Chrome CDP `:9222` 被取消且不许重启 | NOT-RUN |
| L-V25-03 | 密钥轮换演练 | 会打断当前已配好的 pi；本批只做单测覆盖降级路径 | PARTIAL |
| L-V25-04 | `claude` 真实调用 | 本机 `reg.exe` 程序黑名单阻断，不可绕过 | 未验（v2.4 已记） |

## 6. 放行口径

**本批判据三条全部成立**：单测全绿（1080 / 379 / 219）、真实 HTTP 五项符合预期、`db-audit` 7/7。
**整仓 `FAILS=0` 未达成**，唯一原因是第 13 步 Chrome CDP（v2.3 遗留），与本批改动无关，
**因此本报告不构成任何门禁判定，G12 状态仍为「未判」**。

## 修订记录

| 版本 | 日期 | 修订内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版：18 条 TC 结果 + 5 条负向 + 5 条测试中修掉的缺陷 + 4 条遗留 | 大师本轮 7 条指令 |
