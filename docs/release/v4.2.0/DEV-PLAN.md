# v4.2.0 开发计划（RA-DVP-420）

**文档编号** RA-DVP-420 ｜ **版本** v1.0 ｜ **日期** 2026-11-10

## 1. WBS 与验收命令

| 包 | 职责 | 证据目录 |
|---|---|---|
| `W40-00` | 基线与授权确认（三批一次性推进、落号须书面确认） | `docs/evidence/v4.0.0/` |
| `W40-01` | 命令脱敏三类写法（含 `-u user:pass`） | `EW40-01/` |
| `W40-02` | 统一工作区浮层范式（层级 / 焦点 / `aria-modal`） | `EW40-02/` |
| `W40-03` | 账户下拉四分区与权限门控 | `EW40-03/` |
| `W40-04` | 折叠态留白（56 → 64px、20 → 28px） | `EW40-04/` |
| `W40-05` | 可回滚升级机制与演练 | `EW40-05/` |
| `W40-06` | 4.0 横向回归 / 门禁 / ⑦ 回写 | `EW40-06/` |
| `W41-01` | 工作区目录契约、ADR-4X、机检脚本、`V11_28` down | `EW41-01/` |
| `W41-02` | 项目导出（含凭据白名单） | `EW41-02/`、`EW41-02-workspace-contract.log` |
| `W41-03` | 项目导入（预检 / 事务 / 幂等） | `EW41-03/`、同上契约日志 |
| `W41-04` | 三级传递与配额 | `EW41-04/` |
| `W41-05` | 创建项目向导 + 4 模板 | `EW41-05/` |
| `W41-06` | 工作区 UI 与任务中心 | `EW41-06/` |
| `W41-07` | 4.1 横向回归 / 门禁 / ⑦ 回写 | `EW41-07/` |
| `W42-00` | SSH 目标与凭据方案准入 | `EW42-00/` |
| `W42-01` | CI 无 DB 质量门 | `EW42-01/` |
| `W42-02` | `SchemaIT` / `db-audit` 口径对齐 | `EW42-02/` |
| `W42-03` | 远程主机只读采集 | `EW42-03/` |
| `W42-04` | artifact 端到端下载复核 | `EW42-04/` |
| `W42-05` | 4.2 横向回归 / 门禁 / ⑦ 回写 | `EW42-05/` |

关键验收命令：

```bash
bash -lc "bash scripts/gates.sh"                       # 32 步全量门禁
bash -lc "bash scripts/gates.sh --selfcheck"           # 门禁自检（必须登录 shell）
DB_NAME=robot_it mvn -o -B -Pit test -Dtest=SchemaIT   # IT 必须 -Pit
python3 tools/check_release_docs.py                    # 发布文档机检
python3 tools/check_version_consistency.py             # 版本四副本 + 运行期
python3 scripts/gen_build_info.py                      # 写 build-info
python3 tools/gen_api_contract.py                      # 重生成 openapi
```

## 2. 迁移与部署

| 项 | 内容 |
|---|---|
| 数据库迁移 | `V11_28` 新建工作区相关表，附 `V11_28*.down.sql`；业务表 88 → 89 |
| 租户隔离 | 新表登记 `TenantGuard`，`db-audit` 与 `SchemaIT` 同数 |
| 版本落号 | `VERSION` `3.11.0` → `4.2.0`；按计划 §2「版本落号」行，**2026-11-10 大师书面确认后已执行**（四副本一致） |
| 版本四副本 | `VERSION`、`backend/src/main/resources/build-info.properties`、`docs/contracts/openapi.json` `info.version`、`frontend/public/CHANGELOG.md` 首条 |
| 后端 | `mvn -o -q -DskipTests package` → `systemctl --user restart robot-autotest-backend` |
| 前端 | `npm run build` → `systemctl --user restart robot-autotest-entry`（入口托管 `frontend/dist`） |
| CHANGELOG | 根 `CHANGELOG.md` 新增 `v4.2.0` 小节并标 `<!-- current -->`，`cp` 到 `frontend/public/CHANGELOG.md` |

## 3. 门禁与取证

| 项 | 口径 |
|---|---|
| 门禁步数 | **32 步**（原 29 步 + 3 步） |
| 版本一致性 | 第 23 步：静态四项 + 运行期两项（登录 `/api/system/build-info`、入口 `/CHANGELOG.md`） |
| 契约快照 | 第 20 步：`openapi.json` 重生成后 `--check` 不漂移（291 endpoints / 232 paths） |
| 表格规范 | 第 6 步：`WorkspaceView.vue` 三张原生表改 `DataTable` 壳后 PASS |
| 主机清单 | 第 29 步：`tools/check_host_inventory.py` 18 条静态硬约束 |
| 取证纪律 | 单测、契约、真浏览器三层互不替代；判不了的写 `NOT-RUN` |

## 4. 交付清单

| 项 | 位置 |
|---|---|
| 源代码 | 十九个工作包对应的后端 / 前端 / 脚本 / 迁移 |
| 契约快照 | `docs/contracts/openapi.json`（291 endpoints / 232 paths） |
| 迁移 | `V11_28` up / down |
| 证据 | `docs/evidence/v4.0.0/`、`v4.1.0/`、`v4.2.0/` |
| 发布文档 | 本目录六份：调研 / 评估 / PRD / 开发计划 / 测试计划 / 测试报告 |
| 版本地图 | `docs/release/README.md` 增 `v4.2.0/` 行 |

## 5. 偏差记录

| 计划写法 | 实际做法 | 原因 |
|---|---|---|
| `mvn -o -B test -Dgroups=it` | `mvn -o -B -Pit test -Dtest=XxxIT` | 默认 `excludedGroups=it` 会把 `-Dgroups=it` 抵消成 0 用例且仍 `BUILD SUCCESS` |
| IT 直接跑 | 显式 `export DB_NAME=robot_it` | env 文件是 `robot_dev`，会被 `_it` 守卫拦下（守卫按设计生效） |
| 真实远端 10 类采集 | 代码侧 18 例单测 + 本机采集，远端记 `NOT-RUN` | 无受控目标机与已登记指纹 |
| `gh run download` 端到端 | 静态核对 workflow + 记 `NOT-RUN` | 本机无 `gh`、无授权 |
| 跨实例 A → B round-trip | 记 `NOT-RUN` | 无第二空实例 |
| `WorkspaceView.vue` 保持原生表格 | 改用 `DataTable` 壳 | 门禁第 6 步阻断；未扩白名单、未降门禁 |
| `VERSION` 由工作包自行 bump | 收口阶段按大师书面确认后落号 `4.2.0` | 计划 §2「版本落号」要求书面确认 |

## 6. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-10 | 初稿：19 包 WBS、部署顺序、门禁口径、七条偏差 |
