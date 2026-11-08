# v2.5 AI 助手配置与体验修复 · ④ DEV-PLAN

**文档编号** RA-REL-2026-025-D ｜ **版本** v2.5-ai-config ｜ **批次** B13（待落号） ｜ **门禁** G12（未判）
**日期** 2026-11-08 ｜ **状态** 7 包全部完成并自测；**不进 130 包 WBS 分母**

---

## 1. 包与交付

| 包 | 内容 | 主要文件 | 验收命令 | 状态 |
|---|---|---|---|---|
| V25-01 | 退出链路抽 `performLogout`，先本地清后异步撤销 | `frontend/src/composables/useLogout.ts`、`App.vue` | `npx vitest run src/composables/useLogout.spec.ts` | 完成 |
| V25-02 | 折叠态一次性迁移 + 导航项 `title` | `useAiDrawer.ts`、`AppNav.vue` | `npx vitest run src/composables/useAiDrawer.spec.ts src/components/AppNav.spec.ts` | 完成 |
| V25-03 | 760px 窄轨断点（同步藏文字）+ 顶栏 wrap | `frontend/src/styles/main.css` | `python tools/check_tokens.py` | 完成 |
| V25-04 | 一级菜单改名（中英） | `frontend/src/locales/zh-CN.ts`、`en-US.ts` | `python tools/check_i18n.py` | 完成 |
| V25-05 | 版本动态化：`VERSION` 真源 + 来源标识 + 运行期覆盖 + 关于页刷新 | `VERSION`、`scripts/gen_build_info.py`、`BuildInfoService.java`、`AboutView.vue` | `curl :8081/api/system/build-info` | 完成 |
| V25-06 | AI 助手配置：迁移 + 仓储 + 服务 + 控制器 + 设置页 | `V11_14` / `V11_15`、`AiAssistantCatalog`、`AiAssistantConfigService`、`AiAssistantController`、`AiAssistantsView.vue` | `mvn -Dtest=AiAssistantConfigServiceTest test` + 真实 HTTP | 完成 |
| V25-07 | 门禁与文档回写：db-audit 口径、CHANGELOG、README、交接文档、本目录六份 | `scripts/db-audit.sh`、`CHANGELOG.md`、`README.md`、`docs/plan/` | `bash scripts/gates.sh` + `python tools/check_release_docs.py` | 完成 |

## 2. 实施顺序（为什么是这个顺序）

1. **先定根因再动手**：V25-01 与 V25-02/03 是「逻辑」与「布局」两件独立的事，分开做、分开验，
   否则任何一件没好都会被另一件掩盖，表现为「修了还是不行」。
2. **迁移先行**：V25-06 的表必须在服务与控制器之前落地，接口才有东西可读。
3. **补列走增量**：`V11_14` 已 `success=1`，审计四列只能在 `V11_15` 补。顺序上必须先有 11.14 才能有 11.15。
4. **文档最后写**：README 里的迁移数 / 表数 / 门禁步数都是实测读数，先写必然写错。

## 3. 与既有约定的对齐

| 约定 | 落实方式 |
|---|---|
| 七道流程 | 本目录六份产物齐（①②③④⑤⑥），⑦ 回写进各文件末尾 |
| 输出元数据 | 本版不动 `output.xml`，`check_output_metadata` 照旧 |
| 机检进门禁 | db-audit、check_tokens、check_i18n 均为门禁步骤，不是「跑一下看看」 |
| 密钥不外泄 | `SecretCipher` 加密 + 掩码回显 + 明文不日志，有三条单测锁死 |
| 版本号 | 新增 `VERSION` 为唯一真源，`gen_build_info.py` 三档优先级 |

## 4. 质量门读数

| 门 | 读数 |
|---|---|
| 后端 `AiAssistantConfigServiceTest` | 7 tests / 0 failure |
| 前端 `useLogout.spec` | 3 tests / 0 failure |
| 前端 `useAiDrawer.spec` | 3 tests / 0 failure |
| 前端 `AppNav.spec` | 8 tests / 0 failure |
| 前端 `navigation.spec` | 33 tests / 0 failure |
| `vue-tsc` | 0 error |
| `check_i18n` | 124 文件 / 0 硬编码 / 键集相等 / 无同值漏译 |
| `check_tokens` | 1109 处 px 全在白名单 |
| `check_colors` | 185 文件 / 0 硬编码色 |
| `check_css_vars` | 114 定义 / 0 悬空 |
| `db-audit` | 7 项全 OK（业务表数 71） |

## 5. 偏差

| 编号 | 偏差 | 原因与处置 |
|---|---|---|
| D-1 | 断点原打算用 860px | `check_tokens` 判 860 不在白名单；改用已有档位 760px，并合并重复 `@media` 块 |
| D-2 | 「关于页版本」原计划只改 pom | 实测发现改 pom 要重打包才算；改为 `VERSION` 文件 + `APP_VERSION` 运行期覆盖 |
| D-3 | pi 首次测试失败「模型歧义」 | 模型必须写 provider 限定 `ark/deepseek-v4-flash`；已写入默认值与文档 |
| D-4 | pi 二次失败「No API key found for ark」 | `credentialEnv` 由单值改列表，pi 同时注入 `OPENAI_API_KEY` 与 `ARK_API_KEY` |
| D-5 | `V11_14` 上线后 db-audit 变红 | 不能改已应用迁移；追加 `V11_15` 补四列并提精度到 `datetime(3)` |
| D-6 | 窄屏视觉判据无法取证 | Chrome CDP `:9222` 被取消且不许重启；如实记未验，不拿代码正确性替代 |

## 修订记录

| 版本 | 日期 | 修订内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版：7 包 + 实施顺序 + 质量门读数 | 大师本轮 7 条指令 |
| v1.1 | 2026-11-08 | ⑦ 回写：补 D-5 / D-6 两条实测偏差 | 同上 |
