# PRD — EHT pytest 用例库"正确执行 + 过程可查 + 报告真实"

- 日期：2026-11-19
- 状态：**待大师评审**
- 上游：`00-评估报告-为何空执行与假报告.md`
- 编号说明：FR 采用 `FR-EHT-xx` 临时前缀；**正式版本号请大师拍板**，落号后统一替换为 `FR-vX.Y-xx`（机器门禁 `detect_prefix` 只认 `v<数字>`，故入 release 前必须改号）。
- 目标（一句话）：让项目 95 的 pytest 用例**真跑、过程落库、报告真实渲染**，且**空执行判红**，杜绝假绿灯。

---

## 1. 范围与边界

- **范围内**：证据链适配真实产物、步骤自动入库、成功判定加校验、报告四态真实化、10 用例测试集、真链路门禁机检。
- **范围外（本次不做，需在评估评审时裁决是否纳入）**：
  - real 模式真实 DUT 联调（需设备窗口 + 凭据，本轮先做到 fake 模式证据链闭合）。
  - 9P 路径性能优化（repo_path 迁 ext4）——单列后续。
  - 69 个用例的判据载体全量标准化——本次只对 10 个靶子用例做，全量作为后续批次。

---

## 2. 功能需求

### FR-EHT-01 证据链统一到真实执行产物（主路径：junit system-out，已实测）
- **需求**：判据、日志、步骤不再依赖不存在的 `pytest-report.json`。**主证据源 = junit.xml 的 `<system-out>/<system-err>`**（平台已用 `--capture=fd` + `junit_logging=out-err` 保证捕获，实测 `[明细]` 可见）；`cases/<node>/raw/*.commands.jsonl` 作命令证据；`fake_plan.json`/`manual_replay.md` 单独消费（见 FR-EHT-08）；`framework.log` 仅作 junit 捕获失败时的人工兜底，**不拼步骤树**。
- **实测依据（2026-11-19 21:16）**：按平台参数跑 `IM-FUN-020`，junit 实测 `testcase=1 / system-out=2 / system-err=2 / [明细]=6`，用例 SKIPPED（Plan-only）；见 `docs/design/EHT-pytest-证据契约.md` §1。
- **重要前提**：平台设计注释（`PytestEvidenceService.java:36`、`PytestStepExtractor.java:22-24`）反对用 framework.log 拼证据（无用例边界、重试/并行错位）——该判断成立，因此**不采用 framework.log 拼树**；junit `<testcase>` 本身即天然用例边界。
- **验收判据**：
  1. 产物目录**只有 junit.xml、没有 pytest-report.json** 时，判据与步骤提取产出非空结果（fake 下为 `[明细]` Plan-only 原文）。
  2. 能从 `artifacts/<run_id>/cases/<node>/raw/` 提取命令证据，`[明细]/[判定]` 按 `<testcase>` 正确归属（不串用例）。
  3. 证据缺失时报告显示"缺证据(MISSING)"而**不是静默 0/未判定**。
  4. 单元/集成测试用**真实 junit.xml / commands.jsonl 样本**驱动提取，断言提取到 `[明细]`（fake）或 `[判定]`（real）内容。

### FR-EHT-02 步骤证据自动入库（补漏接线）
- **需求**：pytest run 执行完成的回调中自动调用步骤/命令抽取，结果写入 `pytest_case_step`；不需人工点"重新抽取"。
- **验收判据**：
  1. 触发一次靶子测试集 run，结束后 `SELECT COUNT(*) FROM pytest_case_step WHERE run_id=<该run>` **> 0**。
  2. 手动"重新抽取"端点仍保留（幂等覆盖），但不再是证据产生的唯一途径。
  3. 抽取失败只影响证据完整性，不把一次已跑完的 run 判为失败（保持现有容错口径）。

### FR-EHT-03 判据载体标准化（10 靶子用例，降级为"补充路径"）
- **需求**：FR-EHT-01 主路径若已能从 framework.log/raw 拿到完整判据，则本项**非必须**；仅当报告四态需要确定性结构化判据时，才给 10 个靶子用例补 `CRITERIA_JSON` 哨兵行。
- **验收判据**：
  1. 若采用主路径且 10/10 用例 `criteria_json` 已由 framework.log 填充非空 → 本项可跳过（需大师确认）。
  2. 若采用补充路径：10 个用例每一个在 fake/Plan-only 下 stdout 含可识别判据段，跑完 10/10 `criteria_json` 非空。
  3. 用例库脚本改动最小化：只加判据输出，不改测试逻辑与断言。
- **状态**：**待大师裁决方向**（本轮评审问题：A 读现成日志 / B 改用例加哨兵 / A+B）。

### FR-EHT-04 成功判定加"实际执行 + 证据"校验
- **需求**：`success` 不能只看 `exitCode==0`；须叠加"实际执行了用例数 > 0"与"证据非空"校验，空跑/全 SKIP 不得落 FINISHED。
- **验收判据**：
  1. 构造 0 用例空跑（exit 0）→ run 落 **FAILED**，failure 原因明确"no tests executed"。
  2. 构造 exit 0 但步骤证据全空 → 不得判 FINISHED（判 FAILED 或标记"证据缺失"并阻止进入可导出报告，具体态请大师裁决）。
  3. 现有 `ok_after_retry` 强改成功的分支，同样受此校验约束。

### FR-EHT-05 报告四态与证据层真实化
- **需求**：EHT 报告结论层/证据层直接反映真实落库数据；数据缺失要"显性告警"而非静默全 0 空壳。
- **验收判据**：
  1. 跑靶子测试集后生成的报告：`passed/failed/missing/undetermined` 与 `test_result.criteria_verdict` 实测分布**逐项相等**。
  2. 证据层至少列出靶子用例的判据原文与实测值（`criteria_json` 有值），不再出现"本次执行没有用例级证据记录"。
  3. 装配异常时报告顶部显式标注"报告装配失败（原因）"，**不得**伪装成全 0 正常报告。
  4. 三段式结构、脱敏、metadata 门禁等既有约束不回退。

### FR-EHT-06 真链路机检进门禁（替代假绿灯）
- **需求**：新增一道机检，用真实执行产物驱动，禁止用假 launcher/手写 JSON/硬编码 Counts 作为"功能正确"的放行依据。
- **验收判据**：
  1. 机检能真跑靶子测试集（或复用真实 run 产物样本），断言：`pytest_case_step>0`、`criteria_json 非空率=100%`、`log_span 非空`、报告四态与库一致。
  2. `--self-test` 能真注入一处断链（如删除 junit 的 system-out）→ 机检**判红** → 还原后判绿。
  3. 既有的假 fixture 单测（假 launcher / 假 Counts）保留作"渲染器单测"，但**不得**作为 EHT 报告真实性的放行判据；在门禁清单里标注其职责边界。

### FR-EHT-07 可复现的验证证据包
- **需求**：本次修复的每一次判定都产出可复核证据（命令、SQL、run id、报告快照），供大师验收。
- **验收判据**：
  1. 在 `docs/evidence/` 下按版本/主题落盘：执行前后 SQL 对比、run id、真链路机检日志、报告 HTML/截图。
  2. 证据能支撑"从 0 行步骤 → 非空步骤、从 481 空判据 → 10/10 有判据"的量化对比。

### FR-EHT-08 计划文件消费（超集能力 #2，大师指令）
- **需求**：平台当前只写 `fake_plan_file` 路径、从不读取；须读取 `fake_plan.json`/`manual_replay.md`，把 `mode/connection_established/stdout_synthesized/commands[]/alternative_branches[]` 入库并在报告体现每条 BranchPlan 的 name/condition/expected_result/commands。**让 fake 模式"没连设备、没下发命令"的事实进入平台视图**，杜绝空执行被当成成功。
- **验收判据**：
  1. fake 模式跑靶子用例后，平台能读到对应 `fake_plan.json`，`connection_established=false` 在报告中显性呈现。
  2. BranchPlan 的 supported/unsupported 分支（触发条件+预期结果）在报告证据层可见。
  3. 计划文件缺失/为空时，报告标注"无执行计划"而非静默成功。
- **状态**：**待裁决是否纳入本期**（见 `05` 号文档）。

### FR-EHT-09 平台能力超集补齐（大师指令：平台须为用例库框架能力的超集）
- **需求**：对照 `05-9项框架能力超集对照.md`，补齐平台弱于框架的断点：switches 维度（#1）、资源池越权主动校验（#5）、NIC 型号→规格键映射（#6）、counter 复用采集（#8）、framework.log 口径统一（#9）、双命令链路收口。并在门禁里建立"平台侧 9 项能力自检"证明超集成立（当前 scanner 明确跳过这 9 个自测）。
- **验收判据**：
  1. 9 项能力逐项在平台侧有"直接具备"或"间接具备（产出输入/消费产物）"的证据，无"缺失"项。
  2. 平台侧 9 项能力自检进门禁，能真跑并判红判绿。
  3. 不破坏既有 RF / 栈 A 能力。
- **状态**：**待裁决范围与分期**（本期全做 or 先做空执行相关的 1~3，其余分期）。

---

## 3. 非功能需求

- **NFR-01 性能**：fake 模式下靶子测试集（10 用例）端到端执行 + 证据入库 ≤ 3 分钟（9P 路径现状下）。
- **NFR-02 兼容性**：不回退既有 RF 内核、pytest 栈 A（json-report）的现有能力；改动对栈 A 隔离。
- **NFR-03 安全性**：判据/日志落库继续遵守脱敏规则，不得把 DUT 凭据写入 `criteria_json`/报告。
- **NFR-04 可维护性**：证据提取逻辑给出明确契约文档（哪些产物、哪些标记、失败时什么态），写进 `docs/contracts/` 或 design 文档。

---

## 4. 验收总表（Definition of Done）

| # | 判据 | 对应 FR |
|---|---|---|
| D1 | 10 用例真跑，仅 junit.xml 产物也能提取非空判据/日志 | FR-EHT-01 |
| D2 | 跑完 `pytest_case_step` 对应 run 行数 > 0，无需手动抽取 | FR-EHT-02 |
| D3 | 10/10 用例 `criteria_json` 非空 | FR-EHT-03 |
| D4 | 0 用例空跑判 FAILED；证据全空不得 FINISHED | FR-EHT-04 |
| D5 | 报告四态与库逐项相等，证据层非空，装配失败显性告警 | FR-EHT-05 |
| D6 | 真链路机检进门禁，self-test 能真注入→判红→还原 | FR-EHT-06 |
| D7 | 证据包落 `docs/evidence/`，含前后量化对比 | FR-EHT-07 |
| D8 | fake 计划文件被消费，connection_established=false 在报告显性呈现 | FR-EHT-08 |
| D9 | 9 项框架能力逐项在平台有直接/间接具备证据，平台侧 9 项自检进门禁 | FR-EHT-09 |

**D1~D9 全达成（D8/D9 以大师裁决纳入本期为前提），才允许宣布"用例可被正确执行、报告可正确渲染、平台为框架能力超集"。缺一不可。**

---

## 5. 待大师裁决（已在评估 §6 提出，此处对齐）

1. **FR-EHT-03** 动用例库 10 个脚本 —— 是否授权。
2. **FR-EHT-04** "exit 0 但证据全空"落什么态（FAILED 还是"证据缺失"专态）—— 请拍板。
3. **FR-EHT-06** real 模式联调是否本期纳入 —— 建议不纳入，先闭合 fake 证据链。
4. **版本号** —— 请拍板具体号（本报告暂用 `FR-EHT-xx`）。
