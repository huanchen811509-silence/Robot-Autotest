# AIDC自动化测试 · AIDC AutoTest

面向 GPU、智能网卡与基础设施的一体化自动化测试平台：把「设备台账 → 用例编写 → 并发执行 → 报告交付 → 定时 / CI 自动化」收在同一个系统里，并用 metadata 门禁保证每一份对外报告都能追溯。

> 产品对外名称是 **AIDC自动化测试 / AIDC AutoTest**。
> 仓库名 `Robot-Autotest`、Maven `artifactId`、npm `name`、Java 包名与数据库名是**工程标识，保持不变**（改名只作用于显示层）。

---

## 当前状态

| 项 | 值 | 说明 |
|---|---|---|
| 版本 | `4.9.2` | 唯一真源是根 [`VERSION`](VERSION)；可用 `APP_VERSION` 运行期覆盖 |
| 门禁 | 32 步 | `bash scripts/gates.sh`；第 07 步为发布文档机检，第 30 步为交付包机检 |
| 数据库 | 98 张业务表 | Flyway 迁移至 `V11_37`（`V11_30`–`V11_37` 八组均附 `db/down/` 回滚），`scripts/db-audit.sh` 体检 |
| API 契约 | 335 endpoints / 271 paths | `docs/contracts/openapi.json` |
| 前端 | 2072 i18n 键（中英一致） | `tools/check_i18n.py`；硬编码中文 0 处 |
| 交付物 | linux-x64 tarball | GitHub Actions `delivery.yml` 产出，见 [交付指南](docs/DELIVERY-GUIDE.md) |

**口径**：`4.9.2` 是**内部交付版本**，已按 [发布说明](docs/release/v4.9.2/RELEASE-NOTES.md) 部署到统一入口 `:8001`；
G4 / G5 / G7 / G8 **门禁仍未判过**，真库 down→up 全量演练与真实 DUT / 负载未执行
（`V11_30`–`V11_37` 正向迁移已于 2026-11-10 在开发库实跑）
（见 [已知限制](docs/release/v4.9.2/KNOWN-LIMITATIONS.md)），**不得对外称 GA**。
开发期遵守 [ADR-0013](docs/adr/ADR-0013-执行安全分级与开发期禁跑令.md)：不在真实 DUT / 交换机执行台账用例。
版本历史见 [版本地图](docs/release/README.md) 与 [CHANGELOG](CHANGELOG.md)；「关于」页和「版本迭代」页分别读构建信息与本 CHANGELOG。

---

## 交付包：下载、安装、拆卸、运维

面向 **WSL2 Ubuntu 22.04+ / Ubuntu 24.04**。一个 tar.gz 里包含后端 jar、前端静态产物、统一入口、数据库迁移、systemd unit 样例、运维脚本与随包文档；**不含源码依赖、日志、凭据**。

| 阶段 | 命令 | 默认行为 |
|---|---|---|
| 下载 | `gh run download <run-id> -n robot-autotest-linux-x64` | 从 Actions artifact 取回 |
| 本地自产 | `bash scripts/build.sh && bash scripts/package-release.sh` | 产物在 `releases/` |
| 校验 | `sha256sum -c robot-autotest-*.tar.gz.sha256` | 不过就不要用 |
| 体检 | `bin/doctor.sh --ci` 或 `bin/doctor.sh --json` | 只读，`FAIL` 退出 1 |
| 安装 | `./install.sh --prefix /opt/robot-autotest` → 加 `--apply` | **默认 dry-run** |
| 启动 | `bin/ctl.sh start` | 健康检查 `/healthz` |
| 运维 | `bin/status.sh`、`bin/logs.sh`、`bin/backup.sh --apply` | 备份不导出明文口令 |
| 拆卸 | `./uninstall.sh --prefix /opt/robot-autotest` → 加 `--apply` | 先备份、数据库不动 |

完整步骤、前置条件、排障与 systemd 启用方式见 **[docs/DELIVERY-GUIDE.md](docs/DELIVERY-GUIDE.md)**；
命令矩阵见 [scripts/ops/README.md](scripts/ops/README.md)。

> **CI 与门禁的分工**：`delivery.yml` 在 GitHub-hosted `ubuntu-latest` 上只做构建、机检、打包、解包自检与上传 artifact；
> 不连 MySQL、不跑真浏览器、不判发版。**全量 32 步门禁只在 WSL 权威副本跑**。
> 既有 `pr.yml` 依赖尚未注册的自托管 runner `rf-lab`，保留不动，属「待现场」项。

---

## 能做什么

### 资产与用例

| 能力 | 一句话说明 |
|---|---|
| 资产台账 | 设备、端口、链路、环境档案；凭据加密存储，运行时注入不落盘 |
| 用例管理 | 台账 + 脚本双模，用例集 CRUD、批量导入、能力索引 |
| 三视图编辑器 | 代码 / 可视化 / 流程三视图内容不漂移，独立子包构建 |
| 导入沙箱 | 解压前校验条目数、体积、嵌套深度，防 zip slip；只做 `--dryrun` 预检 |
| 资产版本与差异 | 版本标识、引用方检测、差异对比与同步 / 分叉 |

### 公共库与复用

| 能力 | 一句话说明 |
|---|---|
| 双模式 | 项目模式 / 全局模式切换；`project.kind='platform'` 单例平台项目承载公共用例与公共资产，`/g/**` 为全局命名空间 |
| 公共用例库 | 两级分类树；导入按「文件 sha256 / 行 external_id / 版本 content_sha」三层幂等，预览零写入、提交单事务；14 列反向导出并登记批次 |
| 公共资产库 | 按「当前测试环境」分节解析设备 / 端口 / 凭据，业务键 upsert，缺失项软失效；凭据只落引用与掩码 |
| 引用保护 | 资产删除前按 `project_asset` 真实引用计数判定：0 引用可删，被引用返回 409 并报引用方 |
| 模板中心 | 4 个内置模板以**引用**保存用例与资产、不复制正文；创建向导「内容」步按模板落地 |
| 存量同步 | NEW / UPDATE / CONFLICT / UNCHANGED / SKIP 五态预览；冲突逐条选「保留本地 / 接受公共库 / 另存副本」；执行后整单可回滚，`local_override` 永不被覆盖 |

### 执行与调度

| 能力 | 一句话说明 |
|---|---|
| 双测试内核 | Robot Framework（pabot）与 pytest（json-report / junitxml）经 `ExecutionKernel` SPI 分流 |
| 状态机与并发 | 项目 / 设备维度调度、资源租约、4 并发不打架、取消与超时 |
| 分布式执行 | 节点心跳四态；非 ONLINE 当场拒绝；崩溃自动判失败并清理在跑 run |
| 分片执行 | 按套件贪心分配，DB 租约 + CAS fencing 防双跑，失联自动重派 |
| 计划组与依赖图 | cron + 时区固定、依赖 DAG 环检测、冲突只读检测与批量启停 |
| 自动化 | CI 触发幂等 + 限流 + 状态回写；失败聚类与最近 N 轮跨 run 聚类 |

### 报告与交付

| 能力 | 一句话说明 |
|---|---|
| 报告中心 | 解析 `output.xml`，十项 metadata 门禁，用例树、原始日志、跨运行对比 |
| 报告双实体 | `DRAFT → FROZEN → REVOKED / ARCHIVED`；冻结后只读快照，不回查运行 |
| 用例级命令链 | 从 RF / pytest 提取下发命令、来源与退出码，脱敏后在报告 / 打印 / docx 三处可见 |
| 双来源草稿 | 按执行记录（选 run）或按用例集建草稿；冻结快照自包含 |
| 模板与导出 | 字段白名单 + 4 个预置模板，PDF / Word / CSV / xlsx，四格式一致性校验 |
| 对外交付 | `EXTERNAL` 视图只放白名单字段并统一脱敏，导出件文本层可机检扫描 |

### 平台与治理

| 能力 | 一句话说明 |
|---|---|
| 体验与主题 | 中英双语、亮暗主题 + 强调色、密度两档、偏好按用户跟随服务端 |
| 导航与入口 | 7 组菜单 IA、命令面板 `Ctrl/Cmd+K`、统一入口 `:8001`（静态 + 反代） |
| 主机清单 | 10 类只读命令采集，`POST /collect` 显式触发；未采到写「原因」不写占位 |
| 组授权 | 用户组 + 项目角色，有效角色取「直接授权 ∪ 组授权」最高值 |
| AI 辅助 | 7 个候选 CLI 可配端点 / 密钥 / 模型；密钥 AES-256-GCM 入库、接口只回掩码 |
| 可观测性 | `/api/metrics`、run 时间线、性能基线进门禁、错误响应带 `X-Trace-Id`、备份恢复演练 |
| 安全治理 | 登录与令牌、RBAC、项目级租户隔离、审计只增不改、产物保留策略 |

---

## 适用场景

- GPU / 智能网卡性能与稳定性回归，多机多卡并发执行。
- 基础设施巡检：连通性、GPU 健康、存储与磁盘挂载。
- 需要对外交付报告的测试：报告自带设备、链路、软件版本，冻结后不可被后续运行改动。
- 需要「提交即跑、定时跑」的持续测试流水线。
- 同时维护 Robot Framework 与 pytest 两套脚本的团队。
- 多机分布式压测：节点离线当场拒绝、崩溃自动收口。

---

## 技术架构

| 层 | 选型 | 关键约定 |
|---|---|---|
| 后端 | Spring Boot 3 / Java 21 | `api / application / domain / infrastructure / common`，ArchUnit 约束依赖方向 |
| 数据库 | MySQL 8.0 / MariaDB / Flyway | 98 张业务表，手写 down 脚本；dev / it 双库 `robot_dev` / `robot_it` |
| 前端 | Vue 3 / Vite / TS / Pinia / naive-ui | Vitest 单测，主题走语义 token 单向派生 |
| 编辑器 | `packages/editor`（CodeMirror 6） | 独立 vitest 与 dist，**构建先于前端** |
| 执行内核 | Robot Framework 7.5 + pytest | `ExecutionKernel` SPI 统一接入，同一作业不混跑 |
| 部署 | `:8001` 统一入口 + `:8081` 后端 | `serve-entry.mjs` 零依赖静态服务 + `/api`·`/ws` 反代 |

---

## 快速开始（开发态）

前置：JDK 21、Node.js 22+、MySQL 8.0（本地或 WSL 均可）。

```bash
# 1. 初始化数据库（库名 / 账号按需修改）
mysql -uroot -p -e "CREATE DATABASE robot_dev CHARACTER SET utf8mb4;"

# 2. 构建三件套（顺序由 build.sh 锁死：编辑器 → 前端 → 后端）
bash scripts/build.sh

# 3. 开发态分别启动
cd backend  && java -jar target/robot-autotest-backend-0.1.0-SNAPSHOT.jar
cd frontend && npm ci && npm run dev
```

**一条命令起生产形态**：`bash scripts/deploy.sh`（构建 + 停旧进程 + 启动 + 健康检查）。
systemd 样例在 `deploy/`；交付包专用的 unit 样例在 `deploy/release/`。

> ⚠️ 构建顺序不可颠倒：`frontend` 引用 `packages/editor/dist/editor.js`。
> 先构建前端后构建编辑器，会出现「构建全绿、页面没变」的假象（2026-11-06 实测踩过）。

打开 `http://localhost:5173`（开发）或 `http://localhost:8001`（统一入口），使用内置管理员账号登录；**生产部署前必须修改默认口令**。

---

## 门禁与测试

```bash
bash scripts/gates.sh           # 全量 32 步门禁（真库 / 真浏览器 / 真链路）
bash scripts/gates.sh --selfcheck  # 负向自检：删步骤必变红 + 各机检脏样本判红
bash scripts/lint.sh            # 静态检查：密钥扫描 + eslint + Checkstyle + 各类守门
bash artifacts/run_it.sh        # 集成测试，连 robot_it 库
bash scripts/db-audit.sh        # 库结构体检：表数 / 审计四列 / 主键 / 时间精度
bash scripts/backup-drill.sh    # 备份恢复演练
bash scripts/package-release.sh --check  # 交付包前置检查（不产包）
python3 tools/check_delivery_bundle.py --self-check  # 交付包机检自校验
```

> 内网开发以 WSL2 Ubuntu 为准：权威工作副本在 `~/robot-autotest`（ext4）。
> **不要在 Windows 侧或 9P 挂载路径上构建**（实测慢一个量级）；9P 上直接 `git push` 会卡死。

---

## 目录结构

```
backend/           Spring Boot 服务端
frontend/          Vue 3 前端
packages/editor/   Robot Framework 用例编辑器（独立构建）
db/                Flyway 迁移（migration/）与回退脚本（down/）
deploy/            部署样例；deploy/release/ 为交付包专用 unit
scripts/           构建 / 部署 / 门禁 / 运维脚本
scripts/ops/       交付包 bin/ 的同源运维脚本
tools/             各类静态守门与机检工具
.github/workflows/ PR（待现场）与 delivery（GitHub-hosted 交付构建）
docs/              设计、计划、版本、证据、交付与运维文档
releases/          本地打包产物（按脚本重建，不入库）
```

---

## 文档索引

| 文档 | 用途 |
|---|---|
| [交付指南](docs/DELIVERY-GUIDE.md) | 安装 / 拆卸 / 运维 / 排障，**交付包口径优先** |
| [运维命令矩阵](scripts/ops/README.md) | `bin/` 下每个脚本的作用与护栏 |
| [能力评估](docs/plan/安装拆卸运维能力评估-20261109.md) | 安装 / 拆卸 / 运维能力的事实清单与方案比较 |
| [版本地图](docs/release/README.md) | 每版交付口径与六份产物 |
| [v4.9.2 发布说明](docs/release/v4.9.2/RELEASE-NOTES.md) | 本版升级步骤、兼容性、回滚与已知限制 |
| [CHANGELOG](CHANGELOG.md) | 版本迭代页数据源，四类小节齐全 |
| [VERSION](VERSION) | 版本号唯一真源 |
| [WBS-STATUS](docs/plan/WBS-STATUS.md) | 130 个任务包的五态与证据 |
| [版本开发流程](docs/process/版本开发流程.md) | 七道流程与准入门槛 |
| [运维手册](docs/OPS-MANUAL.md) | 2026-11-03 历史口径（开发态 / 部署约定） |
| [回滚手册](docs/ROLLBACK.md) | 版本回滚步骤与风险 |
| [CI 接入指南](docs/CI接入指南.md) | CI 触发接口与流水线示例 |
| [ADR](docs/adr/) | 架构决策记录 |
| [证据目录](docs/evidence/) | 各包验收证据与门禁日志归档 |

---

## 路线图

### 已交付

| 版本 | 主题 | 交付内容 | 产物 |
|---|---|---|---|
| `4.2.0` | 工作区目录化 | 顶栏浮层范式、账户下拉、项目工作区导入 / 导出、创建项目向导、可回滚升级、CI 无库质量门、Schema 单源、远程只读采集 | [v4.2.0](docs/release/v4.2.0/) |
| `4.3.0` | 项目创建 + 资产信息 | 「新建项目」独立入口与 `project:create`、端口网卡归属、连接与拓扑聚合接口与页面、12h 可配缓存、平台资产引用、项目停用与彻底删除 | [v4.3.0](docs/release/v4.3.0/) |
| `4.4.0` | 成员多角色 | 一个成员多角色并按并集求值、成员批量添加、项目编码统一发号与改码留痕 | `V11_30` |
| `4.5.0` | 双模式与平台项目 | 项目 / 全局模式切换、`project.kind='platform'` 单例、`/g/**` 全局命名空间与品牌顶栏 | `V11_31` |
| `4.6.0` | 公共用例库 | 两级分类树、三层幂等导入、预览零写入、14 列反向导出、批次历史与失效 / 恢复 | `V11_32`–`V11_34` |
| `4.7.0` | 公共资产库 | 「当前测试环境」分节解析、业务键 upsert、源缺失软失效、凭据只落引用与掩码 | `V11_35` |
| `4.8.0` | 模板中心 | 模板持久化与 4 个内置模板、模板以引用保存用例与资产、向导「内容」步 | `V11_36` |
| `4.9.0` | 存量同步 | 五态差异预览、冲突逐条处置、整单回滚、`local_override` 永不被覆盖、定时预览默认关闭 | `V11_37` |
| `4.9.1` | 可诊断性 | 错误响应 `X-Trace-Id`、i18n / CSS 变量 / 空错载态专项 | — |
| `4.9.2` | 收尾与发布 | `VERSION` / build-info / OpenAPI / CHANGELOG 同源，发布六件套、已知限制与人工核对清单 | [v4.9.2](docs/release/v4.9.2/) |

合并计划：[PRD](docs/plan/v4.4-v4.9-软件迭代开发PRD-20261110.md) /
[开发计划](docs/plan/v4.4-v4.9-开发计划-20261110.md) /
[测试计划](docs/plan/v4.4-v4.9-测试计划-20261110.md)。逐版本条目见 [CHANGELOG](CHANGELOG.md) 的 `v4.9.2-ga` 小节。

### 待办

1. **收尾「待现场」**：GitHub runner 注册、真实 pabot 子进程树与日志样本、真机 DUT 实网用例。
2. **门禁判定**：G4 / G5 / G7 / G8 逐门出书面结论；未判过之前不宣称正式发版。
3. **真库迁移演练**：在预发执行 `docs/evidence/v4.9.2/migration-drill-V11_30-37.sh`，当前如实记为 `NOT-RUN`。
4. **交付能力正式化**：公开 Release 与匿名下载、拓扑画布、`SchemaIT` 口径对齐，
   已并入 [4.X 总体演进 PRD](docs/plan/v4.x-总体演进-产品PRD-20261109.md) 的 4.2 批。
5. **试点**：搭建小型试点项目，覆盖「建项目 → 编用例 → 并发执行 → 出报告 → 定时 / CI」全链路。
6. **公共库后续**：跨实例 / 跨集群复制、`.xls` / `.csv` 导入、历史一级分类人工归并
   （对应 [已知限制](docs/release/v4.9.2/KNOWN-LIMITATIONS.md) 的 L-05 / L-07 / L-06）。

---

## 许可

第三方组件许可与归属见 [packages/editor/NOTICE.md](packages/editor/NOTICE.md)（编辑器部分源自 RoboScope，Apache-2.0）。
