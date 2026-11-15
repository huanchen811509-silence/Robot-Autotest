# v5.16.0 调研报告（运维管理）

> 流程位置：① 需求调研 / 事实清单。证据分级三档：`[已核实]`（有证据路径）、
> `[推断]`（未取得直接证据）、`[假设]`（草案待验证前提，不得写成结论）。
> 基准：WSL 权威副本 `~/robot-autotest`，HEAD `564bca7`（v5.15.0 收口）；
> 只读检索迁移、脚本、Controller、前端导航、门禁工具与契约快照，**未连库写入**。

## 1. 数据库与迁移

| # | 事实 | 定性 | 证据 |
|---|---|---|---|
| R-1 | 最新迁移为 `V11_77`（`link` 两列 + `run.env_check_note`）；`V11_69~72` 为跳号 | `[已核实]` | `db/migration/` 版本枚举 |
| R-2 | `V11_78` 空闲，无 V12 段 | `[已核实]` | `db/migration/` 文件名枚举 |
| R-3 | `db/schema-contract.json` 的 `expectedTables` = 113（v5.15 后）；新增业务表须同步 | `[已核实]` | `db/schema-contract.json:3` 与 notes |
| R-4 | `db/down/` 命名为 `<up 同号>__<同名>.down.sql` | `[已核实]` | `db/down/` 目录枚举；`V11_28__v4_1_workspace_job.sql:13` |
| R-5 | 数据库是 MySQL / MariaDB，`db_exec` 走 mysql 或 mariadb CLI，无 `psql` 与 `DB_URL` | `[已核实]` | `scripts/db-common.sh:9,17-32`；全仓 grep 无 `psql` |
| R-6 | `ops_task` 表 / Java 类 / 端点在全仓**均不存在**，只在 4 份文档里被提及 | `[已核实]` | 对 ops_task 与 OpsTask 分别做全仓 grep，仅命中 4 份文档 |

## 2. 任务化框架与既有异步实现

| # | 事实 | 定性 | 证据 |
|---|---|---|---|
| R-7 | `workspace_job`（`V11_28`）有 `active_lock` 生成列 + 唯一索引，实现同项目同方向互斥 | `[已核实]` | `db/migration/V11_28__v4_1_workspace_job.sql:15-46` |
| R-8 | `workspace_job.status` 仅 PENDING / RUNNING / SUCCESS / FAILED，**无 ABORTED** | `[已核实]` | `V11_28__v4_1_workspace_job.sql:22-23` |
| R-9 | `ExecutionOrphanSweeper` 用 `@Scheduled` 60s 清孤儿进程树，但不写任务状态 | `[已核实]` | `application/execution/ExecutionOrphanSweeper.java:28-60` |
| R-10 | 阶段进度是内存登记（`RunProgressRegistry`），WebSocket 只推 run 日志、不推进度 | `[已核实]` | `application/execution/RunProgressRegistry.java:16`；`api/execution/ExecutionWebSocketConfig.java:10-20` |
| R-11 | `progress.complete` 属 pytest 运行进度，与本版运维任务无关 | `[已核实]` | `api/kernel/PytestNicRunController.java:258,356,614` |
| R-12 | 既有任务端点 `/api/projects/{projectId}/workspace/jobs` 可作交互参照 | `[已核实]` | `api/workspace/WorkspaceController.java:68-79` |

## 3. 运维脚本与护栏

| # | 事实 | 定性 | 证据 |
|---|---|---|---|
| R-13 | 真实更新脚本是 `scripts/ops/update.sh`（526 行），**不是** `scripts/update.sh` | `[已核实]` | `wc -l scripts/ops/update.sh`；`cat scripts/update.sh` 报不存在 |
| R-14 | 更新十步串 + `trap on_err ERR` 自动回滚；`--apply` 前强制 DB 全量备份 | `[已核实]` | `scripts/ops/update.sh:443-523`、`:98-106`、`:295` |
| R-15 | 真实参数：`--package/--prefix/--apply/--dry-run/--yes/--force/--restore-db`；默认 dry-run | `[已核实]` | `scripts/ops/update.sh:38-52` |
| R-16 | `scripts/ops/` 共 13 项脚本；备份 `backup.sh`(66)、演练 `backup-drill.sh`(146)、清理 `purge-demo.sh`(85) | `[已核实]` | `scripts/ops/` 枚举与 `wc -l` |
| R-17 | `backup.sh` 输出 `sha256=`，但无 manifest 文件、无校验三态、无界面清单 | `[已核实]` | `scripts/ops/backup.sh:55,62` |
| R-18 | `_it` 库名硬守卫只在 `db-reset.sh:7-10`（重置脚本）；`backup-drill.sh` 用 `DRILL_TARGET_DB`，无后缀校验 | `[已核实]` | `scripts/db-reset.sh:7-10`；`scripts/backup-drill.sh:30-31,55-70` |
| R-19 | `PURGE_DEMO_CONFIRM=YES` 是「默认 dry-run + 二次确认」双护栏；只认 `is_demo=1` | `[已核实]` | `scripts/ops/purge-demo.sh:8-12,62-72` |
| R-20 | 门禁 31 `check_update_mechanism.py` 有 29 条静态判据；门禁 32 `check_workspace_bundle.py` 有 7 大类 | `[已核实]` | `tools/check_update_mechanism.py:30-72`；`tools/check_workspace_bundle.py:5-12` |
| R-21 | 门禁 31/32 的自证参数是 `--self-check`；`check_nav_kind.py` 等用 `--self-test` | `[已核实]` | 各脚本 argparse 定义 |
| R-22 | `check_ops_guardrails.py` / `check_ops_audit.py` 均不存在 | `[已核实]` | `tools/` 枚举 |

## 4. 端点、前端与菜单

| # | 事实 | 定性 | 证据 |
|---|---|---|---|
| R-23 | `/api/ops/*` 全仓不存在；已有 `/api/admin/ops/summary`、`/ops/backup`、`/ops/releases` 与 `/api/admin/archive/*` | `[已核实]` | `api/admin/OpsController.java:33,72-178`；`api/admin/ArchiveController.java:20-74` |
| R-24 | `doctor` 是脚本 `scripts/ops/doctor.sh`（163 行，`--ci`），后端无 doctor 端点 | `[已核实]` | `api/` 全量 `@RequestMapping` 枚举 |
| R-25 | `confirmReal` + ADR-0013 在 `PytestNicRunController`（缺则 400）；`RunLaunchController` 的 409 是 DIGEST_MISMATCH / UNCONFIRMED，两者语义不同 | `[已核实]` | `api/kernel/PytestNicRunController.java:163-210,478-486`；`api/execution/RunLaunchController.java:129-180` |
| R-26 | `maintenance` 组有 4 叶；运维控制台页 `/ops/health` 等不存在，只有 `/admin/ops` → `OpsCenterView.vue`(282) | `[已核实]` | `frontend/src/config/navigation.ts:534-550`；`frontend/src/router/index.ts:207-213` |
| R-27 | 项目树 `NAVIGATION` 已 **7 组**（`PROJECT_EXPECTED=7` + spec `toHaveLength(7)`）；`GLOBAL_EXPECTED=6` 属另一棵树 | `[已核实]` | `tools/check_navigation.py:50-51`；`navigation.spec.ts:118-121` |
| R-28 | 门禁共 57 步；冻结 `exec-chain.sha256` 10 个文件全是 execution/report 类，**不含运维类 java** | `[已核实]` | `scripts/gates.sh:15,493`；`docs/evidence/v5.0.0/baseline/exec-chain.sha256:17-27` |
| R-29 | 契约当前 **471 endpoints / 389 paths**；`VERSION` = 5.7.0；双远程 `origin/main` 与 `github/main` 均在 `3e14bea` | `[已核实]` | `gen_api_contract.py --check`；`git rev-parse`；`git ls-remote` 同口径 |

## 5. 与草案冲突（开工前必须订正）

| # | 草案写法 | 真实情况 | 订正方向 |
|---|---|---|---|
| C-1 | `psql "$DB_URL" -c ...` 与 `$IT_DB_URL` | MySQL / MariaDB，无 psql / DB_URL | 改 `mysql -e` / `db_exec` / `db-migrate.sh`，env 从 `~/.config/robot-autotest/env` 读 |
| C-2 | `ops_task` 表结构被写成既有 | 表 / 类 / 端点全无 | 标为**本期新造**，`V11_78` 追加，参照 `workspace_job` 的 active_lock 与审计四列 |
| C-3 | `GET /api/ops/backups` 等 8 个端点被写成接线 | `/api/ops/*` 全无 | 明确为**本期新增端点**，契约同步重生成 |
| C-4 | `scripts/update.sh 526 行` | 路径不存在，真实是 `scripts/ops/update.sh` | 草案内部路径统一 |
| C-5 | D-7「6 组 → 7 组」改 `GLOBAL_EXPECTED` | 项目树已 7 组，改的是 PROJECT 侧；且总纲判断列已写「独立一级」但草案标未定 | 本版按建议**不新增一级菜单**，控制台挂既有 `/admin/ops`，避免两棵树常量连锁改动 |
| C-6 | 「非 `_it` 库恢复被拒」写成熟练复用 | 只有 `db-reset.sh` 有守卫，drill 无 | 界面端点必须**显式新增**库名守卫，不能只写复用 |
| C-7 | 「无 `--apply` 写入行数=0」「任务被杀判 ABORTED」写成既有能力 | dry-run 脚本已有但无统一机检；ABORTED 状态根本不存在 | 新增 `check_ops_guardrails.py` + ABORTED 状态与超时回收 |
| C-8 | 草案 W66-18/19 写 `--self-test` | 门禁 31/32 用 `--self-check` | 新机检参数按同族口径写 `--self-check` |
| C-9 | `ArchiveCenterView.vue(240)` / `ProjectSyncView.vue 385` | 实测 291 / 387 | 行号引用按实测订正 |

## 6. 推断与未取得项（不写成结论）

| # | 内容 | 定性 |
|---|---|---|
| I-1 | 群晖同步的真实通道与凭据是否可用 | `[推断]`：E 侧无 ssh 密钥，需收口时实测；不写成「已具备」 |
| I-2 | 真实升级包与真机升级演练样本 | `[推断]`：本轮不构造破坏性样本，如实标 PARTIAL |
| I-3 | 运维控制台是否必须独立一级菜单 | `[假设]`：D-7 文档口径不一致，本版按「不新增一级」建议施工并留痕 |

## 8. W7 实测补充事实（R-32 ~ R-39）

| 编号 | 事实 | 判定 | 证据 |
|---|---|---|---|
| R-32 | 部署脚本从 `backend/` 起进程，`user.dir` 因此以 `/backend` 结尾 | 已核实 | 首轮探针 `installRoot=/home/evergreen/robot-autotest/backend`，`version=None` |
| R-33 | `VERSION` / `backups/` / `releases/` / `logs/` 都在仓库根，不在 `backend/` | 已核实 | `ls ~/robot-autotest` 与 `scripts/ops/common.sh:43` 的 `BACKUP_DIR=$APP_HOME/backups` |
| R-34 | `backup.sh` 默认 dry-run，且 dry-run 时不创建任何文件 | 已核实 | `bash scripts/ops/backup.sh --dry-run` 输出「未创建任何文件」，`backups/*.gz` 数 0→0 |
| R-35 | `backup.sh` 原本不产 manifest、不落 `.sha256`；清单与三态是本期新增 | 已核实 | 改动前脚本尾部只有 `BACKUP PASS` / `WORKSPACE PASS` 两行输出 |
| R-36 | MariaDB 驱动下 `COUNT(*)` 等聚合返回 `Long`，`CAST(... AS SIGNED)` 可稳定取整 | 已核实 | 健康端点 `sizeMb=38` 与 `tables=115` 均与库内实测一致 |
| R-37 | `robot_it` 库存在但与 `robot_dev` 行数不一致（10 张关键表全部有差） | 已核实 | 比对探针 `compared=10 consistent=False mismatched=10` |
| R-38 | 世界 `ops_task` 表在本版新造，`flyway_schema_history` 110 条、迁移文件 111 个 → 预检稳定报 1 条 `MIGRATION` 提示 | 已核实 | 预检探针 `kinds=['MIGRATION']`；`SELECT COUNT(*) FROM flyway_schema_history WHERE success=1` |
| R-39 | 交易式动作（演练 / 比对）若只在服务层落库，被守卫拒绝时不会留行 | 已核实 | 首轮 `before=0 after=0`；修复后 `before=5 after=6` |

### 8.1 三档标注复核

| 档位 | 条目 | 说明 |
|---|---|---|
| 已核实 | R-32 ~ R-39 | 均有命令输出或接口响应 |
| 推断 | 演练库 `robot_it` 的差集来自「未同步」而非「比对口径错」 | 未逐表核 SQL 语义，仅凭行数差判断 |
| 假设 | 真机备份 `--apply` 会成功 | 本版只跑 dry-run，未对生产库做 `mysqldump` |
