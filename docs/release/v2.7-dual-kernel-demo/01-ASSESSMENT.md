# v2.7 双内核演示：评估报告（2026-11-08）

**撰写** 学徒 ｜ **结论**：**可行，按「完整接线 + 12 条等价套件」实施**。

## 1. 三个决策（大师已拍板）

| 决策 | 选择 | 理由 |
|---|---|---|
| 用例规模 | **新造等价套件 12 条** | 226 条是台账型种子数据（F8），且多为 BMC/BIOS 硬件工程用例，本机无设备跑不了；12 条刻意覆盖五类结果，正好支撑 T4 逐条比对 |
| 接线程度 | **完整接线** | 大师要求「测试结果与对外报告尽量一致」——逐用例入库 + metadata 十项门禁是报告一致性的硬前提 |
| 版本落位 | **v2.7 独立批次** | v2.6 已收口不回灌；本批含真实执行链变更，七道产物独立成套 |

## 2. 改动面评估

**后端接线（6 文件，约 250 行）**：

| 文件 | 改什么 | 风险 |
|---|---|---|
| `RunExecutionService` | 按 `KernelSelector` 分流：RF 走原 `PabotOrchestrator` 路径一行不动；pytest 走新 `PytestRunAdapter`（单命令 + 现有 ProcessLauncher/ProcessRegistry/取消设施，不分片） | 中——类型鸿沟用适配器解决，不动 PabotCommand |
| `PytestKernel` | `command()` 改用 venv pytest 绝对路径 + env 四件套 + `--metadata` 透传 run 级键 | 低 |
| `ExecutionRequestPrecheck` | pytest 项目放行 `.py` 套件存在性预检 | 低 |
| `ArtifactVerifier`/产物校验 | pytest 产物 = `pytest-report.json`（可解析 + tests 节点数），复用内核 `normalize` 试探 | 低 |
| `ReportIngestService` | 按 run.kernel 路由：pytest → `PytestKernel.normalize` → 映射到报告域（PASS→PASS、FAIL→FAIL、SKIP→SKIP、XFAIL→SKIP、XPASS→FAIL、ERROR→FAIL） | 中——映射规则要写进文档与单测 |
| `ProjectService` + `KernelSelector` | 项目建/改时 `testKernel` 同步写 `P_DEFAULT_KERNEL`（执行链唯一真源 = KernelSelector） | 低 |

**数据库（1 迁移）**：`V11_17__run_kernel.sql`：`run` 表加 `kernel VARCHAR(16) NOT NULL DEFAULT 'robot'` + down；`db-audit` 期望表数不变（不加表）。

**演示内容（2 项目 + 24 个脚本等价物）**：种子脚本 `tools/seed_demo_dual_kernel.py`（幂等），造 12 条用例 × 双项目 + RF/pytest 脚本 + 相同环境资产。

**前端**：不动（内核徽章 v2.6.1 已上；运行对话框显示生效内核列为本版可选，不做）。

## 3. 被推翻的假设

| 原假设 | 实测结论 |
|---|---|
| 「pytest 只到导入侧，平台无 pytest 执行能力」 | 内核 SPI 完整存在（F1/F2），缺的只是执行链接线——比从零造内核小一个量级 |
| 「演示项目有 226 条可跑用例」 | 只有台账无脚本（F8），跑通必须新造等价脚本 |

## 4. 不做（明确排除）

- 不做 pytest 用例级 nodeid 绑定执行（`CaseScriptResolver` 仍只解析 .robot；pytest 按**套件级**执行，`--test` 透传暂不支持——套件小，全量跑足够演示）；
- 不做环境变量注入通道（F10，登记一致即视为环境相同）；
- 不动 RF 路径的任何行为（命令逐字等价是 G4 定型约束）；
- 不做 `RunProgressRegistry` 的 pytest 行格式适配（pytest 单命令走简化进度：started→finished 两态）。

## 5. 前置条件

| # | 前置条件 | 状态 |
|---|---|---|
| P1 | pytest 运行时可用（F11：`~/venvs/deepseek-harness`，pytest 9.2.0）| [已核实]，2026-11-07 实测 |
| P2 | RF 执行链回归基线在库（`PabotCommandBuilderTest` 逐字断言）| [已核实]，门禁第 08 步覆盖 |
| P3 | `robot_dev` 库可迁移至 V11_17（`db-migrate.sh` 脚本通道）| [已核实]，迁移实跑通过 |
| P4 | 演示项目种子幂等可重放（登录 API + 分页接口可达）| [已核实]，种子脚本实测 |
| P5 | 大师三项拍板（等价套件 / 完整接线 / v2.7 立项）| 已获得，见 §1 |

**证据分级**：本评估引用的事实分两档——[已核实]（源码行号 / 实库实查 / 真机实测，见 `00-RESEARCH.md` F1–F12）与 [推断]（对齐口径，见调研 §2）；最终判据以 TEST-PLAN 12 条 TC 的真实链路证据为准（`docs/evidence/v3.0.1/v27/raw/`）。

## 修订记录

| 版本 | 日期 | 内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版：三决策 + 改动面 + 排除项 | 大师三项拍板 |
| v1.1 | 2026-11-08 | ⑦ 回写：补前置条件清单与证据分级；§3 两条假设被实测推翻的记录保留 | 大师三项拍板 |
