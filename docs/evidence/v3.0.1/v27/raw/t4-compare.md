# v2.7 T4 抽样比对：五类结果逐条比对（真实双跑）

**日期** 2026-11-08 ｜ **执行** 学徒 ｜ **授权** 大师三项拍板（B9/A4 的 T4 载体）
**双方 run**：RF = `RUN-20261108145647-e80000`（项目 1 演示项目-RobotFramework）
pytest = `RUN-20261108150337-a30000`（项目 80 演示项目-Pytest）
**原始产物**：本目录 JSON（dual-run-*.json / parse-*.json / tree-*.json / t4-compare.json）；
RF 磁盘产物 `~/robot-test/artifacts/RUN-20261108145647-e80000/shard-0/`（output.xml + log.html + report.html）；
pytest 磁盘产物 `.../RUN-20261108150337-a30000/kernel-pytest-0/`（pytest-report.json + junit.xml）。

## 1. 逐条比对表

| 用例 | RF（output.xml） | pytest（json-report） | 一致性口径 | 结论 |
|---|---|---|---|---|
| DEMO-001..005（各 1 例） | PASS ×5 | PASS ×5 | 字面一致 | **一致** |
| DEMO-006 参数化（各 3 例） | PASS ×3（Template 展开） | PASS ×3（@parametrize 展开） | 字面一致（条数一致） | **一致** |
| DEMO-007 条件跳过 | SKIP | SKIP | 字面一致 | **一致** |
| DEMO-008 故意失败 | FAIL | FAIL | 字面一致 | **一致** |
| DEMO-009 预期失败且失败发生 | PASS（Run Keyword And Expect Error） | XFAIL（归一→报告域 SKIP） | **映射一致**：两侧均计非失败 | **一致（映射）** |
| DEMO-010 预期失败未发生 | FAIL | XPASS（归一→报告域 FAIL） | **映射一致**：两侧均计失败 | **一致（映射）** |
| DEMO-011/012 | PASS ×2 | PASS ×2 | 字面一致 | **一致** |

汇总：共同 10 条**字面逐条一致**；预期失败 2 条按 KernelStatusMapper 映射口径一致
（`CaseStatus.isFailure()` 与 RF PASS/FAIL 的通过率口径对齐：XFAIL 非失败 ≡ RF PASS、XPASS 失败 ≡ RF FAIL）。
ERROR 态仅 pytest 侧可产生（RF 测试级无 ERROR），由 `KernelStatusMappingTest` 单测锁死映射，不进演示套件。

## 2. 计数与门禁读数

| 读数 | RF | pytest |
|---|---|---|
| 逐用例条数 | 12 | 14（DEMO-006 展开 3 例） |
| parse 计数 | pass 9 / fail 2 / skip 1 | pass 10 / fail 2 / skip 2 |
| metadata 十项门禁 | 全绿（RUN_ID 经平台补录路径，HRD-02 常规流程） | **全绿且零补录**（conftest 注入 + RUN_ID 命令行透传） |
| exportable | true | true |
| run 记录内核 | run.kernel='robot' | run.kernel='pytest' |

## 3. 真实链路中修掉的问题（对 M1–M10 的价值）

1. json-report 1.5.0 的 metadata 落 **`environment` 键**而非 `metadata`（pytest-metadata 3.1.1 不再支持 ini/--metadata 进报告）——`PytestKernel.fromJson` 已双键兜住；
2. pytest 运行时与 pabot 不同 venv——新增 `execution.pytest-bin` 配置（EXECUTION_PYTEST_BIN），不与 RF venvBin 互相牵连；
3. RF 演示套件三轮迭代修掉 `Evaluate` 赋值语句/字符串乘法两类写法错误——`Run Keyword And Expect Error` 是 RF 侧表达「预期失败」的等价物，已固化进比对口径。

## 4. M1–M10 状态影响

| 组 | 原状态 | 现状态 |
|---|---|---|
| M3 pytest × 单项目 × 串行 | PARTIAL（待现场） | **真实工程实跑补齐**（本文件 + 原始产物） |
| 其余组合（含 RF 侧） | PARTIAL | 不变（本版只覆盖 M3；RF 侧 12 条实跑可作为 M1 的参照产物） |
