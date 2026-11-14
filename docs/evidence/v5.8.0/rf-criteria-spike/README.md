# RF 判据载体 spike（W58-11 / W58-12）

> 目的：回答一个问题——**Robot Framework 的 `output.xml` 里有没有可依赖的「判据载体」**，
> 足以像 pytest 那样把「期望 / 实际」逐条落进 `test_result.criteria_*`。
> 结论先行：**没有框架级载体**，因此本版走「界面标注判据机检范围」这条路，不实现 RF 提取。

## 1. 样本与方法

- 样本：仓库内 10 份真实 `output.xml`（覆盖 pabot 冒烟/故障注入、v1.0 报告链、v2.0 内核分片与
  dryrun、v2.1 样例集），清单见 `samples.txt`；
- 方法：脚本 `tools_spike_scan.py`（本目录留档）逐份解析，统计
  ① `<test>` 内是否有非空 `<doc>`；② 是否出现 RF 断言关键字（`Should Be Equal` 等）；
  ③ 失败用例的 `<msg level="FAIL">` 是否含「期望/实际」可分辨结构；
- 原始读数：`scan.json`（机器生成，未手工改写）。

## 2. 逐份读数

| # | 样本（`output.xml`） | 用例数 | 失败数 | 带 `<doc>` | 断言关键字 | 失败消息可结构化 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `tmp/pabot-smoke/output.xml` | 8 | 0 | 0 | 0 | 0 |
| 2 | `tmp/pabot-fault/output.xml` | 6 | 0 | 0 | 0 | 0 |
| 3 | `v1.0/hrd02b/artifacts/output.xml` | 3 | 1 | 0 | 3 | 1 |
| 4 | `v1.0/rpt05b/artifacts/output.xml` | 3 | 1 | 0 | 3 | 1 |
| 5 | `v1.0/rpt0607b/artifacts/output.xml` | 1 | 0 | 0 | 1 | 0 |
| 6 | `v2.0/a3-rf-kernel/raw/artifacts/shard-1/output.xml` | 8 | 0 | 0 | 0 | 0 |
| 7 | `v2.0/a3-rf-kernel/raw/dryrun-1/artifacts/shard-1/output.xml` | 8 | 0 | 0 | 0 | 0 |
| 8 | `v2.0/a3-rf-kernel/raw/legacy-artifacts/shard-1/output.xml` | 17 | 1 | 17 | 0 | 1 |
| 9 | `v2.0/a8-report-chain/raw/deleted-run-artifacts/RUN-20261107142942-580000/shard-0/output.xml` | 2 | 0 | 0 | 0 | 0 |
| 10 | `v2.1/SMPL-01/output.xml` | 6 | 2 | 6 | 3 | 2 |

**汇总**：10 份全部可解析；带 `<doc>` 的 2 份；出现断言关键字的 3 份；失败消息可结构化的 4 份。

## 3. 关键观察（决定结论的三条事实）

1. **`<doc>` 是作者写的 `[Documentation]`，不是协议**。2 份样本有它，其中
   `legacy-artifacts/shard-1` 的 `<doc>` 是真判据（「1. 能读取目标 CX7 的 PCI Vendor/Device ID…
   2. ens17np0 接口存在、MAC 地址有效…」），而 `v2.1/SMPL-01` 的 `<doc>` 只是说明
   （「最朴素的通过路径…任何环境都应为 PASS」）。**同一字段两种语义**，机器无法区分。
2. **断言关键字参数不稳**。命中的 3 份里，`Should Be Equal` 的参数是 `1 / 1` 这类字面量，
   `v2.1/SMPL-01` 甚至是未替换的占位 `${LEFT} / ${RIGHT}`。作为「期望 vs 实际」的来源，
   它在这些样本上给不出有意义的值。
3. **框架级载体确实不存在**。RF 的 `output.xml` 没有 per-test 的判据块；`<status>` 只给
   PASS/FAIL/SKIP 与时间，`<msg>` 是日志流的投影。这与 pytest 的 junit `<system-out>` 有本质
   差别——后者是「用例自己打印的一块文本」，天然可承载判据原文。

## 4. 结论与决策（W58-12）

| 项 | 结论 |
| --- | --- |
| 是否有框架级判据载体 | **没有** |
| 是否有作者约定级载体 | 有，但不稳定（`<doc>` 2/10 且语义不一；断言参数 3/10 且多为字面量） |
| DEV-PLAN 判据「≥3 份稳定可提取才算有」 | **不满足**：3 份命中的是「出现断言关键字」，不是「能稳定取出判据原文」 |
| 本版选择 | **标注路径**：报告详情页结论区明确写出「判据机检只覆盖 pytest；RF 报告不产出判据结论」 |
| 不做的理由 | 若强行实现，多数 RF 报告会「提取到 0 条」却看起来像已支持——比明确标注更误导 |

**界面标注落点**：`ReportDetailView.vue` 结论卡（`data-testid="report-conclusion-scope"`），
文案键 `reports.conclusion.scopeNote`；spec 断言该行必须同时出现 `pytest` 与 `Robot Framework`。

## 5. 后续可选路径（不是本版承诺）

要让 RF 也有判据，必须先有**载体协议**，再谈提取。可行方向（需产品拍板，本版不实施）：

| 方向 | 做法 | 代价 |
| --- | --- | --- |
| 用例侧约定 | `[Documentation]` 首行写 `CRITERIA: <可判定描述>`，平台只认这个前缀 | 存量用例要批量补；无前缀即「无判据」，不猜 |
| 断言侧约定 | 统一用 `Should Be Equal As ...` 并把期望值写成字面量 | 依赖用例作者纪律，历史报告无法回溯 |
| 执行侧注入 | 框架在每条用例收尾写一条结构化日志（如 `Log    CRITERIA ${expected} / ${actual}`） | 需要改 RF 侧套件模板，收益是 100% 覆盖 |

登记为遗留 **L-58-05**（RF 判据载体协议），归属下一版评估，不在本版实现。
