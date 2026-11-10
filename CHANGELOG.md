# CHANGELOG

> 本文件是版本迭代页（NAV-03 / FR-v2.0-12）的数据源，`scripts/build.sh` 会把它同步到
> `frontend/public/CHANGELOG.md`。四类小标题（新增 / 变更 / 修复 / 破坏性变更）必须齐全，
> 版本按倒序排列。
>
> 机器锚点：小节标题后的 `<!-- added|changed|fixed|breaking -->` 与当前版本标题后的
> `<!-- current -->` 是给解析器用的语言无关标识（Markdown 渲染时不可见）。
> 因此代码里不需要硬编码中文，界面显示文案一律走 i18n 语言包 `changelog.*`。

## v4.9.2-ga（2026-11-10） <!-- current -->

### 新增 <!-- added -->
- **成员多角色（4.4.0）**：一个成员可同时持有多个项目角色，权限按并集求值；成员批量添加与角色回显；项目编码由 `GET /api/projects/next-code` 统一发号，支持改码与审核留痕
- **双模式与平台项目（4.5.0）**：新增项目模式 / 全局模式切换；`project.kind='platform'` 单例平台项目承载公共用例与公共资产；`/g/**` 全局命名空间与品牌顶栏、「公共用例 / 公共资产 / 模板中心 / 系统设置」入口
- **公共用例库（4.6.0）**：两级分类树，用例扩展 `external_id` / `content_sha` / `lifecycle_status` / `version_no`；导入「文件 sha256 / 行 external_id / 版本 content_sha」三层幂等，预览零写入、提交单事务；反向导出 14 列固定表头并登记 `export_record`；批次历史与错误报告 CSV；失效 / 恢复
- **公共资产库（4.7.0）**：「当前测试环境」分节解析（设备 / 端口 / 凭据，列名别名容错），业务键 upsert，源文件缺失项软失效；凭据只落引用与掩码，不落明文；删除前按 `project_asset` 引用计数保护（被引用返回 409）
- **模板中心（4.8.0）**：`project_template` / `project_template_item` / `project_template_asset` 持久化，4 个内置模板；模板以**引用**保存用例与资产、不复制正文；创建向导新增「内容」步，选中的公共用例复制进项目用例集、资产按引用挂载
- **存量同步（4.9.0）**：`sync_job` / `sync_job_item` 记录预览与执行；NEW / UPDATE / CONFLICT / UNCHANGED / SKIP 五态差异，冲突逐条选择「保留本地 / 接受公共库 / 另存副本」；执行后可整单回滚；`local_override` 用例永不被同步覆盖；每日预览定时任务默认关闭（`sync.auto.enabled=false`）
- **错误追踪号（4.9.1）**：接口错误展示服务端 `X-Trace-Id`，用户可连同错误码一起复制给运维定位

### 变更 <!-- changed -->
- 新增 8 组迁移 `V11_30`–`V11_37`，均附 `db/down/` 回滚脚本；`test_case` 增公共库列与索引，`import_batch` / `import_item` / `export_record` / `case_version` / `sync_job` / `sync_job_item` 等表建立
- 用例导入改为「预览 → 提交」两段式：预览只读并可重复回放（同一文件 sha 命中已提交批次时不重复建批次）；提交写入用例、步骤、版本快照与审计
- 侧栏「维护与恢复」新增「存量同步」入口（`/sync`）；全局模式导航独立于项目导航，避免项目内页面误挂全局
- 关于页版本与依赖继续取自构建产物 `build-info.properties`，本版发布口径为 `4.9.2`

### 修复 <!-- fixed -->
- 公共用例导入的 `case_code` 按列宽 64 截断，超长编号不再整行失败；`safety_level` 缺省补 `LX`，避免以 NULL 写 NOT NULL 列
- 公共资产删除改为按真实引用计数判定：0 引用可直接删，被引用才返回 409，修正此前平台资产无引用也拒绝删除的问题
- 模板更新接口补齐 `status` 参数；模板内核读取改取项目实际列 `test_kernel`
- 公共分类回填改从历史唯一分类列 `case_kind` 取值，修正迁移引用不存在列导致无法升级的问题
- 全局项目页 `GlobalProjectsView.vue` 对工作台视图的相对导入改为 `../WorkbenchView.vue`，修复 `vite build` 生产构建无法解析模块（部署阶段实测发现，单测与 `vue-tsc` 未拦截）
- `V11_36` 不再重复添加 `project_template` 已存在的审计四列（`created_at` / `created_by` / `updated_at` / `updated_by`），修复真库执行迁移时报 `Duplicate column name 'created_at'`；`db/down/V11_36` 同步不再删除既有审计列（登录 500 的根因是 `project.kind` 缺失，即该迁移未成功应用）
- 顶栏平台名旁不再重复放产品 logo，品牌标记只在侧栏出现；侧栏折叠后显示标记（只藏字标），不再整块隐藏
- 折叠窄轨由 64px 调整为 72px，图标在轨内居中、左右各留 12px，修复收起后偏窄、右侧贴边的问题
- 修复登录后顶栏「全局 / 项目」切换点击无反应：登录发生在 SPA 内时外壳不重挂、能力码未加载，`setMode` 现在按需补拉能力码后再判定

### 破坏性变更 <!-- breaking -->
- 无。新增列均可空或带默认值；新增接口路径为增量；旧数据在迁移中按 `case_kind` / `case_code` 回填，无需人工干预

---

## v4.3.0-project-create-asset-info（2026-11-10）

### 新增 <!-- added -->
- **「新建项目」独立入口**：侧栏「工作台」组新增 `workbench-create` 叶子，路由 `/projects/new` 由 `settings:read` 改为 `project:create`；账户菜单「新建项目」同码控制显隐。`project:create` 只发给 ADMIN 与 PM，ENGINEER / VIEWER 不渲染该入口
- **项目经理可建项目**：`PermissionMatrix` 的 PROJECT 动作集补 `CREATE`（`READ` / `CREATE` / `UPDATE`），单元测试与 `authz-matrix.yaml` 用例 `AUTHZ-PM-PROJECT-WRITE` 由 DENY 改 ALLOW，权限四处（矩阵 / 单测 / 契约样本 / PRD §3.1）同版本落地
- **端口网卡归属**：`V11_29` 给 `device_port` 增可空列 `nic_name` / `nic_index` 与索引 `idx_device_port_nic`，附 down 脚本；`DevicePort` 记录、仓储映射、`PortCommand` 与前端口表单（可选输入，空值即「未归属」）全链路打通
- **资产信息聚合接口**：`GET /api/projects/{projectId}/asset-info`（`scope=connections|topology|all`、`includeDisabled`）一次返回连接明细与拓扑（`dev:{id}` / `dev:{id}/nic:{name}` / `dev:{id}/nic:{name}/port:{name}` 三级节点 ID）；`POST .../asset-info/refresh` 需 `ASSET UPDATE` 并写审计 `asset-info.refresh`
- **两级缓存与主动失效**：片段键 `link:{id}:{contentHash}`、项目键 `project:{projectId}:{指纹}`，默认 TTL 12 小时（`ASSET_INFO_CACHE_TTL_SECONDS:43200`）、单飞合并并发回源、缓存可整体关闭；设备 / 链路 / 资产引用变更在事务提交后按项目前缀失效，支持手工强制刷新
- **「连接与拓扑」页**：新增 `/environment/connections`，元信息条展示生成时间 / 数据来源 / 缓存命中 / TTL，SVG 拓扑按设备→网卡→接口稳定分层，连接明细表支持设备 / 网卡 / 接口三路本地过滤并带空态引导
- **平台资产引用入口（P1）**：资产页新增「引用平台资产」表单，按设备 / 链路 / 凭据类型引用他项目资产；对 `projectId` 非当前项目的行只给「解除引用」，自有资产只给「删除」，避免误删平台资产本体
- **项目停用与彻底删除**：项目卡新增「停用 / 启用」（`PROJECT UPDATE`，落库 `status=0`，数据全保留可恢复）与「彻底删除」（仅 ADMIN，需输入项目 code 二次确认）；`DELETE /api/projects/{id}` 删除前扫描全部 `project_id` 业务表，非空返回 `409` 并逐类报数，成功写 `project:delete` 审计（含 code / name）

### 变更 <!-- changed -->
- 资产信息复用窗口由短时缓存改为**默认 12 小时可配置**，并叠加「资产变更立即失效」与手工刷新两条兜底；新增 3 个环境变量：`ASSET_INFO_CACHE_ENABLED` / `ASSET_INFO_CACHE_TTL_SECONDS` / `ASSET_INFO_CACHE_MAX_PROJECTS`（`application.yml` 中已给默认值 `true` / `43200` / `512`）
- 账户菜单入口显隐从「可见设置即可新建」改为按 `project:create` 判定，避免只读角色看到点了会被后端拒绝的按钮
- 导航能力集补齐 `asset:read`（ADMIN / PM / ENGINEER / VIEWER 均持有），项目内可见范围仍由后端 `AssetScopeSql` 兜底
- 门禁步数维持 **32** 步；OpenAPI 快照重生成 **293 endpoints / 234 paths**（+2 endpoints / +2 paths）

### 修复 <!-- fixed -->
- 连接明细曾按仓储返回顺序输出，同一数据两次请求顺序可能漂移；现按 `linkId` 升序、拓扑按设备→网卡→接口稳定排序，前端 `<select>` 与表格行序不再抖动
- 端口未填网卡时后端归入统一「未归属」分组，前端显示「未归属」而非空白列

### 破坏性变更 <!-- breaking -->
- 无。`V11_29` 只增可空列与普通索引并附 down 脚本，旧行 `nic_name` / `nic_index` 为 `NULL`，接口新增字段均为可选；`asset-info` 为新增路径，未改既有 API 形状

---

## v4.2.0-workspace-closeout（2026-11-10）

### 新增 <!-- added -->
- **项目工作区导出**：`GET /api/projects/{id}/workspace/export` 产出 `project.json` + `manifest.json`（逐文件 `sha256` 与字节数）+ 7 个子目录 + `members.json`；契约 16 项全 PASS，导出件全文本面密钥扫描 0 命中
- **项目工作区导入**：dry-run 预检先列冲突，默认 `skip` 逐条报告；单事务、失败整批回滚；`skip` / `overwrite` / `rename` 三策略幂等；18 项契约全 PASS（路径穿越、非白名单扩展名、`..`、控制字符全拒；篡改 `sha` 与未验签必拒并给原因）
- **三级传递**：整项目 / 子目录 / 单文件三粒度各自可用，9 项契约全 PASS；单文件 `sha` 与源一致、子目录不夹带
- **创建项目向导 + 4 模板**：5 步（viewer 4 步）每步可回退且无副作用，4 套版本化 JSON 模板；提交后真库 `projects 11 → 12`、`members=2`、`kernel.default=robot`、`audit.project:create=1`
- **工作区 UI 与任务中心**：导出包 / 导入预检 / 任务三视图统一到项目 `DataTable` 壳；任务失败行可单独重试，重试入口真实调用后端
- **远程主机只读采集**：`SshHostCommandRunner` + `HostInventoryCollector`；`StrictHostKeyChecking=yes` + 显式 `UserKnownHostsFile`、`BatchMode=yes`、`sudo` / `su` / `doas` 启动前拒绝、口令只进 `SSHPASS` 不进 `argv`、私钥临时文件 `0600` 用完即删、单条输出上限 256 KiB；单测 18 例、契约 29 项全 PASS
- **CI 无 DB 质量门**：`delivery.yml` 构建前跑后端非 IT 全量单测（1301 例 / 0 失败 / 4 例按环境变量条件跳过）与前端单测，`DB_PASSWORD` 不入 workflow
- **`db/schema-contract.json` 同源契约**：`SchemaIT` 与 `db-audit` 直读同一份常量；新增 `appendOnlyTimeColumns`，明确「`created_at` 或业务时间锚点二选一且互斥」
- **`V11_28` 工作区迁移**：新建工作区相关表并附 `down` 脚本，`TenantGuard` 登记

### 变更 <!-- changed -->
- 命令脱敏扩到 `-u user:pass` 写法，报告 / 打印 / docx / API / 审计 / 运行日志 / 控制台 / 导出包八面明文 0 命中；`-u username` 等合法写法不误脱敏
- 顶栏入口统一「工作区浮层」范式：浮层 `boundingBox.top >= header.bottom`，遮罩不盖顶栏；嵌套浮层逐层 ESC、焦点归还
- 账户下拉四分区（状态 / 外观 / 管理 / 会话）：状态区只读、加载期 0 写请求、非 admin 不渲染管理入口且键盘可达、登出 danger + 二次确认
- 折叠态留白：窄轨 `56 → 64px`、右内边距 `20 → 28px`，5 处同步走令牌；四档视口 + 125% / 150% 缩放无横滚
- 可回滚升级：默认 dry-run、`--apply` 二次确认、同版本拒绝、降级需 `--force --restore-db`、生产库名拦截；`status.sh` 显示上一可用版本并保留 3 版
- 门禁 29 → **32** 步；OpenAPI 快照重生成 **291 endpoints / 232 paths**；业务表 88 → **89**

### 修复 <!-- fixed -->
- 门禁第 6 步阻断：`WorkspaceView.vue` 三张原生 `<table>` 改为项目 `DataTable` 壳，列定义抽成 `computed<DataTableColumns<T>>`；未扩白名单、未降门禁
- `SchemaIT` 用 `-Dgroups=it` 被默认 `excludedGroups=it` 抵消成 0 用例却 `BUILD SUCCESS` → 改 `-Pit`（`DB_NAME=robot_it`）并在类注释写明
- append-only 判据强制 `created_at` 在真库必然判红（`alert_event` / `archived_run` / `release_record` 用业务时间锚点）→ 契约显式二选一互斥，负向样本仍判红
- `GET /collect` 被 `@GetMapping("/{id}")` 吃掉回 400 → 改 `{id:\d+}`，现正确回 405

### 破坏性变更 <!-- breaking -->
- 无。`V11_28` 为新建表并附 `down` 脚本；`VERSION` 由 `3.11.0` 直接落 `4.2.0` 属计划内跨版本落号，未改任何 API 路径或包名。

---

## v3.11.0-asset-pages（2026-11-09）

### 新增 <!-- added -->
- **测试资产主机清单**：`V11_26` 建 `device_host_spec`，10 类只读命令（OS / 内核 / CPU / 内存 / 磁盘 / GPU / 网卡 / IP / RoCE / 路由）原样存进 `spec_json`；采不到的项写「未采集到 + 原因」，不用 `0` / `-` / `未知` 占位；本机实测 `PARTIAL 9/10`，缺的 GPU 原因是 `sh: 1: nvidia-smi: not found`
- **显式采集入口**：`POST /collect` 只挂在「立即采集」按钮且需二次确认，`GET /collect` 回 405；命令白名单 `GET /commands` 公开可查
- **环境与拓扑 5 页接真实数据**：总览 / 采集 / 拓扑快照 / 环境准备 / 采集分析告别恒空占位；补 `GET /snapshots/profiles` 列出环境名
- **维护与恢复 3 页接真实数据**：执行原则读真实设置、恢复记录读真实 `GET /audit` 的 `case.batch.undo.*`、只读找回走 `GET /search?types=run`；不取 `detail`、不展示命令正文、不产生新 run
- **门禁第 29 步**：`tools/check_host_inventory.py` 校验 10 类白名单、未采到原因、接口自述与源码逐字一致，带 20 组正负样本自证
- **V11_27**：`env_profile` 去掉同名唯一键改普通联合索引，同一环境可存多份快照，两快照 diff 才成立

### 变更 <!-- changed -->
- 只读页判据升级为「源码级无写函数 + 真浏览器录 `Network.requestWillBeSent`，加载期 0 个写请求」，交互期写操作必须二次确认
- 门禁步骤 28 → **29**；OpenAPI 快照重生成 **284 endpoints / 225 paths**；i18n 中英各 **1680 键**
- 8 页筛选控件统一用原生 `<select>`：happy-dom 下 `NSelect` 虚拟列表不渲染选项，取证不稳
- 两份真实采集 + 两份真实资产快照均由真实 HTTP 写入，夹具不塞 SQL 假行

### 修复 <!-- fixed -->
- 主机清单列表 `specJson` 由空串改为 `null`，前端不再把「这一栏没查」当成「清单是空的」
- `@GetMapping("/{id}")` 把 `GET /collect` 吃掉回 400 → 改 `{id:\d+}`，现正确回 405
- 同名环境第二份快照因 `uk_env_profile_project_name` 唯一键 500 → 追加 `V11_27`
- `http.ts` 对 GET 有短时缓存，只读找回同参复搜不发请求；探针改为先改关键字再判「只发 GET /search」
- 详情路由、列表 null 等修复只在 WSL 权威副本改过才算数：首跑漏同步导致 jar 里仍是旧类，补同步后重建重启才生效

### 破坏性变更 <!-- breaking -->
- 无。`V11_26` 为新建表、`V11_27` 只改索引，两者均附 down 脚本并做过真实 down→up 演练。

---

## v3.10.0-pick-and-plan（2026-11-09）

### 新增 <!-- added -->
- **计划页信息架构**：首屏主操作 9 → 5、整页可点控件 64 → 30、每行 4 → 1（「更多」菜单 4 项）；冲突处理与跳过事件各成独立区域并默认收起；16 项原功能逐项有对照表
- **报告草稿选执行记录**：`RunPicker` 下拉列最近 100 条 run（ID / 时间 / 结论 / 用例数四段文案），支持关键词与 7d/30d 时间档，选中自动带出标题；手打 `RUN_ID` 降级为「高级」
- **报告草稿用例集来源**：`V11_25` 给 `test_report` 加 `source_type` / `case_set_id`，可选「按用例集」建草稿；冻结快照整段内联用例事实，删掉源 run 后渲染逐字不变（566 字节）
- **迁移演练脚本参数化**：`migration-down-up-drill.sh` 从 up 脚本解析目标表/列并支持 `MODE=table`，不再拿硬编码的 `test_result` 去「通过」另一张表

### 变更 <!-- changed -->
- 计划页首屏高度 2.63 → 1.18 屏；报告草稿页由单来源改为「按执行记录 / 按用例集」双来源，旧 `create` 三参签名不动
- 横向回归基线：前端 80 文件 / 567 条、后端 1262 条 / 0 失败 / 4 跳过；`vue-tsc` 无输出、i18n 1607 键、`vite build` 成功

### 修复 <!-- fixed -->
- 冻结版本号按「本报告 + 1」分配，同 run 两份报告会撞唯一键 `uk_report_run_version` → 改按 run 全局 `maxVersion + 1`，HTTP 500 消失
- 迁移演练脚本首版对 `V11_25` 假 PASS（检查了错误的表）→ 重写为解析目标表/列并打印实际检查项
- `RunPicker.spec` 的「未选项目不发请求」读到上一条用例的调用计数 → `vi.clearAllMocks()` 移到 `beforeEach` 首位

### 破坏性变更 <!-- breaking -->
- 无。`V11_25` 两列带 `DEFAULT 'run'` 回填旧行、均附 down 脚本与真实演练。

---

## v3.9.0-traceability（2026-11-09）

### 新增 <!-- added -->
- **用例级「下发命令」贯通**：`V11_24` 给 `test_result` 增 `command_text` / `command_source` /
  `command_exit_code`；`CaseCommandExtractor` 从 RF 关键字子树（含子关键字 `Log` 消息）与
  pytest 的 setup/call/teardown stdout 提取命令、来源与退出码，脱敏后入库；报告页、打印页、
  docx 导出三处都能看到这一行
- **报告页命令行交互**：超长命令折叠/展开、一键复制、跳转原始日志；pytest 日志落盘为
  `case-log.jsonl`（全局序号），日志抽屉按用例区间定位，不再整页空
- **报告字段模板**：21 个字段分 4 张卡（身份 / 统计 / 环境 / 合规），4 个预置模板
  （简版 / 标准 / 完整 / 审计），必填项锁定不可关
- **列表页统一页大小**：20 / 50 / 100 / 200 四档，选择结果本地记忆，切页不回到第一页
- **门禁第 28 步**：`tools/check_case_command.py` 校验命令链静态契约与双内核覆盖率，
  带 `--self-check` 六类脏数据自证；`tools/case_command_report.py` 产出覆盖率证据

### 变更 <!-- changed -->
- 列表页密度：`/cases` 分页器以下空白 337px → 36px、`/runs` 231px → 36px；
  首屏行数按视口自适应，不再固定 10 行（判据 ≤120px）
- 报告字段模板页从 1 屏压到 0.94 屏（`/reports/templates`）
- 侧栏折叠改为可逆：三角按钮不再被品牌名遮挡，折叠后菜单文字保留图标提示，再点可还原
- 关于页与版本迭代改为一处读源（`build-info` + `CHANGELOG`），版本号四副本由门禁第 23 步守护
- 门禁步骤总数 27 → 28

### 修复 <!-- fixed -->
- 暗色主题下拉控件白底浅字读不出来：全站 `select` 统一不透明底 + 前景对比 ≥4.5
- 侧栏折叠后一二级菜单文字全消失、侧栏空间没收住、点不回来
- pytest 报告日志抽屉恒为空（日志没落盘 + 定位器先试 output.xml 命中 json）
- 报告用例节点在「本来没下发命令」与「v3.9.0 之前未采集」两种情况下都显示空白，
  现在分别显示「本用例未下发外部命令」与「未采集（v3.9.0 之前的报告）」

### 破坏性变更 <!-- breaking -->
- `test_result` 新增 3 个可空列；v3.9.0 之前的历史报告不回填，页面按「未采集」显示
- `GET /api/reports/{id}/tree` 的用例节点新增 `commandText` / `commandSource` /
  `commandExitCode` 三字段（可为 `null`），严格校验响应结构的下游需同步
- 门禁步骤数变为 28：脚本化解析 `gates.sh` 输出的外部流水线需同步步骤号

## v3.8.0-ga-ready（2026-11-08）

### 新增 <!-- added -->
- 多节点与现场能力承接、跨浏览器矩阵与试点验收、GA 检查单三份文档化产物；
  GA 检查单只给模板不预填 `PASS`

### 变更 <!-- changed -->
- 发版门禁口径改为「与既有 G4/G5/G7/G8 配合，不代判、不申请」

### 修复 <!-- fixed -->
- 无（本版为准备性版本，未引入代码修复）

### 破坏性变更 <!-- breaking -->
- 无

## v3.7.0-harden（2026-11-08）

### 新增 <!-- added -->
- 新能力运营化（默认策略 / 降级 / 演练 / 成本四件套）、韧性测试、安全与依赖治理、
  变更管理与发布记录、容量与成本读数、可观测性深化、备份恢复与演练

### 变更 <!-- changed -->
- DORA 只建读数不设考核；变更管理引入特性开关与发布记录

### 修复 <!-- fixed -->
- 备份恢复演练暴露的 RTO/RPO 口径不统一，按演练脚本固化判据

### 破坏性变更 <!-- breaking -->
- 无

## v3.6.0-refine（2026-11-08）

### 新增 <!-- added -->
- 搜索与保存视图精修、统一状态矩阵与错误文案、设计系统与视觉一致性收口、
  报告可读性与证据链精修、AI 能力可控可退

### 变更 <!-- changed -->
- WCAG 对比度与键盘可达按 AA 收口；表单与前端交互性能精修

### 修复 <!-- fixed -->
- 状态文案在不同页面口径不一，统一走一张状态矩阵

### 破坏性变更 <!-- breaking -->
- 无

## v3.5.0-measure-close（2026-11-08）

### 新增 <!-- added -->
- 效果度量最小体系（Top 任务、CWV、WCAG 与跨浏览器基线、`REG-*` 回归集）、度量看板、
  遗留与现场项统一台账

### 变更 <!-- changed -->
- 门禁扩到 27 步，效果度量与安全扫描进门禁；基线固化后可复现

### 修复 <!-- fixed -->
- 上一轮承诺逐条判定并留授权痕迹，未兑现项转台账而非口头挂账

### 破坏性变更 <!-- breaking -->
- 无

## v3.4.0-scale-archive（2026-11-08）

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

> **未发版工作记录（2026-11-09，3.5+ 存量打磨）** —— 本段为引用块，
> 不使用 `## ` 标题，避免被 `frontend/src/config/changelog.ts` 解析成新版本 release。

> 本段是**历史工作记录，不是版本发布**；其中「`VERSION` 仍为 `3.4.0`」是 **2026-11-09 当时**的读数，
> 当前 `VERSION` 已是 `3.11.0`。G4/G5/G7/G8 未判、G6 观察钟未启动，
> 该轮不产生对外口径、不申请发版门禁判定。

| 批次 | 内容 | 结论 |
|---|---|---|
| B0 | 基线固化：v3.1–v3.4 实现入库、模式位噪声清理、双远程同步 | 两仓 + 双远程 sha `2278fb9` 一致；暂存 355 文件、0 个 mode change |
| B1 | 27 步同批次全量门禁 + 负向自检 | 首次 `FAILS=2`（02 前端偶发超时 → 19 连锁）；按 flaky 台账复跑定位后只放宽超时余量，复跑 **`FAILS=0`**，selfcheck `FAILS=0` |
| B2 | 效果度量体系、承诺判定表、统一台账、看板 v1、REG 扩容 | 机检全绿；真人样本类判据 `BLOCKED` |
| B2 | 真实浏览器 CWV / WCAG 2.2 探针 | 实验室 p75 LCP 148ms / INP 16ms / CLS 0.015 三项达阈；**发现 18 个 <24×24 交互目标（`/runs` 页 12 个）**，列入整改 |
| B3–B6 | 黄金集扩至 100 条、运行手册 8 场景、安全自评、DORA 读数 | 离线契约评测 PASS；缺真人/真机/维护窗口的判据如实 `PARTIAL` / `BLOCKED` |
| ⑦ | 文档回写 | 四版六份产物、评估报告修订记录、PRD 验收状态、计划偏差、测试差异全部回写 |

新增工具：`tools/ux_metrics.py`、`check_ledger.py`、`check_reg_catalog.py`、`ai_golden_eval.py`、
`dora_metrics.py`、`scripts/ux-probe.mjs`；新增 ADR-35-01..10 与特性开关清单。

---

> **未发版工作记录（2026-11-09，交付与运维辅助能力）** —— 同样是引用块，
> 不使用 `## ` 标题，避免被 `frontend/src/config/changelog.ts` 解析成新版本 release。

> 本轮**不 bump 版本号、不创建 GitHub Release、不产生对外发版口径**：`VERSION` 仍为 `3.11.0`，
> 当前版本标题仍是 `v3.11.0-asset-pages`。G4/G5/G7/G8 依旧未判。

| 类别 | 内容 | 结论 / 证据 |
|---|---|---|
| 新增 | `scripts/ops/` 九个同源脚本 | `doctor` / `install` / `uninstall` / `ctl` / `status` / `logs` / `backup` / `db-migrate` / `common`；写操作默认 dry-run |
| 新增 | `scripts/package-release.sh` | 产出 linux-x64 tarball + `.sha256` + 逐文件 `MANIFEST.json`，不含凭据 / 日志 / 依赖目录 |
| 新增 | `.github/workflows/delivery.yml` | GitHub-hosted `ubuntu-latest` 构建 → 机检 → 打包 → 解包自检 → 上传 Actions artifact |
| 新增 | `tools/check_delivery_bundle.py` | 静态契约 + 产物实证 + 七类脏数据自校验；接入**门禁第 30 步**（29 → 30） |
| 新增 | `docs/DELIVERY-GUIDE.md` / `scripts/ops/README.md` | 安装 / 拆卸 / 运维 / 排障全流程与命令矩阵 |
| 新增 | `deploy/env.example` / `deploy/release/` 两个 unit 样例 | 凭据模板无明文；入口 unit 显式给 `ENTRY_DIST` |
| 变更 | `README.md` 重排 | 原「能做什么」单行数百字宽表拆成 4 张分组表；状态区更新到 3.11.0 / 30 步 / 88 表 / 284 endpoints |
| 变更 | 「关于」页与「版本迭代」页 | 关于页新增「交付与运维」入口卡；版本迭代页新增交付提示；文案全部走 i18n |
| 变更 | `docs/plan/` 下一轮三份文档 | `v3.12+交付运维演进` 的调研评估 / PRD / 开发与测试计划 |
| 修复 | 顶层 `install.sh` 找不到 `common.sh` | 解包自检实测：改为薄 wrapper 转调 `bin/install.sh`，不再复制第二份实现 |
| 修复 | 停服务会误停全局 systemd unit | 真拆卸实测：加 `var/systemd-managed` 托管标记，`ctl.sh` / `uninstall.sh` 无标记只动本安装根 pid 文件 |
| 口径 | 门禁步骤数 29 → **30** | 脚本化解析 `gates.sh` 输出的外部流水线需同步步骤号（与 v3.9.0 同类变更） |

> 未做（按评估 §3.2）：公开 Release 与匿名下载、deb/rpm、Docker 镜像、Windows 原生安装包、
> CI 内跑真 MySQL / 真浏览器 / 全量门禁、改动既有 `pr.yml`。这些留待大师拍板或下一轮。

---

> **未发版工作记录（2026-11-09，4.X 总体演进规划）** —— 同样是引用块，
> 不使用 `## ` 标题，避免被 `frontend/src/config/changelog.ts` 解析成新版本 release。

> 本轮**只交付规划文档，未实装、未测试、未 bump 版本、未判 G 门**：`VERSION` 仍为 `3.11.0`，
> 当前版本标题仍是 `v3.11.0-asset-pages`。大师补充的 7 项需求经代码级调研后形成 4 份文档，
> 版本线建议 `4.0.0` → `4.1.0` → `4.2.0`，并**收编** `v3.12+` 三份计划（原文档保留并加收编标注）。

| 类别 | 内容 | 结论 / 证据 |
|---|---|---|
| 新增 | `v4.x-总体演进-调研与评估` | 20 条已核实事实 / 7 条推断 / 6 条假设；目录化四方案比较；10 条风险、8 项决策 |
| 新增 | `v4.x-总体演进-产品PRD` | 13 条正式 FR（4.0 五条 / 4.1 四条 / 4.2 四条）+ 4 条候选、11 条 ADR、7 项需求追溯 |
| 新增 | `v4.x-总体演进-开发计划` | 17 个工作包、验收命令、迁移部署、门禁取证、⑦ 回写清单 |
| 新增 | `v4.x-总体演进-测试计划` | 55 条 TC、8 条已知取证缺口、放行命令 |
| 变更 | `v3.12+` 三份计划 | 头部加「已被 4.X 收编」标注，不再作为执行依据 |
| 变更 | `README.md` / `docs/release/README.md` | 路线图与版本地图指向 4.X 规划 |
| 口径 | 目录化策略 | **DB 为运行时唯一真源，`workspace/` 为显式导入导出的交换格式**，不做双真源 |
| 口径 | 「切换用户」 | 登出重登，不引入 impersonation |

> 未做（按评估 §9）：不换文件系统、不做双真源自动同步、不导出凭据明文、不做 deb/rpm/Docker/Windows 包、
> 不在 hosted CI 跑真库 / CDP / 全量门禁、不创建公开 Release、不写周报能力。
> **4.X 同属「待评审 / 未执行」**：⑥ 测试执行与 ⑦ 文档回写尚未发生，不得对外称已交付。
