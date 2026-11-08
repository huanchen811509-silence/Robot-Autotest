# AIDC自动化测试 · AIDC AutoTest

> 产品对外名称：中文 **AIDC自动化测试**，英文 **AIDC AutoTest**。
> 仓库名 `Robot-Autotest`、Maven `artifactId`、npm `name`、Java 包名与数据库名为**工程标识，保持不变**（改名只作用于显示层，见 `docs/plan/多项目与体验基线-评估与设计-20261104.md` §5.5）。

面向 GPU、智能网卡与基础设施的一体化自动化测试平台：把「设备台账 → 用例编写 → 并发执行 → 报告交付 → 定时/CI 自动化」收在同一个系统里，并用 metadata 门禁保证每一份对外报告都能追溯。

> 当前阶段（2026-11-08）：**版本号动态化**（唯一真源是仓库根 `VERSION`，也可用 `APP_VERSION` 环境变量在运行期覆盖，见「关于」页）。
> v3.0.1 平台化 W1–W5 全部落库；v2.2 体验与可观测、v2.3 界面与主题、v2.4 CLI 多 agent、v2.5 AI 助手配置、v2.6 工作助手体验已开发并自测。
> 130 个任务包：**已完成 124 / 待验 0 / 待现场 6 / 未开始 0 / 进行中 0**。
> 余下 6 个 `待现场` 缺的是**外部条件**（GitHub runner、真实 pabot 子进程树与日志样本、真机 DUT），不是代码。
> **G4 / G5 / G7 / G8 门禁尚未判过，不得对外称「已发版」**。
> 开发期遵守 [ADR-0013](docs/adr/ADR-0013-执行安全分级与开发期禁跑令.md)：不在真实 DUT / 交换机执行台账用例。

## 能做什么

| 能力 | 说明 |
|---|---|
| 资产台账 | 设备、端口、链路（DUT-LINK-HOST）、环境档案与技术域标签；凭据加密存储，运行时注入不落盘 |
| 用例管理 | 台账 + 脚本双模；用例/用例集 CRUD、批量导入、能力索引；三视图编辑器（代码 / 可视化 / 流程）内容不漂移 |
| 导入与预检 | 压缩包导入沙箱：解压前校验条目数 / 总体积 / 单文件体积 / 嵌套深度并防 zip slip；只落只读副本，预检只跑 `--collect-only` / `--dryrun`，不执行用例 |
| 执行引擎 | 状态机、项目 / 设备维度调度、资源租约与强制释放、4 并发不打架；实时日志、取消与超时、失败分类与重试；pabot 编排、工作区落盘、全量日志下载 |
| 执行节点与分布式执行 | 节点注册 / 心跳 / 四态（NEW·ONLINE·OFFLINE·DISABLED），令牌只存哈希；指定节点发起执行，按节点独立并发分道；**非 ONLINE 一律当场拒绝——不排队、不重投、不回落本机**；节点 agent 与主控同 jar 双角色（`--spring.profiles.active=node`），复用同一命令构造器保证**同参数命令等价**，产物经 HTTP 回传；节点崩溃（心跳停止）自动判失败并清理在跑 run，不留悬挂；**分片执行**按用例套件贪心分配，DB 租约 + CAS fencing 防双跑，节点失联分片自动重派（`attempt+1`，上限 3 次），主控重启后幂等回收（W2 / W3） |
| 双测试内核 | `ExecutionKernel` SPI 统一抽象：Robot Framework（pabot 编排，复用同一命令构造器）与 pytest（主 `pytest-json-report`、兜底 `junitxml`）；按用例集自动选核，同一作业不混跑两套内核 |
| 计划组与依赖图 | 计划组实体与批量建边；依赖图视图与关键路径；**编排冲突四类只读检测**（cron 无效 / 目标重复 / 同刻触发 / 依赖已暂停）与**批量启停**（审计 + 事务，单条失败不中断）；失败聚类与重跑清单，支持**最近 N 轮跨 run 聚类**（W5） |
| 报告中心 | 解析 `output.xml`，递归采集 metadata；十项门禁；用例树、原始日志分页、跨运行对比、趋势与通过率；PDF / Word / CSV 导出、一次性打印链接与打印页 |
| 报告模板 | 模板实体与字段白名单、按模板冻结 + xlsx 导出、四格式一致性校验（v2.1） |
| 报告双实体 | 报告与运行解耦：`DRAFT → FROZEN → REVOKED / ARCHIVED` 状态机，快照（JSON / HTML / hash）自包含；**冻结后渲染只读快照，不回查运行**，运行数据变更不影响已交付报告 |
| 对外交付 | `EXTERNAL` 视图只放白名单字段并统一脱敏；导出件与打印页文本层可机检扫描，防止凭据 / 内网地址外泄 |
| 资产版本与差异 | 用例/用例集版本标识与引用方检测；资产差异对比与同步 / 分叉（v2.1） |
| 组授权 | 用户组、组-项目角色授权与项目模板；有效角色取「直接授权 ∪ 组授权」的最高值，按 `(用户, 项目)` 缓存并在授权变更时失效；一层测试场景编排（不做 DAG） |
| AI 辅助面板 | 可调宽抽屉（340–760px 拖拽，窄屏悬浮）：可现场切换 **Agent 与模型**；只探测本地 CLI **不代调模型**；打包上下文复用对外脱敏逻辑；发起执行需二次确认；全动作落审计、可一键终止进程树；无 CLI 时降级为 L1 静态建议并标注 `DEGRADED` |
| AI 助手配置 | **7 个候选**（`hermes → dsh → claude → codex → gemini → aider → pi`，`pi` 为随包内置的兜底 agent）；设置页逐个展示安装状态，可配 **API 端点 / 密钥 / 模型**并「测试连接」；密钥 AES-256-GCM 加密入库、接口只回掩码、明文永不回显；保存的配置**真的注入子进程**（环境变量 + 模型参数），不接受注入的 CLI 明确标注「仅记录」 |
| 体验与主题 | 中文 / English 双语切换、亮暗主题 + 强调色、表格密度两档；**主题 / 强调色 / 密度按用户跟随（服务端偏好，登出回默认，换账号互不串）**；登录页支持记住账号名 / 记住密码并自动登录（自动登录失败即清密码防锁号）；首帧主题不闪，暗色对比度经门禁实测；1280 视口无横向滚动 |
| 导航与入口 | **7 组菜单 IA**（工作台 / 测试用例 / 测试资产 / 执行与报告 / 环境与拓扑 / 维护与恢复 / 系统设置）、能力驱动过滤（含管理员专属 `ai:config`）、命令面板 `Ctrl/Cmd+K`、项目选择器常驻顶栏、窄屏自动收窄轨并保留 `title` 提示、统一入口 `:8001`（静态托管 + 深链接回退 + `/api`·`/ws` 反代） |
| 自动化 | 执行计划（cron + 时区固定）、依赖 DAG（环检测 / 前置失败阻断）、CI 触发（幂等 + 限流 + 状态回写）；计划编排冲突检测与批量启停（W5） |
| 可观测性 | `GET /api/metrics`（在线率 / 队列深度 / 失败率 / 重派次数）与告警阈值；run 时间线（入队 → 租约 → 分片完成/失败/丢失 → 终态，带 `fencingToken` / `attempt`）；节点页健康条；5 条只读 GET 性能基线进门禁，劣化超阈值即红；`scripts/backup-drill.sh` 备份恢复演练（W4） |
| 安全与治理 | 登录与令牌、RBAC 权限矩阵、项目级租户隔离、审计日志（只增不改、脱敏）、产物保留策略 |

## 适用场景

- GPU / 智能网卡性能与稳定性回归，多机多卡并发执行。
- 基础设施巡检：连通性、GPU 健康、存储与磁盘挂载。
- 需要对外交付报告的测试：报告自带设备、链路、软件版本等可追溯信息，且冻结后不可被后续运行改动。
- 需要「提交即跑、定时跑」的持续测试流水线。
- 同时维护 Robot Framework 与 pytest 两套脚本的团队：一套执行 / 报告 / 权限体系覆盖两种内核。
- 多机分布式压测：把用例指派到指定执行节点跑，节点离线当场拒绝、崩溃自动收口（v2.1）。

## 技术架构

- **后端**：Spring Boot 3 + Java 21，分层 `api / application / domain / infrastructure / common`，ArchUnit 约束依赖方向。
- **数据库**：MySQL 8.0，Flyway 版本化迁移（当前 **49 个迁移至 `V11_15`，71 张业务表**），手写 down 脚本；dev / it 双库（`robot_dev` / `robot_it`）。每张表须带「代理主键 `id` + 审计四列」，`scripts/db-audit.sh` 可单独体检，且已纳入提交门禁。
- **前端**：Vue 3 + Vite + TypeScript + Pinia + vue-i18n + naive-ui，Vitest 单测；主题走「localStorage + 服务端偏好（按用户）+ `data-theme/data-accent/data-density`」三层，naive-ui 主题由语义 token 单向派生。
- **编辑器**：独立子包 `packages/editor`（CodeMirror 6），有自己的 vitest 工程与 dist 产物，**构建先于前端**。
- **测试内核**：Robot Framework 7.5（pabot 并发、robotdashboard 看板）+ pytest（`pytest-json-report` / `junitxml`），经 `ExecutionKernel` SPI 接入。
- **执行节点**：节点与主控同一 jar、不同 profile（`node`），单机可演示、协议为真实分布式保留；命令由主控统一下发签名（HMAC-SHA256），产物与日志经 `/api/agent/result` 回传。
- **部署**：单入口 `:8001`（`scripts/serve-entry.mjs`，零依赖 Node 静态服务 + 反代），systemd --user 托管三件套。

## 快速开始

前置：JDK 21、Node.js 22+、MySQL 8.0（本地或 WSL 均可）。

```bash
# 1. 初始化数据库（库名/账号按需修改）
mysql -uroot -p -e "CREATE DATABASE robot_dev CHARACTER SET utf8mb4;"

# 2. 启动后端（首次启动前先执行 Flyway 迁移与种子数据）
cd backend && mvn -DskipTests package
java -jar target/robot-autotest-backend-0.1.0-SNAPSHOT.jar

# 3. 启动前端
cd frontend && npm ci && npm run dev
```

**一条命令起生产形态（推荐）**：`bash scripts/deploy.sh` —— 按「编辑器子包 → 前端 → 后端」顺序构建，
停旧进程（systemd unit 优先，未安装 unit 才降级 `nohup`），再拉起后端 `:8081` 与统一入口 `:8001`。
统一入口的 systemd 单元样例见 `deploy/robot-autotest-entry.service`。

> ⚠️ 构建顺序不可颠倒：`frontend` 引用的是 `packages/editor/dist/editor.js`。
> 先构建前端后构建编辑器，会出现「构建全绿、页面没变」的假象（2026-11-06 实测踩过）。

门禁与集成测试（在仓库根目录）：

```bash
bash scripts/gates.sh       # 一键全量门禁（19 步：字体子集 / 前端测试与构建 / 后端测试 / 各类 lint / 库审计 / 浏览器回归 / 性能基线 / 结构自检 / 证据完整性）
bash scripts/lint.sh        # 单跑静态检查：密钥扫描 + eslint + 后端编译 + 颜色/对比度/i18n/token/体积/WBS 一致性等
bash artifacts/run_it.sh    # 集成测试，连 robot_it 库（内部读取 ~/.config/robot-autotest/env）
bash scripts/db-audit.sh    # 库结构体检：表数、审计四列、主键、时间列精度、字符集
bash scripts/backup-drill.sh # 备份恢复演练（dump → 覆盖导入 robot_it → 行数与关键列核对 → 自动还原）
```

打开 `http://localhost:5173`（开发）或 `http://localhost:8001`（统一入口），使用内置管理员账号登录；**生产部署前必须修改默认口令**。

## 目录结构

```
backend/          Spring Boot 服务端（api / application / domain / infrastructure / common）
frontend/         Vue 3 前端
packages/editor/  Robot Framework 用例编辑器（三视图，独立构建）
db/               Flyway 迁移（migration/）与回退脚本（down/）
deploy/           部署样例（统一入口 systemd unit、nginx 反代示例）
scripts/          构建 / 部署 / 门禁 / 运维脚本（deploy.sh、lint.sh、serve-entry.mjs 等）
tools/            设计 token、对比度、i18n、WBS 一致性、报告敏感性等检查工具
artifacts/        门禁日志、集成测试入口（run_it.sh）与过程产物
docs/             设计、计划、版本、证据文档
```

> 内网开发以 WSL2 Ubuntu 为准：权威工作副本在 `~/robot-autotest`（ext4），
> 不要在 Windows 侧或 9P 挂载路径上构建（实测慢一个量级）。

## 文档

| 文档 | 用途 |
|---|---|
| [docs/plan/WBS-STATUS.md](docs/plan/WBS-STATUS.md) | **进度唯一真源**：130 个任务包的五态、证据与阻塞 |
| [docs/release/README.md](docs/release/README.md) | 版本地图与每版交付口径 |
| [VERSION](VERSION) | **版本号唯一真源**：发版只改这一行；`scripts/gen_build_info.py` 在打包时写进产物，「关于」页展示并标注来源 |
| [docs/release/v2.6-assistant-ux/](docs/release/v2.6-assistant-ux/) | v2.6 工作助手体验（含宽度方案评估对比 / PRD / DEV-PLAN / TEST-PLAN / 测试报告） |
| [docs/release/v2.5-ai-config/](docs/release/v2.5-ai-config/) | v2.5 AI 助手配置（PRD / DEV-PLAN / TEST-PLAN 指针） |
| [docs/release/v2.1-platform/](docs/release/v2.1-platform/) | v2.1 的 PRD / 开发计划（DEV-PLAN）/ 测试计划（TEST-PLAN） |
| [docs/evidence/v2.1/](docs/evidence/v2.1/) | v2.1 各包验收证据（真实 HTTP 链路脚本、run.log、截图，可复跑） |
| [docs/plan/W5-总结报告-20261107.md](docs/plan/W5-总结报告-20261107.md) | W5（v3.0.1）总结：做了什么、为什么做、验收读数、已知边界 |
| [docs/plan/交接文档-v3.0.1-20261108.md](docs/plan/交接文档-v3.0.1-20261108.md) | 最新交接文档：总体读数、本轮 7 包、三个缺陷根因、未决欠账、环境运维、接手第一小时（旧版 `-20261107` 留作历史参考） |
| [docs/plan/最终交付报告-W0-W5-20261107.md](docs/plan/最终交付报告-W0-W5-20261107.md) | W0–W5 最终交付报告：六波清单、验收读数、修掉的真实缺陷、风险 |
| [docs/evidence/v3.0.1/](docs/evidence/v3.0.1/) | W5 验收证据（示例用例播种、冲突接口 JSON、跨轮聚类、浏览器截图） |
| [docs/process/版本开发流程.md](docs/process/版本开发流程.md) | 七道版本流程与准入门槛 |
| [docs/design/美术体系-开发与测试计划-20261104.md](docs/design/美术体系-开发与测试计划-20261104.md) | 设计 token、双语 / 密度 / 暗色判据与实测 |
| [docs/evidence/](docs/evidence/) | 各包验收证据（门禁日志、真实链路脚本与读数、截图） |
| [CHANGELOG.md](CHANGELOG.md) | 面向使用者的版本能力变更（同时是「版本迭代」页的数据源） |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 分支模型、提交规范、PR 与评审规则 |
| [docs/OPS-MANUAL.md](docs/OPS-MANUAL.md) | 服务启停、日志、健康检查、备份恢复、常见故障处置 |
| [docs/ROLLBACK.md](docs/ROLLBACK.md) | 版本回滚步骤与风险 |
| [docs/CI接入指南.md](docs/CI接入指南.md) | CI 触发接口与流水线接入示例 |
| [docs/adr/](docs/adr/) | 架构决策记录 |

## 路线图

1. **收尾 6 个 `待现场`**：补齐外部条件后按现场实测关闭（runner 注册、pabot 子进程树与日志样本、真机 DUT 实网用例）。
2. **门禁判定**：G4 / G5 / G7 / G8 逐门出书面结论（含 v2.1 各包证据复核）；未判过之前不宣称发版。
3. **试点**：搭建小型试点测试项目，覆盖「建项目 → 编用例 → 并发执行 → 出报告 → 定时/CI」全链路。
4. **迭代**：按试点反馈优先修可用性问题，再补能力缺口；每个迭代产出可复现的验证记录，能力不倒退。

## 许可

第三方组件许可与归属见 [packages/editor/NOTICE.md](packages/editor/NOTICE.md)（编辑器部分源自 RoboScope，Apache-2.0）。
