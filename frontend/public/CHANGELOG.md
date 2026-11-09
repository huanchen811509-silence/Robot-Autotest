# CHANGELOG

> 本文件是版本迭代页（NAV-03 / FR-v2.0-12）的数据源，`scripts/build.sh` 会把它同步到
> `frontend/public/CHANGELOG.md`。四类小标题（新增 / 变更 / 修复 / 破坏性变更）必须齐全，
> 版本按倒序排列。
>
> 机器锚点：小节标题后的 `<!-- added|changed|fixed|breaking -->` 与当前版本标题后的
> `<!-- current -->` 是给解析器用的语言无关标识（Markdown 渲染时不可见）。
> 因此代码里不需要硬编码中文，界面显示文案一律走 i18n 语言包 `changelog.*`。

## v3.4.0-scale-archive（2026-11-08） <!-- current -->

### 新增 <!-- added -->
- **归档与保留**：`V11_22` 新增 `archive_policy` / `archived_run` / `archived_test_result` /
  `archived_keyword_result`；`ArchiveService` 只搬运行明细、保留运行主记录与聚合数字，
  dry-run 默认开启，按运行逐事务（索引 + 明细 + 审计同时成立），恢复按原主键插回，冲突显式 409；
  `ArchiveScheduler` 按小时触发且默认策略为停用；`/api/admin/archive/*` 提供策略、预览、执行、
  冷表明细与恢复；前端新增归档中心页
- **并发准入与背压**：`RunAdmissionService` 三层上限（全局 50 / 单项目 20 / 单节点 10）、
  公平锁按到达顺序发放、超时以 409 返回当前占用并可解释；运行进入终态释放名额，
  每小时兜底回收超过 6h 的异常占用；`GET /api/admin/ops/admission` 与运维中心准入卡片
- **安全加固**：`SecurityHeadersFilter` 恒定发送 nosniff / DENY / no-referrer / CSP，
  HTTPS 额外发送 HSTS；`LoginRateLimiter` 按 IP + 用户名滑动窗口限流（默认 5 次 / 300s，成功清零，超限 429）
- **新增门禁步骤**：24 AI 黄金集评测、25 安全扫描（密钥 / SAST / 危险依赖，高危必须 0）、
  26 容量与备份演练校验（RTO ≤ 2h、RPO ≤ 24h、候选查询必须走索引）、27 i18n 与对比度 AA；
  `scripts/backup-drill.sh` 输出 `docs/evidence/ops/backup-drill-latest.json`

### 变更 <!-- changed -->
- `RunService` 在落库 + 审计之后申请并发名额，失败整事务回滚（不留孤儿运行）；取消 / 租约 / 分片
  等所有终态路径统一经 `transition` 释放名额
- `run` 表新增 `idx_run_project_created(project_id, created_at)`，归档候选查询不再全表扫描
- 运维中心补并发准入卡片；导航外新增 `/admin/ops` 与 `/admin/archive` 两个管理员路由

### 修复 <!-- fixed -->
- 归档恢复遇到原主键占用时整事务回滚并显式报 CONFLICT，不再静默丢明细或错位引用
- 异常路径造成的并发名额泄漏由定时回收兜底，不再永久占用

### 破坏性变更 <!-- breaking -->
- 超过并发上限的创建运行请求现在返回 409（可用 `execution.admission.max-inflight` /
  `max-per-project` / `max-per-node` / `max-wait-ms` 调整）
- 登录连续失败达到阈值后返回 429，窗口与阈值可通过配置调整

## v3.3.0-ai-ops（2026-11-08）

### 新增 <!-- added -->
- **AI 工作台**：`V11_21` 新增 `ai_project_config` / `ai_prompt_template` / `ai_message` /
  `alert_channel` / `alert_rule` / `alert_event` / `slo_daily` / `release_record`，并内置 7 条提示词模板；
  `AiEvidenceService` 按运行 / 需求 / 模板装配证据，`AiAnswerFactory` 输出事实 / 推测 / 建议三段，
  `AiFactVerifier` 校验数字与引用（无证据必须明确拒答），`AiWorkbenchService` 管配额与会话，
  用例草稿只有 `reviewed=true` 才允许落库（状态 AI_DRAFT / PENDING）
- **SSE 流式对话**：`POST /api/ai/workbench/chat/stream` 事件序列 meta→citations→delta*→done，
  24 字/块、18ms 间隔、30s 超时；取消 / 超时 / 断连均落库为 CANCELLED / TIMEOUT；会话可导出 Markdown
- **运维闭环**：SLO 与错误预算（含 MTTD / MTTR）、告警规则 / 通道 / 事件、Webhook HMAC-SHA256 签名、
  冷却去重与恢复、投递失败重试一次；`/api/admin/ops/*` 汇总 SLO、实时指标、备份状态、发布记录；
  运维中心页面展示 SLO 卡、错误预算、告警与备份状态
- **请求指标**：按项目的 1024 槽环形 P95 与 5xx 计数，`/api/admin/ops/summary` 可读
- **前端接入**：AI 抽屉新增「证据问答」（流式、停止、引用 chip、模板、历史会话、导出）与
  「用例草稿」（生成 → 逐条勾选 → 确认已审阅 → 保存）；报告详情新增 AI 叙述；失败聚类新增 AI 分诊
- **门禁第 24 步**：`tools/ai_eval.py` 直接调用真实端点，校验事实一致性、严重幻觉 0、明确拒答与首字时延

### 变更 <!-- changed -->
- `AiPanelDrawer` 拆成「助手 / 证据问答」双模式，默认仍是助手，既有快捷键与行为不变
- 运维中心与归档中心复用既有布局与对比度令牌，不新增硬编码颜色

### 修复 <!-- fixed -->
- AI 回答必须区分事实与推测；校验不通过时在卡片上明确标红，不冒充结论
- 模型 CLI 不可用时以 `degraded=true` 的本地证据装配继续服务，不再整页报错

### 破坏性变更 <!-- breaking -->
- AI 端点新增会话 / 配额语义：跨会话追问需显式传 `sessionId`，否则按新会话处理

## v3.2.0-fast-stable（2026-11-08）

### 新增 <!-- added -->
- **四类性能预算与门禁**：`tools/perf-budget.json` 单一事实源（API p95 150ms / p99 300ms、慢查询
  200ms WARN / 500ms ERROR、前端入口 300 KiB / 单路由 150 KiB、长列表 50 fps、运行链 create p95 200ms /
  日志 5000 行/s），`check_perf_budget.py`、`check_bundle_budget.py` 越线即红，门禁新增第 20–22 步
- **查询治理**：`PageQuery` 统一 page/size（上限 100）与排序白名单（用例 7 字段 / 运行 4 字段）；
  用例、运行、审计三条高频列表支持键集分页（`beforeId`），列表投影不再 `SELECT *`（生产代码 0 处）
- **索引与慢查询**：`V11_19` 新增 5 个复合索引并带 down；列表 SQL 走 `SlowQuery.measure`，
  >200ms WARN / >500ms ERROR，`/api/admin/observability/summary` 可读 measured / warned / errored
- **进程内读缓存**：`TtlCache` + 能力索引缓存（TTL 30s / 容量 512），键为 projectId；
  新建/修改/删除/批量/导入成功后立即失效；`app.cache.enabled=false` 时行为与开启一致
- **有界异步与优雅停机**：`BoundedExecutor`（队列 1024、core 2 / max 4），拒绝时改由调用线程同步执行并计数；
  停机 `shutdown()` → 等待 10s → `shutdownNow()`，审计不丢；异步任务透传 MDC traceId
- **执行链幂等**：四个提交入口统一解析 `Idempotency-Key`（Header 优先）→ `runRequestId` →
  端点+参数+时间桶 SHA-256；`uk_run_project_request` 唯一键兜底，重复提交 0 重复运行；
  孤儿进程按 DB 活跃运行对账周期清理
- **前端性能**：编辑器改 `defineAsyncComponent` 懒加载（EditorView 路由块 253 KiB → 6.7 KiB）；
  `DataTable` 增 `virtualScroll`（≥200 行自动开启，1 万行 58.5 fps）；`api/http.ts` 增同 GET 在途去重、
  3 秒短缓存、写后清缓存、每请求 `X-Request-Id`；首屏空闲预取工作台/用例/运行三页
- **可观测性**：`TraceIdFilter`（`X-Trace-Id` → `X-Request-Id` → 32 位 hex）与日志 `%X{traceId}`；
  Actuator `health,info,metrics` + liveness/readiness；管理员看板端点 `GET /api/admin/observability/summary`
- **API 契约与架构守护**：`tools/gen_api_contract.py` 从控制器源码生成 `docs/contracts/openapi.json`
  （240 端点 / 188 路径），`--check` 漂移退出 1；8 个核心端点契约测试 + 跨端点约定测试；
  ArchUnit 补 R6/R7/R8（禁字段注入 / 仓储 JDBC 只在 infrastructure / 控制器不直连 JDBC），
  附违规 fixture 证明规则真会红
- **容量报告**（`docs/evidence/v3.2.0/EW32-10/perf/`）：并发（1/20/50/100）与峰值（200 并发 10s）
  两份完整报告；长稳 2h 按要求跳过本轮，仅保留 213 点部分采样作旁证（不作通过结论）。
  另附队列空闲创建基线与日志吞吐探针（实时发布 98.8 万行/s、逐行落盘 22.5 万行/s）
- **测试体系 v2**：门禁 22 步（新增契约 / 包体 / 预算），`--selfcheck` 证明删步骤必红；
  7 类 14 条历史环境失败归档 `docs/evidence/v3.2.0/flaky-ledger.md`

### 变更 <!-- changed -->
- `VERSION` 升至 `3.2.0`；`application.yml` Hikari `maximum-pool-size` 8 → 16（压测实测：20 并发读
  p95 155.76 → 142.59ms，提升有限，瓶颈在 DB 与单实例 CPU，见 EW32-10 报告）
- 分页 `size` 由各仓储各自 `Math.min(size, 200)` 统一为上限 100；超出归一为 100
- 列表默认 `id DESC` 分支加 `FORCE INDEX`，避免索引变多后优化器误选 `(project_id, updated_at)`
  导致 filesort（真实 EXPLAIN 回退，非理论风险）
- 编辑器依赖从 EditorView 路由块拆为懒加载分包；门禁单路由口径改为「路由块 + 按需分包」而非
  「所有懒加载合计」（互不相干的按需包不再相加）
- 日志格式统一带 traceId；`server.shutdown=graceful` + 20s 停机等待

### 修复 <!-- fixed -->
- **用例列表全量 500**（`ERROR 1176`）：代码 `FORCE INDEX (idx_case_project_id)` 依赖的索引从未建，
  `V11_19` 只建了 `idx_case_project_updated`。由 EW32-10 容量压测在 20 并发下暴露，
  新增 `V11_20__case_project_id_index.sql` 补建 `(project_id, id)` 并补 down
- **索引回退**：`V11_19` 后默认 `ORDER BY id DESC` 被优化器改选 `idx_case_project_updated`，
  Extra 出现 `Using filesort`；按表钉死访问路径后恢复 `Backward index scan`
- **编辑器 247 KiB 路由块**：EditorView 静态 `import` 整个编辑器包，首屏多下 94 KiB 的 `editor.js`；
  懒加载后路由块 6.7 KiB，编辑器只在进入编辑器页时加载
- **同 GET 重复请求**：列表刷新/切页会重复打同一接口；改为在途去重 + 3 秒短缓存，写操作前后清空
- **慢查询无读数**：此前只有日志，`/summary` 无慢查询计数；补 `SlowQuery.stats()` 并按 200/500ms 分级
- **审计写入阻塞请求**：写审计同步落库；改为有界池异步，拒绝时同步降级，停机排空

### 破坏性变更 <!-- breaking -->
- 无数据库破坏性变更：`V11_19` / `V11_20` 均为纯增量索引，表结构与旧接口不受影响；
  回滚需先回滚 `FORCE INDEX` 代码再执行 down 迁移（顺序见 `EW32-02` README §9）
- 接口侧唯一行为差异：`size > 100` 归一为 100；非法 `sort` 返回 400 `INVALID_ARGUMENT`
  （名单外字段此前不保证行为）。两者只影响原本越界或非法的请求，合法调用方无需改动

## v3.1.0-experience（2026-11-08）

### 新增 <!-- added -->
- **报告速览卡**：`GET /api/reports/{runId}/summary` 聚合出结论、通过率、总数、失败、跳过、耗时、
  Top 失败项与风险提示；skipped > 0 时结论显式写明跳过数，不把「有跳过」读成「全部通过」
- **新手引导清单**：创建项目 / 导入用例 / 配置执行节点 / 发起运行 / 查看报告五步，
  按概览既有统计判断完成态并给跳转；可折叠、可按用户 + 项目「不再显示」
- **快捷键面板**：`?` 与顶栏帮助按钮都能打开，列出 Ctrl/Cmd+K、`?`、Esc、↑↓、Enter；
  输入框 / 文本域 / 下拉 / contenteditable 内不触发 `?`
- **全局实体搜索 / 保存视图 / 通知中心**：命令面板可按实体跳转；列表筛选可命名保存；
  通知按类型分组、未读计数并在顶栏常驻入口
- **运行预检与 ETA**：发起运行前预检节点与资产，运行中展示预计剩余时间
- **批量撤销与表单草稿**：批量操作提供一次撤销；表单中断后草稿可恢复
- **无障碍基线**：焦点陷阱 composable 统一命令面板 / 侧栏 / AI 抽屉，Tab 首尾循环、
  Esc 关闭、关闭后焦点归还触发元素；新增「跳到主内容」skip link、sr-only 未读文案与
  `prefers-reduced-motion` 收口

### 变更 <!-- changed -->
- 报告详情首屏先给速览，再进入原有套件树；「查看失败明细」锚点滚动并同步移动焦点
- 顶栏工具段新增快捷键帮助入口，图标按钮统一补齐 `aria-label`
- 页面主内容区标记为 `<main id="main">`，`overflow-x: clip` 保证 1280 视口无页面级横滚，
  宽表格仍在 `.table-scroll` 内部滚动
- 版本文件 `VERSION` 升至 `3.1.0`

### 修复 <!-- fixed -->
- 侧栏 / 命令面板此前各自监听 Esc 与焦点归还，卸载路径容易漏移除监听；现统一由
  `useFocusTrap` 管理，避免两套实现
- 通知徽标只有视觉数字，读屏器读不到未读数；现补 `sr-only` 文案与含未读数的 aria-label
- 弹层打开后焦点可能留在遮罩后的页面；现在打开即聚焦容器内首个可聚焦元素

### 破坏性变更 <!-- breaking -->
- 无。数据库迁移 V11_18 为纯增量（新增列 / 表均带默认值），历史数据与旧接口不受影响；
  `GET /api/reports/{runId}/summary` 为新增只读端点，未改动既有报告接口

## v2.7-dual-kernel-demo（2026-11-08）

### 新增 <!-- added -->
- **pytest 项目可真实执行**：执行链按内核分流——RF 走原 pabot 路径（一行未动），
  pytest 走新增 `PytestRunAdapter`（复用进程启动器 / 注册表 / 超时杀树，单命令 + xdist 并行，
  产物校验失败报 `pytest_artifact_invalid`），跑完产物自动归一入库
- **pytest 逐用例报告解析**：`ReportIngestService` 按 run 内核快照分流，
  pytest 产物经 `PytestKernel.normalize` 解析成与 RF 同构的套件 / 用例树；
  状态映射唯一出口 `KernelStatusMapper`（PASS/FAIL/SKIP 三态，
  XFAIL→SKIP、XPASS→FAIL、ERROR→FAIL，与用例失败口径一致）
- **run 级内核事实快照**：`run` 表新增 `kernel` 列（V11_17，默认 `robot`），
  执行时由内核选择器落库，报告解析按该列分流，历史 run 不受影响
- **双演示项目**：`演示项目-RobotFramework` 与 `演示项目-Pytest`——12 条编号 / 标题 / 断言语义
  完全等价的用例（DEMO-001..012，含参数化、预期失败对照），同值设备与环境资产，
  两项目均可独立真实跑通且 metadata 十项门禁全绿
- 新增 `docs/release/v2.7-dual-kernel-demo/` 六份产物（机检 `FR=6 / TC=12`）

### 变更 <!-- changed -->
- pytest 可执行文件三级解析：`EXECUTION_PYTEST_BIN` 配置 > 项目 venv 内 pytest > PATH，
  不与 Robot Framework 的 venv 配置牵连
- 工作区允许上传 `.ini` / `.cfg`（pytest 配置文件此前被文件类型白名单拒绝）
- 项目保存时 `test_kernel` 标注列与系统设置 `kernel.default` 双源自动同步

### 修复 <!-- fixed -->
- pytest 静态 metadata（CASE_ID / RUN_ID / QA_PROJECT 等十项）进不了报告：
  pytest-metadata 3.1.1 已不支持 ini / CLI 注入 json-report，且 json-report 1.5.0 把 metadata
  落在 `environment` 键——现在由 conftest 直写 `config._metadata`，后端按
  `environment` 优先、`metadata` 兜底双键读取，演示项目零补录即达 exportable=true

### 破坏性变更 <!-- breaking -->
- 无。RF 执行路径逐字未动；`run.kernel` 为带默认值的新增列，旧代码与历史 run 不受影响；
  `EXECUTION_PYTEST_BIN` 未配置时按三级解析回落，行为与 v2.6 一致

## v2.6-assistant-ux（2026-11-08）

### 新增 <!-- added -->
- 工作助手（右侧 AI 抽屉）**宽度可调**：拖左缘 340–760px，鼠标、方向键（±20 / Home / End）、
  档位按钮三条路径都能调，值记在本机、刷新后恢复
- 工作助手**可选 Agent 与模型**：新增 `GET /api/ai/agents`（只列本机已安装且未被关闭的助手，
  不含端点与密钥），对话支持按次覆盖 `cli` / `model`，选择随请求真的进入子进程
- 选中助手不可用时的**回落提示**：后端回落到首选可用助手并在响应标 `cliFallback`，
  界面明示「所选助手当前不可用，已回落到 X」，不静默换人
- 宽度**预设档位陈列**：340 窄 / 420 标准 / 520 宽 / 760 最宽一排直接点，当前档高亮
  （拖拽与键盘路径保留）
- **项目测试内核标注与显示**：项目可标 Robot Framework / pytest（建项表单下拉），
  卡牌头徽章显示；存量项目默认 Robot Framework；未知内核 400
- 关于页与设置页之外新增 `docs/release/v2.6-assistant-ux/` 六份产物（机检 `FR=6 / TC=19`）

### 变更 <!-- changed -->
- 抽屉布局从固定 `340px` 改为 CSS 变量 `--ai-drawer-w` 驱动（默认 420），打开助手时主内容自动让位并保持不遮挡
- 抽屉宽度范围 280–520 → **340–760**，默认值 340 → 420
- 对话输入从单行 `<input>` 改为多行 `<textarea>`：约 3 行起步、随内容长高、封顶 240px 后内部滚动；
  Enter 发送、Shift+Enter 换行、中文输入法组合期不误发
- 输出区去掉 `max-height: 240px` 硬上限，改为撑满抽屉剩余高度；正文字号 12 → 13
- 换助手时自动清空模型选择（跨家的模型名基本不存在，留着必然报错）
- 窄屏（≤999px）悬浮态宽度改为跟随同一变量，且 `max-width: 100%` 不超视口

### 修复 <!-- fixed -->
- 抽屉「宽度」按钮其实从未接到布局：`width` 状态只在界面上显示数字，`.app-shell` 第三列写死 340px
- `var(--ai-drawer-w)` 若不在 `theme.css` 声明会被判为悬空引用，且 JS 未跑时无可用宽度（已在 `:root` 补默认值）
- 键盘 Home/End 调宽只改本地状态、不通知父组件，导致「按了没反应」
- **点退出后被「记住密码」自动登回**（表现：退出后仍在登录状态）：退出落一次性退出意图标记，
  登录页据此跳过自动登录、也不预填密码（退出后常要换账号）；账号名记忆保留；401 被动过期
  跳登录页不走退出路径，自动登录照常

### 破坏性变更 <!-- breaking -->
- 无。`POST /api/ai/chat` 新增 `cli` / `model` 两个**可选**字段，不传时行为与 v2.5 完全一致；
  新增的 `GET /api/ai/agents` 只做项目访问校验，不影响既有接口权限

## v2.5-ai-config（2026-11-08）

### 新增 <!-- added -->
- 设置 · AI 助手页：7 条候选（hermes / dsh / claude / codex / gemini / aider / pi）逐个展示安装态、
  端点 URL、模型、凭据，支持「保存 / 清除密钥 / 测试连接」，测试结果回显耗时、退出码、注入的变量名与输出片段
- `ai_assistant_config` 表（V11_14）与 `V11_15` 补审计四列：每个 CLI 一行，凭据只存 AES-256-GCM 密文
- 端点与模型可配置且**真生效**：`AiAssistantConfigService.envFor()` / `modelArgs()` 注入到子进程环境与 argv，
  不再只能改代码或改 systemd
- 版本动态化：仓库根 `VERSION` 为版本号唯一真源，`scripts/gen_build_info.py` 产出 `version` + `versionSource`，
  后端支持 `APP_VERSION` / `PLATFORM_VERSION` 运行期覆盖（重启即生效）
- 关于页 30s 自动刷新（页面可见时才刷）+ 手动刷新 + 版本来源标识（env / file / pom / runtime）
- 设置导航新增「AI 助手」入口，能力键 `ai:config`，仅管理员可见

### 变更 <!-- changed -->
- 一级菜单改名：`用例` → `测试用例`，`资产` → `测试资产`（中英文案同步）
- 侧栏窄轨断点从 999px 收到 760px，并在窄轨下**同步隐藏文字**（此前只压宽度，文字被 overflow 裁掉）
- 侧栏折叠态加一次性迁移键 `robot.layout.side.v23`：v2.3 遗留的 `collapsed=true` 自动归位为展开
- 顶栏改 `flex-wrap: wrap`，账户区不再被推出视口
- `scripts/db-audit.sh` 期望业务表数 70 → 71，并注明 V11_14 的来源
- `BuildInfoService` 自检的 `aiCli` 改为按完整候选清单（含内置 pi）判定，不再只查老四条

### 修复 <!-- fixed -->
- **退出按钮失效**：`auth.logout()` 先 await 服务端撤销再本地清理，后端慢或挂住时表现为「点了没反应」。
  改为 `performLogout()` 先本地清（token 立即移除）再异步撤销，服务端失败不再阻塞跳转
- **菜单只有图标没有文字**：三重根因（折叠态遗留 / 窄屏只改宽度 / 顶栏溢出）分别修掉，并在导航项补 `title`
- 顶栏账户区「看得见位置却点不到」：`flex-wrap: nowrap` 下溢出的那一段既不可见也不可点
- `ai_assistant_config` 缺 `created_at` / `created_by` 且 `updated_at` 为 `DATETIME(0)`，撞 db-audit 两条硬判据
- `AiProcessManager` 不关子进程 stdin，长驻 CLI 读 stdin 时挂住（假 CLI 不读 stdin 所以此前一直没暴露）

### 破坏性变更 <!-- breaking -->
- 无。V11_14 只加表、V11_15 只给新表补列与提精度，旧代码与既有接口不受影响；
  旧配置读不到时按「未配置」处理，`base_url` / `model` / 密钥全为空即沿用 CLI 自带配置

## v2.0-platform（2026-11-06）

### 新增 <!-- added -->
- 统一入口 `:8001`：静态托管 + SPA 深链接回退 + `/api`·`/ws` 反代 + 缓存策略
- 6 域菜单 IA（工作台 / 资产 / 用例 / 执行 / 报告 / 管理）与能力驱动过滤
- 系统设置：平台级 / 项目级分栏、白名单校验、每次变更写审计
- 关于页：版本 / commit / 构建时间来自构建产物，依赖清单与环境自检四项
- 版本迭代页：读取本文件，按版本倒序、当前版本高亮
- 命令面板 `Ctrl/Cmd+K`：中英文搜索、键盘选择、最近 5 条、收藏、折叠持久化
- 403 页与路由能力守卫；顶栏项目选择器
- `/healthz` 存活探针与 `/api/health` 就绪探针分离
- 报告双实体：`DRAFT → FROZEN → REVOKED / ARCHIVED` 状态机，快照（JSON / HTML / hash）自包含；冻结后渲染只读快照，运行数据再变也不影响已交付报告
- 对外报告字段白名单与统一脱敏，导出件与打印页文本层可机检扫描
- 双测试内核：`ExecutionKernel` SPI + Robot Framework（pabot）/ pytest（`pytest-json-report`、兜底 `junitxml`）自动选核，同一作业不混跑
- 用例导入沙箱：解压前校验条目数 / 总体积 / 单文件体积 / 嵌套深度并防 zip slip；只落只读副本，预检只跑 `--collect-only` / `--dryrun`
- 用户组授权：用户组、组-项目角色授权、项目模板，有效角色取「直接 ∪ 组」最高值并按 `(用户, 项目)` 缓存
- 一层测试场景编排（`test_scenario`，不做 DAG）
- AI 辅助抽屉：只探测本地 CLI 不代调模型、上下文复用脱敏、发起执行二次确认、全动作审计、一键终止进程树、无 CLI 时降级为 L1 静态建议
- 三栏布局与可拖拽 AI 抽屉；10 个新页面接入设计 token，中英文案齐全
- `scripts/db-audit.sh` 库结构体检（表数 / 审计四列 / 主键 / 时间列精度 / 字符集）

### 变更 <!-- changed -->
- 导航过滤从单一 `adminOnly` 升级为 `resource:action` 能力集，保留旧接口兼容
- 报告打印基址在开发态仍为 5173，生产由统一入口 8001 承载
- 前端 `request()` 统一由 `BASE_URL` 带 `/api` 前缀，新增模块不再重复书写前缀
- 构建顺序固定为「`packages/editor` → `frontend` → 后端」：前端引用编辑器子包的 dist 产物
- 执行日志订阅改为按「订阅者水位」补发，补发与增量不重叠

### 修复 <!-- fixed -->
- 顶栏此前没有项目选择器，总览页却提示「请在右上角选择项目」的口径不一致
- 关于弹窗版本号只来自 `/_ping`，无法说明 commit 与构建时间
- 代码视图首屏空白：`activeTab` 默认即为代码页，但编辑器初始化只挂在切页动作上，进入页面从不创建实例
- 编辑器只读权限异步到达后不跟随，已加载的文档仍可键入（改为原地重配）
- 构建顺序颠倒导致「改编辑器源码后构建全绿、页面不变」
- 执行日志刷新寄生在执行调度开关上，未开调度时一条日志都不推送
- 日志补发与增量窗口重叠，同一行被推两遍
- 1280 视口整页横向滚动：视图根单列网格轨道按最大内容宽度撑开
- Robot Framework 语法错误时命令行退出码为 0，保存链路只看退出码，导致坏内容能保存成功
- `V8_5`：`test_report` / `import_batch` / `user_group` 补齐 `created_by` / `updated_at` / `updated_by`，与基线审计四列约定一致

### 破坏性变更 <!-- breaking -->
- 无（v0.1–v1.0 的路由、API 与数据表一律不改动）

## v1.0（2026-11-05）

### 新增 <!-- added -->
- 执行域队列语义、设备范围、日志级别过滤、安全级闸门（L2/LX 整单拒绝）
- 报告趋势对比、导出列配置、审计摘要与用户偏好

### 变更 <!-- changed -->
- 状态口径改为 AI 验收放行，人工转旁路建议

### 修复 <!-- fixed -->
- `scripts/db-audit.sh` 业务表数期望值随 V7_6 迁移同步为 47

### 破坏性变更 <!-- breaking -->
- 无

## v0.1–v0.6（2026-11-02 ~ 11-04）

### 新增 <!-- added -->
- 地基 / 台账 / 编辑器 / 执行 / 报告 / 自动化六个版本能力

### 变更 <!-- changed -->
- 版本划分按「用户能拿到什么」切分，一版一门禁单元

### 修复 <!-- fixed -->
- 多轮实测暴露的 iperf3 进程数、metadata、pabot PATH 等口径问题逐条回写

### 破坏性变更 <!-- breaking -->
- 无
