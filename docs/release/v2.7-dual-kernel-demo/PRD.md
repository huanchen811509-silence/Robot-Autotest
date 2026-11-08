# v2.7 双内核演示：PRD（2026-11-08）

**版本** v2.7 ｜ **授权** 大师三项拍板（等价套件 / 完整接线 / v2.7 七道）｜ **上游** 00-RESEARCH / 01-ASSESSMENT

## 1. 需求背景

平台执行链 RF 中心（pabot），双内核 SPI（KRN-01..05）已建但未接入执行。大师要求：
① 基于 pytest 的演示项目；② 原演示项目改名；③ 两项目用例相同、环境相同、各自独立跑通；④ 测试结果与对外报告尽量一致。

## 2. 需求列表（FR 编号 + 可判定判据）

### FR-v2.7-01 pytest 项目真实执行

**判据**：
1. `test_kernel=pytest` 的项目发起 run，执行链路由到 pytest 命令（venv pytest + `--json-report --junitxml -q`，xdist 按 processes>1 加 `-n`），真实跑完并出产物；
2. run 结束后 `run.kernel='pytest'`、`run_status='finished'`、汇总计数来自 pytest 产物；
3. **RF 路径逐字等价不变**：`PabotCommandBuilder` 既有测试零改动全绿。

### FR-v2.7-02 pytest 逐用例入库（报告一致性）

**判据**：
1. pytest run 触发报告解析（`POST /api/reports/{runId}/parse`）后，逐用例结果来自 `PytestKernel.normalize`（json-report 主通道），条数与产物一致；
2. 六态 → 报告域映射固定：PASS→PASS、FAIL→FAIL、SKIP→SKIP、XFAIL→SKIP、XPASS→FAIL、ERROR→FAIL；映射有单测锁死；
3. 通过率口径与 RF 一致：XFAIL 计非失败、XPASS 计失败（`CaseStatus.isFailure()`）。

### FR-v2.7-03 metadata 十项门禁 pytest 可用

**判据**：
1. run 级动态键（RUN_ID）经 pytest 命令行透传；静态键由演示套件携带（实现走 conftest 注入，
   实测 pytest-metadata 3.1.1 已不支持 ini/--metadata 进 json-report，见 TEST-PLAN §4）；
2. 演示 pytest run 入库时门禁全绿（十项齐全、≤40 字符），**不依赖 run_metadata 补录**；
3. 对外报告（导出/打印）对 pytest run 与 RF run 走同一套门禁与模板。

### FR-v2.7-04 内核选择接入执行链 + 双源同步

**判据**：
1. 执行链内核选择唯一走 `KernelSelector`（项目默认→用例集覆盖→用例覆盖）；
2. 项目创建/更新时 `project.test_kernel` 同步写 `P_DEFAULT_KERNEL`，显示与路由不再脱节；
3. pytest 套件预检放行 `.py`；`TestTimeoutInjector` 对 pytest 跳过（RF 路径不变）。

### FR-v2.7-05 双演示项目与等价套件

**判据**：
1. `demo`(1) 名称改为 **演示项目-RobotFramework**（code 不动，避免破坏引用）；
2. 新建 **演示项目-Pytest**（code `demo-pytest`，`test_kernel=pytest`）；
3. 两项目各 **12 条同编号同标题用例**（DEMO-001..012），脚本语义等价：
   - 共同 10 条结果**逐条一致**：PASS×5、参数化×3（各 3 例 PASS）、SKIP×1、FAIL×1；
   - 预期失败 2 条语义一致：DEMO-009 预期失败且失败发生（pytest=XFAIL≡RF PASS-via-expect-error，均计非失败）、DEMO-010 预期失败未发生（pytest=XPASS≡RF FAIL，均计失败）；
4. 两项目登记**同名同值**环境资产（env_profile / credential / device）。

### FR-v2.7-06 双跑取证（M1–M10 / T4 载体）

**判据**：
1. 两项目各真实跑一轮，原始产物（output.xml / pytest-report.json / junit.xml）归档 `docs/evidence/v3.0.1/v27/`；
2. 五类结果逐条比对表落盘：共同 10 条两内核状态一致；DEMO-009/010 按 §FR-05 判据 3 的映射口径一致；
3. 结果矩阵 M3（pytest×串行）由真实工程实跑支撑，不再是 PARTIAL。

## 3. 验收状态（⑦ 回写）

| 验收项 | 状态 | 证据 |
|---|---|---|
| FR-v2.7-01 pytest 真实执行 | **达成** | 双跑取证：run.finished、kernel=pytest、汇总计数一致（v27/raw/dual-run-*.json） |
| FR-v2.7-02 逐用例入库 | **达成** | parse 后 12+2 条与产物一致；映射单测 6 态全绿 |
| FR-v2.7-03 metadata 门禁 | **达成** | 入库门禁 0 FAIL，未用补录 |
| FR-v2.7-04 选择接入+同步 | **达成** | KernelSelector 进执行链；test_kernel 双源同步单测绿 |
| FR-v2.7-05 双演示项目 | **达成** | 种子脚本幂等；12 条编号/标题两项目一致；环境资产一致 |
| FR-v2.7-06 双跑取证 | **达成** | T4 比对表 + M3 记 PASS（其余矩阵组不在本版范围） |

## 修订记录

| 版本 | 日期 | 内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版：6 条 FR，每条带可判定判据 | 大师三项拍板 |
| v1.1 | 2026-11-08 | ⑦ 回写：§3 六条验收全达成；FR-03 判据 1 措辞按实测修正（静态键走 conftest 注入） | 大师三项拍板 |
