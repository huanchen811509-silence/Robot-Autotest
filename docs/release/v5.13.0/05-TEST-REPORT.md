# v5.13.0 测试报告（05-TEST-REPORT）

> 流程位置：⑥ 测试执行 → 结果落定。数据源：`TEST-PLAN.md` 的 49 条 TC + `DEV-PLAN.md` 七波执行状态。
> **不新造用例**：TC 编号与 TEST-PLAN 一一对应，逐条写结论与证据形态。
> 结论口径：`PASS` = 有可复现证据；`PARTIAL` = 只覆盖部分证据形态（写明缺口）；`FAIL` = 未达成。
> **本版不把「单测绿 / 构建绿」当放行理由**；真实链路项在 §6 单列。
> 本轮真实证据：`docs/evidence/v5.13.0/w7-http-probe.txt`（32/32 PASS）与
> `w7-http-probe-extra.txt`（11/11 PASS），运行期 HEAD=`7333338`。

## 1. 读数总览

| 项 | 读数 | 证据 |
|---|---|---|
| 后端全量单测（Maven 当轮） | **2073 条 / 0 失败 / 0 错误 / 4 跳过** | `/tmp/w513_backend_test.log`：`Tests run: 2073 ... BUILD SUCCESS` |
| 后端 surefire 报告汇总口径 | **327 份 / 2147 条**（v5.12 收口 317 / 2095，+10 份 / +52 条） | `backend/target/surefire-reports/TEST-*.xml`；含历史残留报告，故高于当轮数 |
| W7 修复相关单测 | **65 条全绿**，含新增 `DeviceCollectProfilePlatformScopeTest` 4 条 | `mvn -o -B -Dtest=... test` |
| 前端全量单测 | **147 文件 / 1160 例 0 失败**（v5.12 收口 143 / 1139） | `npx vitest run --reporter=dot` |
| 类型检查 | `npx vue-tsc --noEmit` **0 error** | `/tmp/w513_tsc.log` 空文件 |
| 语言包机检 | **5/5 PASS**；zh/en 各 3097 键，源码硬编码中文 0 处 | `tools/check_i18n.py` |
| 接口契约 | **456 endpoints / 374 paths**，`--check` PASS | `tools/gen_api_contract.py` |
| 一次性口令掩码 | **6/6 PASS**（DB / APP_LOG / AUDIT / HTTP_RESPONSE / EVIDENCE_BUNDLE / EXCEPTION_STACK） | `tools/probe_oneshot_secret.py` |
| 数据库体检 | 最新迁移仍为 **V11_68**（零迁移）；`db-audit.sh` **PASS** | 补充探针 `w7-http-probe-extra.txt` |
| 部署自校验 | **PASS=4 FAIL=0 SKIP=0**，运行期 `build-info commit=7333338 == HEAD` | `bash scripts/deploy.sh`（两次） |
| 真实 HTTP + DB 主探针 | **PASS=32 FAIL=0 TOTAL=32** | `w7-http-probe.txt` |
| 真实 HTTP + DB 补充探针 | **PASS=11 FAIL=0 TOTAL=11** | `w7-http-probe-extra.txt` |
| 全量门禁 | **FAILS=0 UNJUDGED=0**（57 步，第二轮全绿） | `docs/evidence/gates/logs/`；`/tmp/w513_gates2.log` |

> **口径说明**：后端「2073」是 Maven 当轮实际执行数，「2147」是 surefire 目录汇总（含历史残留 XML）。
> 两个数只增不减（v5.12 当轮约 2021 / 汇总 2095），差异来自未 clean 的报告目录，不是测试被删。

## 2. 逐条结论（TC-v5.13-01 ~ 49）

| TC | 结论 | 证据 / 说明 |
|---|---|---|
| TC-v5.13-01 | PASS | 真实 HTTP `GET /api/assets/collect-profiles` 返回 13 台，`READY=1 / PARTIAL=11 / BLOCKED=1`；三态各有真实样本 |
| TC-v5.13-02 | PASS | 真实 `BLOCKED` 响应为 `NO_CREDENTIAL`（不互换）；`NO_MANAGEMENT_IP` / `CREDENTIAL_REVOKED` 与判据优先级由 `DeviceCollectProfileTest` 9 条覆盖；D4 真库样本未构造，缺口登记 §5 |
| TC-v5.13-03 | PARTIAL | SQL 机检断言 `ORDER BY cv.verified_at DESC, cv.id DESC LIMIT 1`；未构造「同刻两条」真库记录做逐字比对 |
| TC-v5.13-04 | PARTIAL | 500 台聚合耗时 **未采样**（无压测样本与计时记录），L-63-01 未闭合；不许用单测代替 |
| TC-v5.13-05 | PASS | `flyway_schema_history` 最新成功版本仍 `11.68`，`db-audit.sh` PASS，业务表数 109（排除 flyway 表）与契约一致 |
| TC-v5.13-06 | PASS | 真实 DB 前后计数 `credential_verification` 5 → 10；最新行 `result=SUCCESS`、`credential_id=4`、`detail=source=COLLECT; ...` |
| TC-v5.13-07 | PARTIAL | 真实 FAILED 事实已落库且 `failure_kind=REJECTED` 非空（W7 修复）；但「**错误密码**」样本未构造，本轮用 `127.0.0.2` 不可达构造失败，见 §3 |
| TC-v5.13-08 | PASS | 真实 `/verify` 端点 + DB：`detail LIKE 'source=VERIFY%'` = 2 条、`source=COLLECT%` = 7 条，两类可区分 |
| TC-v5.13-09 | PARTIAL | 未在 v5.12 收口 commit 上真实回放 TC-06；仅由代码事实佐证 `recordFromCollect()` 在 5.13 前是死代码 |
| TC-v5.13-10 | PARTIAL | `CredentialPicker` 四级排序为 5.7 既有能力，本版未新增真实 HTTP / UI 验证，`tier=0` 排序未单独取证 |
| TC-v5.13-11 | PASS | 真实 `POST /collect?confirm=true`（不传 `credentialId`）→ 200，最新验证事实 `credential_id=3` = 设备绑定凭证 |
| TC-v5.13-12 | PARTIAL | `CollectCredentialFallbackTest` 6 条覆盖回退链与安全闸；D4（只绑 BMC、无管理 IP）真库样本未构造 |
| TC-v5.13-13 | **PASS（防线 1）** | 真实 HTTP **400**：`设备 3（leaf-switch-01）未绑定凭证，请先关联或手工指定 credentialId`，含「未绑定凭证」+ 设备标识 |
| TC-v5.13-14 | PASS | 已绑 A=3、显式传 B=4 采集 → 200，最新验证事实 `credential_id=4`，与请求一致 |
| TC-v5.13-15 | PASS | 真实 HTTP `confirm=false` → 400「采集需要二次确认」，未读绑定列即拒绝 |
| TC-v5.13-16 | PASS | 绑定为 NULL 时显式传 4 采集成功，DB `management_credential_id` 由 NULL 变为 **4**（自动填空） |
| TC-v5.13-17 | **PASS（防线 2）** | 真实请求 + DB 前后比对：已绑 A=3 → 用 B=4 采集成功 → 绑定 **仍为 3**；`SQL_FILL_IF_NULL` 命中 0 行 |
| TC-v5.13-18 | PASS | `127.0.0.2` 构造真实失败采集：HTTP 200 + `status=FAILED`，DB 绑定保持 NULL，另写一行 `FAILED/REJECTED` 事实 |
| TC-v5.13-19 | PARTIAL | `w63.autoBindHint` 文案与弹窗接线由 `collectProfileWiring.spec.ts` 断言；真视口 tooltip 截图未取 |
| TC-v5.13-20 | PARTIAL | 真实 `PUT /credentials` → 200 且 DB 绑定落 3；「刷新 / 换号 / 重启」三态截图未取，改由 DB 读数 + 源码机检佐证 |
| TC-v5.13-21 | PASS | 真实 `DELETE .../credentials` → 200，DB 两列 NULL，画像立即 `BLOCKED / NO_CREDENTIAL`（查询期聚合，无滞后） |
| TC-v5.13-22 | PASS | 项目 95 提交项目 1 私有凭证 id=1 → 真实 400「凭证不属于本项目，不能用于采集或关联：1」 |
| TC-v5.13-23 | PASS | 真实 DB：`audit_log` 中 `action LIKE '%credential.bind%'` = 4 行（who / when / device / credential 在 detail 内） |
| TC-v5.13-24 | PARTIAL | `ClearBindingTest` 断言与 5.10 向导写同一组列；真库「向导写入 → 本版关联 → 向导再写」三连对照未跑 |
| TC-v5.13-25 | PARTIAL | 两页三列由 `collectProfileWiring.spec.ts` 断言且都读 `profilesByDevice.value.get(row.id)`；真实画像响应逐字段已取，UI 截图未取 |
| TC-v5.13-26 | PARTIAL | `GlobalAssetsView.spec.ts` 断言未绑定显示「未关联」且主按钮 disabled；点击直达关联的 UI 截图未取 |
| TC-v5.13-27 | PASS | 真实双向对数：`GET /credentials/3/devices` = **5** 台，画像中管理面或 BMC 绑定 3 的设备 = **5** 台 |
| TC-v5.13-28 | PARTIAL | 凭据弹层「最近验证 / 失败原因 / 从未验证」文案在 i18n 与组件中；无截图 |
| TC-v5.13-29 | PASS | `assetDetail.ts` 不再合成 `{id:0}`，`credentials: []`；`collectProfiles.spec.ts` + `AssetDetailPanel.spec.ts` 回归断言 |
| TC-v5.13-30 | PARTIAL | 两 scope 三列由 wiring spec 源码断言；两张真视口截图未取 |
| TC-v5.13-31 | PARTIAL | 真实历史端点返回多条（设备 6 = 5 条）；但 `CollectHistoryRow` **不含凭证名**，FR-07「标注凭证」未达成，登记 §5 |
| TC-v5.13-32 | PARTIAL | `collectDiff.spec.ts` 4 条覆盖新增 / 变化 / 消失；真实两次采集后的界面数字核对未取 |
| TC-v5.13-33 | PARTIAL | `diffOldToNew` 模板与 i18n 就位；无截图 |
| TC-v5.13-34 | PARTIAL | 真实 HTTP 对无历史设备返回 `[]`，组件有 `collect-history-empty` 空态；无截图 |
| TC-v5.13-35 | PARTIAL | 真实 `credential_verification` 有 FAILED 行；`device_host_spec` 失败行与本版历史组件联动未逐条取证 |
| TC-v5.13-36 | PASS | `GlobalAssetsView.spec.ts` 断言未绑定时 `direct-collect-101` 存在且 `disabled=true`、`title` 含「未绑定凭证」；次入口仍可点 |
| TC-v5.13-37 | PARTIAL | 「设为默认」调 `bindGlobal` 写同一绑定端点（端点已真实 200）；UI 勾选 → DB 更新的全链路未跑 |
| TC-v5.13-38 | PARTIAL | 批量采集端点 `POST /credentials/{id}/collect` 为既有能力；本版未真实造 3 台样本跑批量与事实计数 |
| TC-v5.13-39 | PARTIAL | 5.12 既有 `BatchCollectResult` 逐条原因单测；本版未真实构造「部分失败」样本 |
| TC-v5.13-40 | PARTIAL | `canCollect()` 纯函数 5 条单测覆盖四种组合；批量候选列表真实 UI 未取 |
| TC-v5.13-41 | PARTIAL | `collectProfileColumns.spec.ts` 覆盖「只绑 BMC、无 IP → `missingIp`」；真实按钮禁用截图未取 |
| TC-v5.13-42 | PASS | 真实 HTTP：项目 95 引用平台设备 6 后带 `X-Project-Id:95` 采集 → 200 `status=PARTIAL`，验证事实落库 1 行 |
| TC-v5.13-43 | PASS | 真实 HTTP：项目 95 私有设备 20 在项目上下文采集 → 200 `status=PARTIAL`（原 404 消失） |
| TC-v5.13-44 | PASS | 项目 95 私有凭证 id=8 用于平台采集 → 真实 400「凭证不属于本项目，不能用于采集或关联：8」 |
| TC-v5.13-45 | PARTIAL | `w63.deleteImpact` 接 `referenceCount` / `collectCount` 由 wiring spec 断言；删除前真实响应逐字段比对未取 |
| TC-v5.13-46 | PASS | 真实 `GET /api/projects/95/devices/20` → 200，响应键含 `managementCredentialId` / `bmcCredentialId` |
| TC-v5.13-47 | PASS | 真实项目上下文 `?state=PARTIAL` → 200 且过滤生效；项目 95 可见设备均无绑定，`PARTIAL` 返回 0 条（如实记录，未拿别的 scope 数据凑数） |
| TC-v5.13-48 | PASS | 项目侧详情与项目侧画像对同一设备两字段一致（本样本两侧均为 NULL；非 NULL 场景由设备 6 画像 `mgmt=3 / bmc=5` 佐证） |
| TC-v5.13-49 | PASS | `DeviceBindingColumnsTest` 3 条断言实体 / SELECT / `mapRow` 都读两列，且真实详情响应含两字段 |

**汇总**：49 条 TC 中 **25 PASS / 24 PARTIAL / 0 FAIL**。
PARTIAL 全部是「真视口截图 / 500 台性能采样 / 错误密码样本 / 反向回放 / 批量真跑 / UI 全链路」证据形态缺口，
逐条在 §5 登记原因，不用推断补齐。**两条不可降级防线 TC-13 与 TC-17 均为 PASS 且是真实链路证据**。

## 3. 真实链路抓到的真缺陷（W7 修复，不是单测发现）

| # | 缺陷 | 怎么抓到的 | 修复 |
|---|---|---|---|
| 1 | **全局 / 项目画像端点恒返回空列表**：`AssetScopeSql.DEVICE_WHERE` 要求平台设备必须被项目 `project_asset(DEVICE)` 引用，但平台项目 88 只引用了 CREDENTIAL，没有 DEVICE 引用 | 首次真实 HTTP 探针 `GET /api/assets/collect-profiles` → `count=0`，设备 6 画像 `mgmt=None` | `AssetScopeSql` 抽出 `DEVICE_BODY` 单一真值，新增 `DEVICE_WHERE_PLATFORM`；仓储按 `projectId == platformId` 选 SQL；提交 `7333338`，新增 4 条回归单测 |
| 2 | **采集器返回 `status=FAILED` 时 `failure_kind` 为空**：`recordCollectFact` 只在「抛异常」时分类，落 FAILED 状态但无异常时不填 | 首次真实探针 `row=FAILED\|\|source=COLLECT... kind=-` | 改为「FAILED 状态也算失败」+ `FAILURE_UNCLASSIFIED=REJECTED` 兜底（L-63-04 粗分口径），`CollectWritesFactTest` 断言更新 |
| 3 | 探针自身断言口径错误：把「采集失败」写成「HTTP 非 200」 | 首次探针 TC-18 判红，但 DB 事实与绑定行为都正确 | 判据改为「HTTP 200 + `status=FAILED` + DB 事实 FAILED + `failure_kind` 非空 + 绑定不回填」，如实修正而非改期望值放水 |
| 4 | 草案隐含 `credential_verification` 有 `source` 列、枚举 `OK/FAIL` | 读 `V11_64` 迁移与 `SHOW COLUMNS` | 真实枚举 `SUCCESS/FAILED`、无 `source` 列；改用 `detail` 稳定前缀 `source=COLLECT; / source=VERIFY;`，零迁移 |
| 5 | W5 新增的 `CollectHistoryPanel.vue` 写原生 `<table>`，绕过 UX-05 表格规范壳 | 全量门禁第 06 步 `check_tables.py` 判红（`FAILS=1`） | 改用 `DataTable` 壳 + `columns` / `rowProps`；`check_tables.py` 68 处全走壳；**不放宽白名单**，重跑门禁 `FAILS=0` |

## 4. 与 v5.12.0 的读数对比

| 项 | v5.12.0 | v5.13.0 | 变化 |
|---|---|---|---|
| 后端单测（当轮 / 汇总） | 约 2021 / 2095 | 2073 / 2147 | +52（新增 11 个测试文件） |
| 前端单测 | 143 文件 / 1139 例 | 147 文件 / 1160 例 | +4 文件 / +21 例 |
| 契约 | 451 / 370 | 456 / 374 | +5 endpoints / +4 paths |
| TC | 44（37 PASS / 7 PARTIAL） | 49（25 PASS / 24 PARTIAL） | 真实链路判据显著增加，UI 截图缺口沿用 |
| 迁移 | 零迁移（`V11_71` 未追加） | 零迁移（最新仍 `V11_68`） | `V11_72` 降级预案未触发 |
| 真实 HTTP 探针 | 22 条 | 43 条（32 + 11） | 含两条防线的 DB 前后比对 |

## 5. 未达成 / 降级项登记（不冒充达成）

| 编号 | 内容 | 状态 | 归属 / 处理 |
|---|---|---|---|
| L-63-01 | 500 台画像聚合 ≤800ms 未采样；物化表预案未触发也未被证伪 | **未闭合**（TC-04） | 需造 500 台带压测前缀样本并计时；本版不因 13 台响应快就宣称达标 |
| L-63-05 | `CollectHistoryRow` 不含凭证名，FR-07「历史条目标注凭证」未达成 | **未达成**（TC-31） | 需在只读摘要中补掩码凭证名，不回 `spec_json`；留后续版本 |
| L-63-04 | 失败原因四类粗分，`REJECTED` 是「无异常可分类」兜底而非诊断结论 | 已登记、按设计接受 | 与真实采集错误码对齐需单独评估 |
| L-63-02 / 03 | 凭证轮换与过期提醒未做；scope 放宽后越权告警长期观察 | 沿用登记 | 本版越权用例 TC-22 / 44 已真实判红 |
| D-63-01 | 真视口截图、500 台性能、错误密码样本、反向回放、批量真跑、UI 全链路共 24 条 PARTIAL | 未闭合 | 逐条在 §2 写明缺口；**授权人待大师人工复核** |

**放行口径复核**：P0（FR-01/02/03/04/05/06/10）均有 `HTTP` 或 `DB` 或 `COLLECT` 一级证据，
其中 FR-06 的截图判据降级为源码机检 + DOM 断言（与 5.12 FR-01 同口径），逐条写明；
P1（FR-07/08/09）中 FR-07 **未完全达成**、FR-08 **部分达成**、FR-09 达成。
**本版不对外称「全部验收通过」**。

## 6. 两条不可降级防线（真实链路证据）

| 防线 | TC | 真实请求 | DB 前后 | 结论 |
|---|---|---|---|---|
| 都未绑 → 400 点名 | TC-v5.13-13 | `POST /api/global/assets/3/collect?confirm=true` → **400** `设备 3（leaf-switch-01）未绑定凭证，请先关联或手工指定 credentialId` | `credential_verification` 未新增行 | **PASS**，未静默兜底 |
| 已绑 A 用 B 采集仍为 A | TC-v5.13-17 | `PUT .../credentials` 绑 3 → `POST .../collect?confirm=true&credentialId=4` → **200 / status=PARTIAL** | `management_credential_id` 采前 **3**、采后 **3** | **PASS**，`AND management_credential_id IS NULL` 命中 0 行 |

样本说明：设备 3 临时把 `management_ip` 改为 `127.0.0.1` 走**平台本机 Linux 采集器**（真实只读命令 + 真实落 `device_host_spec` / `credential_verification`），
不是 mock；`finally` 逐列还原，探针末条断言 `restored=10.202.54.1,0,0` PASS。补充探针的 device 6 / 20 与新增 `project_asset` 引用同样全部还原。

## 7. 门禁与收口

| 项 | 判据 | 结果 |
|---|---|---|
| 单测 | 后端全量 `Failures=0 / Errors=0`；前端例数 ≥ 上一版 | 达成（2073 / 1160） |
| 类型 | `npx vue-tsc --noEmit` 无输出 | 达成 |
| 门禁 | `GATES_RESULT FAILS=0 UNJUDGED=0` | **达成**：57 步全绿（首轮 06-lint 判红，修复后重跑全绿） |
| 发布文档 | `check_release_docs.py v5.13.0` PASS | 六份齐全 + FR=10 / TC=49 逐条有结论，FAIL 0 / WARN 0 |
| 表格 | `check_md_tables.py` 对 v5.13.0 目录全 PASS | **达成**：6 份 / 89 表 / 636 数据行，列数一致 |
| 契约 | `gen_api_contract.py --check` PASS | 456 / 374 |
| 掩码 | `probe_oneshot_secret` 六处 PASS；画像 19 字段无明文 | 达成 |
| 两条防线 | TC-13 / TC-17 真实链路证据 | 达成（§6） |
| 越权 | NFR-06 跨项目 / 跨凭证用例逐条有结果 | 达成（TC-22 / 44 判红，TC-42 / 43 正向） |

## 8. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：49 条 TC 逐条结论；25 PASS / 24 PARTIAL / 0 FAIL；登记 W7 抓到的 4 个真缺陷 | 两轮真实探针 + 全量单测 + 部署自校验 |
| 2026-11-15 | **W7 收口**：补登记门禁抓到的第 5 个缺陷（表格壳）；门禁读数落定为 `FAILS=0 UNJUDGED=0` | `06-lint.log` + 第二轮 `gates.sh` 全量日志 |
