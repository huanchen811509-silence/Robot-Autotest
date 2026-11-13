# v5.6.0 测试报告（第⑥道：测试执行）

测试日期：2026-11-13 ~ 2026-11-14（GMT+8）
被测版本：`VERSION` 仍为 **5.0.0**（本版未 bump、未判 G 门、不得对外称 GA）
被测提交：以 `git rev-parse HEAD` 为准（本报告随提交一起入库）
环境：WSL Ubuntu-24.04 单机；后端 `jar` 由 systemd --user 托管（`:8081`），Web 入口 `:8001`；
真机 `gpu01@10.202.54.101`、`gpu02`；用例库在 `/mnt/e/workspace/自动化测试/pytest-cases`。

## 一、结论摘要

| 维度 | 结论 |
|---|---|
| 本版 34 条 TC | 达成 30 条、达成（方式替代）2 条、降级 2 条（取消未上真机、命令占比未重解析） |
| 单测 | 后端 1791 例（跳过 4）、前端 114 文件 / 807 例，全绿；`vue-tsc --noEmit` 无输出 |
| 全量门禁 | 见 §四（`GATES_RESULT`） |
| 真机链路 | `IM-MIX-011` PASS、`IM-RDM-022` 复测 2 批落库、报告树/摘要/三格式导出可用 |
| 是否可对外 | **不可**。十项 metadata 门禁、G 门、发版决定权均在大师；本报告只陈述测试事实 |

两处「方式替代」写在这里而不是藏在表里：TC-03（双屏人工对照）与 TC-06（五页截图）
本版用**同源单测 + 探针**替代人工截图。理由是菜单与空态都由单一函数/单一组件产出，
人工截图能证明的东西比集合断言少；但这确实是偏离原计划的方法，**不按「达成」计满分**。

## 二、TC 逐条结果

### 2.1 IA 信息架构

| 编号 | 结果 | 实测 | 证据 |
|---|---|---|---|
| TC-v5.6-01 | 达成 | `navigation.spec.ts` 51 例：pytest 叶子 path 集合 = 标准集合 ∪ {`/pytest-workbench`} | 前端单测摘要 |
| TC-v5.6-02 | 达成 | `PYTEST_SUITE_NAVIGATION` 在 `frontend/src` 出现 0 次 | `grep` 输出 |
| TC-v5.6-03 | 达成（方式替代） | 两套菜单同由 `navTreeFor()` 产出，差异已被 TC-01 集合断言覆盖；未做人工双屏截图 | 同 TC-01 |
| TC-v5.6-04 | 达成 | 工程 95 真实落入 gpu01/gpu02（含项目级 SSH 凭据 id=8），设备清单前后对比可见 | `docs/evidence/v5.6.0/w4-host-assets/raw/inventory-*.yaml` |
| TC-v5.6-05 | 达成 | 同一 run 三格式导出：pdf 337593 B / docx 4415 B / xlsx 5231 B，均 `FINAL` | `docs/evidence/v5.6.0/w3-report/raw/export-three-formats.json` |
| TC-v5.6-06 | 达成（方式替代） | 五页空态统一走 `EmptyGuide` + `DataTable.emptyAction`，组件单测覆盖；未逐页截图 | 前端单测摘要 |
| TC-v5.6-07 | 达成 | 无设备时首屏给「台架未配置」+「去配资产」跳转 | `PytestReadinessServiceTest` 5 例 |
| TC-v5.6-08 | 达成 | 资产/凭据就位后 `e2e-04` 直接「预检通过，可以发起」 | `effect-metrics/e2e-smoke-20261114.json` |
| TC-v5.6-09 | 达成 | `check_nav_kind` PASS：孤儿叶子 0 | 门禁输出 |
| TC-v5.6-10 | 达成 | 四入口探针全 200（`/plans` 等数据为空，归入空态需求） | `docs/evidence/v5.6.0/probe/w56-00-availability.json` |

### 2.2 EX 执行体验

| 编号 | 结果 | 实测 | 证据 |
|---|---|---|---|
| TC-v5.6-11 | 达成 | 纯界面完成一次 fake 执行并拿到 `EHT-FAKE-…` runId | `effect-metrics/e2e-smoke-20261114.json` |
| TC-v5.6-12 | 达成 | `real` 未带 `confirmReal` → 400 且 `execute` 从未被调用（安全闸前置） | `PytestNicRunControllerTest` 17 例 |
| TC-v5.6-13 | 达成 | `POST /runs/async` 返回 202 + runId，实测响应在 1s 内（判据 < 2s） | 异步探针输出 |
| TC-v5.6-14 | 达成 | 2s 轮询序列 `finished` 单调不减，末次 `finished = total`；`passed+failed ≤ finished` | `PytestRunProgressRegistryTest` 6 例 + 探针 |
| TC-v5.6-15 | **降级** | 取消只在 fake / 单测级验证，未在真机批次内实测 kill（按测试计划 T-02 结论） | 遗留 L-56-03 |
| TC-v5.6-16 | 达成 | `/precheck` 给出台架/配置/设备/安全级四类结论并可跳转；含 LX 时提交禁用并列编号 | `PytestNicRunControllerTest` + `e2e-04` |
| TC-v5.6-17 | 达成 | 单条 fake 端到端 7.4s（界面耗时字段），低于 10s | `effect-metrics/e2e-smoke-20261114.json` |
| TC-v5.6-18 | 达成 | 旧 `POST /runs` 语义未变：本轮真机复测仍走旧入口，200 + 同结构响应 | `w3-report/raw/run-response.json` |

### 2.3 RP 报告质量

| 编号 | 结果 | 实测 | 证据 |
|---|---|---|---|
| TC-v5.6-21 | **降级** | 机制已通（`COMMAND_INDEX` 来源 + 导出前补录），但「59 条重解析后非空 ≥90%」未重跑统计；本轮真机样本是 setup `UsageError`，本身无命令可采 | 遗留 L-56-01 |
| TC-v5.6-22 | 达成 | `topFailures[0].display` 为完整断言原文（含「双端用例需要至少 2 台 Host…」），`clusterKey` 仍为指纹 | `w3-report/raw/report-summary.json` |
| TC-v5.6-23 | 达成 | xlsx 双 sheet，明细列头 5 列齐备（编号/结论/耗时/失败原因/命令） | `export-three-formats.json` + xlsx 结构探测 |
| TC-v5.6-24 | 达成 | 真机 `retry=1` → 2 批执行、两份 junit 并存（sha256 不同）、`retry_count=1`/`flaky=0` 落库并进报告树 | `w3-report/README.md` §一 |
| TC-v5.6-25 | 达成 | 21 条用例 → 章节标题 2 个、明细单表 22 行（表头 1 + 用例 21） | `w3-report/raw/docx-heading-stat.json` |
| TC-v5.6-26 | 达成 | `summary` 返回 `firstSeenAt` / `consecutiveRuns=1` / `reproRate=0.1`；无历史显式 `null` | `w3-report/raw/report-summary.json` |
| TC-v5.6-27 | 达成 | 十项齐全 → `qualityGate.passed=true`、`label=FINAL`、无 DRAFT；缺项降级由单测覆盖 | `export-three-formats.json` + `ExportQualityGateTest` |

### 2.4 AS 资产渲染 / OP-QA 运维与体系

| 编号 | 结果 | 实测 | 证据 |
|---|---|---|---|
| TC-v5.6-31 | 达成 | 渲染产物 `devices.gpus=2`、`cpu=1`、`storage` 非空（从已采集 `spec_json` 派生） | `w4-host-assets/README.md` |
| TC-v5.6-32 | 达成 | `IM-MIX-011` 真机单跑 **PASS**，2.65s，`reason=ok`，不再 `UsageError: 资源池中没有可用的 GPU` | `w4-host-assets/raw/im-mix-011-junit-summary.json` |
| TC-v5.6-33 | 达成 | 迁移 `V11_57` 顺延应用成功（`success=1`），业务表 105 张不变，`db-audit` PASS | 迁移日志 |
| TC-v5.6-41 | 达成 | 重建 jar 不重启 → 判据 2（进程晚于 jar）、4（运行期 commit）判红 | `w5-ops/raw/deploy-verify-不重启判红.log` |
| TC-v5.6-42 | 达成 | 重启后再验 4/4 PASS，退出码 0 | `w5-ops/raw/deploy-verify-四判据通过.log` |
| TC-v5.6-43 | 达成 | 删采样 → 退出码 3 = UNJUDGED，门禁总表计「未判」并计入非绿 | `w5-ops/README.md` §二 |
| TC-v5.6-44 | 达成 | 三份带日期指标：Top8 100%（160 次）/ create P95 44.5ms / fps **未测** | `effect-metrics/*-20261114.json` |
| TC-v5.6-45 | 达成 | `gen_api_contract.py --check` 一致：410 endpoints / 341 paths | 门禁 20 步输出 |
| TC-v5.6-46 | 达成 | E2E 10 条断言 PASS（含「报告页必须出现用例编号」加强断言） | `effect-metrics/e2e-smoke-20261114.json` |

## 三、回归（不得倒退）

| 编号 | 结果 | 实测 |
|---|---|---|
| RG-v5.6-00 标准工程菜单未受影响 | 达成 | `navigation.spec.ts` 对标准工程 28 项 path/顺序/capability 逐项断言，与改动前一致 |
| RG-v5.6-01 旧执行入口语义不变 | 达成 | `POST /runs` + `confirmReal` 仍 200 且响应结构未变（本轮真机复测即走此入口） |
| RG-v5.6-02 报告导出旧格式不退化 | 达成 | pdf/docx/xlsx 三格式仍可导出（大小与门禁阈值内） |

## 四、门禁

全量门禁 `bash scripts/gates.sh`（52 步），末行读数：

```text
GATES_RESULT FAILS=0 UNJUDGED=0
```

| 曾判红的步骤 | 结论 | 处理 |
|---|---|---|
| 29 host_inventory | **证据过期**，不是运行期缺陷 | 旧证据产出于 2026-11-09，而 v4.12.0 才给 10 类命令加 `export LC_ALL=C;` 前缀。按门禁自身设计重采本机清单到 `docs/evidence/v5.6.0/EW56-HI/`（`RESULT PASS fails=0`），**没有改证据去凑源码** |
| 05 backend_test | 本轮新增的 `@Autowired` 字段注入违反 `ArchitectureTest` R6（依赖一律构造器注入） | 改回构造器注入，类里仍只保留一个公开构造器；新增 2 例单测随构造器参数同步 |
| 19 evidence_integrity | 05 的连带：基线机检要求日志里有 `BUILD SUCCESS` | 05 修好后自愈 |
| 36 v50_evidence | 主机清单证据放在 `v5.0.0/` 目录，含内网 IP，撞上「v5.0.0 证据不得含内网 IP」 | 证据移到 `v5.6.0/EW56-HI/`；`gates.sh` 的 `HI_EVID` 改为「取最近的、确有 EW* 证据的版本目录」——5.x 开发期 `VERSION` 一直停在 5.0.0，按 VERSION 拼路径必然对着冻结快照判 |

49–52 步的 `--self-test`（真注入 → 判红 → 还原 → 判绿）按段登记，不与 01–48 段相加成「全绿」。
13 号 CDP 版式回归本轮正常执行（此前若干轮因无浏览器被判红/取消）。

## 五、缺陷与遗留

### 5.1 本轮发现并已修复的缺陷

| 编号 | 现象 | 根因 | 修法与回归 |
|---|---|---|---|
| D-56-01 | `/pytest-workbench` 整页空白，控制台 `SyntaxError: 10` | 文案 `hostname@ip` 的 `@` 被 vue-i18n 当链接消息语法，组件编译失败 | 改写为 `{'@'}`（整串用双引号）；E2E `e2e-03` 守住 |
| D-56-02 | 执行成功但报告页「暂无可统计的用例结果」、十项 metadata 全缺失 | ①pytest 入口跑完不入库；②异步线程无租户上下文（`未认证`）；③进度终态早于入库上报 | 控制器收尾自动入库 + `TenantContext.runAs` 接管身份 + `progress.complete` 后移；E2E 断言由「页面不报错」加强为「必须出现用例编号」 |

### 5.2 遗留（未在本版关闭）

| 编号 | 内容 | 影响 | 去向 |
|---|---|---|---|
| L-56-01 | FR-v5.6-21「59 条重解析后 `command_text` 非空 ≥90%」未重跑统计 | 命令列仍有一批显示「未采集」 | 下一轮真机批次重解析后统计 |
| L-56-02 | FR-v5.6-26「至少 3 条用例可算复现率」需 ≥2 轮真实数据，本轮仅单条 | 复现率算法只有单测与单样本证明 | 多轮回归跑后复测 |
| L-56-03 | TC-v5.6-15 真机批次内取消未实测 | 取消只证明到 fake/单测层 | 需大师按 T-02 授权真机中断 |
| L-56-04 | 「首轮 FAIL → 复测 PASS → FLAKY」无真机样本 | 该分支只有 `RetryMergeTest` 构造序列证明 | 等自然出现抖动用例后补证 |
| L-56-05 | `check_perf_budget` 的 10000 行 fps 仍「未测」 | 长列表性能无基线 | 造数据后测量 |
| L-56-06 | pytest 入口的报告入库是「收尾同步调用」，超大批次会拉长响应 | 59 条以上批次响应时间线性增长 | 后续版本改异步入库队列 |

## 六、口径声明

- 判定只认尾部 `GATES_RESULT`；日志里历史 `FAIL` 行不算本轮结论。
- 「达成（方式替代）」不按满分计：方法偏离计划的，理由写在表内，供大师复核时驳回。
- 真机证据一律落 `docs/evidence/v5.6.0/`，每条含原始响应与命令原文，不复述、不推测。
- 本版未 bump `VERSION`、未判 G 门、未发版；本报告只陈述测试事实，不含对外的定级措辞。
