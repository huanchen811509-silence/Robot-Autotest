# DEV-PLAN — EHT pytest 执行与报告修复（v5.24.0）

- 日期：2026-11-19
- 状态：**待大师评审；未开工**
- 上游：`01-PRD.md`（FR-EHT-01 ~ 09 / 验收总表 D1 ~ D9）、`04-70个测试函数性质分析.md`、`05-9项框架能力超集对照.md`
- WBS 采用 `W-EHT-xx` 临时前缀，落号 `v5.24.0` 时随 FR 一并改为正式前缀。
- 流程：七道流程已走到第 5 道（本 TEST-PLAN 同批）；**第 6 道执行须大师批准本计划后才开始**。

---

## 0. 开工前约束（所有 WBS 通用）

1. 工作区：WSL `~/robot-autotest` 为唯一编码/构建/测试区；9P（E 盘）禁止构建测试。
2. 环境：`set -a; . ~/.config/robot-autotest/env; set +a`；curl 加 `--noproxy '*'`。
3. 依赖注入：新增/改动类一律构造器注入；一个类只允许一个公开构造器（`ArchitectureTest` R6 禁字段注入）。
4. 异步线程：`TenantContext.runAs(...)` 包裹；`progress.complete` 放数据库写入之后。
5. 每个 WBS 完成即跑相关 `mvn test` 并记录输出，不攒批、不跳步。
6. **不用假数据造绿**：新增/改动的测试若涉及真实链路，必须用真实产物样本（framework.log / commands.jsonl / junit.xml），禁止手写 JSON、假 launcher、硬编码 Counts 冒充真实链路。
7. 不擅自 bump 版本；版本号 `v5.24.0` 由大师已拍板，落号动作仍按流程由大师确认。
8. 执行用例一律用 **61 个已绑定 nodeid 白名单**，禁止裸跑整个仓库（否则 9 个框架自测混入污染报告）。

---

## 1. 批次划分（供分期决策）

| 批次 | 范围 | 目标 | 可否单独交付 |
|---|---|---|---|
| **批 1（必做）** | W-EHT-01/02/03/05/06/07/10a/11 | 修复"空执行/假报告"，让 10 用例真跑出过程记录与真实报告 | 是（对应 D1~D8） |
| **批 2（超集补齐）** | W-EHT-08/09/10b | 平台能力达到用例库框架 9 项能力的超集 | 是（对应 D9） |
| **条件项** | W-EHT-04 | 判据载体标准化，仅当证据方向选 B/A+B 时执行 | 可跳过 |

> 大师若定"本期只做批 1"，批 2 顺延但必须留 FR-EHT-09 台账；若定"全做"，按 WBS 顺序推进。

---

## 2. WBS 分解

### W-EHT-01 证据契约与方案设计（先设计后编码）
- **对应**：FR-EHT-01 / FR-EHT-08 / FR-EHT-09 / NFR-04
- **交付**：`docs/design/EHT-pytest-证据契约.md`，必须写清：
  1. 产物清单与优先级：`cases/<node>/raw/*.commands.jsonl`（有边界，首选）> `framework.log`（按用例区间切分）> `fake_plan.json`/`manual_replay.md` > junit `<system-out>` > stdout 哨兵行。
  2. 用例边界切分算法（nodeid sanitize 规则、区间判定、重试/并行不错位的约束）。
  3. 失败场景 → 态的映射（PASS/FAIL/MISSING/未判定/空执行）。
  4. 计划文件 → 平台字段映射（BranchPlan 的 name/condition/expected_result/commands）。
- **验收**：评审通过；被 W-EHT-02/07 引用。
- **命令**：`python docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/design/EHT-pytest-证据契约.md`

### W-EHT-02 证据提取适配真实产物（去掉 json-report 硬依赖）
- **对应**：FR-EHT-01
- **实测结论**：junit 的 `<system-out>/<system-err>` 已含 `[明细]/[判定]`（见契约 §1），**现有 `PytestStepExtractor.fromJunit` 基本可用**，本 WBS 以"确认 + 补测 + 兜底口径"为主，不重写。
- **改动类**：`PytestCriteriaExtractor`、`PytestLogPersister`、`PytestStepExtractor`、`PytestEvidenceService`、`ReportIngestService`
- **要点**：
  1. 判据/日志/步骤统一走 junit；`pytest-report.json` 降为可选兼容，缺失不再导致全空。
  2. 命令证据从 `*.commands.jsonl` 提取，按 `cases/<node>/` 边界归属。
  3. `framework.log` 降为**兜底**：junit 捕获为空时读取并提示，不拼步骤树；删除"tail framework.log"假表述。
  4. 保留栈 A（json-report）兼容，不回退。
  5. 补"真实 junit 样本"回归测试（含 `[明细]` 去重、`[判定]` 三种写法、failure 行）。
- **验收命令**：
  - `mvn -q -pl backend test -Dtest=PytestCriteriaExtractorTest,PytestLogPersisterTest,PytestStepExtractorTest,PytestEvidenceAttributionTest`
  - 新增以**真实产物样本**驱动的测试（样本入 `backend/src/test/resources/eht/`）
- **判据**：有 junit 时提取非空且归属正确；捕获为空时返回 MISSING 而非静默 0。

### W-EHT-03 步骤抽取接入自动执行回调
- **对应**：FR-EHT-02
- **改动类**：`PytestNicRunController`（完成回调）、抽 `PytestEvidenceAutoService`（复用 `PytestEvidenceService.extract()`）
- **要点**：执行完成自动抽步骤写 `pytest_case_step`；保留手动端点作幂等补抽；抽取失败只影响证据完整性，不把已跑完的 run 判失败。
- **验收命令**：
  - `mvn -q -pl backend test -Dtest=PytestEvidenceAttributionTest,PytestNicRunControllerTest`
  - 集成：`POST /runs` 跑靶子集 → `SELECT COUNT(*) FROM pytest_case_step WHERE run_id=?` > 0

### W-EHT-04 判据载体标准化（10 用例，条件项）
- **对应**：FR-EHT-03
- **前置**：大师裁决证据方向。选 A 且报告四态已满足 → **跳过**。
- **改动位置**：E 盘 `pytest-cases/tests/` 下 10 个靶子脚本（TEST-PLAN §1），只加判据输出行，不改断言。
- **流程约束**：E 盘改 → 同步 WSL（单向，不反向）。
- **验收命令**：
  - `cd ~/robot-autotest && git status --short`（确认只动 10 个脚本）
  - fake 跑 10 用例 → 10/10 `criteria_json` 非空

### W-EHT-05 空执行硬门禁 + 成功判定校验（批 1 核心）
- **对应**：FR-EHT-04
- **改动类**：`PytestNicRunService`（success 计算）、`PytestNicRunController`（终态落库）、`PytestResultNormalizer`（`allSkipped` 空 merged 漏洞）
- **核心缺陷（已核实）**：`PytestResultNormalizer.java:172` `allSkipped = skipped == merged.size() && !merged.isEmpty()` → merged 为空时 `allSkipped=false` → `:89-92 passRateApplicable()=true` → 0 用例空跑仍算通过率；叠加 `PytestNicRunService.java:309-310 success = lastExit==0` → 空执行判成功。
- **要点**：
  1. junit testcase=0 或归一 `cases().size()=0` → 禁止 `success=true`、禁止算通过率，落"空执行"态（落态以大师裁决，默认 FAILED）。
  2. "有用例但步骤证据全空"同样不得 FINISHED。
  3. `ok_after_retry` 强改成功分支受同一约束。
- **验收命令**：
  - `mvn -q -pl backend test -Dtest=PytestResultNormalizerTest,PytestNicRunControllerTest,PytestNicRunServiceTest`
  - 构造空跑 → 断言落 FAILED + failure 原因 "no tests executed"
- **判据**：空 merged / 空证据 / 空重试三种场景均判红。

### W-EHT-06 报告四态与装配失败显性化
- **对应**：FR-EHT-05
- **改动类**：`EhtSectionRepositoryJdbc`、`ReportSnapshotService`、`ReportIngestService`
- **要点**：去掉静默 `EhtSections.empty()`；装配失败带原因标注；四态与库逐项相等；证据层展示真实判据。
- **验收命令**：
  - `mvn -q -pl backend test -Dtest=EhtPytestReportTest`
  - 生成报告 HTML → 解析四态 → 与 SQL 分布比对相等

### W-EHT-07 计划文件消费（批 1）
- **对应**：FR-EHT-08
- **改动类**：新增 `PytestPlanEvidenceService`（或并入 evidence）、`ReportIngestService`、报告渲染
- **要点**：读 `fake_plan.json`/`manual_replay.md`；入库 `mode/connection_established/stdout_synthesized/commands[]/alternative_branches[]`；报告展示 BranchPlan 的 name/condition/expected_result/commands；计划缺失/为空标"无执行计划"。
- **验收命令**：
  - `mvn -q -pl backend test -Dtest=PytestPlanEvidenceServiceTest`
  - fake 跑靶子集 → 报告可见 `connection_established=false` 与至少 1 条 BranchPlan
- **判据**：fake 模式"没连设备"的事实进入平台视图。

### W-EHT-08 超集能力补齐 A：资源维度
- **对应**：FR-EHT-09（能力 #1/#5/#6）
- **改动类**：`PytestEnvRenderer`（switches、资源池）、新增 `PytestNicModelCatalog`（对齐 `PytestGpuModelCatalog`）
- **要点**：
  1. `external_resources.switches` 提供非空来源（或明确契约"本平台不含交换机"并让基准自测适配）。
  2. 资源池越权主动校验：inventory 有但 pool 外的资源显式拒绝/告警，不只靠框架执行期 `PermissionError`。
  3. NIC 型号 → 规格键映射（现仅 GPU 有），避免框架按规格键取规格 KeyError。
- **验收命令**：
  - `mvn -q -pl backend test -Dtest=PytestEnvRendererTest,PytestNicModelCatalogTest`
  - 构造 pool 外资源 → 平台侧显式拒绝

### W-EHT-09 超集能力补齐 B：采集与链路口径
- **对应**：FR-EHT-09（能力 #8/#9/双链路）
- **改动类**：`DutCollectorService`、`PytestNicKernelCommand`、双链路收口
- **要点**：
  1. `DutCollectorService` 增加"一次 `ethtool -S` payload 按多 counter 过滤"的等价能力（现全量单次、只过滤 interrupts）。
  2. `framework.log` 口径统一（与 W-EHT-02 一致，删假表述或真读）。
  3. 双命令链路收口：`PytestKernel` 写死 `--json-report` 在新用例库会 `unrecognized arguments`；明确纳管只走 `PytestNicKernelCommand`，避免旧链路空 junit 被当结果。
- **验收命令**：
  - `mvn -q -pl backend test -Dtest=DutCollectorServiceTest,PytestContractProbeTest`
  - grep 确认纳管执行路径唯一

### W-EHT-10 真链路机检 + 平台侧 9 项能力自检
- **对应**：FR-EHT-06 / FR-EHT-09（第 2 层）
- **交付**：`scripts/guard/eht_pytest_realchain.sh`（真链路机检）+ 平台侧 9 项能力自检 + 接入门禁
- **要点**：
  1. 机检真跑或用真实 run 产物，断言 `pytest_case_step>0`、`criteria_json 非空率=100%`、`log_span 非空`、四态与库一致、计划文件被消费。
  2. 平台侧 9 项能力自检：对 `05` 号文档 9 项逐一验证平台输入产出/产物消费（替代"scanner 跳过 9 个自测"）。
  3. `--self-test` 真注入断链（删 junit system-out / 掏空 commands.jsonl）→ 判红 → 还原判绿。
- **验收命令**：
  - `bash scripts/guard/eht_pytest_realchain.sh` → PASS
  - `bash scripts/guard/eht_pytest_realchain.sh --self-test` → 先红后绿（保留日志）
  - 全量门禁 → `FAILS=0 UNJUDGED=0`

### W-EHT-11 证据包与门禁总验收
- **对应**：FR-EHT-07
- **交付**：`docs/evidence/v5.24.0/eht-pytest-fix/` 下：前后 SQL 对比、run id、机检日志、报告快照、10 用例 nodeid 冻结清单。
- **验收命令**：
  - 全量门禁 → `FAILS=0 UNJUDGED=0`
  - 证据包逐项核对 D1~D9
  - `check_md_tables.py` 通过

---

## 3. 依赖关系

```
W-EHT-01 设计（契约）
   ├─► W-EHT-02 提取 ──► W-EHT-06 报告
   ├─► W-EHT-03 步骤接线
   ├─► W-EHT-05 空门禁（依赖 01 的"空执行"定义）
   ├─► W-EHT-07 计划消费
   ├─► W-EHT-08 资源维度 ┐
   └─► W-EHT-09 采集口径 ┘─► W-EHT-10 机检+自检 ─► W-EHT-11 总验收
W-EHT-04 判据载体（条件，独立）
```

- W-EHT-01 必须最先完成。
- 批 1：01 → {02,03,05,07} → 06 → 10a → 11。
- 批 2：08、09 → 10b → 11。
- W-EHT-10 必须在 02~09 全绿后接入（否则没东西可验）。

---

## 4. 风险与对策

| 风险 | 对策 |
|---|---|
| junit 默认无 system-out | W-EHT-01 定主通道为 commands.jsonl + framework.log；junit 仅辅助 |
| framework.log 无用例边界，切分错位 | 优先用 `cases/<node>/` 目录边界；区间切分只作补充；重试/并行场景必须验证 |
| fake 模式"证据非空但全 SKIP"算不算合格 | 报告如实标 Plan-only/SKIP；空执行与全 SKIP 分开判（W-EHT-05） |
| switches 资产表无实体 | W-EHT-08 定"补实体"或"契约声明不含交换机"，二选一并写进契约 |
| NIC 规格键来源不明 | 以 `config/specs/device_specs.yaml` 为准，对齐 GPU 做法 |
| counter 复用需改采集服务，影响面大 | W-EHT-09 单独 WBS，先加等价方法再切换调用点，保留旧行为测试 |
| 9 项自检真跑耗时/依赖环境 | 自检用 fake 模式 + 真实产物样本双轨；不依赖真实设备 |
| 假 fixture 单测仍被当放行依据 | W-EHT-10 在门禁清单重写职责边界，真链路机检为唯一真实性判据 |

---

## 5. 验收命令总表（复跑入口）

```bash
# 环境
wsl.exe -d Ubuntu-24.04 bash -lc 'set -a; . ~/.config/robot-autotest/env; set +a; cd ~/robot-autotest'

# 批 1 单测
mvn -q -pl backend test -Dtest=PytestCriteriaExtractorTest,PytestLogPersisterTest,PytestStepExtractorTest,PytestEvidenceAttributionTest
mvn -q -pl backend test -Dtest=PytestResultNormalizerTest,PytestNicRunControllerTest,PytestNicRunServiceTest
mvn -q -pl backend test -Dtest=EhtPytestReportTest,PytestPlanEvidenceServiceTest

# 批 2 单测
mvn -q -pl backend test -Dtest=PytestEnvRendererTest,PytestNicModelCatalogTest,DutCollectorServiceTest,PytestContractProbeTest

# 真链路机检 + 自检
bash scripts/guard/eht_pytest_realchain.sh
bash scripts/guard/eht_pytest_realchain.sh --self-test

# 全量门禁（以仓库实际入口为准）
bash scripts/quality-gate.sh

# Markdown 表校验
python docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/EHT-pytest执行与报告修复-20261119/*.md
```

---

## 6. 进入执行前的决策闸（大师裁决）

1. 证据主路径：A / B / A+B（影响 W-EHT-04 是否执行）。
2. 空执行落态：FAILED / 新增"证据缺失"专态（影响 W-EHT-05 落库字段与前端）。
3. 分批：批 1 only / 批 1+2 全做。

> 以上三项，默认按 A + FAILED + 批 1+2 编写；若大师另有指示，按指示调整后开工。

---

## 7. 执行结果回写（2026-11-20）

> 第 7 道流程要求：计划必须回写实际完成情况与偏差原因，不是写完就锁死。

### 7.1 完成情况

| WBS | 计划内容 | 实际 | 偏差说明 |
|---|---|---|---|
| W-EHT-01 | 证据契约设计 | 达成 | 见 `docs/design/EHT-pytest-证据契约.md` |
| W-EHT-02 | 证据提取适配真实产物 | 达成 | 主通道确认为 junit（实测 `--capture=fd` + `junit_logging=out-err` 后 `<system-err>` 有 `[明细]`），原计划"读 framework.log"被实测推翻，已改 |
| W-EHT-03 | 步骤自动接线 | 达成 | `PytestNicRunController` 回调内抽取；step 0→23（fake）/8（real） |
| W-EHT-04 | 判据载体标准化 | **未执行** | 实测证明不需要改用例脚本：junit 通道已带判据，改脚本属多余动作 |
| W-EHT-05 | 空执行硬门禁 | 达成 | `allSkipped` 空集漏洞修正 + `no_tests_executed` |
| W-EHT-06 | 报告四态显性化 | 达成 | 三态装配（OK/NO_DATA/FAILED）+ 空字段显性化；测试 15 项 |
| W-EHT-07 | 计划文件消费 | 达成 | 新增 `PytestPlanEvidenceService`；实测 34 条命令进报告 |
| W-EHT-08 | 资源维度（switches/资源池/NIC 映射） | **部分达成** | fake 设备排除已做并实测通过；switches 实体、资源池主动校验、NIC 型号映射未做 |
| W-EHT-09 | 采集与链路口径 | **部分达成** | 假表述与错误注释已修；counter 复用、双链路改码未做（核实后判定无需改码） |
| W-EHT-10 | 真链路机检 + 自检 | **部分达成** | 机检脚本 6 断言 + `--self-test` 达成；平台侧 9 项自检完整版未做 |
| W-EHT-11 | 证据包与门禁总验收 | **未完成** | 证据包已出（`docs/evidence/v5.24.0/eht-pytest-fix/` 下 01 与 02 两份）；全量门禁未跑 |

### 7.2 偏差原因

1. **W-EHT-04 取消**：原计划担心"只有 1/70 用例有判据载体"，实测发现 `step.check()` 的 `[明细]/[判定]` 经 `logging` 写 stderr、被 junit 捕获，平台只是漏读 `<system-err>`。修平台一行即可，不需要动用例库。
2. **W-EHT-08 只做最痛的一项**：实测发现 real 执行被 fake 设备顶掉（127.0.0.1），这是**阻塞级**问题，优先修；switches/NIC 映射属超集能力，与本轮目标（用例可执行、报告可信）无因果关系。
3. **W-EHT-11 未跑全量门禁**：本轮持续改源码，跑门禁会因工作树变化产生假红（记忆里的既有坑）；应在改动冻结后一次性跑。

### 7.3 后续建议顺序

1. 冻结改动 → 跑全量门禁 → 出 `FAILS=0` 证据（补 W-EHT-11）。
2. 超集能力补齐（switches 实体、资源池主动校验、NIC 型号映射、counter 复用、9 项自检）单独排一期。
3. 报告 PDF 导出回归（与证据链解耦，但要确认新增的 plan 段不影响 PDF 渲染）。
