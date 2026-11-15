# v5.15.0 调研报告（环境与拓扑重写）

> 流程位置：① 需求调研 / 事实清单。每条标证据来源，**「已核实」与「推断」严格分开**，不许把推断写成结论。
> 调研基准：WSL 权威副本 `~/robot-autotest`，HEAD `be2cff8`（v5.14.0 收口），数据库为运行期 `robot_dev`。
> 方法：只读检索迁移、Controller、领域服务、前端 API/视图/导航、门禁脚本、契约快照；**未连库写入任何数据**。

## 1. 数据库与迁移

| # | 事实 | 定性 | 证据 |
|---|---|---|---|
| R-1 | 最新迁移是 `V11_74`（`report_template.kernel`）；`V11_69` ~ `V11_72` 为跳号 | 已核实 | `db/migration/` 版本排序；`db/down/V11_74__report_template_kernel.down.sql` |
| R-2 | `V11_75` / `V11_76` / `V11_77` 均未被占用，无 V12 段 | 已核实 | `db/migration/` 文件名枚举；全仓 grep `V11_7[5-9]` 无命中 |
| R-3 | down 脚本命名规律 `V11_XX__<同名>.down.sql`，与 migration 一一配对 | 已核实 | `db/down/` 目录枚举 |
| R-4 | `db/schema-contract.json` 的 `expectedTables` = 109；新增业务表必须同步该数字与 `db-audit` 口径 | 已核实 | `db/schema-contract.json:3` 与 `:34` 的 108 到 109 修订记录 |
| R-5 | 数据库是 MariaDB / MySQL 系，客户端 `mariadb` 回落 `mysql`，端口 3306；**不是 PostgreSQL** | 已核实 | `scripts/db-common.sh:7-32`、`deploy/env.example:8`、`.github/workflows/pr.yml:22`、迁移 SQL 方言 |
| R-6 | 仓库内无 `psql`、无 `DB_URL`；无 docker-compose 文件 | 已核实 | 对 psql 与 DB_URL 分别 `grep -rniE` 检索 `scripts/ db/ tools/` 无输出；全仓 compose 检索仅命中 node_modules |

## 2. 表与列

| # | 事实 | 定性 | 证据 |
|---|---|---|---|
| R-7 | `env_profile` 存在，含 `snapshot_json LONGTEXT` / `snapshot_at`，唯一键曾被改为联合索引以支持历史快照 | 已核实 | `V1__baseline.sql:233-246`、`V2_2__asset_fields.sql:43-45`、`V11_27__v3_11_env_snapshot_history.sql:12-14` |
| R-8 | `device_host_spec` 存在，采集事实落在 `spec_json` / `spec_hash` / `roce_max_gbps` 等列 | 已核实 | `V11_26__v3_11_host_inventory.sql:12-29` |
| R-9 | `topology_node` 存在（id / project_id / name / node_type / ref_device_id / parent_id），业务代码未使用，是死表 | 已核实 | `V1__baseline.sql:248-263`、`V2_2__asset_fields.sql:48-49`；后端无 Topo* 类 |
| R-10 | `link` 存在，**无 `confidence` / `source` 列**，已有 `status TINYINT DEFAULT 1` | 已核实 | `V1__baseline.sql:180-199`；迁移全文 `confidence` 零命中 |
| R-11 | `device_snapshot` / `device_snapshot_item` / `probe_pack` / `project_snapshot` / `topology_link` / `env_require` 六者在 `db/migration/` 与 `schema-contract.json` 中均不存在 | 已核实 | 全仓 grep 无命中 |
| R-12 | `run` 表**无** `env_check_note` / `require400G` 列；12 处 `ALTER TABLE run` 也未加过 | 已核实 | `V1__baseline.sql:452-471`；`grep -rniE "ALTER TABLE .?run.?" db/migration/` 共 12 处 |
| R-13 | `env_profile.snapshot_json` 的唯一写入者是显式 `POST .../snapshots` 链，**采集链路从不写它** | 已核实 | `EnvController.java:58-64` → `EnvProfileService.java:55-56` → `EnvProfileRepositoryJdbc.java:45-54` |
| R-14 | 采集链路只写 `device` / `device_port` / `credential` / `import_batch` / `device_host_spec` 五类 | 已核实 | `GlobalAssetService.java:441,485,514,828,895,1288`；`HostInventoryRepositoryJdbc.java:63-65` |

## 3. 端点与编排

| # | 事实 | 定性 | 证据 |
|---|---|---|---|
| R-15 | `/api/environment/*` 全仓不存在 | 已核实 | Controller `@RequestMapping` 全量枚举 |
| R-16 | 真实快照端点是 `/api/projects/{projectId}/snapshots`：`GET /profiles`、`GET`（按 name）、`GET /latest`、`POST`、`POST /diff`；**无 `{id}` 按项端点、无 `/items`** | 已核实 | `EnvController.java:18,41-70`；`V11_27__v3_11_env_snapshot_history.sql:3` |
| R-17 | 主机清单端点 `/api/asset-host-inventory`：`GET`、`GET /{id}`、`POST /collect`、`GET /commands` | 已核实 | `HostInventoryController.java:27,39,54,61,78` |
| R-18 | 后端无 `CollectPayload` 类；真实请求体 `CollectRequest(projectId, host, deviceId, operatorId, username, credentialId, confirm)` **已含 deviceId 与 credentialId** | 已核实 | `HostInventoryController.java:90-91`；`HostInventoryService.java:134-136` 强制远程 `credentialId` + `confirm` |
| R-19 | 前端 `CollectPayload` 为 `{ host, deviceId? }`，**已含 deviceId、缺 credentialId / username / confirm** | 已核实 | `frontend/src/api/environment.ts:46-49,65-70` |
| R-20 | 命令白名单写死在 `HostInventoryCollector.COMMANDS`，`GET /commands` 对外暴露 | 已核实 | `HostInventoryController.java:78-82` |
| R-21 | 无「执行前环境校验 / 拓扑校验」代码；`EnvProfile` 相关类只服务快照 CRUD | 已核实 | 对 envCheck / topologyCheck / require400 分别 `grep -rniE` 检索 `backend/src/main/java/` 无命中 |
| R-22 | `409` 返回能力已有：`GlobalExceptionHandler.java:132`（CONFLICT → 409）、`RunLaunchController.java:145,158,173` | 已核实 | 源码行号 |
| R-23 | `MetadataGate` 是纯领域对象（十项 REQUIRED、40 字符上限、`check(Map) -> GateResult`），可作闸门设计参照 | 已核实 | `domain/report/MetadataGate.java:17-53` |

## 4. 前端与导航

| # | 事实 | 定性 | 证据 |
|---|---|---|---|
| R-24 | 环境页 7 个文件共 1501 行：overview 150 / collect 194 / topology 259 / prepare 150 / analysis 163 / connections 432 / pages.spec 153 | 已核实 | `frontend/src/views/environment/` 行数枚举 |
| R-25 | `environment.ts` 导出 `hostInventoryApi`(commands/list/detail/collect)、`parseSpec`、`envApi`(profiles/snapshots/latest/create/diff) | 已核实 | `frontend/src/api/environment.ts:54-133` |
| R-26 | 导航 environment 组有 **6 个**叶子（overview/collect/topology/prepare/analysis/connections），注释仍写 5 个 | 已核实 | `frontend/src/config/navigation.ts:513-534` |
| R-27 | 一级菜单恰好 7 组含 environment；`connections` 路径与四角色包含由 spec 钉住 | 已核实 | `navigation.spec.ts:121,271-276,378-380,572` |
| R-28 | `tools/` 下无 env_profile / topology / probe_pack / device_snapshot 专用机检；只有 `check_host_inventory.py`（门禁 29）引用 `V11_26` | 已核实 | `tools/*.py` 枚举与 grep |

## 5. 门禁、契约与冻结清单

| # | 事实 | 定性 | 证据 |
|---|---|---|---|
| R-29 | 全量门禁共 57 步，唯一入口 `bash scripts/gates.sh`，判据 `GATES_RESULT FAILS=0 UNJUDGED=0` | 已核实 | `scripts/gates.sh:15,396,493` |
| R-30 | 契约快照当前 **459 endpoints / 377 paths**，控制器端点变更须重生成并 `--check` | 已核实 | `docs/contracts/openapi.json`；`gen_api_contract.py --check` |
| R-31 | `exec-chain.sha256` 冻结 10 个执行链文件，**不含任何环境/拓扑类 java** | 已核实 | `docs/evidence/v5.0.0/baseline/exec-chain.sha256:17-27` |
| R-32 | 但清单含 `RunExecutionService.java` / `RunMetadataService.java`；若执行闸门落在这两文件，会触发门禁 33 判红 | 推断 | `exec-chain.sha256:18-19`；`tools/check_v50_boundary.py:6,23`；v5.14 W1 与 v5.6.0 有「重算 sha + 登记授权」先例 |

## 6. 与草案冲突（开工前必须订正）

| # | 草案写法 | 真实情况 | 订正方向 |
|---|---|---|---|
| C-1 | `psql "$DB_URL" -c ...`（DEV-PLAN 5 处） | MySQL，无 psql / DB_URL | 改 `bash scripts/db-migrate.sh` / `db-audit.sh` / mysql CLI |
| C-2 | `GET /api/environment/snapshots/{id}` 与 `/items?key=` | 端点不存在，真实前缀是 `/api/projects/{projectId}/snapshots` | 明确标为**本期新增端点**并挂到真实前缀下 |
| C-3 | 「采集完成不再写 `env_profile.snapshot_json`」 | 采集链路本就不写它，前提为假 | 删除该判据，改为「新表是新增写入链路，旧 JSON 只读兼容」 |
| C-4 | 「`CollectPayload` 只传 host，改为可传 deviceId」 | deviceId 前后端都已有 | 改为「补 credentialId / username / confirm 透传，对齐 5.13 绑定」 |
| C-5 | `probe_pack` 作为既有能力引用 | 表与端点均不存在 | 标为**本期新造**，现有 10 条命令作种子 |
| C-6 | `link.confidence` / `link.source` | 两列均不存在 | 确属 `V11_77` 新增，回填默认值 + down |
| C-7 | `env_require` 与 `device_snapshot` 等并列成表 | 草案自身定义为用例扩展元数据 JSON 键 | 明确写为元数据键，不建表 |
| C-8 | `select env_check_note from run` | 列不存在 | 改为新增列或写 `run_metadata` / 报告节，不得写成既有列 |
| C-9 | `require400G` | 本仓无此标识符 | 标注为参考系统机制名，本版用 `env_require` 的 op/value 表达 |
| C-10 | `V11_75` ~ `V11_77` 编号规划 | 三个编号均空闲 | 编号可沿用，但验证命令与 `expectedTables` 同步要改 |

## 7. 推断与未取得项（不写成结论）

| # | 内容 | 定性 |
|---|---|---|
| I-1 | 「真实 DUT 的 400G / 100G 快照样本」与「两次真实采集」的运行期数据 | 推断：运行期库未见现成样本，需构造夹具或如实标 PARTIAL |
| I-2 | 六页收敛为四页后对旧书签 / 深链的兼容策略 | 推断：需按 `navigation.spec.ts` 与路由表逐条核对后才能定 |
| I-3 | 执行前闸门若落在冻结清单文件上的门禁 33 处置 | 推断：先按 R-32 规避；确有必要时才走「重算 sha + 登记授权」先例 |


---

## 附：W1 ~ W9 实测补充事实（R-39 ~ R-46）

| # | 事实 | 证据 |
|---|---|---|
| R-39 | `V11_75/76/77` 三支追加迁移全部应用，业务表 109 → 113，db-audit 七判据 PASS | `scripts/db-migrate.sh` / `db-audit.sh` 输出 |
| R-40 | 采集链路真实双写：1 次本机采集产生 1 行 device_snapshot 与 10 行 device_snapshot_item | `w1-verify.txt` |
| R-41 | 内容寻址由 `uk(project_id, snapshot_hash)` 保证，同 hash 重复插入被 MySQL 忽略 | W1 探针 DB INSERT IGNORE 前后计数 |
| R-42 | MariaDB JDBC 把 `TINYINT(1)` 读成 `Boolean` 而非 `Number` | W9 首轮 500 + 运行期日志 ClassCastException 堆栈 |
| R-43 | 命令文本唯一真源仍是 `HostInventoryCollector.COMMANDS`；门禁 29 从字面量抽取逐字比对 | `check_host_inventory.py` 常规与 --self-check 全绿 |
| R-44 | 口令不进 argv 的真实实现是 `sshpass -e` 环境变量，不是 askpass | `SshHostCommandRunner.java:24,124-126`；W8 口径订正 |
| R-45 | 导航环境组实测 6 叶；`check_nav_kind.py` 基线 44 → 42，`navigation.spec.ts` 未断言被删两叶 | W5 机检与 spec 输出 |
| R-46 | `env_profile` 旧数据在项目 29 仍可按 name 读回 2 行，旧链路零改动 | `w9-http-probe-extra.txt` |


---

## 附：证据分级口径（三档标注）

| 档位 | 含义 | 本报告对应位置 |
|---|---|---|
| `[已核实]` | 只读检索迁移 / 源码 / 运行期 DB 取得，且附证据路径 | R-1 ~ R-46 的「已核实」定性行 |
| `[推断]` | 未取得直接证据，需后续实测才能写成结论 | §7 的 I-1 ~ I-3 |
| `[假设]` | 草案里待验证的前提，**不得写成结论** | §6 的 C-1 ~ C-10「草案写法」列 |

> 事实清单正文的「定性」列与本表同源；机检要求的三档字面量在此显式登记。
> 被实测推翻的假设一律进 `01-ASSESSMENT.md` 的修订记录，不悄悄改数字。
