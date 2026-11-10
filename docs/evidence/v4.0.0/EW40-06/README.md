# EW40-06 · 4.0 横向回归 / 门禁 / ⑦ 回写（W40-06）

| 项 | 内容 |
|---|---|
| 工作包 | `W40-06` 横向回归 / 门禁 / ⑦ 回写 |
| 覆盖 | `FR-v4.0-01` 至 `FR-v4.0-05` 的横向回归与门禁结论 |
| 日期 | 2026-11-09 |
| 环境 | WSL Ubuntu-24.04 · MySQL 8.0 `127.0.0.1:3306`（`robot_dev`）· 后端 `:8081` · 前端 `:8001` · CDP `:9222` |
| 结论 | `PASS`：30 步门禁 `FAILS=0`、结构自校验 `FAILS=0`、浏览器回归 40 项 `fails=0`、文档机检 `FAIL 0 / WARN 0` |

---

## 1. 读数

| 检查 | 命令 | 真实读数 |
|---|---|---|
| 全量门禁 30 步 | `bash scripts/gates.sh`（`gates.log`） | `GATES_RESULT FAILS=0`；后端单测 1279（0F/0E/4S）、前端 583、浏览器 40、交付包与文档机检全 PASS |
| 门禁结构自校验 | `bash scripts/gates.sh --selfcheck`（`gates-selfcheck.log`） | `SELFCHECK_RESULT FAILS=0`；`SELFCHECK 30 PASS`、删步骤 13/14/19/20/23/24/27/28/29/30 均「删了变红」 |
| 浏览器横向回归 | `node scripts/browser-regression.mjs`（`browser-regression.log`） | `BROWSER_REGRESSION checks=40 fails=0` |
| 计划文档表格 | `check_md_tables.py docs/plan/v4.x-*.md`（`md-tables.log`） | 4 份 / 45 表格块 / 354 数据行 `[PASS]` |
| 发布文档机检 | `python3 tools/check_release_docs.py`（`release-docs.log`） | 22 个版本 `FAIL 0 / WARN 0`（未启动 11 个版本按流程要求先 ① ②） |
| 版本四副本 | `python3 tools/check_version_consistency.py`（`version-consistency.log`） | `VERSION=3.11.0` 一致（本批次**未 bump**，落号归大师） |

## 2. 本轮修掉的真实红灯（不是计划偏差）

| # | 红灯 | 根因 | 处置 |
|---|---|---|---|
| 1 | `05-backend_test` / `19-evidence_integrity` | `AuditService` 仍保留第二份 `SENSITIVE` 正则并输出 `***`，与 `SecretRedactor` 的 `<redacted>` 双口径 | 删除 `SENSITIVE`，`sanitize()` 直连 `SecretRedactor`；中文键名（口令/密码/密钥/令牌/凭据）并入单一规则源；`AuditAspectTest` 断言同步 |
| 2 | `scripts/side-toggle-probe.mjs` 期望 56px | W40-04 已把窄轨改 64px，探针断言过期 | 断言改 64，并与 `FR-v4.0-04` 关联说明 |

## 3. 证据文件

| 文件 | 内容 |
|---|---|
| `gates.log` | 30 步全量门禁输出 |
| `gates-selfcheck.log` | 结构自校验与「删步骤必变红」实证 |
| `browser-regression.log` | 多视口路由 / 无 JS 异常 / 无横滚 40 项 |
| `md-tables.log`、`release-docs.log`、`version-consistency.log` | 文档与版本机检 |

## 4. 未做 / 判不了（不冒充通过）

| # | 项 | 说明 |
|---|---|---|
| 1 | `axe` 无障碍扫描 | 本轮未引入 axe 运行时，`TC-v4.0-24` 的 axe 半项记 `NOT-RUN`，人工核对阶段补 |
| 2 | Firefox 抽查 | 环境仅 Chromium |
| 3 | 4.0 版本落号 | `VERSION` 仍 `3.11.0`，按计划「版本落号由大师书面确认」，收口时统一 bump |
