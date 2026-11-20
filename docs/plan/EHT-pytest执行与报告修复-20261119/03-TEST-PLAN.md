# TEST-PLAN — EHT pytest 执行与报告修复（v5.24.0）

- 日期：2026-11-19
- 状态：**待大师评审；未执行**
- 上游：`01-PRD.md`（FR-EHT-01~09 / 验收总表 D1~D9）、`02-DEV-PLAN.md`、`05-9项框架能力超集对照.md`
- TC 采用 `TC-EHT-xx` 临时前缀，落号时改 `TC-v5.24-xx`。
- 核心原则：**只认真链路**。假 launcher / 手写 JSON / 硬编码 Counts 只能验渲染器，不得作为真实性判据。

---

## 1. 10 用例测试集（迭代靶子）

- 来源：`EHT pytest 用例库工程`（project 95，`pytest-cases`，62 脚本 / 70 测试函数 = 61 IM 业务用例 + 9 框架自测）。
- **合法性约束**：10 个靶子必须全部落在平台已绑定的 **61 条 `case_script_binding`** 内（当前 10 个已核对，全部合规）。
- **执行约束**：只按 nodeid 白名单执行，**禁止裸跑整个仓库**（否则 9 个框架自测混入 junit/报告，污染四态）。

| 序号 | 用例编号 | 脚本 | 类别 | 挑选理由 |
|---|---|---|---|---|
| 1 | IM-FUN-014 | tests/nic/new_scripts/test_im_fun_014.py | new_scripts | 唯一 `CRITERIA_JSON` 哨兵行，判据黄金样本 |
| 2 | IM-FUN-020 | tests/nic/base/test_im_fun_020.py | base | 3 步骤、明细/判定齐全 |
| 3 | IM-NET-002 | tests/nic/ethernet/test_im_net_002.py | ethernet | 光模块 DDM 只读 |
| 4 | IM-NET-007 | tests/nic/ethernet/test_im_net_007.py | ethernet | 链路自协商 |
| 5 | IM-NET-116 | tests/nic/ethernet/test_im_net_116.py | ethernet | PTP 时间戳，字段多 |
| 6 | IM-FUN-037 | tests/nic/new_scripts/test_im_fun_037.py | new_scripts | GUID 唯一性 |
| 7 | IM-FUN-058 | tests/nic/new_scripts/test_im_fun_058.py | new_scripts | PN/SN/PSID/EC 多字段 |
| 8 | IM-NET-088 | tests/nic/new_scripts/test_im_net_088.py | new_scripts | MTU 配置，状态变更步骤 |
| 9 | IM-RDM-020 | tests/nic/rdma/test_im_rdm_020.py | rdma | RDMA 设备枚举 |
| 10 | IM-CER-008 | tests/nic/hardware/test_im_cer_008.py | hardware | 空闲功耗，覆盖硬件类 |

- nodeid 清单：开发阶段用 `pytest --collect-only -q <10文件>` 生成并冻结到证据包。
- **两轮执行**（大师已定 real 纳入本期）：
  - 第 1 轮 fake/Plan-only：验证证据链、计划文件、报告渲染（D1~D8）。
  - 第 2 轮 real：在 DUT 可达窗口，验证真实命令回显、[明细]/[判定]、报告真实（D1~D5 加 real 场景）。
- 若 real 环境不可用，real 轮挂起并记录原因，不得用 fake 冒充 real。

---

## 2. 测试用例

### TC-EHT-01 证据链脱离 json-report、吃 junit 仍工作
- **对应**：FR-EHT-01 / D1 / W-EHT-02
- **前置**：靶子集产物含 junit.xml（`--capture=fd` 已保证 system-out 有 `[明细]`）+ `cases/<node>/raw/*.commands.jsonl`，**无** pytest-report.json。
- **步骤**：跑一次 → 判据提取 + 日志落盘 + 步骤抽取。
- **期望**：
  1. junit 有 `[明细]` 时 `criteria_json` / 步骤非空；捕获为空时判 MISSING 而非静默 0。
  2. 命令证据从 `*.commands.jsonl` 提取，按 `<testcase>` 边界正确归属（不串用例）。
- **证据形态**：真实 junit/commands 样本驱动的单测输出 + `ls` 产物 + SQL 查询。

### TC-EHT-02 步骤证据自动入库
- **对应**：FR-EHT-02 / D2 / W-EHT-03
- **步骤**：`POST /runs` 跑测试集 → **不点**任何手动抽取按钮 → 查 `pytest_case_step`。
- **期望**：该 run 的 `pytest_case_step` 行数 > 0，命令列有值。
- **证据形态**：`SELECT COUNT(*) ... WHERE run_id=?`（改造前=0 / 改造后>0 对比）。

### TC-EHT-03 10/10 用例判据有效
- **对应**：FR-EHT-03 / D3 / W-EHT-04
- **步骤**：跑测试集 → 查 10 条 `test_result.criteria_json`。
- **期望**：10/10 非空且归属正确。
  - 方向 A：判据来自 framework.log 的 `[明细]/[判定]`，按边界切分。
  - 方向 B：判据来自用例 `CRITERIA_JSON` 哨兵行。
- **证据形态**：SQL 逐条输出（case_key + 是否空）+ 归属核对。
- **注**：方向 A 满足时本项无需改用例脚本，W-EHT-04 跳过。

### TC-EHT-04 空执行硬门禁（含 allSkipped 空 merged 漏洞）
- **对应**：FR-EHT-04 / D4 / W-EHT-05
- **步骤 A**：构造 0 用例、exit 0 → 查 run 终态。
  - **期望**：FAILED，failure 原因 "no tests executed"。
- **步骤 B**：构造 junit 存在但 `merged` 为空（`allSkipped` 旧逻辑会误判）→ 查终态与通过率。
  - **期望**：不得 FINISHED；通过率标"不适用/空执行"，**不得**显示 0% 或 100%。
- **步骤 C**：构造 exit 0 但步骤证据全空 → 查终态。
  - **期望**：不得 FINISHED。
- **步骤 D**：`ok_after_retry` 分支注入空跑 → 期望同上。
- **证据形态**：构造脚本 + run 终态 SQL + API 返回 + 通过率字段。

### TC-EHT-05 报告四态与库一致、证据层非空
- **对应**：FR-EHT-05 / D5 / W-EHT-06
- **步骤**：跑测试集 → 生成 EHT 报告 → 解析四态与证据层。
- **期望**：
  1. 报告 passed/failed/missing/undetermined 与 `test_result.criteria_verdict` 分布逐项相等。
  2. 证据层列出 ≥10 条判据原文/实测值，不出现"本次执行没有用例级证据记录"。
- **证据形态**：报告 HTML 快照 + 四态比对脚本输出 + 截图。

### TC-EHT-06 报告装配失败显性化
- **对应**：FR-EHT-05 / D5 / W-EHT-06
- **步骤**：人为让装配抛异常（断 junit / 坏数据）→ 生成报告。
- **期望**：报告顶部标注"报告装配失败（原因）"，**不是**全 0 正常报告。
- **证据形态**：失败场景报告快照 + 触发条件记录。

### TC-EHT-07 真链路机检进门禁且 self-test 有效
- **对应**：FR-EHT-06 / D6 / W-EHT-10
- **步骤**：
  1. `eht_pytest_realchain.sh` → PASS。
  2. `eht_pytest_realchain.sh --self-test` → 先判红（注入断链）再还原判绿。
  3. 全量门禁 → `FAILS=0 UNJUDGED=0`。
- **期望**：断言 step>0 / criteria 非空 100% / log_span 非空 / 四态一致 / 计划已消费；self-test 真红真绿。
- **证据形态**：三次命令完整输出日志。

### TC-EHT-08 假 fixture 单测职责边界（回归保护）
- **对应**：FR-EHT-06 / D6 / W-EHT-10
- **步骤**：检查 `PytestRunAdapterTest` / `EhtPytestReportTest` 在门禁清单的职责标注。
- **期望**：标为"渲染器/归一化单测"，**不得**作为 EHT 报告真实性放行判据。
- **证据形态**：门禁清单 diff + 评审记录。

### TC-EHT-09 证据包完整可复核
- **对应**：FR-EHT-07 / D7 / W-EHT-11
- **步骤**：核对 `docs/evidence/v5.24.0/eht-pytest-fix/`。
- **期望**：含前后 SQL 对比（step 0→>0、判据 481 空→10/10）、run id、机检日志、报告快照、nodeid 冻结清单；D1~D9 逐项可追。
- **证据形态**：证据包目录清单 + `check_md_tables.py` 通过。

### TC-EHT-10 计划文件被消费
- **对应**：FR-EHT-08 / D8 / W-EHT-07
- **步骤**：fake 跑靶子集 → 查平台是否读到 `fake_plan.json` / `manual_replay.md` → 看报告。
- **期望**：
  1. 报告显性呈现 `connection_established=false`、`stdout_synthesized=false`。
  2. 至少 1 条 BranchPlan（name/condition/expected_result/commands）可见。
  3. 计划文件缺失/为空 → 报告标"无执行计划"，不静默成功。
- **证据形态**：fake_plan.json 原文 + 平台入库字段 SQL + 报告快照。

### TC-EHT-11 超集能力补齐 A（资源维度）
- **对应**：FR-EHT-09 / D9 / W-EHT-08
- **步骤**：
  1. 查 `external_resources.switches` 来源（非空或契约声明不含交换机）。
  2. 构造 "inventory 有但 pool 外" 资源 → 平台侧是否显式拒绝。
  3. 用 NIC 型号取规格键 → 是否命中不 KeyError。
- **期望**：三项均有平台侧明确行为，无静默放过。
- **证据形态**：单测输出 + 产物 inventory.yaml 片段 + 拒绝日志。

### TC-EHT-12 超集能力补齐 B（采集与链路口径）
- **对应**：FR-EHT-09 / D9 / W-EHT-09
- **步骤**：
  1. 一次 `ethtool -S` payload 按多 counter 过滤 → 断言不重复下发。
  2. `framework.log` 口径：无"注释声称 tail 但无实现"的矛盾。
  3. 纳管执行路径 → 唯一走 `PytestNicKernelCommand`，旧 `PytestKernel` 不产空 junit。
- **期望**：counter 复用等价实现；framework.log 口径一致；单链路。
- **证据形态**：单测输出 + grep 结果 + 采集命令清单。

### TC-EHT-13 平台侧 9 项能力自检
- **对应**：FR-EHT-09（第 2 层）/ D9 / W-EHT-10
- **步骤**：跑平台侧 9 项能力自检（对应 `05` 号文档 9 项）。
- **期望**：9 项逐条"直接/间接具备"证据成立，无"缺失"项；自检进门禁可判红判绿。
- **证据形态**：自检脚本输出逐项 PASS + 对照表引用。

---

## 3. 执行顺序与门禁

```
批1: TC-EHT-01 → TC-EHT-02 → TC-EHT-03 → TC-EHT-04 → TC-EHT-05/06 → TC-EHT-10
批2: TC-EHT-11 → TC-EHT-12 → TC-EHT-13
收口: TC-EHT-07/08 → TC-EHT-09
```

**放行判据（全部满足才算"用例可正确执行、报告可正确渲染、平台为框架能力超集"）**：
- D1~D9 全达成；
- 全量门禁 `FAILS=0 UNJUDGED=0`；
- self-test 真红真绿有日志；
- real 轮有真实 DUT 证据（或按裁决明确挂起原因）；
- 证据包经大师复核。

**禁止放行的情形**：
- 只有单测绿/构建绿，无真链路证据；
- 报告四态靠硬编码 Counts 或假 metadata 拼出；
- `pytest_case_step` 仍为 0 行却声称"过程可查"；
- 空执行被标 FINISHED / 通过率显示 0% 或 100%。

---

## 4. 待大师裁决（进入执行前必须明确）

| # | 事项 | 选项 | 影响 |
|---|---|---|---|
| 1 | 证据主路径 | A / B / A+B | 决定 W-EHT-04 是否执行 |
| 2 | 空执行落态 | FAILED / "证据缺失"专态 | 决定 W-EHT-05 字段与前端 |
| 3 | 分批 | 批 1 only / 批 1+2 | 决定交付节奏与 D9 是否本期 |

**已定**：real 联调纳入本期；版本号 `v5.24.0`。
