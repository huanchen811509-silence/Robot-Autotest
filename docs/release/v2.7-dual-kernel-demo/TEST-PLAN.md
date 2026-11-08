# v2.7 双内核演示：测试计划（2026-11-08）

**版本** v2.7 ｜ **证据形态**：单测（仓库内 mvn/vitest）+ 真实双跑产物 + 机检脚本。纪律：真实链路为准，不接受「单测绿」当放行。

## §1 TC 列表

| TC | 对应 FR | 判据 | 证据形态 |
|---|---|---|---|
| TC-v2.7-01 | FR-v2.7-01 | pytest run 执行链分流正确：argv 含 venv pytest + json-report + junitxml，不含 pabot | 单测（PytestRunAdapterTest） |
| TC-v2.7-02 | FR-v2.7-01 | RF 路径回归：`PabotCommandBuilderTest` 全绿零改动 | 仓库单测 |
| TC-v2.7-03 | FR-v2.7-01 | 真实执行：demo-pytest 发起 run → finished、kernel='pytest'、计数来自产物 | 真实 HTTP + 库读数（raw/dual-run-pytest.json） |
| TC-v2.7-04 | FR-v2.7-02 | pytest 逐用例 parse：条数=产物 tests 数；12 条状态与产物一致 | 真实 HTTP（raw/parse-pytest.json） |
| TC-v2.7-05 | FR-v2.7-02 | 六态映射单测：PASS/FAIL/SKIP/XFAIL/XPASS/ERROR → 报告域 | 单测（KernelStatusMappingTest） |
| TC-v2.7-06 | FR-v2.7-03 | pytest run 入库 metadata 门禁全绿（十项、≤40），未用补录 | 真实 HTTP（raw/metadata-gate.json） |
| TC-v2.7-07 | FR-v2.7-04 | test_kernel 双源同步：create/update 项目后 P_DEFAULT_KERNEL 同值 | 单测（ProjectServiceKernelTest） |
| TC-v2.7-08 | FR-v2.7-04 | pytest 套件预检放行 .py；TestTimeoutInjector 对 pytest 不注入 | 单测 |
| TC-v2.7-09 | FR-v2.7-05 | 两项目 12 条用例编号/标题逐条一致；环境资产一致 | 种子脚本 --verify + 库读数 |
| TC-v2.7-10 | FR-v2.7-05 / FR-v2.7-06 | RF 侧真实跑：12 条状态 = PASS×5 + 参数化×3 PASS + SKIP×1 + FAIL×1 + 预期失败×2（PASS/FAIL） | 真实跑（raw/dual-run-rf.json） |
| TC-v2.7-11 | FR-v2.7-06 | T4 逐条比对表：共同 10 条状态两内核一致；DEMO-009/010 按映射口径一致 | 比对表（raw/t4-compare.md） |
| TC-v2.7-12 | FR-v2.7-06 | 机检：check_i18n/check_tokens/check_css_vars/check_colors + db-audit PASS | 机检输出 |

## §2 负向用例

| # | 场景 | 预期 |
|---|---|---|
| N1 | robot 内核项目提交 `.py` 套件 run | 请求期**不拦**（预检只判文件存在）；执行期由 pabot 失败暴露——本版不额外拦截，记录为已知行为 |
| N2 | pytest 产物缺失（手工删 pytest-report.json） | 产物校验失败、reason 含 `pytest_artifact_invalid` |
| N3 | test_kernel 写非法值（junit） | 400（v2.6.1 既有判据回归） |

## §3 执行状态（⑦ 回写）

**日期** 2026-11-08 ｜ **状态** 12 条 TC 全部执行：**12 PASS / 0 NOT-RUN**，失败 0；负向 N2/N3 PASS、N1 按修订口径记录

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

## §4 真实链路中修掉的问题（回写）

1. **json-report 1.5.0 的 metadata 落 `environment` 键**而非 `metadata`，且 pytest-metadata 3.1.1 已不支持 ini/--metadata 进报告——`PytestKernel.fromJson` 改为 environment 优先、metadata 兜底；
2. pytest 运行时与 pabot 不同 venv——新增 `execution.pytest-bin`（EXECUTION_PYTEST_BIN），RF 的 venvBin 不动；
3. RF 演示套件三轮迭代：`Evaluate` 只接受表达式（赋值语句 SyntaxError）、`${base * 2}` 对字符串是重复拼接——演示用例即真实工程的回归镜子。

## 修订记录

| 版本 | 日期 | 内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版：12 TC + 3 负向 | 大师三项拍板 |
| v1.1 | 2026-11-08 | ⑦ 回写：§3 真实读数（12 PASS）、§4 真实链路修掉的问题；N1 口径修正（预检不拦 .py，执行期暴露） | 大师三项拍板 |
