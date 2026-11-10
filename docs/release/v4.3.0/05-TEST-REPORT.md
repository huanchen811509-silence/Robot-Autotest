# v4.3.0 项目创建与资产信息 · 测试报告（05-TEST-REPORT）

| 项 | 内容 |
|---|---|
| 文档 | `docs/release/v4.3.0/05-TEST-REPORT.md` ｜ v1.0 ｜ 2026-11-10 |
| 编号 | `RA-RPT-43-001` |
| 流程位置 | ⑥ 测试执行（上游 `TEST-PLAN.md` 41 条 TC） |
| 被测版本 | `VERSION=4.3.0`，commit `9fb2358` |
| 执行环境 | WSL2 Ubuntu-24.04 权威副本 `~/robot-autotest`；后端 `:8081`、统一入口 `:8001`、MySQL `robot_dev` |
| 结论口径 | 只有 `PASS` 算通过；`PARTIAL` / `BLOCKED` / `NOT-RUN` 一律**不得**读作通过 |

---

## 1. 总览

| 结果 | 条数 | 说明 |
|---|---:|---|
| `PASS` | 8 | 静态断言 / 单测 / 契约 / 构建 / 部署 / 真 HTTP 往返，判据可机检 |
| `PARTIAL` | 25 | 逻辑已由自动化测试覆盖，但「真项目数据 / 真浏览器」侧缺证据 |
| `NOT-RUN` | 8 | 依赖 CDP `:9222`、多项目真实数据、压力环境或负向注入，本机不具备 |
| `FAIL` | 0 | 本版引入的缺陷 0 条 |

**关键判断**：本版交付的**代码与契约**侧判据全部可复现地成立（后端全量测试 `BUILD SUCCESS`、前端
`vitest` + `vue-tsc` + `vite build` 全绿、OpenAPI `--check` 不漂移、`V11_29` 迁移已应用、交付包
`robot-autotest-4.3.0-9fb2358-linux-x64.tar.gz` 产出且 MANIFEST `version=4.3.0`）。
**未成立的**是「真浏览器交互」「多项目真实资产对照」「性能采样」「负向注入」四类现场证据，
原因与补齐方法逐条写在 §3，并按 `TEST-PLAN` §0.2 不把它们计入通过。

---

## 2. 逐条结果

### 2.1 R1 · 菜单与权限

| TC | 结果 | 实测值 / 说明 | 证据 |
|---|---|---|---|
| `TC-v4.3-01` | `PARTIAL` | 静态侧成立：`navigation.ts` 中 `workbench` 组含 `workbench-overview`（`/overview`）与 `workbench-create`（`/projects/new`），`labelKey` 分别为 `nav.workbenchOverview` / `nav.workbenchCreate`；`navigation.spec.ts` 断言 workbench 子路径恰好 4 条并全绿。DOM 双菜单并列的浏览器读数缺失（无 CDP）。 | `frontend/src/config/navigation.ts`、`logs/gates/02-frontend_test.log` |
| `TC-v4.3-02` | `PARTIAL` | 叶子节点不随「已选项目」变化：`navigation.ts` 的 workbench 组为静态常量，选中项目只影响项目上下文数据，不裁剪菜单；无浏览器 diff 证据。 | 同上 |
| `TC-v4.3-03` | `PASS` | 一级 7 组、每组 ≥2 子项、7 个一级图标两两不同由 `navigation.spec.ts` 断言；前端全量 `592` 例通过。 | `02-frontend_test.log` |
| `TC-v4.3-04` | `PARTIAL` | HTTP 侧成立：`AuthorizationBoundaryTest.IAM05-29`（PM 建项目 → `200`，`data.code=newproj`）、`PermissionMatrixTest`（PM 项目动作 `READ/CREATE/UPDATE`）；ENGINEER/VIEWER 的菜单可见性需浏览器证据，未取。 | `05-backend_test.log` |
| `TC-v4.3-05` | `PARTIAL` | 路由守卫已挂 `meta.capability='project:create'`，无权限跳 `/403` 的判定由 `router/index.ts` + 既有守卫单测覆盖；「0 写请求」需 CDP 网络清单，未取。 | `frontend/src/router/index.ts` |
| `TC-v4.3-06` | `PARTIAL` | 创建落库、创建者成为项目 `ADMIN` 成员、审计 `project:create` 由 `ProjectService` 事务内完成并有单测；真实账号四步联调未做。 | `05-backend_test.log` |
| `TC-v4.3-07` | `NOT-RUN` | 五步向导 UI 需浏览器走查；本机无 CDP。补测方法：启动带 `--remote-debugging-port=9222` 的 Chrome 后重跑 `TEST-PLAN` §2.1。 | — |
| `TC-v4.3-08` | `PARTIAL` | `AccountMenu.vue` 按 `canCreateProject`（能力 `project:create`）显隐，与侧栏同源；四角色截图未取。 | `frontend/src/components/AccountMenu.vue` |
| `TC-v4.3-09` | `PASS` | `PermissionMatrixTest` 对 PM 建项目判 `ALLOW`；`authz-matrix.yaml` 的 `AUTHZ-PM-PROJECT-WRITE` 期望为 `ALLOW`；`AuthzMatrixTest` 随 `mvn test` 全量通过。 | `05-backend_test.log`、`docs/security/authz-matrix.yaml` |
| `TC-v4.3-10` | `PASS` | 新增词条中英键集相等，门禁第 27 步 `check_i18n.py` 通过（0 缺失）；页面文案全部走 i18n key。 | `27-i18n_contrast.log` |

### 2.2 R2 · 连接与拓扑

| TC | 结果 | 实测值 / 说明 | 证据 |
|---|---|---|---|
| `TC-v4.3-11` | `PARTIAL` | 端口 `nicName/nicIndex` 落库与派生：`AssetInfoServiceTest` 断言 A/B 端网卡归属；真库 `device_port` 现有 **0** 行（`device=5 / link=2`），无真数据可走查。 | `05-backend_test.log`、§4 库读数 |
| `TC-v4.3-12` | `PARTIAL` | 未填网卡归「未归属」分组由 `AssetInfoServiceTest` 覆盖，接口不报错；页面分组截图未取。 | 同上 |
| `TC-v4.3-13` | `PARTIAL` | `ConnectionItem` 15 个字段（`linkId/linkName/linkType/aDevice/aNic/aPort/bDevice/bNic/bPort/protocol/speedGbps/mtu/macAddress/ipAddress/portStatus/enabled`）由 `AssetInfoControllerTest` 逐字段断言；真库无端口数据。 | 同上 |
| `TC-v4.3-14` | `PARTIAL` | 排序按 `linkId` 稳定 + `nicIndex` 升序 / `nicName` 字典序由单测断言；两次真实响应逐字节比对未做。 | 同上 |
| `TC-v4.3-15` | `NOT-RUN` | 无 `P-BIG`（120 设备 / 300 端口 / 150 链路）数据集，条数与库内计数一致性无法实测。补测方法：按 `TEST-PLAN` §1 D5 建数据后比对 `count` 与 `select count(*)`。 | — |
| `TC-v4.3-16` | `PARTIAL` | 设备→网卡→接口三级、边为端口级 id、`stats` 去重计数由 `AssetInfoServiceTest` 断言；真数据拓扑未走查。 | `05-backend_test.log` |
| `TC-v4.3-17` | `PARTIAL` | `includeDisabled` 默认 `true`，`false` 时节点/边/`stats` 同步过滤，单测覆盖；两次真实响应 diff 未做。 | 同上 |
| `TC-v4.3-18` | `PARTIAL` | 别名只作用于展示、不进缓存键：`AssetInfoServiceTest` 断言同资产在两项目下别名互不串；真实双项目数据未建。 | 同上 |
| `TC-v4.3-19` | `PARTIAL` | 引用（`shareApi.reference`）后立即可读：缓存键含项目指纹、引用写库即改指纹 → 下次 GET 必然重算；单测覆盖「引用后 `cacheHit=false`」。真 HTTP 引用流程未走。 | 同上 |
| `TC-v4.3-20` | `PARTIAL` | 12h 窗口内第二次请求 `cacheHit=true` 由 `AssetInfoCacheTest` 断言；两项目共用资产的真实对照未做。 | 同上 |
| `TC-v4.3-21` | `PARTIAL` | TTL 可配（`asset-info.cache.ttl-seconds`，默认 `43200`）并在配置层生效，单测用短 TTL 验证过期后 `cacheHit=false`；真配置改动未记。 | 同上 |
| `TC-v4.3-22` | `PARTIAL` | 三类变更（设备改 IP / 链路改速率 / 解除引用）均发 `AssetChangedEvent` → `AssetInfoInvalidator` 清缓存，单测覆盖「变更后立即反映」；真实变更链路未走。 | 同上 |
| `TC-v4.3-23` | `PARTIAL` | `POST /refresh` 强制重算、`force=false` 遵循窗口、写审计 `asset-info.refresh` 由 `AssetInfoControllerTest` 覆盖；真实审计表查询未做。 | 同上 |
| `TC-v4.3-24` | `PARTIAL` | `refresh` 需 `asset:update`，VIEWER/ENGINEER `403` 由权限单测覆盖；按钮可见性截图未取。 | 同上 |
| `TC-v4.3-25` | `PASS` | 单飞：并发 10 请求只重算 1 次、结果同一份，`AssetInfoCacheTest` 断言（并发用例用 `force=false`，与单飞语义一致）。 | `05-backend_test.log` |
| `TC-v4.3-26` | `PARTIAL` | 私有资产不出现在他项目由 `AssetScopeSql` 可见范围兜底，越权返回 `403` 不泄漏名称由 `AuthorizationBoundaryTest` 覆盖；资产接口的跨项目用例未单列。 | 同上 |
| `TC-v4.3-27` | `PARTIAL` | 无资产返回 `200 + empty=true` 由 `AssetInfoServiceTest` 覆盖；空态页面截图未取。 | 同上 |
| `TC-v4.3-28` | `NOT-RUN` | `/environment/connections` 页面已实装（拓扑区 / 明细表 / 元信息条 / 刷新 / 筛选），但页面读数与截图需 CDP。 | `frontend/src/views/environment/ConnectionsView.vue` |
| `TC-v4.3-29` | `NOT-RUN` | 「加载期 0 写请求」必须用 CDP `Network.requestWillBeSent` 证明；无 CDP 时不接受源码断言替代。 | — |
| `TC-v4.3-30` | `PARTIAL` | P1 已交付：平台共享资产行有「引用 / 解除引用」，`shareApi.reference/unreference` 接后端；无权限提示与表单保留由前端逻辑覆盖；完整浏览器流程未走。 | `frontend/src/views/assets/AssetsView.vue` |

### 2.3 项目生命周期

| TC | 结果 | 实测值 / 说明 | 证据 |
|---|---|---|---|
| `TC-v4.3-40` | `PARTIAL` | 前端单测 10 例覆盖：无管理能力不渲染入口、停用/启用文案随状态切换、点击只发事件；`ProjectCard` 按 `canManage`（ADMIN/PM）与 `canDelete`（仅 ADMIN）显隐。真 HTTP 已验 `PUT enabled=false/true` 往返（读回 `enabled` 同步），**页面截图与 ENGINEER/VIEWER 登录态未采**（CDP 不可用）。 | `ProjectCard.spec.ts`、`tmp-lifecycle` 读数 |
| `TC-v4.3-41` | `PASS` | 真 HTTP：临时项目 86 建一条保存视图后 `DELETE` → `409`「项目下仍有保存视图 1 条，只能停用（归档），不能彻底删除」；删视图后 `DELETE` → `200`，`GET` → `404`；`audit_log` 新增 `project:delete`（`summary=before={code=…; name=…}; deleted=true`）。后端单测 3 例锁定 409 / 空删 / 404。 | API 读数、`audit_log` id=1711013、`ProjectServiceDeleteTest.java` |

### 2.4 横切与负向

| TC | 结果 | 实测值 / 说明 | 证据 |
|---|---|---|---|
| `TC-v4.3-31` | `PARTIAL` | 正向已应用：`flyway_schema_history` 中 `11.29 / v4 3 device port nic / success=1 / 2026-11-10 13:55:56`；`device_port` 现有 16 列且含 `nic_name`、`nic_index`。**回滚 → 再正向**未在 `robot_it` 演练，`SchemaIT` 未单跑。 | §4 库读数 |
| `TC-v4.3-32` | `PASS` | `python3 tools/gen_api_contract.py --check` 通过，快照 `293 endpoints / 234 paths` 不漂移；旧调用不传 `nicName/nicIndex` 行为不变（新列可空、DTO 字段可空）。 | `20-api_contract.log` |
| `TC-v4.3-33` | `NOT-RUN` | 负向注入（把别名塞进缓存键）未执行；`TEST-PLAN` §3 要求「备份 → 注入 → 判据必红 → 还原」，本轮未做，不以单测绿代替。 | — |
| `TC-v4.3-34` | `NOT-RUN` | 注释失效监听的反向验证未执行。 | — |
| `TC-v4.3-35` | `NOT-RUN` | `LIMIT 100` 截断注入未执行（且缺 `P-BIG` 数据）。 | — |
| `TC-v4.3-36` | `PASS` | 后端全量 `mvn -o -B test` `BUILD SUCCESS`；前端 `vitest` **592** 例全绿、`vue-tsc --noEmit` 通过、`vite build` 成功；`TopologySnapshotView` 既有用例未回归。 | `02/03/04/05` 日志 |
| `TC-v4.3-37` | `PARTIAL` | 门禁 32 步：本版引入的失败已全部清零；**仍余 1 步基线红**（第 13 步 CDP 浏览器回归，改动前即红），故不写 `FAILS=0`。`--selfcheck` 的 `STRUCT_FAIL / 删步骤 PASS` 是判据自证的预期输出。 | §5 门禁读数 |
| `TC-v4.3-38` | `NOT-RUN` | 无压力环境，P95 采样未做；不以单次读数当结论。 | — |
| `TC-v4.3-39` | `PASS` | `python3 scripts/gen_build_info.py` → `version=4.3.0`；`mvn -DskipTests package` + `systemctl --user restart robot-autotest-backend` 后 `/actuator/health=UP`，运行期 `/api/system/build-info=4.3.0`；`frontend` 重建后入口托管 `CHANGELOG.md` 首条 = `4.3.0`；根 `CHANGELOG.md` 与 `frontend/public/CHANGELOG.md` 小节逐字一致。 | §5 门禁读数、`logs/w43-build.log` |

---

## 3. 未通过项的原因与补齐方法

| 类别 | 涉及 TC | 原因 | 补齐方法 |
|---|---|---|---|
| 真浏览器交互 | 01 部分、02 部分、05、07、08 部分、28、29、30 部分 | WSL 内无 Chromium/Playwright 浏览器，`CDP :9222` 不可用（门禁第 13 步自 v4.2.0 起即为基线红）；Windows 侧 Chrome 可启动，但 WSL 与该 Chrome 的 `:9222` 双向不通（`10.202.16.1:9222` 连接超时），无法桥接 | 在 WSL 安装 Chromium 或用可连通的 CDP 端点，`export CDP_BASE=<端点>` 后重跑第 13 步与 §2.1/§2.2 交互用例；不接受源码断言冒充页面读数 |
| 多项目真实资产 | 11–18、19–24、26 部分 | `robot_dev` 现有 `device=5 / device_port=0 / link=2`，`TEST-PLAN` §1 的 `D1`–`D8` 数据集未建 | 按 §1 用真实 API 建 `P-A / P-B / P-BIG / P-EMPTY` 与别名，重建后复跑对应用例 |
| 迁移回滚演练 | 31 部分 | 只在 `robot_dev` 验证了正向；`robot_it` 上「正向 → down → 再正向」未执行 | `bash scripts/db-migrate.sh` 在 `robot_it` 演练三次并留 `SHOW COLUMNS` 输出 |
| 负向注入 | 33、34、35 | 需临时改实现再还原，本轮未做 | 按 `TEST-PLAN` §3 备份 → 注入 → 定向判据必红 → 用备份拷贝还原 → 复跑绿 |
| 性能采样 | 38 | 无第二实例 / 压力环境 | 在有压力环境的机器上 20 次采样取 P95，同时记录机器负载 |

---

## 4. 环境与库读数

| 项 | 读数 |
|---|---|
| `VERSION` | `4.3.0`（`build-info.properties`：`app.version=4.3.0`，`commit=9fb2358`，`deps=23`） |
| 后端 | `:8081` `actuator/health={"status":"UP"}`；`java -jar backend/target/robot-autotest-backend-0.1.0-SNAPSHOT.jar` |
| 统一入口 | `:8001` 监听；入口托管 `CHANGELOG.md` 首条 `4.3.0` |
| 迁移 | `flyway_schema_history`：`11.29 / success=1 / 2026-11-10 13:55:56`；上一版 `11.28` |
| `device_port` 列 | `id, device_id, port_name, port_type, mac_address, speed_gbps, created_at, created_by, updated_at, updated_by, protocol, port_status, mtu, ip_address, nic_name, nic_index`（16 列） |
| 业务表计数 | `device=5`、`device_port=0`、`link=2`、`project=5`（原 12：清理 78、81~85 六个空壳探针 + 75/76 两个 A8 报告夹具；`TC-v4.3-41` 的临时项目 86 已删）。现存 `1 demo` / `29 qa-e2e-fixture` / `77 ED202-COMMON` / `80 demo-pytest` / `87 P001`（87 为人工新建、`status=0` 停用态，保留） |
| OpenAPI | `293 endpoints / 234 paths`，`info.version=4.3.0` |
| 交付包 | `releases/robot-autotest-4.3.0-9fb2358-linux-x64.tar.gz`（48,267,736 B），`sha256=e08c88e3e1eaa8b341052d2c253872dbc478116ecde37da1cfe5e4edfe703e66`，MANIFEST `files=293`、`version=4.3.0` |

---

## 5. 门禁读数（32 步）

| 步骤 | 结果 | 读数 |
|---|---|---|
| 01 subset_font | PASS | 见 `docs/evidence/gates/logs/01-subset_font.log` |
| 02 frontend_test | PASS | `vitest` 592 例通过 |
| 03 type_check | PASS | `vue-tsc --noEmit` 0 错误 |
| 04 frontend_build | PASS | `vite build` 成功 |
| 05 backend_test | PASS | `BUILD SUCCESS`（全量 `mvn -o -B test`） |
| 06 lint | PASS | 修复 `frontend/src/config/changelog.ts` 的 `no-control-regex` 后转绿 |
| 07 release_docs | PASS | `docs/release/v4.3.0/` 六份产物齐、`FR=9 / TC=41` |
| 18 structure_selfcheck | PASS | 步骤 13 / 14 / 17 / 19 被删时该检查是唯一变红环节（判据自证） |
| 19 evidence_integrity | PASS | 05 日志含 `BUILD SUCCESS`；前端 592 ≥ 325、后端 ≥ 1016、editor ≥ 219 |
| 20 api_contract | PASS | `293 endpoints / 234 paths` 不漂移 |
| 23 version_consistency | PASS | 静态四处一致；运行期 `build-info=4.3.0`；入口 `CHANGELOG=4.3.0` |
| 30 delivery_bundle | PASS | `releases/robot-autotest-4.3.0-9fb2358-linux-x64.tar.gz` + `.manifest.json`（`version=4.3.0`） |
| 13 browser_regression | **FAIL（基线）** | `browser regression fetch failed`：`CDP :9222` 不可用；`docs/evidence/v4.3.0/EW43-00/README.md` 已记改动前即红 |
| 其余 19 步 | PASS | 01–04、08–12、14–17、21、22、24–29、31、32；见 `docs/evidence/gates/logs/` |

**门禁结论**：`GATES_RESULT FAILS=1`（31 步 PASS / 1 步 FAIL），且该 1 条为**基线既有红**（`EW43-00` 记录
`FAILS=2`：06-lint + 13-browser_regression；本版修掉 06-lint，13 因环境缺浏览器无法消）。**不声称 `FAILS=0`**，也不把第 13 步记 PASS。

---

## 6. 缺陷与遗留

| # | 级别 | 描述 | 状态 |
|---|---|---|---|
| 1 | S3 | 门禁第 13 步因环境无浏览器长期红，使「全绿」不可达 | 遗留：需补浏览器运行时 |
| 2 | S3 | 本机 `robot_dev` 无端口数据，连接/拓扑页只能空态展示 | 遗留：按 `TEST-PLAN` §1 建数据集 |
| 3 | S3 | 负向注入三条未执行 | 遗留：发版前补 |

**无 S1 / S2 缺陷**：越权、别名串项目、审计缺失三类暂停条件本轮均未复现（对应单测 `AuthorizationBoundaryTest`、
`AssetInfoServiceTest` 别名用例、`AssetInfoControllerTest` 审计用例全绿）。

---

## 7. 结论

- 本版**功能实装完整**：新建项目独立入口与 PM 建项目、端口网卡归属、`/asset-info` 与 `/refresh`、两级缓存
  12h 可配 + 单飞 + 变更失效、`/environment/connections` 页、平台资产引用/解除引用（P1）。
- 本版**代码侧判据全部成立**，`FAIL` 0 条；`7` 条 `PASS`、`24` 条 `PARTIAL`、`8` 条 `NOT-RUN`，
  `PARTIAL`/`NOT-RUN` 全部集中在「真浏览器 / 真多项目数据 / 压力 / 负向注入」四类现场证据，已在 §3 给出可执行的补齐方法。
- **落号不等于判定**：G 门与对外口径仍归用户裁决；本节只报实装与机检结论。
