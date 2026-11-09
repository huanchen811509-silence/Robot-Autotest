# v3.11.0 开发计划（RA-DVP-311）

> 上游：`docs/plan/v3.9+版本演进-开发与测试计划-20261109.md`。本文件是**发布口径**的 WBS 与验收命令。
> 执行顺序：B4（EW311-01 → 02 → 03）→ B5 收口（05-TEST-REPORT + 版本四副本 + 门禁 + 三处推送）。

## 1. WBS 与验收命令

| WBS | 内容 | 改动面 | 验收命令 | 状态 |
|---|---|---|---|---|
| EW311-01 | 资产主机清单采集与展示 | `V11_26` + down、`domain/host/*`（3 个）、`application/host/*`（4 个）、`HostInventoryRepositoryJdbc`、`HostInventoryController`、`TenantGuard`、`HostInventoryCollectorTest`、`tools/check_host_inventory.py`、`scripts/host-inventory-contract.sh`、门禁第 29 步 | `bash scripts/host-inventory-contract.sh`（`RESULT PASS fails=0`）、`bash scripts/migration-down-up-drill.sh V11_26__v3_11_host_inventory`、`python3 tools/check_host_inventory.py`、`bash scripts/gates.sh --selfcheck` | 完成 |
| EW311-02 | 环境与拓扑 5 页实装 | 5 个 `.vue` 重写、`api/environment.ts`（新）、`EnvProfileSummary`/`EnvProfileRepository`/`EnvProfileService`/`EnvController`、`V11_27` + down、`pages.spec.ts` 重写、`scripts/env-pages-probe.mjs`（新）、`scripts/env-fixtures.sh`（新）、locales 两份 | `node scripts/env-pages-probe.mjs`（`ENV_PAGES PASS FAILS=0`）、`bash scripts/env-fixtures.sh`（`RESULT PASS fails=0`）、索引 down→up 演练 | 完成 |
| EW311-03 | 维护与恢复 3 页实装 | 3 个 `.vue` 重写、`api/maintenance.ts`（新）、`pages.spec.ts` 重写、locales 两份 | `node scripts/env-pages-probe.mjs`（3 页判据全 PASS）、`npx vitest run src/views/maintenance/pages.spec.ts` | 完成 |

## 2. 迁移与部署

| 项 | 内容 |
|---|---|
| V11_26 | `CREATE TABLE device_host_spec`（`spec_json LONGTEXT` + `spec_hash` + 摘要列 + 两个联合索引）；down = `DROP TABLE`；真实演练 `MODE=table` 分支 `RESULT PASS fails=0` |
| V11_27 | `env_profile` 去掉 `uk_env_profile_project_name` 唯一键，改普通联合索引 `(project_id, name, snapshot_at)`；down 逆序恢复唯一键；索引型 down→up 演练 7 项全 PASS、行数不变 |
| 接口 | 新增 `GET /api/projects/{projectId}/snapshots/profiles`（纯 GET，先 `bindProject` + `require(ASSET, READ)`）；openapi 快照已重生成（284 endpoints / 225 paths） |
| 部署 | 重建 jar → `systemctl --user restart robot-autotest-backend` → 前端 `vite build`（`:8001` 读 `frontend/dist`）→ 重跑探针 |
| 兼容 | V11_27 不改任何列，旧数据（一个环境名一行）语义不变；恢复唯一键的 down 在已有同名多快照时会失败，属预期并在 down 脚本注释里写明 |

## 3. 门禁与取证

| 项 | 变更 |
|---|---|
| 门禁步数 | 28 → **29 步**：第 29 步 `host_inventory` 跑 `tools/check_host_inventory.py`；`check_structure` 与 `--selfcheck` 同步加「删第 29 步必变红」实证 |
| 判据自证 | 第 29 步 `--self-check` 20 组正负样本（少一类 / 顺序错 / 无原因 / 占位值 / status 不符 / 命令漂移 / 缺时间 / 语义矛盾 / 写操作 / 非白名单 / 接口漂移）全部符合预期 |
| 新增探针 | `env-pages-probe.mjs` 依赖 CDP、`:8001`、真实项目 29 数据，**不进 CI 门禁**（与 v3.10.0 同口径）；产物入库供复核，源码级判据由两份 `pages.spec.ts` 兜底 |
| 证据 | `docs/evidence/v3.11.0/EW311-01/`（清单 JSON、命令清单、契约日志、演练日志、自证日志）、`EW311-02/`（env-pages.json、fixtures.json、索引演练日志）、`EW311-03/`（maintenance-pages.json） |

## 4. 交付清单

- 代码：后端 3 个域对象 + 4 个应用类 + 2 处仓储/接口 + 2 个控制器改动 + 1 个单测；前端 2 个 API 模块 + 8 个页面重写 + 2 份 spec 重写 + 2 份语言包；2 份迁移 + 2 份 down；4 支脚本/工具（checker、contract、probe、fixtures）。
- 文档：本目录 5 份产物 + `05-TEST-REPORT.md` + `docs/evidence/v3.11.0/README.md` 索引。
- 数据：真实采集 2 次、真实资产快照 2 份（内容取自真实 `/devices` 与 `/links`），均由真实 HTTP 写入，未直接塞 SQL 假行。

## 5. 偏差记录

| 项 | 计划 | 实际 | 原因 |
|---|---|---|---|
| 后端改动量 | 计划「5 页接既有接口，后端零改动」 | 补 1 个 `GET /snapshots/profiles` + 1 个域对象 + 仓储/服务/控制器 | 实测没有任何接口能列出环境名，而 `list(name)` 对空 name 抛 400（调研 #8/#9） |
| 迁移 | 计划「V11_26 一份」 | 追加 `V11_27` | `env_profile` 唯一键让同名第二份快照 500，「两快照对比」不可能成立（ADR-311-09） |
| 恢复记录数据源 | 计划「恢复/重试记录」 | 用审计日志的 `case.batch.undo.*`；当前 0 条如实显示 | `run.retry_of_run_id` 与 `run_attempt.retried` 均为 0 条，且没有列表接口；审计 `GET /audit` 已有且是真值（ADR-311-12） |
| 只读口径取证 | 计划「源码静态断言」 | 源码断言 + 真浏览器录请求方法 | 写动作搬到 API 层后源码干净也可能发写（ADR-311-06） |
| diff 的 HTTP 方法 | 计划「对比走 GET」 | 沿用既有 `POST /snapshots/diff`（纯计算，不写库） | 既有契约如此；探针把它记在「交互期」而非「加载期」，只读判据按加载期判 |
| 列表 `specJson` | 计划「列表不返回该字段」 | 返回 `specJson: null` | 字段名一定会出现（null 也序列化）；真正要判的是**值**，首跑按键名判出现过假红 |
| 详情路由 | 计划 `@GetMapping("/{id}")` | 改为 `@GetMapping("/{id:\\d+}")` | 否则 `GET /collect` 被详情路由吃掉，回「参数类型不正确：id」400，而正确答复是 405 |
| 探针 GET 缓存 | 计划「点搜索即有 GET」 | 检索前先改关键字 | `http.ts` 对 GET 短时缓存，同参复搜不发请求，首跑判据假红 |
| 页面筛选控件 | 计划统一 naive-ui | 用原生 `<select>` | happy-dom 下 `NSelect` 虚拟列表不渲染选项（沿用 v3.10.0 结论） |
