# v5.8.0 测试计划（TEST-PLAN）：12 FR / 46 TC

> 流程位置：⑤ 测试计划。**无测试计划不许进入开发后期**。
> 上游：`PRD.md`（12 FR + 8 NFR）、`DEV-PLAN.md`（6 波 17 WBS）。
> 规则：每条 TC 必须写清**怎么跑、看什么、证据留在哪**；写不出证据形态的 TC 不算 TC。

---

## 1. 执行方式分类

| 代号 | 含义 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| `UT` | 后端/前端单测 | 逻辑分支正确 | 真实请求能通、界面能看见 |
| `MECH` | 机检脚本（带 `--self-test`） | 规则被守住且能判红 | 业务语义对不对 |
| `HTTP` | 真实服务请求（curl/脚本） | 契约、错误码、真实数据 | 界面渲染 |
| `UI` | 真实浏览器（CDP/人工） | 用户能看见、能点 | 后端边界 |
| `DB` | 直连数据库读数 | 落库事实 | 链路是否被调用 |
| `REAL` | 真机执行（DUT） | 端到端真跑 | 与 DUT 无关的回归 |
| `MANUAL` | 人工走查 | 观感与一致性 | 可重复性（须留截图） |

**放行口径**：P0 条目（FR-01/02/07）必须至少拿到 `HTTP` 或 `REAL` 一级证据；
只有 `UT` 的条目不得计为达成。

---

## 2. 环境与前置

```bash
# WSL Ubuntu-24.04 / ext4 权威副本
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest
# 后端重建 + 重启（改码后必须，否则 :8081 跑的是旧 jar）
bash scripts/build.sh && bash scripts/deploy.sh
# 前端 dev server（UI 类 TC 用）
cd frontend && npx vite --port 5173
```

真机类 TC 额外前置：gpu-node-01（`10.202.54.106`）SSH 可达、`confirmReal=true`、ADR-0013 安全级已判定。

---

## 3. TC 清单

### 3.1 FR-v5.8-01 报告结论贯通（4 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-01 | 详情响应含 `criteriaConclusion` | `HTTP` | 字段存在，取值 ∈ `{PASS,FAIL,UNDETERMINED}` | `raw/report-conclusion.json` |
| TC-v5.8-02 | 页面三态渲染 | `UI` | 有判据显示结论；无判据显示「未判定」+ 原因 | 3 张截图 |
| TC-v5.8-03 | `failCount>0` 但判据 PASS → 结论 PASS | `HTTP`+`UI` | 页面结论 PASS，不显示「失败」 | 截图 + API 响应 |
| TC-v5.8-04 | 反向判据：`9fda073` 上回放 | `UT` | 回放时单测判红（无字段/无结论区） | 回放日志 |

### 3.2 FR-v5.8-02 批量接受选择描述符（5 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-05 | 带 `selection.mode=filter` 批量 | `HTTP` | 200，含 `resolvedCount` | `raw/batch-selection.log` |
| TC-v5.8-06 | 只带 `ids` 的旧请求 | `HTTP`+`UT` | 行为不变，逐条结果与 `undoToken` 语义不变 | 日志 + 单测 |
| TC-v5.8-07 | `ids` 与 `selection` 同时给 | `HTTP` | 400，错误信息点名两字段 | 日志 |
| TC-v5.8-08 | 条件命中 >10000 | `UT`+`HTTP` | 400，错误含上限 | 日志 |
| TC-v5.8-09 | `resolvedCount` 与界面数不一致 | `UT` | 出现「可能已变化，请刷新」提示 | spec + 截图 |

### 3.3 FR-v5.8-03 用例集服务端筛选（4 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-10 | 第 2 页筛只命中第 1 页的关键字 | `UI` | 返回正确结果（修前为空） | 反向截图 + 修后截图 |
| TC-v5.8-11 | 翻页保留筛选 | `UI` | 条件不丢，URL 可复现 | 截图 + URL |
| TC-v5.8-12 | 底部计数 = 服务端 total | `UI`+`HTTP` | 与当前页行数无关 | DOM 断言 |
| TC-v5.8-13 | 筛选变化触发重新请求 | `UT` | 每变化一次请求一次 | spec |

### 3.4 FR-v5.8-04 列表骨架收敛（3 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-14 | 台账每条 C 类都有二选一结论 | `MECH` | 无「待补」字样 | `list-crud-matrix.md` |
| TC-v5.8-15 | 接入页行为一致 | `MANUAL` | 筛选/分页/空态/批量入口一致 | 检查表 + 截图 |
| TC-v5.8-16 | 前端全量单测不减少 | `UT` | 例数 ≥ 上一版 1015 | `npm test` 输出 |

### 3.5 FR-v5.8-05 证据包补接（4 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-17 | 导出件写证据包并可回读 | `UT`+`HTTP` | `read` 回 hash 一致 | 日志 |
| TC-v5.8-18 | 编辑器保存件写证据包 | `UT`+`HTTP` | 同上，重复保存生成新版本 | 日志 |
| TC-v5.8-19 | 大文件不整读进内存 | `MANUAL` | 导出 ≥50MB 时内存不线性增长 | 说明 + 代码路径 |
| TC-v5.8-20 | 反向判据：`9fda073` 上 `read` 抛 not found | `UT` | 回放判红 | 回放日志 |

### 3.6 FR-v5.8-06 长列表 fps（5 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-21 | ≥3 轮采样 | `MECH` | 每轮 fps + 机器信息 | `perf_longlist.json` |
| TC-v5.8-22 | 判定 PASS/FAIL | `MECH` | `min_fps ≥ 50` 才 PASS | 机检输出 |
| TC-v5.8-23 | 数据集 manifest | `MECH` | 行数 + sha256 | `manifest.json` |
| TC-v5.8-24 | `--selfcheck` 仍判红 0 轮冒充 | `MECH` | 判红 | 输出 |
| TC-v5.8-25 | 数据隔离（不污染既有统计） | `DB` | 导入前后项目数/用例数不变 | 前后查询对比 |

### 3.7 FR-v5.8-07 判据真实样本（5 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-26 | 执行前基线 | `DB` | `criteria_json IS NOT NULL` 计数 = 0 | 查询输出 |
| TC-v5.8-27 | real 执行后 ≥1 | `REAL`+`DB` | 计数 ≥1，`verdict` 与声明一致 | 查询输出 |
| TC-v5.8-28 | 页面结论 = 该行 verdict | `UI` | 一致 | 截图 |
| TC-v5.8-29 | 安全闸四守卫 | `HTTP` | 缺 `confirmReal` / 未绑定 / 高危 / 拼错 nodeId 全 400 | 4 条日志 |
| TC-v5.8-30 | 报告软删、判据行保留 | `DB`+`HTTP` | 报告列表不可见，`test_result` 行在 | 查询输出 |

### 3.8 FR-v5.8-08 切批下发（5 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-31 | 500 条 → 3 批 | `HTTP` | 3 次批次请求，界面显示「分 3 批」 | 网络日志 + 截图 |
| TC-v5.8-32 | 每批结果可查 | `DB`+`UI` | 报告按批可查 | 查询 + 截图 |
| TC-v5.8-33 | 10001 条仍拒绝 | `HTTP` | 400 | 日志 |
| TC-v5.8-34 | 取消后剩余批次不发 | `HTTP` | 已跑批次保留 | 日志 + DB |
| TC-v5.8-35 | 边界单测 200/201/10000/10001 | `UT` | 四个边界各一条断言 | spec |

### 3.9 FR-v5.8-09 一次性凭证采集（3 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-36 | 一次性凭证驱动真实采集 | `REAL` | 采集成功 | 日志 |
| TC-v5.8-37 | 采集后材料不可读 | `UT`+`HTTP` | `read` 抛 not found / 401，吊销记录存在 | 日志 |
| TC-v5.8-38 | 六处零泄漏 | `MECH` | `PROBE_RESULT PASS=6/6` | 机检输出 |

### 3.10 FR-v5.8-10 真机批次运维取证（4 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-39 | 命令重解析非空比例 | `REAL`+`DB` | ≥90%（59 条样本）或如实登记 | 统计输出 |
| TC-v5.8-40 | 复现率多轮 | `REAL`+`DB` | ≥2 轮数据，算法结果与人工核对一致 | 统计输出 |
| TC-v5.8-41 | 批次内取消 | `REAL` | 剩余批次不发、无孤儿（无 `exitCode=143`） | 日志 |
| TC-v5.8-42 | FLAKY 判定 | `REAL`+`DB` | 出现自然样本或明确「本批无抖动」 | 查询输出 |

### 3.11 FR-v5.8-11 RF 判据口径（3 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-43 | 10 份样本逐份结论 | `MANUAL` | 有/无载体 + 片段 | spike README |
| TC-v5.8-44 | 实现或标注 | `UT` / `UI` | 二选一落地 | spec 或截图 |
| TC-v5.8-45 | 「未参与判据机检」可见 | `UI` | 界面有可见提示 | 截图 |

### 3.12 FR-v5.8-12 口径裁决（1 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.8-46 | 三条遗留状态不再是「待办」 | `MECH` | 台账状态 ∈ {已裁决-不修}，各有理由 | 遗留表 |

---

## 4. 覆盖矩阵（FR → TC）

| FR | TC 编号 | 条数 |
|---|---|---|
| FR-v5.8-01 | 01 ~ 04 | 4 |
| FR-v5.8-02 | 05 ~ 09 | 5 |
| FR-v5.8-03 | 10 ~ 13 | 4 |
| FR-v5.8-04 | 14 ~ 16 | 3 |
| FR-v5.8-05 | 17 ~ 20 | 4 |
| FR-v5.8-06 | 21 ~ 25 | 5 |
| FR-v5.8-07 | 26 ~ 30 | 5 |
| FR-v5.8-08 | 31 ~ 35 | 5 |
| FR-v5.8-09 | 36 ~ 38 | 3 |
| FR-v5.8-10 | 39 ~ 42 | 4 |
| FR-v5.8-11 | 43 ~ 45 | 3 |
| FR-v5.8-12 | 46 | 1 |

合计 **46 条 TC**，覆盖 12 条 FR，无孤儿 TC。

---

## 5. 不可用推断补齐的条目（执行纪律）

| 情形 | 处理 |
|---|---|
| 真机窗口未到 | TC 标「未执行（待真机窗口）」，**不许**用 fake 结果替代 |
| 判据样本用例无判据声明 | 换一条；若全部无声明，标「无可用样本」并登记 |
| 性能测量环境不一致 | 记录机器与轮次；跨机器结果不可直接比 |
| 界面类无法人工走查 | 用 CDP 截图 + DOM 断言，仍须标「自动取证，非人工确认」 |

---

## 6. 证据目录规范

```
docs/evidence/v5.8.0/
├── report-conclusion-<date>/raw/*.json|log        # FR-01
├── batch-selection-<date>/raw/*.log               # FR-02
├── case-sets-filter-<date>/*.png                  # FR-03
├── list-crud-matrix.md                            # FR-04
├── evidence-bundle-<date>/raw/*.log               # FR-05
├── perf/                                          # FR-06
├── real-criteria-<date>/raw/*.log|sql             # FR-07
├── batching-<date>/raw/*.log                      # FR-08
├── oneshot-collect-<date>/raw/*.log               # FR-09
├── real-batch-<date>/raw/*.log                    # FR-10
└── rf-criteria-spike/                             # FR-11
```

---

## 7. 门禁与收口判据

| 项 | 判据 |
|---|---|
| 单测 | 后端全量 Failures=0 / Errors=0；前端全量例数 ≥ 1015 |
| 类型 | `npx vue-tsc --noEmit` 无输出 |
| 门禁 | `GATES_RESULT FAILS=0 UNJUDGED=0`（56 步） |
| 发布文档 | `check_release_docs.py` 对 v5.8.0 判 PASS（六份齐全 + FR 覆盖 + TC 有结果） |
| 表格 | `check_md_tables.py` 对 v5.8.0 目录全 PASS |
| 契约 | `gen_api_contract.py --check` PASS（因 FR-02 改请求体必须重生成） |
| 遗留 | 17 条中已关闭 ≥12 条，未关闭的逐条写明原因 |

> **开发期预期红点**：本版文档先建、代码后到，因此门禁第 07 步（发布文档机检）在开发期间会因
> 「TC 无结果」判红 —— 这是**预期**，收口时必须靠真实执行结果转绿，**不许**用「未执行」占位或删 TC 躲开。

---

## 8. 执行结果（滚动更新）

> 只记**已经按 TC 方式跑过**的结果；单测类结果直接给命令与读数，真实链路类给证据文件路径。
> 未执行的 TC 一律留空，不预填「预计通过」。

| TC | 方式 | 结果 | 证据 |
|---|---|---|---|
| FR-v5.8-11 的 3 条（spike / 标注 / 界面可见） | MANUAL + UT | **PASS** | `docs/evidence/v5.8.0/rf-criteria-spike/README.md`；`ReportDetailView.spec.ts` 14 例；界面标注 `data-testid="report-conclusion-scope"` |
| TC-v5.8-21（≥3 轮采样 + 每轮 fps + 机器信息） | `MECH` | **PASS**（5 轮 × 3 秒，产品口径 60.1/60.5/60.3/60.4/60.5） | `docs/evidence/v5.8.0/perf/longlist-raw.json`、`perf_longlist.json` |
| TC-v5.8-22（判 PASS/FAIL，`min_fps ≥ 50`） | `MECH` | **PASS**（`min_fps 60.1`；`check_perf_budget --require` `PERF_RESULT PASS`） | `python3 tools/check_perf_budget.py --report docs/evidence/v5.8.0/perf/perf-report.json --require` |
| TC-v5.8-23（数据集 manifest 行数 + sha256） | `MECH` | **PASS**（standard：10000 用例 / `contentHash 59824208438adcd7…`，两次生成一致） | `docs/evidence/v5.8.0/perf/dataset-manifest.json` |
| TC-v5.8-24（`--selfcheck` 判红 0 轮冒充） | `MECH` | **PASS**（4 类反例全判红；期间抓出「2 轮 60fps 判 PASS」漏洞并修） | `python3 tools/perf_longlist.py --selfcheck` |
| TC-v5.8-25（数据隔离：导入前后项目数/用例数不变） | `DB` | **PASS**（既有工程 13→13、既有用例 2491→2491；压测数据全在 `PERF-LONGLIST`） | `docs/evidence/v5.8.0/perf/seed-report.json` |

**W58-11/12 的执行说明**：spike 用仓库内 10 份历史 `output.xml`（pabot 冒烟/故障注入、v1.0 报告链、
v2.0 内核分片与 dryrun、v2.1 样例集），**不是**新跑的执行——判据载体是框架产物属性，与跑哪一版无关。
这一点如实写明，避免被读成「用新执行证明的」。

**W58-09/10 的执行说明**：量测对象是**产品真实页面**（`/cases`，`PERF-LONGLIST` 10000 条数据集，
每页 100 行 = 产品上限），不是造一个页面糊一个数字。同批另测「10000 行同屏」的一次性 HTML（harness 口径，
`minFps 20.4`），它是补充基线、**不参与 `status`**——理由见 PRD FR-v5.8-06 实测结论与 DEV-PLAN D-58-08/09。
浏览器是 Windows 侧的 HeadlessChrome 159（经 localhost 转发），帧率受 vsync 封顶 ~60fps，读数只用于判
「有没有掉到 50 以下」，不用于横向比较机器。

---

## 9. 遗留缺陷（本版新增）

| # | 遗留 | 影响 | 处置 |
|---|---|---|---|
| L-58-05 | RF 无框架级判据载体，本版只做界面标注 | RF 报告的结论恒为 `UNDETERMINED`，用户可能误以为「系统判不了」而不是「RF 不参与」 | 界面已标注；下一版评估「载体协议」（用例侧 `CRITERIA:` 前缀 / 断言侧统一写法 / 执行侧注入），选定后实现提取 |
| L-58-06 | 10000 行同屏渲染只有 20.4~28.5fps（一次性 HTML 口径，低于预算 50） | 当前**不影响用户**：产品每页 100 行，该场景在产品形态下不可达 | 不改产品；登记为「将来取消分页或上虚拟滚动」的前置输入。若真要做，先解决 10000 个 `<tr>` 的渲染（虚拟滚动）再谈 fps |

---

## 10. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-14 | 首版：46 条 TC 覆盖 12 FR；定 7 类执行方式与放行口径 | `PRD.md` / `DEV-PLAN.md` |
| 2026-11-14 | 追加 §8 的 W58-11/12 与 W58-09/10 执行结果（TC-v5.8-21~25），§9 增 L-58-06 | 实测证据 `docs/evidence/v5.8.0/perf/` |
