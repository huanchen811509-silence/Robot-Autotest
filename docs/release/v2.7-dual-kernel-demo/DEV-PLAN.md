# v2.7 双内核演示：开发计划（2026-11-08）

**版本** v2.7 ｜ **上游** 01-ASSESSMENT（改动面）+ PRD（判据）｜ 纪律：RF 路径逐字等价，一行不动。

## §1 工作分解（WBS）

| # | 包 | 内容 | 主要文件 | 验收命令 | 状态 |
|---|---|---|---|---|---|
| 1 | **V27-01** | 执行链内核分流：`KernelSelector` 注入执行链；pytest 走新 `PytestRunAdapter`（单命令 + ProcessLauncher + 现有取消设施），RF 走原 `PabotOrchestrator` 路径零改动；`ExecutionRequestPrecheck` 按内核放行 `.py`；`TestTimeoutInjector` pytest 跳过 | `RunExecutionService.java`、新增 `application/kernel/PytestRunAdapter.java`、`ExecutionRequestPrecheck.java`、`TestTimeoutInjector.java` | `mvn -Dtest=RunExecutionServiceTest test` | 完成 |
| 2 | **V27-02** | pytest 命令补全：venv 绝对路径、env 四件套、`--metadata` 透传 run 级键；`V11_17__run_kernel.sql` run 表加 `kernel` 列 + down；执行落库时记 kernel | `PytestKernel.java`、`db/migration/`、`RunRepositoryJdbc.java` | `mvn -Dtest=PytestKernelTest test` + `bash scripts/db-audit.sh` | 完成 |
| 3 | **V27-03** | 逐用例入库路由：`ReportIngestService` 按 run.kernel 分流到 `PytestKernel.normalize`，六态映射到报告域（XFAIL→SKIP、XPASS/ERROR→FAIL），映射单测锁死 | `ReportIngestService.java`、新增映射器 | `mvn -Dtest=ReportIngestServiceTest,KernelStatusMappingTest test` | 完成 |
| 4 | **V27-04** | 内核选择双源同步：`ProjectService.create/update` 把 `testKernel` 同步写 `P_DEFAULT_KERNEL`；显示列与路由真源合一 | `ProjectService.java` | `mvn -Dtest=ProjectServiceKernelTest test` | 完成 |
| 5 | **V27-05** | 双演示项目种子：`tools/seed_demo_dual_kernel.py`（幂等）——demo 改名、建 demo-pytest、12 条等价用例 + RF/pytest 脚本 + 环境资产 | `tools/seed_demo_dual_kernel.py` | `python3 tools/seed_demo_dual_kernel.py --verify` | 完成 |
| 6 | **V27-06** | 双跑取证 + 五类逐条比对 + 文档回写 | `docs/evidence/v3.0.1/v27/` | 双跑 JSON + T4 比对表 | 完成 |

## §2 关键设计

1. **分流点**：`RunExecutionService.execute` 在物化工作区后按 `KernelSelector.select(projectId,...).kernel()` 分流。pytest 分支复用现有骨架（目录/预检/租约/RUNNING/终态/释放），执行段换 `PytestRunAdapter`；进度走简化两态（started→finished），不碰 `RunProgressRegistry` 的 pabot 行格式。
2. **类型鸿沟**：`PytestRunAdapter` 返回复用 `PabotCommand` 类型（argv/env/workDir/outputDir 语义相同），不改 `PabotOrchestrator`。
3. **metadata**：run 级键（QA_PROJECT/DUT/LINK/SW_BUILD/BASELINE/OPERATOR/STARTED_AT/CASE_KIND）由执行上下文拼 `--metadata`；`CASE_ID` 在套件级写死演示值口径与 RF 侧一致；`RUN_ID` 由 `PytestKernel.withRunId` 归一补齐。
4. **六态映射**（V27-03）：映射表写进 `KernelStatusMapping`（新类），报告域 `CaseNode.status` 收 PASS/FAIL/SKIP/UNKNOWN；对 pytest run 的 UNKNOWN 恒不出现（ERROR/XPASS 显式映射 FAIL）。

## §3 实际完成与偏差

- ①②③④⑤ 按计划完成，无范围偏差；`PytestRunAdapter` 落在 `application/kernel`（原计划 `application/execution`，因依赖 KernelRegistry/EnvironmentProbe 放同包更内聚）。
- §2.3 的 CASE_ID 口径实测调整：pytest-metadata 3.1.1 不再把 ini/--metadata 写进 json-report（其 metadata 落 `environment` 键），静态键改由演示套件 **conftest.py 直写 `config._metadata`**，`PytestKernel.fromJson` 对 environment/metadata 双键兜底；RUN_ID 仍由命令行 `--metadata` 透传 + 归一兜底。
- 新增 `execution.pytest-bin`（EXECUTION_PYTEST_BIN）配置：pytest 运行时与 pabot 不同 venv，不共用 venvBin。
- `FileWorkspaceService` 放行 `.ini/.cfg` 后缀（pytest.ini 是 pytest 生态标准配置）。
- §2.4 双源同步实现：`ProjectService.create/update` 里 `testKernel` 经 `SystemSettingService.putProject` 同步写 `kernel.default`（带审计）。
- 前端零改动成立；`db-audit` 期望表数不变成立（V11_17 只加列）。
- V27-05 种子脚本实测三轮迭代（RF Evaluate 表达式约束、字符串乘法、pytest.ini 格式）；V27-06 双跑取证完成，T4 比对见 `docs/evidence/v3.0.1/v27/raw/t4-compare.md`。
