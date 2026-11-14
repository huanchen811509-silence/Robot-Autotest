# v5.8.0 需求调研：遗留项清算与「结论可见 / 选择可下发」收口

> 流程位置：① 需求调研（`docs/process/版本开发流程.md` §2.1）。
> 证据分级：**[核实]** 有代码行号 / 表结构 / 端口探测为证；**[推断]** 由已核实事实推导，未实测；**[未知]** 需下一阶段确认。
> 版本号 v5.8.0 为拟用号；本文件**不修改 `VERSION`**，落号以 G 门判定与大师授权为准。

---

## 1. 本轮输入

| 来源 | 内容 |
|---|---|
| 大师 2026-11-14 22:00 指示 | ① 推进 v5.8.0；② 白泽可以停止（已执行，见 §6） |
| v5.7.0 测试报告 §五 降级登记 | `L-57-01` ~ `L-57-11` 共 11 条，逐条带影响与处置归属 |
| v5.6.0 测试报告 §5.2 遗留 | `L-56-01` ~ `L-56-06` 共 6 条（真机批次与性能基线） |
| 本文件的方法 | **不抄上一版结论**：每条都回到代码/DB/端口重新核实，凡与旧描述不符的立即订正 |

---

## 2. 为什么要先做一次「遗留清算」

v5.7.0 的遗留表是**测试视角**写的（「这条没测到」），不是**实现视角**（「这条为什么测不到」）。
两者差别很大：例如 `L-57-11` 上一版写成「前端报告页不展示结论列」，
本轮核实发现**根因在后端 DTO 压根没返回这个字段** —— 按旧描述只改前端，会写出一列永远空白。

同理 `L-57-10` 上一版写成「判据无真实入库样本（fake 入口不写 `test_result`）」，
看上去像入库逻辑漏了；核实后是**fake 入口本来就是 Plan-only**（只渲染计划、不执行 pytest），
没有 junit 自然没有 `test_result`。所以它不是 bug，缺的是**一条真的跑过的执行**。

> 结论：v5.8.0 的第一步不是「按遗留表打勾」，而是**把每条遗留改写成可判定的验收判据**（见 §4）。

---

## 3. 现状事实盘点

### 3.1 报告结论（对应 L-57-11）

| 事实 | 级别 | 证据 |
|---|---|---|
| 后端报告详情 DTO **没有结论字段** | [核实] | `api/report/ReportController.java:142` `record ReportDetailView(runId, projectId, state, exportable, metadataMissing, metadata, counts, startedAt, finishedAt)` |
| 前端 `ReportDetail` 类型同样没有 | [核实] | `frontend/src/api/reports.ts:38` `interface ReportDetail extends ReportListItem { metadata }`，`ReportListItem` 只有 `passCount/failCount/skipCount` |
| 详情页只渲染「单用例结论」对比徽标，不渲染**整体结论** | [核实] | `ReportDetailView.vue:428-433` `verdict-cell` 用的是 `side.verdict`（对比表两侧），非 `criteriaConclusion` |
| 结论值确实存在于领域对象与快照/导出链路 | [核实] | `domain/report/ReportRecord.java:38` `criteriaConclusion`；`ReportSnapshotService.java:56` 写入 `criteriaConclusion`；`ReportExportService.java:445` 导出时兜底 `UNDETERMINED` |

**改写后的判据**：`GET /api/reports/{runId}` 响应体含 `criteriaConclusion`，且报告详情页有可见的结论区域；
未声明判据时显示 `UNDETERMINED` 而不是空。

### 3.2 条件全选（对应 L-57-09、L-57-06）

| 事实 | 级别 | 证据 |
|---|---|---|
| `toDescriptor()` 只被单测调用，无生产调用方 | [核实] | `composables/useServerSelection.ts:196` 定义、`:234` 导出；全仓 `grep` 命中仅 `useServerSelection.spec.ts` |
| 通用批量端点只收显式 `ids` | [核实] | `api/batch/BatchController.java:28-30` → `GenericBatchService.BatchRequest(entity, action, ids, comment, priority, domainTag, safetyLevel)`，`ids` 为 `List<Long>` |
| 用例批量端点同样只收 `ids` | [核实] | `api/caseasset/CaseController.java:149` → `TestCaseService.BatchRequest(action, ids, comment, priority, domainTag, safetyLevel)` |
| 用例集页筛选仍是前端内存过滤 | [核实] | `views/CaseSetsView.vue:121` `visibleItems = computed(() => items.value.filter(...))` |
| 选择快照侧已能表达「条件」 | [核实] | `application/execution/SelectionResolver.java` 支持 `ALL / SET / CASE / MIXED`，上限 `MAX_CASES = 10000`（:30） |

**改写后的判据**：执行「全选所有命中（条件模式）」后，前端发出的请求体含 `mode/filters/excludeIds`（而非 `ids`），
后端按条件求交集再逐条返回结果；候选数 > 上限时返回明确 400 而不是静默截断。

### 3.3 大选择下发上限（对应 L-57-01）

| 事实 | 级别 | 证据 |
|---|---|---|
| 选择解析允许到 10000 条 | [核实] | `SelectionResolver.MAX_CASES = 10000`（:30） |
| 但执行侧硬拦 200 条 | [核实] | `application/execution/CaseRunService.java:35` `MAX_CASES = 200`，:58 / :117 两处超限即抛 |
| 发起台注释已自认这条缝 | [核实] | `api/execution/RunLaunchController.java:50` 「与 `CaseRunService.MAX_CASES` 一致；该常量是包级私有，跨包…」 |

**改写后的判据**：201~10000 条的选择能落到「按批切分」的下发路径（pytest 内核已有 `MAX_CASES_PER_BATCH = 100` 切批能力），
或至少在界面上明确「需分批」并给出批数与入口，不得出现「选了 500 条点了没反应」。

### 3.4 判据真实入库（对应 L-57-10）

| 事实 | 级别 | 证据 |
|---|---|---|
| 入库收尾确实会调判据提取并落库 | [核实] | `api/kernel/PytestNicRunController.java:598-601` → `reportIngest.parseAndIngest(projectId, runId, null)`；`application/report/ReportIngestService.java:181` 调 `PytestCriteriaExtractor`，:206-218 写 `criteriaPayload` |
| `/runs/fake` 是 Plan-only，不执行 pytest | [核实] | `PytestNicRunController.java:200-215` fake 分支 → `execute()` → `runPrepared(prepared, null)`（:459-463，注释「同步路径不记账」） |
| 因此 fake 的 run 不会有 junit，也不会有 `test_result` | [推断] | 上两条 + `parseAndIngest` 以 junit 为输入 |
| 真机可用：两台 GPU 节点 SSH 22 可达 | [核实] | DB `device`：`gpu-node-01 = 10.202.54.106`、`gpu-node-02 = 10.202.54.107`；`/dev/tcp` 探测均可达 |

**改写后的判据**：用 real 模式跑一条**带判据声明**的用例，`test_result` 里该行 `criteria_json` 非空且 `verdict` 与判据一致；
同时报告详情能读到同一个结论（与 §3.1 串成一条链）。

### 3.5 证据包接入面（对应 L-57-03）

| 事实 | 级别 | 证据 |
|---|---|---|
| 只有「用例导入」一处接入 | [核实] | 全仓 `grep EvidenceBundleService`：唯一生产调用 `application/caseasset/CaseImportService.java:602` `evidenceBundles.write(...)` |
| 报告导出未接 | [核实] | `application/report/ReportExportService.java` 无 `EvidenceBundleService` 引用 |
| 编辑器保存未接 | [核实] | `packages/editor` 后端保存路径无 `EvidenceBundleService` 引用 |

**改写后的判据**：导出件与编辑器保存件都能在证据包里按 hash 找到，三处 `write/read/verify` 走同一套接口。

### 3.6 列表骨架与行内 CRUD（对应 L-57-05）

| 事实 | 级别 | 证据 |
|---|---|---|
| `ListScaffold` 只有 1 页接入 | [核实] | `grep -rl ListScaffold frontend/src/views` 命中 `TestReportsView.vue` 与其 spec |
| 台账里 `ReportsView` / `GlobalTemplatesView` / `GlobalAssetsView` 在「另版补接」白名单 | [核实] | `docs/evidence/v5.7.0/list-crud-matrix.md`（42 行：A13/B7/C22） |

**改写后的判据**：台账里 C 类（缺行内入口）条目数下降，且缺的那几页要么接上、要么逐条写明「按设计不做」的理由。

### 3.7 判据覆盖内核（对应 L-57-02）

| 事实 | 级别 | 证据 |
|---|---|---|
| 判据提取只实现 pytest 侧 | [核实] | `application/kernel/pytest/PytestCriteriaExtractor.java`；`ReportIngestService:181` 是唯一调用点 |
| RF（robot）侧入库走 `PytestRules` 之外的另一条解析路径，无判据提取 | [核实] | `ReportIngestService` 中 `criteria*` 只出现在 pytest 分支（:181-218） |

**改写后的判据**：RF 报告入库时同样产出判据行（或明确登记「RF 不参与判据门禁」并写进 PRD 的降级预案）。

### 3.8 性能基线 fps（对应 L-56-05）

| 事实 | 级别 | 证据 |
|---|---|---|
| 性能机检要求 `long_list.fps`，缺则直接记违规 | [核实] | `tools/check_perf_budget.py:126-133`：`fps is None → "长列表缺 fps，无法判定"`；预算 `minFps = 50` |
| 当前无 10000 行长列表样本 | [核实] | `docs/evidence/v3.2.0/EW32-01/perf/perf-report.json` 无 `long_list` 段（门禁 22 因此长期按「未测」处理） |

**改写后的判据**：造 10000 行样本 → 用现有性能探针量 fps → 数值入 `perf-report.json` 且 `check_perf_budget` 判 PASS 或如实判红。

### 3.9 一次性凭证采集链（对应 L-57-04）

| 事实 | 级别 | 证据 |
|---|---|---|
| `recordFromCollect` 只有定义，无调用方 | [核实] | `application/asset/CredentialVerificationService.java:183`，全仓无其他引用 |
| 「受理 + 内存材料 + 六处零泄漏」已实测 | [核实] | v5.7.0 `05-TEST-REPORT.md` §一 `PROBE_RESULT PASS=6/6` |

**改写后的判据**：手工输入的一次性凭证能驱动一次真实采集（需要真机），且采集后材料即焚、
审计只留「已用」事实。**本条依赖 §3.4 的 real 通路先打通。**

### 3.10 口径类（不改代码，只改文档）

| 编号 | 现状 | 处置建议 |
|---|---|---|
| `L-57-07` | 平台集写权限落 `PermissionAction.UPDATE`（枚举无 `WRITE`） | 语义等价，**建议保持**并继续在 PRD 说明，不新增枚举 |
| `L-57-08` | RF dryrun 只做 `[ ERROR ]` 静态判定，不拉起进程 | PRD 已定「预览不得启动进程」，**建议标记「按设计不做」** |
| `L-56-06` | pytest 入库是收尾同步调用，超大批次拉长响应 | 行为已获大师认可，**建议仅作性能项**，不改为异步 |

---

## 4. 待办清单与分类（按「能否在无真机条件下闭合」）

### A 类：纯代码/数据可闭合（不碰 DUT，可全程机检）

| 编号 | 事项 | 判据落点 |
|---|---|---|
| A1 | 报告结论贯通：DTO + 前端 + 未声明判据显示 `UNDETERMINED` | §3.1 |
| A2 | 条件全选可下发：批量端点接受选择描述符 | §3.2 |
| A3 | 用例集页接服务端筛选与计数 | §3.2 |
| A4 | 列表骨架补接剩余页（按台账 C 类逐条决定接或写明不做） | §3.6 |
| A5 | 证据包补接报告导出与编辑器保存 | §3.5 |
| A6 | 10000 行性能基线（造数据 + 量 fps） | §3.8 |
| A7 | RF 侧判据提取，或在 PRD 明确降级 | §3.7 |

### B 类：需真机（G-1 已按 61 条授权，安全闸仍不可绕）

| 编号 | 事项 | 前置 |
|---|---|---|
| B1 | real 跑一条带判据的用例 → `test_result.criteria_*` 真实样本 | `confirmReal=true` + ADR-0013 安全闸 + gpu-node-01/02 在线（已探可达） |
| B2 | 201~10000 条选择的真实下发（切批） | 同 B1，且需要一批绑定好安全级的用例 |
| B3 | 一次性凭证驱动真实采集 | 同 B1 |
| B4 | 命令重解析统计 / 复现率多轮 / 批次内取消 / FLAKY 样本（L-56-01~04） | 需多轮真机批次 |

### C 类：口径决定，不改代码

`L-57-07`、`L-57-08`、`L-56-06`（见 §3.10）。

---

## 5. 未知项（须在 DEV 阶段确认）

| # | 未知 | 影响 |
|---|---|---|
| U1 | 批量端点接受「条件描述符」后，`undoToken` 的撤销语义怎么定义（逐条还是整批） | A2 的接口形状 |
| U2 | 报告结论在「历史快照 vs 当前重算」不一致时显示哪个 | A1 的显示口径 |
| U3 | 10000 行造数据放在哪个库（`robot_dev` 还是独立压测库），会不会污染报告列表 | A6 的执行方式 |
| U4 | RF 判据的载体（`output.xml` 里没有结构化判据，可能只能从 tag/文档字符串取） | A7 是否值得做 |
| U5 | real 跑一条后，`test_result` 的 `run_id` 会进入报告列表，是否需要事后软删 | B1 的清理动作 |

---

## 6. 已执行的环境动作（白泽停止）

| 服务 | 原状态 | 处置 | 恢复命令 |
|---|---|---|---|
| `baize8010.service`（白泽后端，java -jar） | active running 7h，`disabled` | `systemctl stop baize8010` → inactive | `sudo systemctl start baize8010` |
| 8011 前端代理（`node .../调研报告-白泽/tools/proxy.js`，孤儿进程） | 由 `/init` 直接拉起 | `kill 451597` → 已退出 | `node /mnt/e/workspace/自动化测试/调研报告-白泽/tools/proxy.js &` |
| 3307 独立 mysqld（`--defaults-file=/home/evergreen/baize-my.cnf`，datadir `~/baize-db`） | 运行中 | `mysqladmin --defaults-file=... -u root shutdown`（正常关机，datadir 保留） | `mysqld --defaults-file=/home/evergreen/baize-my.cnf &` |

复核：三个端口**全部释放**；系统 `mysql.service`（3306）**未动**，仍 active —— 本项目测试数据不受影响。

---

## 7. 结论

1. 遗留 11 条（L-57）+ 6 条（L-56）中，**7 条可在无真机条件下闭合**（A 类），4 条需真机批次（B 类），3 条属口径（C 类）。
2. 两条「看着像 bug」的遗留经核实是**诊断写偏了**：L-57-11 根因在后端 DTO，L-57-10 根因是没跑过 real —— 这两条的修法与前版描述不同，必须在 PRD 里改口径。
3. 真机通路可用（两台 GPU 节点 22 可达），B 类不是「做不到」，是「要不要在这一版做」。
4. 因此 v5.8.0 的范围**必须由大师拍板**：详见 `01-ASSESSMENT.md` 的三档建议。

---

## 8. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-14 | 首版：遗留 17 条逐条回代码核实，改写为可判定判据，分 A/B/C 三类；附白泽停止记录 | 本轮 grep / DB 查询 / 端口探测 |
