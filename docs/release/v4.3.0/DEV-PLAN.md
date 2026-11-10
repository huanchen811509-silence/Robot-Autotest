# v4.3.0 项目创建与资产信息 · 开发计划（DEV-PLAN）

| 项 | 内容 |
|---|---|
| 文档 | `docs/plan/v4.3.0-项目创建与资产信息-开发计划-20261110.md` ｜ v1.0 ｜ 2026-11-10 |
| 编号 | `RA-DEV-43-001` |
| 流程位置 | ④ 开发计划（**无开发计划不许开工编码**） |
| 上游 | `v4.3.0-项目创建与资产信息-产品PRD-20261110.md`（`RA-PRD-43-001`）、`项目创建菜单与测试资产获取-评估与设计-20261110.md`（`RA-EVAL-43-001` v1.1 §10） |
| 下游 | `v4.3.0-项目创建与资产信息-测试计划-20261110.md`（`RA-TEST-43-001`） |
| 状态 | **待评审 / 未开工**。本文不含实装、不 bump 版本、不改生产代码 |
| 基线 | `VERSION=4.2.0`；前端 7 组导航硬断言；后端 `PermissionMatrix` 112 格授权契约；最新迁移 `V11_28` |

---

## 1. 基线、范围与纪律

### 1.1 开工基线（`W43-00` 须现场核实并留档）

| # | 基线项 | 核实命令 | 记录 |
|---|---|---|---|
| B1 | 版本与门禁步数 | `cat VERSION`、`bash scripts/gates.sh --list` | 版本号、步数与编号 |
| B2 | 三端测试读数 | `mvn -o -B test`、`npx vitest run` | 用例数 / 失败数 |
| B3 | 迁移最新版本 | `ls db/migration \| sort -V \| tail -3`、`ls db/down \| tail -3` | 期望 `V11_28` 为最新正向 |
| B4 | 授权契约现状 | `grep -n "AUTHZ-PM-PROJECT-WRITE" backend/src/test/resources/authz-matrix.yaml` | 当前 `DENY` + reason 原文 |
| B5 | 导航现状 | `npx vitest run src/config/navigation.spec.ts` | workbench 子路径断言原文（3 项） |
| B6 | 服务就绪 | `curl -s --noproxy '*' 127.0.0.1:8081/actuator/health`、`curl -s --noproxy '*' 127.0.0.1:8001/_ping` | 200 才算就绪；`8001` 自回 200 不等于后端就绪 |
| B7 | 真库可见 | `mysql -e "select count(*) from device; select count(*) from link; select count(*) from device_port;"` | 测试项目 id 与三表计数 |
| B8 | i18n 检查器路径 | `ls scripts/tools/check_i18n.py tools/check_i18n.py 2>/dev/null` | 实际路径写入 §7 |

### 1.2 范围

- **在做**：`FR-v4.3-01` ~ `FR-v4.3-07`（P0）、`FR-v4.3-08`（P1）、`FR-v4.3-09`（项目停用与空项目删除）。
- **不做**：外部布线系统对接、后台定时远端采集、`topology_node` 改造、向导步骤重排、拓扑历史版本、多实例分布式缓存（见 PRD §2.2）。
- **不改**：`TopologySnapshotView` 既有手工快照、既有设备/链路字段语义、既有项目创建事务语义。

### 1.3 纪律

1. 一个工作包一个 PR；`W43-01`（权限四处联动）必须整体提交，禁止拆成两个 PR 造成门禁中间态红。
2. 不改测试迁就实现；不降门禁；不改范围外代码。
3. 构建/测试**只在 WSL 权威副本**执行（`~/robot-autotest`）；Windows 侧仅编辑与查看，9P 上禁止构建。
4. 迁移脚本一经应用不可改字节；回滚走 `db/down/`。
5. 版本 bump 由大师书面确认后执行，本计划不自行改号。

---

## 2. 工作包与顺序

| 包 | 内容 | 覆盖 FR | 前置 | 验收命令 | 交付物 |
|---|---|---|---|---|---|
| `W43-00` | 基线快照与开工确认 | — | 本计划评审通过 | §1.1 B1–B8 全部命令 | 基线快照（版本/门禁步数/测试读数/真库计数/授权契约原文），落 `docs/evidence/v4.3.0/EW43-00/` |
| `W43-01` | 权限与能力码（四处联动） | `FR-v4.3-02` | `W43-00` | `mvn -o -B test -Dtest=PermissionMatrixTest,AuthzMatrixTest` | `PermissionMatrix.java` PM 加 `PROJECT CREATE`；`PermissionMatrixTest` 改断言；`authz-matrix.yaml` `AUTHZ-PM-PROJECT-WRITE` 改 `ALLOW`；PRD §3.1 回写 |
| `W43-02` | 导航叶子与账户下拉口径 | `FR-v4.3-01` | `W43-01` | `npx vitest run src/config/navigation.spec.ts` | `navigation.ts` 新增 `workbench-create`；`ROLE_CAPABILITIES` 加 `project:create`；`router/index.ts` `/projects/new` meta 改码；`AccountMenu.vue` 显隐改码；`zh-CN.ts`/`en-US.ts` 加 `menu.newProject`；`navigation.spec.ts` 子路径 3→4 项 + 已选项目仍呈现断言 |
| `W43-03` | 迁移与端口网卡字段 | `FR-v4.3-03`（R1） | `W43-00` | `bash scripts/migration-down-up-drill.sh`、`DB_NAME=robot_it mvn -o -B -Pit test -Dtest=SchemaIT` | `db/migration/V11_29__v4_3_device_port_nic.sql`、`db/down/V11_29__v4_3_device_port_nic.down.sql`、`DevicePort` 记录 + 仓储 + `DeviceController` 端口 DTO 增 `nicName/nicIndex` |
| `W43-04` | 派生服务与两级缓存 | `FR-v4.3-03`、`FR-v4.3-04`、`FR-v4.3-05` | `W43-03` | `mvn -o -B test -Dtest=AssetInfoServiceTest,AssetInfoCacheTest` | 新增 `AssetInfoService`（连接/拓扑派生）、`AssetInfoProperties`（`asset-info.cache.ttl-seconds=43200`）、内容指纹、项目指纹、单飞、别名组装期套用 |
| `W43-05` | 接口、事件失效与审计 | `FR-v4.3-05`、`FR-v4.3-06` | `W43-04` | `bash scripts/asset-info-contract.sh`、`python3 tools/gen_api_contract.py --check` | `AssetInfoController`（GET + POST refresh）+ DTO；`share/reference/unreference` 与设备/端口/链路写事件 `AFTER_COMMIT` 失效；审计 `asset-info.refresh`；OpenAPI 快照 +2 path |
| `W43-06` | 前端「连接与拓扑」页 | `FR-v4.3-07` | `W43-05`、`W43-02` | CDP 页面探针、`npx vitest run`、i18n 检查 | `frontend/src/views/environment/ConnectionsView.vue`、`frontend/src/api/assetInfo.ts`、`router/index.ts` 新路由、`navigation.ts` 环境组新增叶子、元信息条 / 刷新按钮 / 空态 / 筛选 |
| `W43-07` | 资产分配最小入口（P1） | `FR-v4.3-08` | `W43-05`、`W43-06` | CDP 引用/解除引用流程、真 HTTP 403 负向 | `AssetsView.vue` 引用与解除引用操作 + `frontend/src/api/asset.ts` 补 `reference/unreference`；权限失败提示不清空表单 |
| `W43-08` | 横向回归 / 门禁 / 证据回写 | 全部 | `W43-01` ~ `W43-07` | `bash scripts/gates.sh`、`bash scripts/gates.sh --selfcheck`、`python3 tools/check_release_docs.py` | 门禁日志、`docs/evidence/v4.3.0/EW43-*/` 索引、`docs/release/v4.3.0/` 六份产物、偏差记录回写 |
| `W43-09` | 项目停用与空项目彻底删除 | `FR-v4.3-09` | `W43-08` | `mvn -o -B test -Dtest=ProjectServiceDeleteTest`、`vitest run src/components/ProjectCard.spec.ts`、真 HTTP `PUT/DELETE` | `ProjectRepository#busyCounts`（`information_schema` 动态发现 `project_id` 表）、`ProjectService#delete` 前置校验 409、`ProjectCard` 停用/删除入口 + 输入 code 弹窗、中英词条 |

**顺序纪律**

1. `W43-01` 未绿，不得开 `W43-02`——否则导航会放出「能看见入口但后端仍 403」的新缺陷。
2. `W43-03` 迁移未在 `robot_it` 演练通过（正向 + `down` + 再正向），不得开 `W43-04`。
3. `W43-04` 的缓存键必须**先有单测**再写控制器；别名进入缓存键视为 P0 缺陷（PRD K2）。
4. `W43-05` 的事件失效必须覆盖 `share/reference/unreference` 三条与设备/端口/链路写路径，缺一条则 `W43-08` 不放行。
5. `W43-06` 不得复用 `TopologySnapshotView` 的前端拼装逻辑；数据只来自 `asset-info` 接口。
6. `W43-07` 为 P1，可与 `W43-06` 并行；未完成时 `FR-v4.3-08` 记 `未交付`，不阻断 P0 收口。
7. 每个包收口即在 `docs/evidence/v4.3.0/<包号>/README.md` 留读数与命令原文，不做事后补记。

---

## 3. 文件级改动清单

### 3.1 后端

| 类型 | 文件 | 改动 |
|---|---|---|
| 改 | `backend/src/main/java/com/qa/autotest/domain/iam/PermissionMatrix.java` | PM 的 `PROJECT` 动作集合增加 `CREATE`（约 L43–L70 区块） |
| 改 | `backend/src/test/java/com/qa/autotest/domain/iam/PermissionMatrixTest.java` | 约 L120–L125「PM 不能建项目」断言改为「PM 可建项目」；补一条 ENGINEER/VIEWER 仍拒 |
| 改 | `backend/src/test/resources/authz-matrix.yaml` | `AUTHZ-PM-PROJECT-WRITE`：`expected: ALLOW`，`reason` 改为「PM 可新建项目（v4.3.0）」 |
| 改 | `backend/src/main/java/com/qa/autotest/domain/asset/DevicePort.java` | 记录增 `nicName`、`nicIndex`（可空，置于 `speedGbps` 之后） |
| 改 | `backend/src/main/java/com/qa/autotest/infrastructure/` 端口仓储实现 | 端口 INSERT / UPDATE / SELECT 增 `nic_name`、`nic_index` 列映射 |
| 改 | `backend/src/main/java/com/qa/autotest/api/asset/DeviceController.java` | 端口请求/响应 DTO 增 `nicName`、`nicIndex`（可空，不校验必填） |
| 改 | `backend/src/main/java/com/qa/autotest/application/asset/ProjectAssetService.java` | `share` / `reference` / `unreference` 成功后发布失效事件（事务提交后生效） |
| 新 | `backend/src/main/java/com/qa/autotest/application/asset/AssetInfoService.java` | 连接明细派生 + 拓扑派生 + 两级缓存 + 单飞 + 别名组装 |
| 新 | `backend/src/main/java/com/qa/autotest/application/asset/AssetInfoProperties.java` | `asset-info.cache.enabled`（默认 true）、`asset-info.cache.ttl-seconds`（默认 43200） |
| 新 | `backend/src/main/java/com/qa/autotest/api/asset/AssetInfoController.java` | `GET /api/projects/{projectId}/asset-info`、`POST .../refresh` |
| 新 | `backend/src/main/java/com/qa/autotest/api/asset/dto/`（连接/拓扑 DTO） | `AssetInfoResponse`、`ConnectionItem`、`TopologyNode`、`TopologyEdge`、`TopologyStats`、`AssetInfoMeta` |
| 新 | `backend/src/main/java/com/qa/autotest/application/asset/AssetInfoInvalidator.java` | 监听资产写与引用变更事件，按资产/项目维度失效 |
| 新 | `backend/src/test/java/com/qa/autotest/application/asset/AssetInfoServiceTest.java` | 连接/拓扑派生、未归属网卡、排序稳定性、禁用资产标记 |
| 新 | `backend/src/test/java/com/qa/autotest/application/asset/AssetInfoCacheTest.java` | 缓存键不含别名、指纹变化即失效、12h 过期、单飞并发 |
| 新 | `backend/src/test/java/com/qa/autotest/api/asset/AssetInfoControllerTest.java` | 403/404/400、scope 解析、refresh 审计 |

### 3.2 前端

| 类型 | 文件 | 改动 |
|---|---|---|
| 改 | `frontend/src/config/navigation.ts` | workbench 组新增 `{ key:'workbench-create', labelKey:'menu.newProject', icon:'plus', path:'/projects/new', capability:'project:create' }`（置于 `workbench-board` 之后）；环境组新增 `{ key:'environment-connections', labelKey:'menu.envConnections', icon:'link', path:'/environment/connections' }` |
| 改 | `frontend/src/config/navigation.ts`（`ROLE_CAPABILITIES`，约 L398–L419） | admin、pm 追加 `'project:create'` |
| 改 | `frontend/src/router/index.ts`（约 L29–L35） | `/projects/new` 的 `meta.capability` 由 `settings:read` 改为 `project:create` |
| 改 | `frontend/src/router/index.ts` | 新增 `/environment/connections` 路由（`meta.capability: 'asset:read'`） |
| 改 | `frontend/src/components/AccountMenu.vue`（约 L57、L194–L207） | 新建项目入口显隐由 `settings:read` 改为 `project:create` |
| 改 | `frontend/src/config/navigation.spec.ts`（约 L223–L231 等） | workbench 子路径严格等于 4 项；新增「已选项目时 `workbench-create` 仍存在」断言；四角色快照更新 |
| 新 | `frontend/src/views/environment/ConnectionsView.vue` | 拓扑图区 + 连接明细表 + 元信息条 + 刷新按钮 + 空态 + 本地筛选 |
| 新 | `frontend/src/api/assetInfo.ts` | `getAssetInfo(projectId, scope, includeDisabled)`、`refreshAssetInfo(projectId, scope, force)` |
| 改 | `frontend/src/api/asset.ts` | 补 `referenceAsset` / `unreferenceAsset`（对应后端 `POST/DELETE /api/projects/{id}/assets/{type}/{id}`） |
| 改 | `frontend/src/views/AssetsView.vue` | 平台资产行增加「引用」「解除引用」操作；403 提示保留表单 |
| 改 | `frontend/src/locales/zh-CN.ts`、`en-US.ts` | 新增 `menu.newProject`、`menu.envConnections`、`connections.*`（标题/列头/元信息/空态/刷新确认/筛选/错误） |

### 3.3 数据与契约

| 类型 | 文件 | 改动 |
|---|---|---|
| 新 | `db/migration/V11_29__v4_3_device_port_nic.sql` | `ALTER TABLE device_port ADD COLUMN nic_name VARCHAR(64) NULL, ADD COLUMN nic_index INT NULL, ADD KEY idx_device_port_nic (device_id, nic_name, nic_index);` |
| 新 | `db/down/V11_29__v4_3_device_port_nic.down.sql` | `DROP KEY` + `DROP COLUMN` 两列（社区版无 undo，手写） |
| 新 | `docs/contracts/openapi.json`（重生成） | +2 path：`GET /api/projects/{projectId}/asset-info`、`POST /api/projects/{projectId}/asset-info/refresh` |
| 改 | `frontend/public/CHANGELOG.md` | 与根 `CHANGELOG.md` 同步 |
| 改 | `VERSION` | `4.2.0` → `4.3.0`（**仅在落号授权后**） |

### 3.4 证据与发布

| 类型 | 路径 | 内容 |
|---|---|---|
| 新 | `docs/evidence/v4.3.0/EW43-00/README.md` | 基线快照 |
| 新 | `docs/evidence/v4.3.0/EW43-01/README.md` | 单测 + YAML diff |
| 新 | `docs/evidence/v4.3.0/EW43-02/README.md` | vitest + 四角色快照 + 已选项目断言 |
| 新 | `docs/evidence/v4.3.0/EW43-03/README.md` | 迁移演练日志 + `SchemaIT` |
| 新 | `docs/evidence/v4.3.0/EW43-04/README.md` | 派生/缓存单测读数 |
| 新 | `docs/evidence/v4.3.0/EW43-05/README.md` | 契约脚本 + 审计 + OpenAPI diff |
| 新 | `docs/evidence/v4.3.0/EW43-06/README.md` | CDP 截图/读数 + i18n |
| 新 | `docs/evidence/v4.3.0/EW43-07/README.md` | P1 流程与 403 负向 |
| 新 | `docs/evidence/v4.3.0/EW43-08/README.md` | 门禁与自检日志 |
| 新 | `docs/release/v4.3.0/` | 六份产物（调研/评估/PRD/DEV-PLAN/TEST-PLAN/05-TEST-REPORT） |

---

## 4. 数据迁移与回滚

| 项 | 方案 |
|---|---|
| 正向脚本 | `V11_29__v4_3_device_port_nic.sql`；只 `ADD COLUMN ... NULL` + `ADD KEY`，不重建表、不改既有列 |
| 兼容性 | expand-contract：`v4.2.0` 二进制在 `V11_29` schema 上必须可启动（新列不被旧代码引用） |
| 回滚脚本 | `db/down/V11_29__v4_3_device_port_nic.down.sql`：`ALTER TABLE device_port DROP KEY idx_device_port_nic, DROP COLUMN nic_name, DROP COLUMN nic_index;` |
| 演练 | `bash scripts/migration-down-up-drill.sh`：`MODE=alter`；正向 → 回滚 → 再正向，三次均 PASS；`DB_NAME=robot_it` |
| 数据回填 | **不做**强制回填；历史端口 `nic_name/nic_index` 为 `NULL`，展示归「未归属」 |
| 租户守卫 | 未新增表，`TenantGuard.TENANT_TABLES` 不变；`asset-info` 查询必须带项目可见范围（复用 `AssetScopeSql`） |
| db-audit | 表数不变（88 表基线不漂移）；列级变更由 `SchemaIT` 校验 |
| 备份 | 迁移前 `mysqldump` 备份 `robot_dev`；`robot_it` 可 `db-reset` |

---

## 5. 实现要点（避免返工的关键约束）

### 5.1 连接派生

1. 以 `link` 为行单位，左连 A/B 端 `device` + `device_port`；端口网卡归属取 `nic_name/nic_index`。
2. 端点缺失（`a_device_id` / `a_port_id` 为空）时该行仍返回，端点字段置 `null`，不得丢行。
3. 排序：设备名 → `nic_index`（`NULL` 视为最大值）→ `nic_name` → `port_name`；`linkId` 兜底，保证可复现。
4. 输出套用 `project_asset.alias`（项目级），**只在组装阶段**，不写回实体、不进缓存键。
5. 不设 `size` 上限，不分页；性能不足时降级为「返回全部但标记 `partial=false`」以外的处理须走 PRD 变更。

### 5.2 拓扑派生

1. 节点 id 类型化：`dev:{deviceId}`、`dev:{deviceId}/nic:{nicName}`、`dev:{deviceId}/nic:{nicName}/port:{portName}`；未归属网卡用 `nic:__unassigned__`。
2. 边仅在两端口均有 `id` 时生成，`from/to` 用端口级 id；`linkId` 唯一对应一条链路。
3. `stats` 由节点/边集合去重计数，不单独查库，避免与明细不一致。
4. 默认包含禁用资产并标 `enabled=false`；`includeDisabled=false` 时过滤节点与边，但 `stats` 同步过滤。

### 5.3 缓存与失效

| 项 | 约束 |
|---|---|
| L1 片段键 | `"{assetType}:{assetId}:{contentHash}"`；`contentHash` 由设备/端口/链路可序列化字段 + `updated_at` 计算，**不得包含 alias / projectId** |
| L2 项目键 | `"project:{projectId}:{assetSetFingerprint}"`；指纹 = 该项目可见资产集合的 `type:id:contentHash` 排序后哈希 |
| TTL | 复用既有 `TtlCache`，独立实例 + `AssetInfoProperties`；默认 43200s；`enabled=false` 时直算 |
| 失效 | `share/reference/unreference` 与设备/端口/链路写操作 **`@TransactionalEventListener(AFTER_COMMIT)`** 触发；按资产 id 清 L1、按受影响项目清 L2 |
| 单飞 | 以 `projectId + scope` 为键，`ConcurrentHashMap` + `CompletableFuture`；在途时后续请求等待同一结果 |
| 降级 | 缓存异常只记日志并直算，不把 500 抛给前端 |
| 可观测 | 日志输出 `cacheHit`、`recomputed`、`invalidated`、耗时；不打敏感字段 |

### 5.4 权限与错误码

| 场景 | 期望 |
|---|---|
| `GET` 无 `ASSET/READ` | 403（`ApiResponse` 统一体） |
| `GET` 项目不存在或不在可见范围 | 404 |
| `scope` 空 / 含未知值 | 400，返回允许值 `connections,topology` |
| `POST refresh` 无 `ASSET/UPDATE` | 403 |
| 前端无 `project:create` 访问 `/projects/new` | 跳 `/403`，不发请求 |

### 5.5 前端页

1. 元信息条固定展示四项：`generatedAt`、来源（`internal`）、`cacheHit`、`expired`；不得把「12 小时缓存」写成「实时」。
2. 刷新按钮 `v-if` 受 `asset:update` 能力控制，点击弹二次确认（文案「将忽略 12 小时复用窗口，强制重算」）。
3. 空态分两种：`empty=true`（无数据，引导去分配资产）与请求失败（可重试），文案不同。
4. 筛选为纯前端本地过滤，不改请求参数。
5. 中英词条一次补齐，禁止页面内硬编码中文。

---

## 6. 门禁与取证

| 门禁 | 判据 |
|---|---|
| 单测 | `PermissionMatrixTest`、`AuthzMatrixTest`、`AssetInfoServiceTest`、`AssetInfoCacheTest`、`AssetInfoControllerTest` 全绿 |
| 前端 | `navigation.spec.ts` 全绿（7 组 / 4 子路径 / 图标唯一 / 四角色快照）；`npx vitest run` 全绿；`vue-tsc --noEmit` 通过 |
| 迁移 | `migration-down-up-drill.sh` 三态通过；`DB_NAME=robot_it mvn -o -B -Pit test -Dtest=SchemaIT` 7/7 |
| 契约 | `python3 tools/gen_api_contract.py` 重生成后 `--check` 不漂移；新增 2 path 在 diff 中可见 |
| i18n | 中英键集相等；新增键全部落 `menu.*` / `connections.*` |
| 审计 | `project:create`、`asset-info.refresh` 两条在真库可查，含操作者/项目/scope/force |
| 真链路 | 接口用真 HTTP + 真库 + 真浏览器；禁止夹具塞 SQL 假行充当证据 |
| 只读 | 页面加载期 0 写请求，由 CDP `Network.requestWillBeSent` 证明 |
| 负向 | 「别名进缓存」「变更不失效」「超 100 条截断」三类必须能判红（单测或契约脚本注入） |
| 全量 | `bash scripts/gates.sh` → `GATES_RESULT FAILS=0`；`--selfcheck` → `SELFCHECK_RESULT FAILS=0` |

---

## 7. 完成定义（DoD）

每个工作包同时满足以下才算完成：

1. 代码合入 `main` 前通过包内验收命令，日志落 `docs/evidence/v4.3.0/<包号>/`。
2. 新增/修改行为有对应测试；无「只改实现不改测试」。
3. 新增文案中英齐全；无裸键。
4. 涉及契约的包已重生成 OpenAPI 且 `--check` 通过。
5. 涉及迁移的包已做「正向 → 回滚 → 再正向」演练。
6. `README.md` 记录命令原文、读数、时间、执行人。
7. 偏差写进 §8，不事后改数字。

版本级 DoD：

- P0 七条 FR 全部达 PRD §9 判据；P1 未交付须在 §8 记明。
- `bash scripts/gates.sh` 与 `--selfcheck` 均 `FAILS=0`。
- `docs/release/v4.3.0/` 六份产物齐备，四处 sha 一致，版本四副本一致。
- 无 S1/S2 未关闭缺陷。

---

## 8. 偏差记录（开工后填写，不许事后悄悄改数字）

| 包 | 计划 | 实际 | 偏差原因 | 授权 |
|---|---|---|---|---|
| `W43-00` | 待填 | 待填 | 待填 | 待填 |
| `W43-01` | 待填 | 待填 | 待填 | 待填 |
| `W43-02` | 待填 | 待填 | 待填 | 待填 |
| `W43-03` | 待填 | 待填 | 待填 | 待填 |
| `W43-04` | 待填 | 待填 | 待填 | 待填 |
| `W43-05` | 待填 | 待填 | 待填 | 待填 |
| `W43-06` | 待填 | 待填 | 待填 | 待填 |
| `W43-07` | 待填 | 待填 | 待填 | 待填 |
| `W43-08` | 待填 | 待填 | 待填 | 待填 |

---

## 9. 风险与依赖

| # | 风险 / 依赖 | 处置 |
|---|---|---|
| 1 | 权限四处联动只改两处 → 单测与契约互斥 | `W43-01` 一个 PR 全改；PR 描述逐项勾选 P1–P4 |
| 2 | `navigation.spec` 强断言 7 组 + 固定子路径 | 只加叶子不改一级；子路径断言 3→4 项同一 PR 内更新 |
| 3 | 别名被写入缓存键导致串项目 | 缓存键仅 `type:id:contentHash`；专项单测 `AssetInfoCacheTest` 覆盖两项目共用资产 |
| 4 | 大项目全量组装超时 | L1/L2 两级缓存 + 单飞 + 500 设备基线压测；不足则先在证据中记录实测值再议优化 |
| 5 | 事件失效漏路径（`unreference` 常被忘） | 失效点清单化，`AssetInfoCacheTest` 逐条断言 |
| 6 | 端口新字段历史为空 | 允许 `NULL`；前端「未归属」分组；不做强制回填 |
| 7 | 资产分配无入口导致功能空转 | `W43-07` P1 同期交付；未交付时在页面空态文案给出路径说明 |
| 8 | 单实例缓存口径被误解为多实例可用 | PRD §2.2 O6 + 页面元信息展示来源；多实例另立评估 |
| 9 | 后端改动未重启 → 源码对齐 ≠ 部署对齐 | 收口必做 `systemctl --user restart robot-autotest-backend` + `:8081/actuator/health` 200 |
| 10 | 前端未重建 → 旧 dist 仍是旧菜单 | 收口必做 `npx vite build`；`CHANGELOG.md` 同步到 `frontend/public/` |
| 11 | Windows 侧 9P 构建慢 35 倍 | 构建/测试一律回 WSL 权威副本 |

**依赖**：`v4.2.0` 已发布；`device` / `device_port` / `link` / `project_asset` / `project_member` 表与既有 API 可用；`TtlCache` / `AssetScopeSql` / `ProjectAssetService` 可复用。

---

## 10. ⑦ 文档回写清单

| 文档 | 回写内容 |
|---|---|
| 评估报告 `RA-EVAL-43-001` | 实测推翻的假设逐条改（如 `updated_at` 可用性、性能实测值），留修订记录 |
| PRD `RA-PRD-43-001` | 每条 FR 的验收状态（达成 / 未达成 / 降级 + 理由与授权人）；§9 判据逐条勾选 |
| 本开发计划 | §8 偏差表按包填写 |
| 测试计划 `RA-TEST-43-001` | 实测结果段、遗留缺陷、`NOT-RUN` 说明 |
| `docs/release/v4.3.0/05-TEST-REPORT.md` | 用例通过率、证据索引、缺口状态 |
| `CHANGELOG.md` / `frontend/public/CHANGELOG.md` | 版本条目（新增菜单、PM 建项目、连接与拓扑、刷新） |
| `docs/release/README.md` 版本地图 | 追加 `v4.3.0` 能力条目与依赖关系 |
