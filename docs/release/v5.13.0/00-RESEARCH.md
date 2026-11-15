# v5.13.0 需求调研（00-RESEARCH）：先把「绑定是事实」这四张表核清楚

> 流程位置：① 需求调研。每条结论标注**证据来源**；[已核实] / [推断] / [假设] 三档分开写。
> 上游：`docs/plan/重构评估-20261115/03-资产与采集闭环评估.md` §一~§二、`00-总纲-维度划分与版本路线.md` §5。
> 本文件核的是 **v5.12.0 收口（commit `687df14`）到 v5.13.0 W7（`7333338`）** 期间的实测事实，
> 重点是「草案写的东西在库里到底长什么样」，不替评估下结论。

## 1. 实测事实（2026-11-15，WSL 权威副本）

| # | 事实（三档标注） | 证据 |
|---|---|---|
| R-1 | [已核实] `credential_verification` **没有 `source` 列**，且 `result` 枚举是 `SUCCESS/FAILED`，不是草案隐含的 `OK/FAIL`；`failure_kind` 为 `AUTH/TIMEOUT/UNREACHABLE/REJECTED` | `db/migration/V11_64__credential_verification.sql`；`SHOW COLUMNS FROM credential_verification` |
| R-2 | [已核实] 项目侧 `domain/asset/Device.java` 与 `DeviceRepositoryJdbc.java` **读不到** `management_credential_id` / `bmc_credential_id`，但列在库里早已存在（V11_45） | W1 前源码；`SHOW COLUMNS FROM device` |
| R-3 | [已核实] 5.10 项目向导的 `GlobalAssetService.bindDeviceCredentials()` 已经在写**同一组**绑定列；本版不该再建一张绑定表 | `GlobalAssetService.java`（W1 前）；`ClearBindingTest` 断言同列 |
| R-4 | [已核实] `CredentialVerificationService.recordFromCollect()` 在 5.13 前是**死代码**：采集主路径从不调用它，`credential_verification` 表实测 0 行 | W2 前源码；W7 探针前 `SELECT COUNT(*)` = 0 |
| R-5 | [已核实] 最新迁移是 **V11_68**（不是草案隐含的 V11_71 之后），`schema-contract.json` `expectedTables=109`；`db-audit.sh` 的表数 SQL 排除 `flyway_schema_history`，故 `information_schema` 的 110 与 109 不矛盾 | `flyway_schema_history` 排序；`scripts/db-audit.sh:29-31`；补充探针 |
| R-6 | [已核实] `frontend/src/api/assetDetail.ts` 用 `device.account` 合成 `{id:0, name:'credential', keyMasked:...}` 假凭据行；`GlobalAssetsView.vue` 的 `canCollect()` 只看绑定、不看 `managementIp` | W5 前源码；`collectProfiles.spec.ts` 回归 |
| R-7 | [已核实] **平台项目 88 的 `project_asset` 只有 CREDENTIAL 引用，没有 DEVICE 引用**；`AssetScopeSql.DEVICE_WHERE` 要求平台设备「被本项目引用」才可见，直接套到画像查询会让全局画像恒返回空列表 | W7 首次真实 HTTP 探针 `count=0`；`SELECT asset_type,asset_id FROM project_asset WHERE project_id=88` |
| R-8 | [已核实] `GlobalAssetService.list()` 的全局资产列表用 `WHERE d.owner_scope='PLATFORM'`，**从不要求 project_asset 引用**，与 R-7 的画像口径不一致 | `GlobalAssetService.java:232`；5.12 W6 真实 HTTP 全局 13 台 |
| R-9 | [已核实] `LocalHostCommandRunner` 白名单是 `localhost / 127.0.0.1 / ::1`（或本机主机名）；本机采集**不需要凭据材料、不需要二次确认**，走真实 Linux 只读命令并真实落 `device_host_spec` | `LocalHostCommandRunner.java:29,68-81`；`HostInventoryService.accessOf()`；两轮真实探针 |
| R-10 | [已核实] 采集器返回 `status=FAILED` 时接口仍是 **HTTP 200**（不是抛异常）；`recordCollectFact` 旧逻辑只在「有异常」时填 `failure_kind`，故这类失败行的 `failure_kind` 为空 | W7 首次探针 `row=FAILED\|\|...kind=-`；`GlobalAssetService.java:594`（修复前） |
| R-11 | [已核实] 契约快照 **456 endpoints / 374 paths**；后端当轮 2073 条 / 汇总 2147 条；前端 147 文件 / 1160 例；`vue-tsc` 0 error；i18n 5/5（3097 键） | `gen_api_contract.py --check`；`mvn -o -B test`；`npx vitest run`；`check_i18n.py` |
| R-12 | [已核实] 部署自校验四项 PASS，运行期 `build-info commit=7333338` 与 HEAD 一致；两轮真实 HTTP + DB 探针共 **43 条断言全绿**，两条防线均有 DB 前后比对 | `bash scripts/deploy.sh`；`docs/evidence/v5.13.0/w7-http-probe*.txt` |
| R-13 | [已核实] 真实库样本：21 台设备（13 台 PLATFORM）、8 条凭证；凭证 3/4/5/6/7 为平台可见，凭证 8 为项目 95 私有（越权样本）；`device_host_spec` 95 行历史采集 | 只读 DB 探查脚本输出 |

## 2. 由事实推出的结论（[推断]，未逐条实测）

1. **R-1 决定「零迁移」是可行的**：没有 `source` 列就不改表，用 `detail` 的稳定前缀 `source=COLLECT; / source=VERIFY;` 区分来源，DB 侧 `LIKE 'source=COLLECT%'` 可查；将来真要加列只需把前缀换成列值，查询口径不变。
2. **R-3 + R-4 说明本版不是「建新链路」，而是「补写入链」**：绑定列和验证表都在，缺的是采集路径读绑定、写事实、界面展示。按「查询期聚合」做画像比物化表更省，且不会引入状态滞后。
3. **R-7 + R-8 说明可见性口径必须按调用方分**：项目侧走「私有 OR 已引用」，平台项目自身走「公共资产恒可见」；把两者混成一条 SQL 会静默丢数据，且单测全绿也发现不了（只有真实 HTTP 会暴露）。
4. **R-9 让「无 DUT 窗口」不再是不可执行借口**：本机采集通道是真实链路（真实命令 + 真实落库），可用它验证凭证回退、自动填空、两条防线；但它不能替代「远程错误密码」样本，后者仍须单独构造。
5. **R-10 说明「失败」有两种形态**：抛异常与落 FAILED 状态。验收判据必须同时覆盖，否则会把 200 + FAILED 误判成成功。

## 3. 未核实项（不写成结论）

| # | 待核 | 为什么没核 |
|---|---|---|
| U-1 | [假设] 500 台设备画像聚合 ≤800ms（TC-04 / NFR 性能） | 未造 500 台带压测前缀样本、未计时；13 台响应快不能外推 |
| U-2 | [假设] 真实「错误密码」凭证采集的失败分类（TC-07） | 未改凭证密码构造样本；本轮用 `127.0.0.2` 不可达构造 FAILED，分类为 `REJECTED` 兜底 |
| U-3 | [假设] v5.12 commit 上反向回放 TC-06 的行数不变（TC-09） | 未在旧 commit 上起服务回放；仅有代码事实佐证 |
| U-4 | [假设] 真视口 1440×900 下三列 / 历史对比 / 差异截图的视觉表现 | 无截图环境；UI 判据降级为 DOM / 源码机检，逐条标 PARTIAL |
| U-5 | [假设] 批量采集 3 台成功/失败分别计数与逐台原因（TC-38 / 39） | 未造批量样本真跑；仅复用 5.12 既有单测 |
| U-6 | [假设] 历史条目标注凭证名（TC-31） | **已核实为未达成**：`CollectHistoryRow` 只读摘要确无凭证名字段，登记 L-63-05 |

## 4. 与上游评估的差异

- 评估 03 建议「采集结果写 `credential_verification` 并加 `source` 列」；R-1 表明该表无此列且已有稳定枚举，本版改为 `detail` 前缀，**零迁移**达成。
- 评估 03 隐含「项目侧读不到绑定是因为没表」；R-2/R-3 表明是**实体与仓储列清单漏读**，5.10 向导一直在写，故本版只补读、不建表。
- 评估 03 未预见「平台项目自身没有 DEVICE 引用」；R-7 由 W7 真实探针补上，并直接促成 `DEVICE_WHERE_PLATFORM` 修复。
- 评估 03 的「500 台 ≤800ms」在本轮**未采样**（U-1），不因画像实现完成而自动视为达成。

## 5. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：13 条实测事实 + 5 条推断 + 6 条未核实项；核掉「加 source 列」「新建绑定表」两个过期假设 | commit `687df14` → `7333338` 的源码、DB 与两轮真实探针输出 |
