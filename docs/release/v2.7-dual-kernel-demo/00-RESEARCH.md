# v2.7 双内核演示：需求调研（2026-11-08）

**撰写** 学徒 ｜ **授权** 大师本轮三项拍板：① 等价套件 ~12 条；② 完整接线（逐用例入库+metadata 门禁）；③ 按 v2.7 版本批次走七道。

## 1. 事实清单（F1–F12 均为 [已核实]：来源 = 仓库源码 / WBS 状态表 / 实库实查，逐条见证据列）

| # | 事实 | 证据 |
|---|---|---|
| F1 | 双内核 SPI 已完成（KRN-01..05 记「已完成」）：`ExecutionKernel` 接口 + `RobotKernel`/`PytestKernel` + `KernelRegistry`/`KernelSelector`/`KernelController`（能力探测 /api/kernels、选择 /select、命令预览 /command） | `WBS-STATUS.md` L179-183；`application/kernel/` 包 |
| F2 | `PytestKernel` 完整：pytest-json-report 主通道 + junitxml 兜底 + xdist 并行（ADR-0007）+ 六态映射（`CaseStatus.fromPytest`：PASS/FAIL/SKIP/XFAIL/XPASS/ERROR）+ metadata 十项门禁归一 | `PytestKernel.java`；`CaseStatus.java` L14-31 |
| F3 | **真实执行链对内核零引用**：`RunExecutionService`→`PabotOrchestrator`→pabot 硬编码 RF；`KernelSelector` 只有 `KernelController`（预览）调用。pytest 项目能建、能导入，**跑不起来** | grep 全 main 源码确认 |
| F4 | 执行链 RF 专属点：`ExecutionRequestPrecheck` 套件预检只认 `.robot`（L85/L179）；`ArtifactVerifier` 硬编码 output.xml；`TestTimeoutInjector` 只注入 .robot；`ReportIngestService` 逐用例解析写死 `OutputXmlParser`（RF StAX） | 各文件行号见调研 |
| F5 | `PabotCommand`（argv+env+outputDir+workDir）与内核 SPI `command()`（仅 argv）存在类型鸿沟；`PabotCommandBuilderTest` 对 RF argv 有逐字等价断言，**RF 路径一行不能动** | `PabotCommandBuilder.java` |
| F6 | 逐用例入库只在人工触发（`POST /api/reports/{runId}/parse`）或节点回传时发生；报告域状态模型为 PASS/FAIL/SKIP/UNKNOWN（`CaseNode`），与内核六态是两套模型 | `ReportIngestService.java` L94-131 |
| F7 | metadata 十项门禁在入库/导出侧（`MetadataGate.check`）；`PabotCommandBuilder` 不传 `--metadata`；pytest 的 `PytestKernel.command` 也无 `--metadata` 透传 | `MetadataGate.java` L22-24 |
| F8 | **演示项目(1) 只有 226 条 TN-IMP-* 用例台账，`robot_file` 表为空**——无任何可执行脚本，本身跑不通；是工作台默认内容来源 | `robot_dev` 实查 2026-11-08 |
| F9 | `project.test_kernel`（V11_16，v2.6.1 加）是纯标注列；真正的内核路由设计走 system_setting `P_DEFAULT_KERNEL`（粒度：项目默认→用例集覆盖→用例覆盖，KRN-04）。两处目前不同步 | `KernelSelector.java` L26 |
| F10 | 环境（env_profile/credential/device）是资产域登记簿 + 租约互斥键，不注入进程环境；`run` 表无 environment_id | `EnvProfileService` 等 |
| F11 | pytest 运行时已就绪：`~/venvs/deepseek-harness` Python 3.12 / pytest 9.2.0 + xdist 3.8.0 + json-report 1.5.0 | 2026-11-07 实测 |
| F12 | B9/A4 要求「M1–M10 双内核矩阵 + T4 抽样比对：真实 pytest 工程与 RF 工程各跑一轮，五类结果逐条一致」——本版本正是取证载体 | `后续中期开发工作评估-20261107.md` L45/L77 |

## 2. 推断与澄清（以下各条均为 [推断]：对齐口径的设计决策，不写成已核实结论）

- 「环境相同」落实为：两演示项目登记**同名同值**的 env_profile / credential / device 资产（平台现状下环境是登记+互斥语义，进程级注入是独立能力，不在本版范围）。
- 「用例相同」落实为：两项目用例**编号、标题、数量一致**（12 条），脚本语义等价。
- 五类结果的对齐口径：RF 原生只有 PASS/FAIL/SKIP（RF 无 xfail 概念）；XFAIL/XPASS 用「预期失败」语义对齐——pytest `xfail` ≡ RF `Run Keyword And Expect Error`（预期失败发生→按非失败计），pytest `xpass` ≡ RF 预期失败未发生（按失败计，`CaseStatus.isFailure()`：XFAIL 非失败 / XPASS 失败，与 RF PASS/FAIL 的通过率口径一致）。ERROR 仅 pytest 侧可产生（RF 测试级无 ERROR 状态），在单测层覆盖映射，不进演示套件。
