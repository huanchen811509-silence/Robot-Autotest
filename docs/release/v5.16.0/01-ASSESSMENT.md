# v5.16.0 评估报告（运维管理）

> 流程位置：② 可行性 / 方案评估。**无评估报告不许开 PRD**；本报告在 W0 补齐，
> 以「假设复核 + 方案重定」形式回写，被实测推翻的假设逐条留修订记录。
> 依据：`00-RESEARCH.md` 的 R-1 ~ R-29；基准 HEAD `564bca7`。

## 1. 结论

**可以做，但草案把「本期新造」写成了「既有能力只需取证」。** 真实性质是：
在既有运维 CLI（13 个脚本、526 行可回滚更新流程）之上，**新增一张 `ops_task` 表、
一套任务状态机与只读运维端点、四条硬护栏的统一机检**，并把控制台挂到既有 `/admin/ops`，
而不是新增一级菜单、更不是执行真实升级。

可行性判断：`V11_78` 空闲（R-2）、冻结清单不含运维类 java（R-28）、
项目树已是 7 组无需再改常量（R-27），增量开发不会破坏 v5.0 执行链。**方案可行，建议开工。**

## 2. 不做什么（明确范围外）

| # | 不做 | 理由 |
|---|---|---|
| 1 | 不执行真实升级、不覆盖运行中的包 | L3 受控升级默认不做，只生成可复制命令（PRD FR-11 P2） |
| 2 | 不把安装 / 卸载搬上界面 | L4 留 CLI，界面只读展示命令与护栏提示 |
| 3 | 不新增一级菜单（D-7 按建议取「不新增」） | 项目树已 7 组，改动会连锁 `PROJECT_EXPECTED`、`navigation.spec.ts` 与叶数基线 |
| 4 | 不改 `gates.sh` 的 57 步结构 | 该脚本有「删步骤判红」的负向自证，新增护栏机检做成独立工具更稳 |
| 5 | 不跑破坏性的恢复演练到真实库 | 只允许 `_it` 演练库，且默认 dry-run |
| 6 | 不 bump `VERSION`、不 tag、不 push、不同步群晖 | 统一收口留到 5.16 后由大师拍板 |

## 3. 前置条件清单（开工前逐条确认）

| # | 前置条件 | 确认读数 |
|---|---|---|
| 1 | WSL 为权威副本，代码改动只在 WSL | HEAD `564bca7`，工作树干净 |
| 2 | `V11_78` 空闲且 down 成对 | 只读枚举通过 |
| 3 | 门禁唯一入口与判据 | `bash scripts/gates.sh`，`GATES_RESULT FAILS=0 UNJUDGED=0` |
| 4 | 冻结执行链不含运维类 java | `exec-chain.sha256:17-27` 10 文件 |
| 5 | 真实 HTTP + DB 探针可跑，口令只从 env 读 | `qa-e2e-admin` 登录 200 |
| 6 | 数据库是 MySQL / MariaDB | `scripts/db-common.sh:19-32` |
| 7 | 大师既定授权：分阶段自动推进、全部完成再人工复核 | 会话指令 |
| 8 | 统一收口动作（发版 / 双远程 / 群晖）留到版本完成后 | 本版不擅自 bump / tag |

## 4. 被实测推翻的假设（修订记录）

| # | 被推翻的假设 | 实测 | 处置 |
|---|---|---|---|
| A-1 | 数据库是 PostgreSQL，验证用 `psql "$DB_URL"` | MySQL / MariaDB，无 psql / DB_URL（R-5） | 全部验证命令改为 mysql CLI / `db_exec` / `db-migrate.sh` |
| A-2 | `ops_task` 表与端点已存在 | 全仓无表无类无端点（R-6） | 定性为本期新造，参照 `workspace_job` 的 `active_lock` |
| A-3 | `/api/ops/*` 只需接线 | 全无（R-23） | 明确为新增端点，契约重生成 |
| A-4 | 更新脚本在 `scripts/update.sh` | 真实是 `scripts/ops/update.sh`（R-13） | 草案内部路径统一 |
| A-5 | D-7 改 `GLOBAL_EXPECTED` 6→7 | 项目树已 7 组，混淆两棵树（R-27） | 本版不新增一级菜单，控制台挂 `/admin/ops` |
| A-6 | `_it` 库名守卫可直接复用 | 只有 `db-reset.sh` 有，`backup-drill.sh` 无（R-18） | 恢复演练端点显式新增守卫 |
| A-7 | ABORTED 是既有状态 | `workspace_job` 无此状态，Sweeper 不写状态（R-8/R-9） | 新增状态值 + 超时回收对账 |
| A-8 | 四条护栏已有统一机检 | `check_ops_guardrails.py` 不存在（R-22） | 新增独立机检，参数用 `--self-check` |
| A-9 | `confirmReal` 挂在 `RunLaunchController` | 实为 `PytestNicRunController` 的 400 闸；RunLaunch 的 409 是摘要不一致（R-25） | 草案口径订正，不混写两种语义 |

## 5. 方案重定（按真实代码分层）

| 层 | 做法 | 不做什么 |
|---|---|---|
| 数据 | `V11_78` 一张 `ops_task`（含 `active_lock` 互斥、ABORTED、超时锚点与审计四列）+ down；`schema-contract.json` 114 | 不改 `workspace_job`、不动冻结执行链表 |
| 后端 | `OpsTaskService` 状态机 + `OpsTaskSweeper` 超时对账；`/api/ops/*` 只读健康、备份清单、任务、恢复演练守卫、升级预检、护栏读数 | 不执行真实升级、不跑非 `_it` 库恢复、不新增轮询 |
| 前端 | `frontend/src/api/ops.ts` 契约化 + 既有 `OpsCenterView.vue` 最小接线 | 不新增一级菜单、不新建四个空壳页 |
| 机检 | `tools/check_ops_guardrails.py`（四条硬护栏 + `--self-check` 负向自证） | 不改 `gates.sh` 57 步结构 |
| 证据 | 真实 HTTP + DB 探针、契约 `--check`、`db-audit`、全量门禁 | 不拿单测绿 / 构建绿冒充真实链路放行 |

## 6. 一票否决项（不可降级）

| # | 判据 | 形态 |
|---|---|---|
| V-1 | 非 `_it` 库恢复演练必须被拒且点名库名 | 真实 HTTP + DB |
| V-2 | 无 `--apply` 等价的 dry-run 路径，任务写入行数 = 0 | DB 前后计数 |
| V-3 | 超时 RUNNING 任务经对账必须落 `ABORTED` | 真实 HTTP + DB |
| V-4 | 三支迁移只追加、各配 down；`VERSION` 与 tag 不动 | 迁移 + git |

## 7. 风险与应对

| # | 风险 | 应对 |
|---|---|---|
| K-1 | 新增 1 张表使 `expectedTables` 113 漂移 | 同步 `db/schema-contract.json` 与 notes，不放宽判据 |
| K-2 | 新增端点使契约快照失配 | `gen_api_contract.py` 重生成 + `--check` |
| K-3 | 新建机检被误写进 `gates.sh` 破坏负向自证 | 独立工具脚本，文档给验证命令 |
| K-4 | 异步线程漏 `TenantContext.runAs` 或 `progress.complete` 早于入库 | 本版任务状态机走同步分阶段写入，异步执行如实标缺口 |
| K-5 | 真视口截图 / 真机升级 / 群晖通道未取得 | 与 v5.13 ~ v5.15 同口径标 PARTIAL，不冒充达成 |

## 8. 修订记录与授权口径

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | W0 首版：补调研与评估，9 条草案冲突、9 条假设复核逐条订正 | 只读调研事实清单 |

按大师既定授权「不要停下来问我、按建议自动推进、全部完成再找我确认」，本版按 W1 ~ W8
逐波开发与验证；版本内只做开发 / 验证 / 文档归档 / 门禁收口。最终人工复核时请大师逐项确认
`05-TEST-REPORT.md` §5 的降级登记与统一收口口径。

## 7. W7 被实测推翻的假设（A-24 ~ A-27）

| 编号 | 原假设 | 实测结论 | 处置 |
|---|---|---|---|
| A-24 | 把 `user.dir` 当安装根即可读到 `VERSION` 与 `backups/` | 部署从 `backend/` 起进程，两处都读空，且看起来像「真的没备份」 | 改 `appHome()`：`user.dir` 以 `backend` 结尾且父目录有 `VERSION` 时上跳 |
| A-25 | dry-run 不写库＝审计也零行 | 与 P4「全量审计」冲突：查不到「谁预演过备份」 | dry-run 落一行审计（只到 PRECHECK，标 `DRY_RUN`），业务仍零写入 |
| A-26 | 演练与比对是只读动作，不必落 `ops_task` | 审计机检直接判红两条写端点 | 两者都落 `ops_task`；被守卫拒绝时不落行，避免脏审计 |
| A-27 | 备份清单可复用 `var/backups` | 真实目录是 `<root>/backups`（`scripts/ops/common.sh:43`） | 改用 `<root>/backups`；目录不存在返回空清单交前端空态 |

### 7.1 前置条件（本版成立的前提）

| 前置条件 | 现状 | 影响 |
|---|---|---|
| MySQL 8.0 / mariadb 客户端可用 | 成立（`mysql --version` 可用） | 库体检、行数比对可跑 |
| `robot_it` 演练库存在 | 成立 | 比对有真实目标库 |
| 部署脚本可重建 jar + 前端 | 成立（`PASS=4 FAIL=0`） | 新端点能生效 |
| 交付包 `dist/MANIFEST.json` 存在 | **不成立**（本地无 dist） | 版本一致性只能判「不可比」，不冒充一致 |
| 真机备份 `--apply` 可跑 | 未验证 | 备份产物与 manifest 只做了 dry-run 与手工样本 |
