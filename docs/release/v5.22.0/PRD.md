# v5.22.0 产品需求文档（PRD）：报告可信 + 体验收尾

> 流程位置：③ PRD。上游：`00-RESEARCH.md` r2（25 条事实）、`01-ASSESSMENT.md`（方案 B + 5 条决策）。
> 本文件只写「做什么、怎么判定做完」；实现路径写在 `DEV-PLAN.md`。
> 编号规范：`FR-v5.22-01 … 07`，判据编号 `01-1` 形式。

---

## 1. 版本定位

**一句话**：让报告**敢发给客户**——界面、导出文件、列表三处说同一件事，不出现假值，撤回的报告导不出去；顺带把 v5.20 挂账的 FR-06 四条体验判据收口。

### 1.1 为什么三条 P0 必须一起做

| 只做一条会怎样 | 结果 |
|---|---|
| 只补 metadata 三键 | 界面看得到设备/链路了，但导出的 PDF 还是 1.5 KB 空壳、中文还是 `?` → 用户仍然不能交 |
| 只修导出 | 导出的文件里仍带着 `coverageRate=100.0` 这种假值 → 交出去是失实陈述 |
| 只加撤回闸 | 撤回了导不出去，但能导出的那份本身没有明细 → 闸保护的是一个不可用的东西 |

三条共用同一套「对外视图 + 导出装配」，拆开做等于同一个函数改三遍、测三遍。

### 1.2 两个"报告"的定义（本版判据的唯一口径）

本版**只改 `test_report` 这一套**（报告级），不改 run 级入口（D-22-04）。

| 名称 | 指什么 | 本版是否改 |
|---|---|---|
| **报告级** | `test_report` 实体 + `GET /api/test-reports/{id}/…` + `TestReportsView.vue` | **是**（本版全部 FR 落在这里） |
| **run 级** | `run` 表视图 + `/api/reports/{runId}/…` + `ReportsView.vue` | **否**（只做回归，保证不退化） |

### 1.3 冻结语义（本版新增的硬约束）

报告是**冻结快照**：冻结那一刻的内容不再随底层数据变化。由此推出两条：

1. 报告级导出**只准读快照**，不准回查 `run` / `test_result` 现场装配——否则同一份报告今天导和明天导内容不同。
2. 因此用例明细必须在**冻结时写进快照**；存量报告冻结时没写，导出时明细区标「该报告冻结时未记录用例明细」，**不许回填伪造**。

---

## 2. 术语与硬约束

| 术语 | 定义 |
|---|---|
| metadata 七键 | `SW_BUILD` / `CASE_KIND` / `QA_PROJECT` / `DUT` / `LINK` / `CASE_ID` / `RUN_ID`（扩自原五键） |
| 对外视图 | `GET /api/test-reports/{id}?external=true` 返回的字段 Map，受 C5 白名单约束 |
| 假值字段 | 无真实数据源却输出固定值的字段：`coverageRate`(恒 100.0) / `caseCovered`(=caseTotal) / `trend`(恒 `[]`) / `failureDistribution`(固定单键) |
| 明细 | 逐条用例结果：用例编号、名称、结论、耗时、失败原因、命令原文（脱敏后） |

**硬约束**

1. 白名单是唯一真源：改字段必须先改 `docs/plan/C5-对外报告字段白名单-20261106.md`，再改代码（D-22-01 / D-22-03 已授权）。
2. 字段顺序不可随意调整：`EXTERNAL_ORDER` 的顺序 = 导出列序，调整需单独授权。
3. 明细文本必须过凭据闸（`assertNoSecretsInDetails` 同款规则），命中即拒导，不允许「脱敏后照样导」。
4. 本版不新增菜单项、不改 `GLOBAL_NAVIGATION` / `NAVIGATION` 结构（D-01）。
5. 存量报告不重刷（改写冻结快照 = 篡改审计对象）。

---

## 3. 需求清单

| 编号 | 类别 | 标题 | 优先级 |
|---|---|---|---|
| FR-v5.22-01 | 可信 | 对外视图字段收口：补 5 键、删 4 假值、换真项目名 | P0 |
| FR-v5.22-02 | 可信 | 报告级导出带用例明细（冻结时入快照） | P0 |
| FR-v5.22-03 | 可信 | 报告级 PDF 中文可读（改 HTML→PDF，不再手写 Helvetica） | P0 |
| FR-v5.22-04 | 可信 | 导出闸：`REVOKED` / `exportable=false` 拒绝导出 | P0 |
| FR-v5.22-05 | 一致 | 计数口径：`run` 表补 `skipped_tests` 并回填 | P1 |
| FR-v5.22-06 | 体验 | FR-06 四件：新建即选中 / 空态三动作 / 0 次运行显 `—` / 参数化说明 | P1 |
| FR-v5.22-07 | 工程 | C5 文档、单测、语言包与字段集同源 | P0 |

---

## 4. 需求详述

### FR-v5.22-01 对外视图字段收口【P0】

**现状**（F-22-03/04/05/06/08）：视图 21 键里，metadata 只渲染 `swBuild` / `caseKind`；
`QA_PROJECT` / `DUT` / `LINK` 在快照里齐全却不输出；`projectName` 是 `"project-"+id` 拼接；
`coverageRate` 写死 `100.0`、`caseCovered` 恒等于 `caseTotal`、`trend` 恒空、`failureDistribution` 固定单键。

**要做什么**

1. `METADATA_ORDER` 由 5 键扩为 7 键（加 `CASE_ID` / `RUN_ID`）。
2. `putMetadata` 渲染全部 7 键 → 视图键为 `qaProject` / `dut` / `link` / `caseId` / `runId`（`swBuild` / `caseKind` 不变）。
3. `projectName` 取 `project.name` 真名；查不到时输出 `""` 并记 warn，**不许再拼 `project-<id>`**。
4. 删除 4 个假值字段的产出代码与白名单项（`buildView` 里那 4 行 `put` 一并删除，不是「置空」）。
5. `EXTERNAL_ORDER` 变为 22 项，顺序：
   `reportId, title, version, frozenAt, conclusion, passCount, failCount, skipCount, passRate, caseTotal, projectName, swBuild, caseKind, qaProject, dut, link, caseId, runId, startedAt, finishedAt, disclaimer, watermark`

**验收判据**

| # | 判据 | 证据形态 |
|---|---|---|
| 01-1 | `GET /api/report-templates/fields` 返回 **22 项**且顺序与上面一致；**全选模板**的新报告 `GET /api/test-reports/{id}?external=true` 键集等于这 22 项、顺序一致 | 接口 JSON（键序列）+ 单测 `ReportTemplateKernelTest` 断言 22 |
| 01-2 | 视图含 `qaProject` / `dut` / `link` / `caseId` / `runId` 且值为快照里的真实值 | 接口 JSON 对照 `snapshot_json.metadata` |
| 01-3 | 视图**不含** `coverageRate` / `caseCovered` / `trend` / `failureDistribution`（键不存在，不是值为 null） | 接口 JSON + `ReportViewRenderDumpTest` |
| 01-4 | `projectName` = 工程真名（样本：`EHT pytest 用例库工程`），不是 `project-95` | 接口 JSON + 库表 `project.name` |
| 01-5 | 四种导出格式的字段集合与顺序一致，各 22 项 | `ReportFourFormatDumpTest` 输出 |
| 01-6 | 旧快照（模板里含已删字段）渲染不报错，已删字段被静默丢弃，**键数 = 冻结模板中仍在白名单里的项**（样本 report 22 = 17） | 用 report 22 渲染 + 输出 |

> **口径说明（v5.22 实测修正）**：外部视图键集 = **快照内模板字段 ∩ 白名单**，不是恒等于 22。
> 模板是冻结时的用户选择，改白名单不得让历史报告凭空多出字段——那是破坏「冻结」语义。
> 因此 01-1 用「全选模板的新报告」验证字段集上限，01-6 用存量报告验证裁剪行为。

### FR-v5.22-02 报告级导出带用例明细【P0】

**现状**（F-22-11）：报告级 xlsx 只有单 sheet `report`（22 行 = 表头 + 21 字段），无明细；
`ReportExportService.xlsx(…, details)` 在该链路传的是空集合。

**要做什么**

1. **冻结时把明细写入快照**（新增键 `cases`，形态为数组，逐条含
   `caseCode / name / verdict / durationMs / failureMsg / commandText`；`commandText` 与 `failureMsg` 先过凭据闸）。
2. 导出 xlsx 时第二 sheet `用例明细` 从快照 `cases` 读取（**不回查 `test_result`**，见 §1.3）。
3. 快照无 `cases` 的存量报告：明细 sheet 仍生成，仅一行说明文字「该报告冻结时未记录用例明细」，**不置空、不伪造**。
4. `CASE_ID` / `RUN_ID` 同时进视图（同 FR-01），保证文件打开即可追溯。

**验收判据**

| # | 判据 | 证据形态 |
|---|---|---|
| 02-1 | 新建报告后导出 xlsx 含 **2 个 sheet**，第二 sheet 行数 = 明细条数 + 1 | xlsx 解析输出（sheet 名 + `rowCounts`） |
| 02-2 | 明细列含用例编号、结论、失败原因，且与 `test_result` 逐条一致（条数 + 结论分布） | 导出物 vs SQL 对照表 |
| 02-3 | 存量报告（无 `cases`）导出明细 sheet 只有 1 行说明，且 response 200 | 用 report 20 导出 + 解析 |
| 02-4 | 明细含疑似凭据（如 `password=`）时导出被拒，错误码可读 | 构造样本 + 接口响应 |
| 02-5 | 导出后不改动 `run` / `test_result`（冻结语义） | 导出前后 `select count(*)` 相同 |

### FR-v5.22-03 报告级 PDF 中文可读【P0】

**现状**（F-22-10）：报告级 PDF 用 `ReportExportService.pdf()`，字体 `/F1 Helvetica`，
文本经 `ascii()` 把 `c < 32 || c >= 127` 一律替换 `'?'`，中文标题/项目名全变问号，产物 1521B。

**要做什么**

1. 报告级 PDF 改走**同一套 HTML→PDF 渲染**（系统内已有能力：run 级 pdf 十页中文正常，嵌入 NotoSansSC / SimSun）。
2. 输入必须是**快照渲染出的 HTML**（不是 run 表现场装配），保持 §1.3 冻结语义。
3. `ascii()` 与手写 PDF 写入路径**整体删除**，不留「以后可能用」的死代码。
4. 页眉/水印规则不变（internal 模式带红色 INTERNAL 标识，external 带免责声明）。

**验收判据**

| # | 判据 | 证据形态 |
|---|---|---|
| 03-1 | 报告级 PDF 能提取出中文项目名与报告标题（非 `?`）；字段值与 xlsx 同批 | `pdftotext` 输出片段（NFKC 归一化后比对，中文字体常把汉字映射成兼容码位） |
| 03-2 | PDF 字节数 ≥ 100 KB（含嵌入字体），且 `/BaseFont` 不是只有 Helvetica | PDF 头部 `strings` 输出 |
| 03-3 | 同一份报告导出的 PDF 与 xlsx 结论、计数、metadata 完全一致 | 两份产物对照表 |
| 03-4 | 中文、英文、含 emoji 的标题各导出一次都不报错、不乱码 | 三份样本 PDF |
| 03-5 | run 级 PDF 不受影响（仍 10 页 / 中文正常） | 导出同一 run 对照 |

### FR-v5.22-04 导出闸：撤回与不可对外【P0】

**现状**（F-22-12/13/14/15）：`TestReportController.export()` 只做项目权限校验即导出；
`state=REVOKED` 且 `exportable=false` 仍返回 200 + 1521B。

**要做什么**

1. 报告级导出前**先判状态**：`REVOKED` / `ARCHIVED` → 拒绝，返回 **409**（`codeName=REPORT_NOT_EXPORTABLE`），
   body 带 `state` 与一句人话原因。
2. 再判 metadata 闸：`exportable=false` → 拒绝，返回 **400**，body 带 `missing` 数组（复用 `MetadataGate`，与 run 级同一口径）。
3. `mode=internal` 是否豁免：**不豁免状态**（撤回就是撤回），但**豁免 metadata 缺项**（允许内部自查，产物带 INTERNAL 标）。
4. 前端口径：报告详情页的导出按钮在 `REVOKED` 时禁用，`title` 说明原因（复用 FR-v5.20-01 的禁用态规范）。

**验收判据**

| # | 判据 | 证据形态 |
|---|---|---|
| 04-1 | `state=REVOKED` 的报告，四种格式导出全部 409，`codeName=REPORT_NOT_EXPORTABLE` | 接口响应 ×4 |
| 04-2 | `exportable=false` 的 DRAFT 报告，`mode=external` 导出 400 且 `missing` 非空 | 接口响应 |
| 04-3 | `exportable=false` 的 DRAFT 报告，`mode=internal` 导出 200 且文件带 INTERNAL 标识 | 响应 + 产物首屏/页眉 |
| 04-4 | 存量 3 份报告（20/21/22）全部导不出，且错误原因可读 | 三条响应原文 |
| 04-5 | 前端导出按钮在 `REVOKED` 下禁用且 `title` 非空，点击有 toast 说明 | 截图 + DOM 断言 |
| 04-6 | `exportable=true` 且 `state=FROZEN` 的报告正常导出（不误伤） | 接口响应 200 |

### FR-v5.22-05 计数口径：`skipped_tests` 与回填【P1】

**现状**（F-22-18/19/20/21）：`run` 表只有 `total_tests/passed_tests/failed_tests`，**无 `skipped_tests`**；
`passed_tests` 存在未回填（明细 5 PASS → 表 0）；robot run 连 `failed_tests` 也未回填；
`verdict=DISPATCHED`（未落定）会混进分组统计。

**要做什么**

1. 迁移追加 `run.skipped_tests int NULL`（只追加，不改历史脚本）。
2. 结果落库时按明细聚合回填四列：`total = pass+fail+skip`（**不含 DISPATCHED**）、`passed`、`failed`、`skipped`。
3. 提供**幂等**回填脚本：从 `test_result` 聚合重算全库 `run` 四列，可重复执行；执行前后各输出一行核对摘要。
4. 报告/列表读路径统一用这四列；`DISPATCHED` 单列统计为「未完成」，不计入 total。

**验收判据**

| # | 判据 | 证据形态 |
|---|---|---|
| 05-1 | 迁移后 `desc run` 含 `skipped_tests` | SQL 输出 |
| 05-2 | 回填后 `run` 四列与 `test_result` 聚合逐 run 一致（抽 20 个 run，含 pytest 与 robot 各半） | SQL 对照表，差异行数 = 0 |
| 05-3 | 回填脚本连跑两次结果相同（幂等） | 两次摘要输出一致 |
| 05-4 | 新跑的用例（pytest 参数化 61→70）落库后 `passed+failed+skipped = total` | 新 run 的 SQL 读数 |
| 05-5 | `DISPATCHED` 记录不计入 total，且能单独查出条数 | SQL + 接口读数 |

### FR-v5.22-06 FR-06 四件体验收尾【P1】

**现状**（F-22-25）：四条判据 **0/4**。

**要做什么**

1. 新建用例集成功后 `activeSet = created` 并展开成员面板（标题为新集合名）。
2. 用例集空态给 3 个动作：**新建用例集** / **导入用例** / **查看样例**，各自跳对应入口；viewer 角色下「新建」不可见（按 `PermissionResource` 判）。
3. 项目卡 `runCount === 0` 显示 `—`（不是 `0`），并给「发起第一次运行」入口；`null` 仍显示 `—`。
4. 参数化展开处（用例集成员/运行详情）在「总数」旁标「N 用例 → M 测试项」，N=M 时标注为「N 用例 → N 测试项」而非隐藏。

**验收判据**

| # | 判据 | 证据形态 |
|---|---|---|
| 06-1 | 新建用例集后右栏自动展开，标题 = 新集合名，无需再点一次 | 截图 ×1 + DOM 断言 `activeSet.id === created.id` |
| 06-2 | 空态 3 个动作各自可跳到对应页；admin 看到 3 个，**viewer 只看到 1 个**（三个动作里「新建」「导入」都是写动作，只读角色只留只读的「查看样例」） | 截图 ×2 + DOM 计数 + 门禁 64 role-buttons |
| 06-3 | `runCount=0` 的项目卡显示 `—` 且有「发起第一次运行」入口，点击进运行发起页 | 截图 + 路由断言 |
| 06-4 | 参数化用例集显示「61 用例 → 70 测试项」，非参数化显示「N 用例 → N 测试项」 | 截图 ×2 |

### FR-v5.22-07 C5 文档、单测、语言包同源【P0】

**现状**：字段集散落在 6 处——白名单常量、C5 文档、后端 3 个单测（`ReportTemplateKernelTest` 断言 21、
`ReportTemplateFreezeTest` 断言视图键集 == `EXTERNAL_ORDER`、`ReportTemplateServiceTest` 同）、
前端 `ReportTemplatesView.spec.ts`（21 字段分类卡）、`zh-CN.ts` / `en-US.ts` 标签。

**要做什么**

1. 先改 `docs/plan/C5-对外报告字段白名单-20261106.md`（进 5 出 4，附修订记录），再改代码。
2. 同步 3 个后端单测 + 1 个前端单测的字段数与键集断言。
3. 语言包删 4 个字段的标签、加 5 个新字段的标签（中英各一份）。
4. 新增一条**机检**：白名单字段集与 C5 文档表格里的字段集必须逐项相等（防下次再漂移）。

**验收判据**

| # | 判据 | 证据形态 |
|---|---|---|
| 07-1 | C5 文档字段表与 `EXTERNAL_ORDER` 逐项相等（顺序也一致） | 机检脚本输出 PASS |
| 07-2 | 4 个单测全绿（后端 3 + 前端 1），断言值已更新为 22 | 测试输出 |
| 07-3 | 语言包无 `coverageRate` / `caseCovered` / `trend` / `failureDistribution` 残留键 | `grep` 输出为空 |
| 07-4 | 新增机检进 `gates.sh`，故意改白名单不改文档时判红（`--self-test` 真注入） | 门禁日志：注入→红→还原→绿 |

---

## 5. 非功能需求（NFR）

| 编号 | 要求 | 判定 |
|---|---|---|
| NFR-v5.22-01 | 报告级导出（xlsx / pdf）单次耗时 ≤ 5 s（70 条明细样本） | 实测两次取大值，日志留时间戳 |
| NFR-v5.22-02 | 冻结时写明细不使冻结接口耗时翻倍（≤ 原 150%） | 改造前后各测一次 |
| NFR-v5.22-03 | 计数回填脚本在 550 run 全库上 ≤ 60 s，且事务可回滚 | 实测耗时 + 回滚验证 |
| NFR-v5.22-04 | 明细文本脱敏覆盖：`password` / `token` / `secret` / 私钥头 / 连接串 | 构造 5 类样本各一条，全部命中拒导 |
| NFR-v5.22-05 | 公开接口契约不变（`/api/test-reports/{id}/export` 的响应类型仍为二进制流），仅状态码新增 409 | `openapi.json` diff 只增 409 |
| NFR-v5.22-06 | 67 步门禁不得减少，且 `FAILS=0` | 门禁汇总日志 |
| NFR-v5.22-07 | 前端 i18n 中英键集相等（新增键两语都加） | `check_i18n` PASS |
| NFR-v5.22-08 | 权限不放大：导出闸对 `viewer` 仍按原 `PermissionResource` 判定，不因新增状态码放宽 | 角色矩阵探针 |

---

## 6. 与前后版本的关系

| 版本 | 关系 |
|---|---|
| v5.19.0 | 本版**兑现**其降级项 L-19-05（导出内容量）与 L-19-06（计数真源） |
| v5.20.0 | 本版**补做**其未达成项 FR-06 四条判据 |
| v5.21.0 | 本版沿用其角色矩阵与门禁基线（67 步），不回改其判据 |
| v5.23（候选） | 承接 L-22-01（两套入口合并）、L-22-02（重复报告）、L-22-03（trend 真值） |

---

## 7. 验收总表（不可降级）

| 项 | 达成条件 | 未达成后果 |
|---|---|---|
| 对外视图无假值 | 判据 01-3 全过（4 个键不存在） | **本版未完成** |
| 字段可追溯 | 判据 01-2 + 02-1 全过（metadata 五键 + 明细 sheet） | **本版未完成** |
| 导出中文可读 | 判据 03-1 + 03-2 全过 | **本版未完成** |
| 撤回导不出 | 判据 04-1 + 04-4 全过 | **本版未完成** |
| 文档与代码同源 | 判据 07-1 + 07-4 全过 | **本版未完成** |

**可降级**（须在测试报告写明理由与授权人）：

| 项 | 降级条件 |
|---|---|
| 计数回填（FR-05） | 读路径已改对、回填脚本已交付但历史数据未跑 → 报告写明「历史 run 数字未回填」 |
| FR-06 判据 06-4 | 参数化语义未定时，可降为「标注 N → M 两数」不做文案解释 |

---

## 8. 本版不做（明确划界）

| 不做 | 理由 |
|---|---|
| 合并两套报告入口 | D-22-04：破坏性变更，且需先补齐能力 |
| 重刷存量报告快照 | 改写冻结快照 = 篡改审计对象 |
| `trend` 真实历史趋势 | 无数据源，L-22-03 |
| 同一 run 报告唯一约束 | L-22-02，需产品定「是否允许重出一版」 |
| 报告列表加计数列 | L-22-04，随列表改版 |
| 报告视觉改版 / 新模板类型 | 与本版目标无关 |

---

## 9. 待确认（需大师/产品拍板）

| # | 事项 | 默认取向（若不定） |
|---|---|---|
| C-22-01 | 409 与 400 的选择是否影响既有调用方（BFF / 脚本） | 按本文：状态 409、metadata 缺项 400 |
| C-22-02 | `ARCHIVED` 是否与 `REVOKED` 同样禁止导出 | 按本文：同样禁止（归档=只读留档） |
| C-22-03 | 冻结明细的体积上限（70 条样本约多少 KB，上千条要不要截断） | 默认不截断，超 5 MB 拒绝冻结并报错 |

---

## 10. 修订记录

| 日期 | 变更 | 依据 |
|---|---|---|
| 2026-11-17 | 首版：7 条 FR（4 P0 可信 + 1 P1 计数 + 1 P1 体验 + 1 P0 工程），**35 条判据**；8 条 NFR；5 条不可降级 | `00-RESEARCH.md` r2 + `01-ASSESSMENT.md` + 大师 4 条决策（D-22-01~04） |
| 2026-11-17 | **判据 01-1 措辞修正**：原文「视图键数 = 22」被实测推翻——视图键集 = 快照模板字段 ∩ 白名单，存量 report 22 为 17 键（模板冻结在旧 21 字段）。改为「清单 22 项 + 全选模板报告 22 键」，并把裁剪行为写进 01-6 | W72-02 实测（报告 33 = 22 键 / 报告 22 = 17 键），证据 `docs/evidence/v5.22.0/w522_w1_fields.json` |
| 2026-11-17 | **判据 06-2 措辞修正**：原文「viewer 看到 2 个」被门禁推翻——「导入用例」最终也写用例集，属写动作，viewer 进导入页提交不了。改为 viewer 只看到 1 个（只读的「查看样例」），与 v5.21.0 既有判据「viewer 在用例集页写按钮数为 0」同口径 | 门禁 64 role-buttons 首跑判红（`viewer 用例集 写按钮 1 → 导入用例`）；修 `2bd72ff` 后 viewer 归 0、admin/engineer 仍 3 |
