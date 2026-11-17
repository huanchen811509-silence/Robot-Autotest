# v5.22.0 调研笔记（报告可信 + 体验收尾）

> 流程位置：① 需求调研。范围由大师 2026-11-16 拍板：**报告可信 + 体验收尾**
> （L-19-05 导出内容量 / L-19-06 计数真源 / L-20-07 FR-06 四件）。
> 证据级别：**A = 本轮实测（接口响应 / 代码行 / 库表读数）= [已核实]**，**B = 先前版本文档记载 = [推断]**，**C = 待核实 = [假设]**。
> 本笔记只陈述事实与差异，**不提方案、不写判据**（那是 ②评估 与 ③PRD 的事）。
> **修订 r2（2026-11-17）**：补测 L-19-06 与 FR-06；推翻 r1 的两条结论（见 §6 / §7）。

---

## 1 调研方法与样本

| 项 | 内容 |
|---|---|
| 环境 | 后端 `:8081`（5.21.0 / `e5bc9b6`）+ 入口 `:8001`，真实库 `robot_dev`（MySQL `:3306`） |
| 账号 | `qa-e2e-admin`（管理员），口令来自 `~/.config/robot-autotest/env` |
| 样本 1 | 工程 95「EHT pytest 用例库工程」的 3 份报告（`id=20/21/22`，同一 run `w56-24-retry-2`） |
| 样本 2 | run `EHT-FAKE-1794805219801`（工程 95 / pytest / 明细 70 条：PASS 5、FAIL 4、SKIP 61） |
| 手法 | 只读接口 + 只读 SELECT；导出实测会写导出记录与落盘文件（测试环境可接受，未改业务数据） |
| 探针 | `w522_prd/w522_probe_counts_v2.py`（四口径计数）、`w522_prd/w522_probe_export_two_paths.py`（两链路导出 + 撤回闸）、`w522_prd/w522_probe_db_v3.sh`（库内三方比对） |
| 代码交叉 | `ReportViewRenderer` / `ReportFieldWhitelist` / `ExportService` / `ReportExportService` / `ReportRepositoryJdbc` |

---

## 2 A 组：对外报告「有什么、缺什么」（L-19-05）

### 2.1 视图层事实

| # | 事实 | 证据 |
|---|---|---|
| F-22-01 | 对外视图实际返回 **21 个键**（`external=true`），与 `ReportFieldWhitelist.EXTERNAL_ORDER` 的 21 项一致 | A：`report 22 keys=21` |
| F-22-02 | 白名单**允许**的字段是 21 通用 + 5 pytest 专属 = 26 项；`METADATA_ORDER` 另有 5 键，但**不是**视图键 | A：`ReportFieldWhitelist:33-79` |
| F-22-03 | 视图里 metadata 只出现 **2 项**：`swBuild`、`caseKind`；`QA_PROJECT` / `DUT` / `LINK` **未进入视图** | A：`putMetadata` 只 `put("swBuild")` / `put("caseKind")`（`ReportViewRenderer:210-219`） |
| F-22-04 | `projectName` 不是真项目名，是 `"project-" + projectId` 拼出来的（实测 `project-95`，真名「EHT pytest 用例库工程」） | A：`ReportViewRenderer:219` |
| F-22-05 | `coverageRate` **恒为 100.0**、`caseCovered` **恒等于 `caseTotal`** | A：`ReportViewRenderer:146-147` |
| F-22-06 | `failureDistribution` 固定为 `{fail: failCount}`、`trend` 恒为空数组 | A：`ReportViewRenderer:148-149` |
| F-22-07 | 白名单里**没有任何用例明细字段**（无 `caseList` / `caseRows` / `caseResults` / `details`），模板链路的对外视图不可能列出逐条用例 | A：`ReportFieldWhitelist:33-52` + 视图 21 键 |
| F-22-08 | 同一份对外视图里同时出现 `passRate=0.0`、`conclusion=FAIL`、`coverageRate=100.0` —— **失败的报告覆盖率 100%** | A：`GET /api/test-reports/22?external=true` |

> **实测的关键反例（两处）**
>
> 1. 报告 22 的快照 JSON 里 metadata **五键齐全**
>    （`SW_BUILD=v5.6.0-w56-24b`、`CASE_KIND=pytest`、`QA_PROJECT=EHT-PYTEST-LIB`、
>    `DUT=gpu01`、`LINK=dual-up-pfc`），`metadataMissing` 只有 `["STARTED_AT"]`。
>    所以「对外报告看不到设备 / 链路」**不是没采到数据，而是渲染时没往外放**。
> 2. `coverageRate` 与通过率无关：同一视图 `passRate=0.0` 而 `coverageRate=100.0`。
>    `caseCovered = caseTotal` 是恒等式，所以该比值永远是 100，**不具备区分能力**。

### 2.2 导出物事实：**两条链路必须分开测**

系统里有两条导出入口，实现与服务对象完全不同：

| 链路 | 端点 | 数据来源 | 是否带用例明细 | 是否有 metadata 闸 |
|---|---|---|---|---|
| **报告级**（模板链路） | `GET /api/test-reports/{id}/export?format=` | `test_report` 快照 + 模板白名单 | 无 | **无** |
| **run 级**（执行链路） | `POST /api/reports/{runId}/export?projectId=&mode=&format=` | `run` 表 + `test_result` 现场装配 | **有**（xlsx 第二 sheet） | 有（`MetadataGate`） |

**报告级实测（`report id=22`，`state=REVOKED`）**

| 格式 | 字节 | sheet / 页 | 含用例明细 | 含 `CASE_ID` / `RUN_ID` | 结论 |
|---|---|---|---|---|---|
| pdf | 1521 | 1 页 | 否 | 否 | 空壳级 |
| html | 1531 | — | 否 | 否 | 空壳级 |
| docx | 2683 | — | 否 | 否 | 空壳级 |
| xlsx | 4104 | 单 sheet `report`，22 行（表头 + 21 字段） | 否 | 否 | 汇总级，无用例 |

**run 级实测（`run=EHT-FAKE-1794805219801`，明细 70 条）**

| 格式 | 字节 | sheet / 页 | 含用例明细 | 结论 |
|---|---|---|---|---|
| xlsx | 7051 | 双 sheet：`report`（16 行）+ **`用例明细`（71 行 = 表头 + 70）** | **有** | 可交 |
| pdf | 539607 | **10 页**，嵌入 NotoSansSC / SimSun / Microsoft YaHei | 有 | 可交 |

- F-22-09（A）：**能交出去的只有 run 级**。报告级四格式都不含用例、不含 `CASE_ID` / `RUN_ID`，PDF 仅 1.5 KB。
- F-22-10（A）：报告级 PDF **中文全部变成 `?`**。根因是 `ReportExportService.pdf()` 用
  `/F1 Helvetica` + `ascii()`，而 `ascii()` 把 `c < 32 || c >= 127` 的字符一律替换成 `'?'`
  （`ReportExportService:217-223`）。不是「没写内容」，是**写不进去**。
- F-22-11（A）：报告级 xlsx **只有一张 sheet**，`ReportExportService.xlsx(…)` 的明细参数在该链路
  传的是空集合（`Map.of()` + 无 `detailRows`）。

### 2.3 撤回闸：报告级链路**根本没有闸**

| # | 事实 | 证据 |
|---|---|---|
| F-22-12 | 报告 22 快照 `exportable=false`、`metadataMissing=["STARTED_AT"]`、`state=REVOKED` | A：库表 `test_report.snapshot_json` |
| F-22-13 | `GET /api/test-reports/22/export?format=pdf` → **200 + 1521B**；`format=xlsx` → **200 + 4104B** | A：实测 |
| F-22-14 | `TestReportController.export()` 只做 `requireProjectAccess` 后直接 `reports.export(id, format)`，**无 metadata 闸、无状态判断** | A：`TestReportController:127-138` |
| F-22-15 | 有闸的那条（`ExportService.export`）只判 `MetadataGate.check(report.metadata())`，**不判 `report.state`** | A：`ExportService:150-163` |

→ 即：**作废（REVOKED）与不可对外（`exportable=false`）两条闸，在用户实际点的那条链路上都不存在。**

---

## 3 B 组：同一 run 的计数（L-19-06）

### 3.1 四口径对照（同一 run `w56-24-retry-2`）

| 口径 | total | pass | fail | skip | 备注 |
|---|---|---|---|---|---|
| R1 运行记录 `GET /api/execution/runs/{runId}?projectId=95` | 1 | 0 | 1 | — | 无 skip 字段 |
| R2 报告视图 `GET /api/test-reports/22?external=true` | 1 | 0 | 1 | 0 | 另有 `coverageRate=100.0` |
| R3 报告快照 `test_report.snapshot_json` | 1 | 0 | 1 | 0 | 与 R2 同源 |
| R4 报告列表行 `GET /api/test-reports?projectId=95` | — | — | — | — | **行里根本不含计数**，只有 `id/runId/state/version/title/…` 14 个字段 |
| 明细真源 `test_result` | 1 | 0 | 1 | 0 | 一致 |

- F-22-16（A）：该样本三方一致，**L-19-06 在这个 run 上不复现**。
- F-22-17（A）：**报告列表行不含任何计数**，列表页无法显示「过了几条 / 挂了几条」。

### 3.2 计数真源确实不一致 —— 在另外两个 run 上复现

| run | 明细 `test_result`（pass/fail/skip） | `run` 表（total/passed/failed） | 差异 |
|---|---|---|---|
| `EHT-FAKE-1794805219801`（pytest，id 17057） | 70 行 = **5 / 4 / 61** | 70 / **0** / 4 | **`passed_tests` 少记 5**；**skip 无列可存** |
| `RUN-20261117030052-cf0000`（robot，id 17060） | 1 行 = 0 / 1 / 0 | **0 / 0 / 0** | 明细有 1 条 FAIL，run 表三项全 0 |
| `RUN-20261117030022-cf0000`（robot，id 17059） | 1 行 = 0 / 1 / 0 | **0 / 0 / 0** | 同上 |

- F-22-18（A）：`run` 表**没有 `skipped_tests` 列**（`desc run` 只有 `total_tests` / `passed_tests` / `failed_tests`）
  → 跳过数在任何 run 级读数里都无法表达。
- F-22-19（A）：`passed_tests` 存在**未回填**：明细 5 条 PASS，run 表记 0。
- F-22-20（A）：robot 内核的 run 连 `failed_tests` 也未回填（明细 1 FAIL → run 表 0）。
- F-22-21（A）：`verdict` 取值分布（全库）：`FAIL 497 / PASS 165 / SKIP 132 / DISPATCHED 4` ——
  `DISPATCHED` 是未落定态，会混进按 verdict 分组的统计。

→ **真源是 `test_result`（逐条明细），`run` 表的三列是派生值且回填不全。**

---

## 4 结构性发现：两套「报告」并存

| 维度 | run 级（旧） | test_report 级（新，v5.14+） |
|---|---|---|
| 后端入口 | `/api/reports/{runId}/…` → `ExportService` | `/api/test-reports/{id}/…` → `TestReportService` |
| 前端页面 | `ReportsView.vue` / `ReportDetailView.vue` | `TestReportsView.vue` / `TestReportDetailView.vue` |
| 数据来源 | `run` 表 + `test_result` 现场装配 | `test_report.snapshot_json/html`（冻结快照） |
| 导出 | 带明细、有闸、中文正常 | 无明细、无闸、中文变 `?` |
| 状态语义 | 无状态机（跟随 run 状态） | `DRAFT` / `REVOKED` / `ARCHIVED` |

- F-22-22（A）：同一 run 的两种入口给出**相反结论**。
  样本 `w56-24-retry-2`：`run.deleted_at=2026-11-16 10:18:20`（已软删）
  → run 级 `POST /api/reports/w56-24-retry-2/export` 返回 **404「报告不存在」**
  → 报告级 `GET /api/test-reports/22/export` 返回 **200 + 1521B**
  → 而报告列表 `GET /api/test-reports?projectId=95` **仍列出 20/21/22 三份**。
  即：**软删之后，报告「列表可见、能导出，但 run 级说它不存在」。**
- F-22-23（A）：`ReportRepositoryJdbc.find()` 查的是 `run` 表（`REPORT_COLUMNS FROM run`），
  过滤 `deleted_at IS NULL`；`TestReportController` 查的是 `test_report` 表，
  **不过滤 run 软删** → 两处可见性口径不同（`ReportRepositoryJdbc:172-178` 对照 `TestReportController:38-44`）。
- F-22-24（A）：同一 run `w56-24-retry-2` 有 **3 份报告**（`20:DRAFT`、`21:REVOKED`、`22:REVOKED`），
  版本 1/3/5，无唯一约束拦截。

---

## 5 C 组：FR-06 四件现状（L-20-07）

判据原文取自 `docs/release/_draft/v5.20.0/PRD.md` FR-v5.20-06（判据 1–4）。

| # | 判据 | 代码实测 | 判定 |
|---|---|---|---|
| 1 | 新建用例集后右栏自动展开且标题为新集合名 | `CaseSetsView.submit()` 创建分支只做 `message` → `closeForm()` → `load()`，**未对 `activeSet` 赋值**（`CaseSetsView.vue:324-331`） | **未达成** |
| 2 | 空态 3 个动作（新建 / 导入 / 看样例）各自可跳转 | 空态插槽 `#emptyAction` 只有两个条件分支：过滤态 →「清空筛选」、错误态 →「重试」（`CaseSetsView.vue:859-871`）；**无新建 / 导入 / 样例入口** | **未达成** |
| 3 | 0 次运行项目卡显示 `—` + 「发起第一次运行」入口 | `formatNumber(runCount, t('workbench.noData'))` 只在 `null/undefined/NaN` 时回落（`utils/format.ts:111-118`），**`0` 会原样显示 `0`**；`ProjectCard.vue` 无「发起第一次运行」入口 | **未达成**（`null` 显示 `—`，`0` 显示 `0`） |
| 4 | 参数化展开处标注「用例 → 测试项」 | 全前端 `zh-CN.ts` 仅 1 处「测试项」，出现在项目类型说明里，**参数化展开处无该说明** | **未达成** |

- F-22-25（A）：FR-06 四条判据 **0/4 达成**，与 v5.20 测试报告「FR-06 整条未做」的记载一致，
  v5.21 也未补做（v5.21 范围是角色可用性与工程同构）。

---

## 6 与旧结论的差异（修订 r1 的错误）

| 旧说法 | 本轮实测 | 处理 |
|---|---|---|
| r1：「四格式导出**全部**空壳级（1.5–4 KB）」 | 只有**报告级**链路是空壳；**run 级** xlsx 7 KB 双 sheet 含 70 条明细、pdf 540 KB 10 页中文正常 | **推翻**，见 §2.2 |
| r1 / 旧日志：「`exportable=false` 仍可导 → 闸没生效」 | 准确表述是：**报告级链路没有闸**；有闸的 run 级链路因 run 软删先 404，从未执行到闸 | **修正**，见 §2.3 |
| v5.19：「对外视图只有 19 字段、不含 metadata，需改白名单体系（大工程）」 | 视图 21 键；`METADATA_ORDER` 五键已在白名单里，缺的是 `putMetadata` 只映射 2 项 | **缩小范围** |
| v5.19 / L-19-06：「后端 `passedTests=0` 而报告 5/4/61」 | 在 `EHT-FAKE-1794805219801` 上**复现**（明细 5/4/61 vs run 表 0/4/0） | **确认**，见 §3.2 |

---

## 7 修订记录

| 版本 | 时间 | 改动 | 起因 |
|---|---|---|---|
| r1 | 2026-11-16 23:5x | 首版：A 组视图与导出、L-19-05 推翻 | 大师拍板 5.22 范围 |
| r2 | 2026-11-17 04:5x | 补测 L-19-06 与 FR-06；拆开两条导出链路；修正「全部空壳」与「闸没生效」两条结论；新增两套报告并存、软删可见性矛盾、重复报告三节 | 大师「请继续核实」 |

---

## 8 开放问题（已于 2026-11-17 拍板，见 `01-ASSESSMENT.md` §11）

| # | 问题 | 决定 |
|---|---|---|
| Q1 | 对外报告要不要放 `CASE_ID` / `RUN_ID` / 用例明细？ | **放**（D-22-01），授权改 C5 白名单文档 |
| Q2 | `REVOKED` 报告应「禁止导出」还是「允许但打内部标」？ | **禁止导出**（D-22-02），存量 3 份一并禁止 |
| Q3 | 假值（`coverageRate` / `caseCovered` / `trend` / `failureDistribution`）算真值还是去掉字段？ | **删字段**（D-22-03），`EXTERNAL_ORDER` 21 → 17 |
| Q4 | 两套报告是否合并为一条链路？ | **本版不动入口**（D-22-04），只补齐报告级能力 |
| Q5 | 计数回填是「修回填」还是「查明细实时算」？ | **修回填 + 幂等迁移**（D-22-05），`run` 表补 `skipped_tests` 列 |

---

## 9 本轮结论（供 ②评估 用）

1. **「报告可信」的真问题不是「字段少」，而是三条**：
   ① 报告级导出链路没有闸、没有明细、中文写不进去；
   ② 对外视图有 4 个硬编码假值，且与 `passRate` 自相矛盾；
   ③ metadata 五键在库里齐全，渲染只放 2 键，项目名是拼接假名。
2. **「能交的报告」其实已经存在**（run 级 xlsx / pdf），问题在于**前端主入口指向的是不能交的那套**。
3. **计数不一致真实存在且不止 pytest**：`run.passed_tests` 有未回填，`skipped_tests` 列不存在。
4. **FR-06 四条判据 0/4**，都是小改动（每个 10–30 行级），适合作为本版体验收尾。
5. 本轮未做任何代码改动，只做只读实测与代码交叉核对。
