# v2.7 双内核演示：测试执行报告（2026-11-08）

**执行** 学徒 ｜ **结论** 12 条 TC 全部 PASS，双内核真实链路打通，T4 逐条比对达成。

## §1 结论先行

- **pytest 项目首次在平台真实执行链跑通**：`RUN-20261108150337-a30000`（项目 80 演示项目-Pytest），
  venv pytest + json-report 双产物 + xdist 兼容，run.kernel='pytest'，逐用例 14 条入库；
- **RF 路径逐字等价零回归**：`RUN-20261108145647-e80000` 12 条真实跑，
  `PabotCommandBuilderTest` / `RunExecutionServiceTest` 零改动全绿；
- **T4 比对达成**：共同 10 条字面逐条一致、预期失败 2 条映射一致（见 raw/t4-compare.md）；
- 双方 metadata 十项门禁全绿、exportable=true（pytest 侧零补录）。

## §2 测试环境与证据

| 项 | 值 |
|---|---|
| 数据库 | robot_dev @ V11_17（run.kernel 列） |
| 后端 | 3.0.1 + v2.7 接线（jar 2026-11-08 14:21/15:0x 两轮构建） |
| pytest 运行时 | ~/venvs/deepseek-harness（pytest 9.2.0 + json-report 1.5.0 + xdist 3.8.0），EXECUTION_PYTEST_BIN 指定 |
| 证据目录 | `docs/evidence/v3.0.1/v27/raw/`（dual-run / parse / tree / t4-compare，8 份 JSON + 1 份比对文档） |

## §3 单测读数

| 套件 | 条数 | 结果 |
|---|---|---|
| PytestRunAdapterTest（新增） | 4 | 全绿 |
| KernelStatusMappingTest（新增） | 3 | 全绿 |
| PytestKernelTest（venv 路径断言更新） | 8 | 全绿 |
| KernelSelectorTest | 4 | 全绿 |
| RunExecutionServiceTest（RF 回归） | 10 | 全绿 |
| ReportIngestServiceTest | 5 | 全绿 |
| NodeAgentRunnerTest | 4 | 全绿 |
| ProjectServiceKernelTest / ProjectCardServiceTest / ProjectCardVisibilityTest | 4 / 8 / 3 | 全绿 |

## §3.1 逐 TC 执行结果

**日期** 2026-11-08 ｜ **状态** 12 条 TC 全部执行：**12 PASS / 0 NOT-RUN**，失败 0；负向 N2/N3 PASS、N1 按修订口径记录（详见 TEST-PLAN §2/§3）

| TC | 结果 | 读数 |
|---|---|---|
| TC-v2.7-01 | **PASS** | PytestRunAdapterTest 4 绿：argv[0]=venv pytest、--metadata RUN_ID、env 四件套、outputDir=kernel-pytest-0 |
| TC-v2.7-02 | **PASS** | PabotCommandBuilderTest 零改动全绿（门禁回归确认） |
| TC-v2.7-03 | **PASS** | 真实跑 `RUN-20261108150337-a30000`：state=failed（含 2 条故意失败）、run.kernel='pytest'、14 条计数来自产物 |
| TC-v2.7-04 | **PASS** | parse 14 条与产物一致：pass 10 / fail 2 / skip 2 |
| TC-v2.7-05 | **PASS** | KernelStatusMappingTest 3 绿（六态显式映射、无 UNKNOWN、失败口径对齐 isFailure） |
| TC-v2.7-06 | **PASS** | parse-pytest.json metadataMissing=[]（十项齐全、RUN_ID 命令行透传、**零补录**） |
| TC-v2.7-07 | **PASS** | ProjectServiceKernelTest 4 绿 + 真实链路：demo-pytest 建项后 kernel.default 同步 pytest，执行路由命中 pytest |
| TC-v2.7-08 | **PASS** | 真实链路验证：tests/test_demo.py 通过请求预检（.py 放行）并真实执行；RF 路径回归 RunExecutionServiceTest 10 绿 |
| TC-v2.7-09 | **PASS** | seed --verify PASS：12 条编号/标题两项目一致；demo-dut-local / demo-env 两项目同值 |
| TC-v2.7-10 | **PASS** | RF 真实跑 `RUN-20261108145647-e80000`：12 条 = PASS×9（含参数化 3 + 预期失败 009）/ SKIP×1 / FAIL×2（008、010），与预期逐条一致 |
| TC-v2.7-11 | **PASS** | t4-compare：共同 10 条字面逐条一致；DEMO-009（RF PASS ≡ XFAIL→SKIP）、DEMO-010（RF FAIL ≡ XPASS→FAIL）映射一致；详见 raw/t4-compare.md |
| TC-v2.7-12 | **PASS** | check_i18n / check_tokens / check_css_vars / check_colors + db-audit 全 PASS（V11_17 只加列，表数不变） |

## §4 真实双跑读数（TC-03/04/06/10/11 证据）

| 读数 | RF | pytest |
|---|---|---|
| run 状态 | failed（含 2 条故意失败，平台语义：有用例失败即 failed） | 同左 |
| run.kernel | robot | **pytest** |
| 逐用例 | 12 条 = PASS 9 / SKIP 1 / FAIL 2 | 14 条 = PASS 10 / SKIP 2 / FAIL 2 |
| 门禁 | 十项全绿（RUN_ID 经补录，HRD-02 常规流程） | 十项全绿、**零补录** |
| T4 比对 | 共同 10 条字面一致；009/010 映射一致 | 同左 |

## §5 测试中修掉的问题（真实链路价值）

1. json-report 1.5.0 metadata 落 `environment` 键（pytest-metadata 3.1.1 移除 ini/CLI 进报告）——`PytestKernel.fromJson` 双键兜住；
2. pytest 与 pabot 不同 venv——新增 `EXECUTION_PYTEST_BIN` 配置；
3. RF 演示套件三轮迭代（Evaluate 表达式约束、字符串乘法）——演示用例即真实工程镜子；
4. `FileWorkspaceService` 放行 `.ini/.cfg`（pytest.ini 是 pytest 生态标准配置文件）。

## §6 遗留

| # | 事项 | 状态 |
|---|---|---|
| L-27-01 | M1–M10 其余 8 组（RF 并行压测、pytest×并行等）仍 PARTIAL | 本版只补 M3；沿 B9/A4 排期 |
| L-27-02 | pytest 用例级 nodeid 绑定（--test 过滤） | 未做（套件级足够演示，评估阶段明确排除） |
| L-27-03 | pytest run 状态与「故意失败」的 run_status=failed 展示语义 | 平台既有语义，不在本版范围 |
| L-27-04 | 环境变量注入通道（env_profile → 进程环境） | 未做（评估阶段明确排除，登记一致即环境相同） |
