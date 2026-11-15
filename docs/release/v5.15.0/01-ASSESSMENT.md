# v5.15.0 评估报告（环境与拓扑重写）

> 流程位置：② 可行性 / 方案评估。**无评估报告不许开 PRD**；本报告在 W0 补齐，晚于三份草案，
> 因此以「假设复核 + 方案重定」形式回写，被实测推翻的假设逐条留修订记录，不悄悄改数字。
> 依据：`00-RESEARCH.md` 的 R-1 ~ R-32 事实清单；基准 HEAD `be2cff8`。

## 1. 结论

**可以做，但不能按草案原文施工。** 草案把一批「本期新造」的能力写成了「既有能力接线」，
并把数据库误判为 PostgreSQL。按 R-15 / R-16 / R-18 / R-19，本期真实性质是：
**在既有 `/api/projects/{projectId}/snapshots` 与 `/api/asset-host-inventory` 两条真实链路上，
新增三张表、若干按项端点、探针包配置化、拓扑两列、执行前闸门与页面收敛**；
不是「把采集从 JSON 迁到对象表」这种替换式改造。

可行性判断：三处编号空闲（R-2）、旧端点可保持只读兼容、冻结清单不含环境类文件（R-31），
因此增量开发不会破坏 v5.0 执行链，也不会触碰已交付的 5.13 采集绑定事实。**方案可行，建议开工。**

## 2. 被实测推翻的假设（修订记录）

| # | 被推翻的假设 | 实测 | 处置 |
|---|---|---|---|
| A-1 | 数据库是 PostgreSQL，验证用 `psql "$DB_URL"` | MySQL / MariaDB，`db_exec` 用 mysql CLI（R-5/R-6） | 全部验证命令改为 `db-migrate.sh` / `db-audit.sh` / mysql，不引入 psql |
| A-2 | 环境快照端点在 `/api/environment/*` | 真实在 `/api/projects/{projectId}/snapshots`（R-15/R-16） | 新增端点挂真实前缀，契约同步重生成 |
| A-3 | 采集链路当前写 `env_profile.snapshot_json`，本期要「不再写」 | 采集只写 `device_host_spec`（R-13/R-14） | 该判据整体删除；改为「新增链路 + 旧数据只读兼容」 |
| A-4 | `CollectPayload` 只传 host，需要补 deviceId | 前后端都已有 deviceId（R-18/R-19） | 真实缺口改为凭证相关入参透传 |
| A-5 | `probe_pack` 是既有配置项 | 表 / 端点 / 代码全不存在（R-11/R-20） | 重新定性为「本期新造」，`COMMANDS` 十条作种子 |
| A-6 | `link.confidence` / `link.source` 待「回填」 | 两列根本不存在（R-10） | 定性为 `V11_77` 新增列 + 默认值回填，配 down |
| A-7 | `run.env_check_note` 可直接 SELECT 留痕 | 列不存在（R-12） | 改为新增列或写 `run_metadata`，不得冒充既有列 |
| A-8 | `topology_node` 只需「启用」 | 表存在但零业务引用（R-9） | 定性为「启用为规划源」需新增读写链路，不是改开关 |
| A-9 | 六页收敛纯前端删除即可 | 导航 6 叶且 `navigation.spec.ts` 四处钉住（R-26/R-27） | 导航、路由、spec、i18n、门禁常量必须同版改，旧深链保留重定向 |
| A-10 | 执行前闸门可挂在既有校验链上 | 无环境校验代码；冻结清单含执行链文件（R-21/R-32） | 闸门独立成领域服务 + 独立端点，避开 10 个冻结文件；确需触碰才走重算 sha 先例 |

## 3. 方案重定（按真实代码分层）

| 层 | 做法 | 不做什么 |
|---|---|---|
| 数据 | `V11_75` 快照两表（内容寻址 hash + 按项）、`V11_76` 探针包两表、`V11_77` `link` 两列；各配 down；同步 `schema-contract.json` | 不删 `env_profile`、不改 `env_profile.snapshot_json` 的写入语义、不重命名任何既有表 |
| 后端 | 新增按项读写端点挂 `/api/projects/{projectId}/snapshots`；新 `EnvironmentGate` 领域对象 + 独立 `POST .../env-check`；采集侧在新仓储双写 | 不新建 `/api/environment/*` 平行前缀；不改 `RunExecutionService` 等冻结文件 |
| 前端 | `environment.ts` 补凭证入参；对比页改按项；导航 6 叶收敛为 4 叶并保留旧路径重定向；i18n 同版 | 不删旧页文件前先确认无深链引用；不硬编码中文 |
| 证据 | 真实 HTTP + DB 探针、契约 `--check`、`db-audit`、全量门禁 57 步 | 不拿单测绿 / 构建绿冒充真实链路放行 |

## 4. 一票否决项（不可降级）

| # | 判据 | 形态 |
|---|---|---|
| V-1 | 用例声明 400G 而快照实测 100G → **409 且点名 key / 期望 / 实际 / 快照 id** | 真实 HTTP + DB |
| V-2 | 无声明的执行路径**不拦截**，但必须留下可查询留痕 | 真实 HTTP + DB |
| V-3 | 采集只填空不覆盖已有事实；新增双写不得覆盖 `device_host_spec` 既有行 | DB 前后对比 |
| V-4 | 三支迁移只追加、各配 down，旧 `env_profile` 快照仍可按 name 读 | 迁移 + DB |

## 5. 风险与应对

| # | 风险 | 触发点 | 应对 |
|---|---|---|---|
| K-1 | 新增 4 张业务表使 `expectedTables` 109 漂移 | 门禁 12-db_audit | 同步 `db/schema-contract.json` 并写明修订依据，不放宽判据 |
| K-2 | 新增端点使契约快照失配 | 门禁 20-api_contract | `gen_api_contract.py` 重生成 + `--check`，禁止手改快照 |
| K-3 | 导航叶数变化 | 门禁 44-nav_kind / `navigation.spec.ts` | 同版改期望值并给旧路径重定向，留走查清单 |
| K-4 | 冻结执行链被误改 | 门禁 33-v50_boundary | 闸门独立文件；若确需改，按 v5.6.0 / v5.14 W1 先例重算 sha 并登记授权 |
| K-5 | 新增 .vue 用原生 table、硬编码色值、i18n 漏译 | 门禁 06/15/10 | 复用 `DataTable` 壳、语义色令牌、i18n 双语种同版 |
| K-6 | 前端 bundle 超预算 | 门禁 21 | 优先按路由懒加载拆包；确需重新定档必须写明真实增长且不动另两条独立判据 |
| K-7 | 真视口截图 / 真实 DUT 400G 样本未取得 | 证据形态 | 与 v5.13 / v5.14 同口径如实标 PARTIAL，不冒充达成 |

## 6. 授权与推进口径

按大师既定授权「不要停下来问我、按建议自动推进、全部完成再找我确认」，
本版按 W1 ~ W9 逐波开发与验证，每波留证据；版本内只做开发 / 验证 / 文档归档 / 门禁收口，
**不 bump VERSION、不 tag、不 push、不同步公司群晖**，统一留到 5.16 后由大师拍板。
最终人工复核时请大师逐项确认 `05-TEST-REPORT.md` §5 的降级登记。


---

## 附：W1 ~ W9 假设复核与修订记录（A-11 ~ A-16）

| # | 被推翻 / 新增的假设 | 实测 | 处置 |
|---|---|---|---|
| A-11 | 快照两表可与 device_host_spec 同口径 append-only | 成立，db-audit 七判据 PASS | 登记进 schema-contract 的 appendOnlyTables + createdAtRequired |
| A-12 | 探针包应把 10 条命令文本存进配置表 | 门禁 29 从 COMMANDS 字面量抽取并与真实证据逐字比对，存两份必然漂移 | 表里只存 command_key 与 required，运行时与 COMMANDS 合并 |
| A-13 | `TINYINT(1)` 读出来是数字 | MariaDB JDBC 返回 Boolean，强转 Number 导致两个端点 500 | 抽 `requiredOf(Object)` 兼容三种形态 + 2 例单测；首轮判红留痕 |
| A-14 | 执行前闸门必须改 RunExecutionService 才能生效 | 冻结清单含该文件，改动会触发门禁 33；独立端点同样能给出 409 判据 | 闸门独立成领域服务 + 独立端点，冻结链零改动 |
| A-15 | 六页收敛只删菜单即可 | `check_nav_kind.py` 有叶数基线，旧深链要有 redirect | 同版改基线 44 → 42、路由两条 redirect、spec 4 例 |
| A-16 | 「口令不进 argv」靠 askpass | 真实实现是 `sshpass -e` 环境变量 | W8 核对关键字订正，如实留痕；机检 9/9 通过，不判缺陷 |


---

## 附：W0 前置条件清单（开工前逐条确认）

| # | 前置条件 | 确认读数 |
|---|---|---|
| 1 | 双副本纪律：WSL 为权威副本，E 盘为文档工作区，同步方向只可 E 改后同步 WSL | HEAD `be2cff8`，代码改动全在 WSL |
| 2 | 迁移编号 `V11_75` / `76` / `77` 空闲，各配 `.down.sql` | W0 只读枚举与 `flyway_schema_history` |
| 3 | 门禁唯一入口 `bash scripts/gates.sh`，判据 `GATES_RESULT FAILS=0 UNJUDGED=0` | `scripts/gates.sh` 57 步 |
| 4 | 冻结执行链 10 个 java 文件不含环境 / 拓扑类，闸门可独立成端点 | `exec-chain.sha256:17-27` |
| 5 | 真实 HTTP + DB 探针可跑：后端已部署、夹具账号口令只从 env 读 | `qa-e2e-admin` 登录 200 |
| 6 | 数据库是 MySQL / MariaDB，验证命令用 mysql CLI 与 `db-audit.sh` | `scripts/db-common.sh:19-32` |
| 7 | 大师既定授权：分阶段自动推进、全部完成再人工复核；不中途询问 | 会话指令 |
| 8 | 版本内不 bump `VERSION`、不 tag、不 push、不同步群晖 | 统一留到 5.16 后由大师拍板 |

> 前置条件在 W0 逐条确认后才进入 W1 编码；未满足项不得开工（本版 8 条全部满足）。
