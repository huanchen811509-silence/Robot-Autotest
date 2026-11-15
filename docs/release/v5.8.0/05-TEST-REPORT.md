# v5.8.0 测试报告（第⑥道：测试执行）

> **补记说明**：本报告成文于 **v5.9.0 收口阶段（2026-11-15）**。v5.8.0 编码与验证发生在
> 2026-11-14，当时结果散落在 `DEV-PLAN.md §12`（W58-01~13 逐条状态与 D-58-01~13 偏差）与
> `TEST-PLAN.md §8`（10 条 TC 的实测读数）里，未独立成文——于是门禁 `07-release_docs`
> 一直判 `FAIL 缺产物: 05-TEST-REPORT.md`。本报告不新造数据：**每条读数都能在 §2 给出的
> 命令或 §1 引用的原文档里复核**；凡是 5.9 收口期复跑的读数，都标明「复跑」并注明代码边界。

## 1. 结论摘要

| 项 | 实测结果 | 判据来源 |
|---|---|---|
| 后端全量单测（5.8 当时） | **1946 例，Failures=0 Errors=0** | `DEV-PLAN.md §12` W58-13 行 |
| 后端全量单测（5.9 收口复跑） | **1966 例，Failures=0 Errors=0**（含 5.9 新增 20 例） | `cd backend && mvn -o -B test` |
| 前端全量单测（5.8 当时） | **130 个文件 / 1030 例全过** | `DEV-PLAN.md §12` W58-08 行 |
| 前端全量单测（5.9 收口复跑） | **132 个文件 / 1051 例全过** | `cd frontend && npx vitest run` |
| 前端类型检查 | **0 error** | `npx vue-tsc --noEmit` |
| API 契约 | 5.8 当时重生成并 `--check` PASS；5.9 收口复跑 **446 endpoints / 365 paths PASS** | `python3 tools/gen_api_contract.py --check` |
| 长列表性能 | `PERF_RESULT PASS`，产品口径 5 轮 `min_fps 60.1`（预算 ≥ 50） | `tools/check_perf_budget.py --require`，证据 `docs/evidence/v5.8.0/perf/` |
| 数据集隔离 | 导入前后既有工程 13→13、既有用例 2491→2491 | `docs/evidence/v5.8.0/perf/seed-report.json` |
| 判据贯通（真机） | `EHT-REAL-1794671083499` 1 行 `criteria_verdict=PASS`，三条判据实测值全 `passed=true` | `docs/evidence/v5.8.0/criteria/raw/row-verdict.json` |
| 判据贯通（反向样本） | 70 行判据全 `MISSING` → 页面显示「未判定」，无「全部通过」 | `docs/evidence/v5.8.0/criteria/raw/ui-measure.json` |
| 安全闸四守卫 | 缺 `confirmReal` / 不存在 nodeId / 近邻拼错 / 高危 `LX` **四条真实请求全 400** | `docs/evidence/v5.8.0/criteria/raw/safety-gate.json` |
| 报告软删 | 删后列表不可见、详情 404，`test_result` 与 `criteria_json` 保留 | `tools/w13_safety_delete.py` |
| 列表 CRUD 台账 | 42 行逐条有结论，缺口 7 → 6；`LIST_CRUD_RESULT PASS=114 FAIL=0` | `docs/evidence/v5.8.0/list-crud-matrix.md` |
| RF 判据载体 spike | 10 份 `output.xml` 逐份核查：**无框架级载体**（`<doc>` 2/10、断言 3/10、结构化失败消息 4/10） | `docs/evidence/v5.8.0/rf-criteria-spike/` |
| **总计** | 46 条 TC：有实测读数 **10 条 PASS**；其余 36 条由单测/契约/机检覆盖（见 §3），其中真机与人工视觉项按 §4 登记未执行 | — |

**判定口径**：本版**不判 G 门、不宣称对外交付**。结论止于「代码、自动化验证、真实链路取证、门禁
在 5.9 收口复跑时全绿」。G 门判定归大师。

## 2. 环境与命令（可复跑）

```bash
# WSL Ubuntu-24.04 / ext4 权威副本 ~/robot-autotest（禁止在 /mnt/* 上构建）
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest

cd backend && mvn -o -B test                 # 1966 例
cd ../frontend && npx vitest run             # 132 文件 / 1051 例
npx vue-tsc --noEmit                         # 0 error
cd .. && python3 tools/gen_api_contract.py --check
python3 tools/check_list_crud.py --self-test   # 注入判红 → 还原判绿
python3 tools/check_perf_budget.py --report docs/evidence/v5.8.0/perf/perf-report.json --require
bash scripts/gates.sh                        # 全量门禁
```

## 3. TC 逐条结果

### 3.1 有实测读数的 10 条（PASS）

| TC | 方式 | 读数 | 证据 |
|---|---|---|---|
| TC-v5.8-21 长列表 ≥3 轮采样 | MECH | 5 轮 × 3 秒，60.1/60.5/60.3/60.4/60.5 fps | `docs/evidence/v5.8.0/perf/longlist-raw.json` |
| TC-v5.8-22 判 PASS/FAIL，`min_fps ≥ 50` | MECH | `min_fps 60.1` → `PERF_RESULT PASS` | `perf-report.json` |
| TC-v5.8-23 数据集 manifest 行数 + sha256 | MECH | 10000 用例 / `contentHash 59824208438adcd7…`，两次生成一致 | `dataset-manifest.json` |
| TC-v5.8-24 自检能判红 0 轮冒充 | MECH | 4 类反例全判红（并抓出「2 轮 60fps 判 PASS」漏洞当场修） | `tools/perf_longlist.py --selfcheck` |
| TC-v5.8-25 压测数据隔离 | DB | 既有工程 13→13、既有用例 2491→2491 | `seed-report.json` |
| TC-v5.8-26 执行前基线 `criteria_json IS NULL` = 0 | DB | 基线 0（当时 715 行 `criteria_verdict` 非空而 `criteria_json` 全 NULL） | PRD FR-v5.8-07 实测结论表 |
| TC-v5.8-27 real 执行后 ≥1 且与声明一致 | REAL+DB | 1 行 `PASS`，`link_speed 400000Mb/s` / `mtu 9000` / `15b3:1021` 全 `passed=true` | `criteria/raw/row-verdict.json` |
| TC-v5.8-28 页面结论 = 该行 verdict | UI | 正向 PASS / 反向 UNDETERMINED 双样本，三方比对 14 项断言全过 | `criteria/raw/ui-measure.json` + 两张 1440 截图 |
| TC-v5.8-29 安全闸四守卫 | HTTP | 四条真实请求全 `400 INVALID_ARGUMENT` | `criteria/raw/safety-gate.json` |
| TC-v5.8-30 报告软删、判据行保留 | DB+HTTP | 列表不可见、详情 404；`test_result` 仍 1 行、`criteria_json` 256B 保留 | `tools/w13_safety_delete.py` |

### 3.2 由单测 / 契约 / 机检覆盖的 36 条

| FR | TC 数 | 覆盖方式 | 读数 |
|---|---|---|---|
| FR-v5.8-01 报告结论贯通 | 4 | `ReportControllerTest` 11 例（新增 3，含空值 → `UNDETERMINED`） | 全绿 |
| FR-v5.8-02 批量接受选择描述符 | 5 | 后端 13 例（用例 6 + 通用批量 7）；契约 `--check` PASS | 全绿 |
| FR-v5.8-03 用例集服务端筛选 | 4 | `TestSetControllerTest` 5 例 + `CaseSetsView.spec` 15 例 | 全绿 |
| FR-v5.8-04 列表骨架收敛 | 3 | `CasesView.spec` 14 例、`useServerSelection.spec` 16 例 | 全绿 |
| FR-v5.8-05 证据包补接 | 4 | `ExportServiceTest` 11 例（新增 2：读回逐字节一致、通道故障不影响导出） | 全绿 |
| FR-v5.8-08 切批下发 | 5 | `EditorSaveServiceTest` 12 例（新增 3，含同一 docId 两次保存 = 2 版本） | 全绿 |
| FR-v5.8-09 一次性凭证采集 | 3 | `ManualCredentialCollectServiceTest` + 一次性凭证探针 | 全绿 |
| FR-v5.8-10 真机批次运维取证 | 4 | `docs/evidence/v5.8.0/criteria/` 9 份原件 | 已有证据 |
| FR-v5.8-11 RF 判据口径 | 3 | spike + 界面标注 + `ReportDetailView.spec` 14 例 | 全绿 |
| FR-v5.8-12 口径裁决 | 1 | 选择依据写在 spike `README.md §4` 与 PRD FR-11 | 已成文 |
| **合计** | **36** | FR-06（5 条）与 FR-07（5 条）的 10 条读数在 §3.1，不在此重复计 | 36 + 10 = 46 |

### 3.3 全部 46 条 TC 结果索引（逐条）

> 结果只有三种取值，`BLOCKED` 必须带原因（机检判据 4 就是这么查的）。
> `PASS`=有可直接复核的读数；`BLOCKED`=需要真实服务/浏览器/真机取证而那一步本轮没做，
> 行为本身可能已由单测覆盖 —— 但「单测绿」不等于「链路验过」，所以不写 PASS。

| TC | 内容 | 方式 | 结果 | 读数 / 未执行原因 |
|---|---|---|---|---|
| TC-v5.8-01 | 详情响应含 `criteriaConclusion` | `HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-02 | 页面三态渲染 | `UI` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-03 | `failCount>0` 但判据 PASS → 结论 PASS | `HTTP`+`UI` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-04 | 反向判据：`9fda073` 上回放 | `UT` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-05 | 带 `selection.mode=filter` 批量 | `HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-06 | 只带 `ids` 的旧请求 | `HTTP`+`UT` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-07 | `ids` 与 `selection` 同时给 | `HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-08 | 条件命中 >10000 | `UT`+`HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-09 | `resolvedCount` 与界面数不一致 | `UT` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-10 | 第 2 页筛只命中第 1 页的关键字 | `UI` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-11 | 翻页保留筛选 | `UI` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-12 | 底部计数 = 服务端 total | `UI`+`HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-13 | 筛选变化触发重新请求 | `UT` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-14 | 台账每条 C 类都有二选一结论 | `MECH` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-15 | 接入页行为一致 | `MANUAL` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-16 | 前端全量单测不减少 | `UT` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-17 | 导出件写证据包并可回读 | `UT`+`HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-18 | 编辑器保存件写证据包 | `UT`+`HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-19 | 大文件不整读进内存 | `MANUAL` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-20 | 反向判据：`9fda073` 上 `read` 抛 not found | `UT` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-21 | ≥3 轮采样 | `MECH` | **PASS** | 实测读数见 §3.1 |
| TC-v5.8-22 | 判定 PASS/FAIL | `MECH` | **PASS** | 实测读数见 §3.1 |
| TC-v5.8-23 | 数据集 manifest | `MECH` | **PASS** | 实测读数见 §3.1 |
| TC-v5.8-24 | `--selfcheck` 仍判红 0 轮冒充 | `MECH` | **PASS** | 实测读数见 §3.1 |
| TC-v5.8-25 | 数据隔离（不污染既有统计） | `DB` | **PASS** | 实测读数见 §3.1 |
| TC-v5.8-26 | 执行前基线 | `DB` | **PASS** | 实测读数见 §3.1 |
| TC-v5.8-27 | real 执行后 ≥1 | `REAL`+`DB` | **PASS** | 实测读数见 §3.1 |
| TC-v5.8-28 | 页面结论 = 该行 verdict | `UI` | **PASS** | 实测读数见 §3.1 |
| TC-v5.8-29 | 安全闸四守卫 | `HTTP` | **PASS** | 实测读数见 §3.1 |
| TC-v5.8-30 | 报告软删、判据行保留 | `DB`+`HTTP` | **PASS** | 实测读数见 §3.1 |
| TC-v5.8-31 | 500 条 → 3 批 | `HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-32 | 每批结果可查 | `DB`+`UI` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-33 | 10001 条仍拒绝 | `HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-34 | 取消后剩余批次不发 | `HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-35 | 边界单测 200/201/10000/10001 | `UT` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-36 | 一次性凭证驱动真实采集 | `REAL` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-37 | 采集后材料不可读 | `UT`+`HTTP` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-38 | 六处零泄漏 | `MECH` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-39 | 命令重解析非空比例 | `REAL`+`DB` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-40 | 复现率多轮 | `REAL`+`DB` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-41 | 批次内取消 | `REAL` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-42 | FLAKY 判定 | `REAL`+`DB` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-43 | 10 份样本逐份结论 | `MANUAL` | **PASS** | 单测 / 机检读数见 §3.2 |
| TC-v5.8-44 | 实现或标注 | `UT` / `UI` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-45 | 「未参与判据机检」可见 | `UI` | **BLOCKED** | 未执行：需真实服务/浏览器/真机取证，见 §4 |
| TC-v5.8-46 | 三条遗留状态不再是「待办」 | `MECH` | **PASS** | 单测 / 机检读数见 §3.2 |

## 4. 未执行清单（不得写成达成）

| 项 | 为什么没跑 | 影响 |
|---|---|---|
| 真机 SSH 批量采集（多设备并发） | 需实验室多台空闲 DUT + 一次性凭证，窗口未协调 | 采集链路的**并发**行为只有单台证据；单台链路已 PASS |
| 真实派发执行（非 EHT real 单例） | 受 ADR-0013 安全闸约束，需 `confirmReal=true` 且绑定 nodeId | 批量派发未实测，仅单例 real 走通 |
| 人工视觉走查（发版前人工复核） | 归大师执行，AI 不代签 | 视觉与文案最终判定未落 |
| W58-01/02/04/05/06/07 的「真实 curl / 截图 / 网络日志 / 回放」待补项 | 见 `DEV-PLAN.md §12` 各行的「（…待补）」标注 | 这些项的行为已由 spec 覆盖，但缺「真实链路取证」这一层 |

## 5. 遗留缺陷（本版新增）

| # | 遗留 | 影响 | 处置 |
|---|---|---|---|
| L-58-05 | RF 无框架级判据载体，本版只做界面标注 | RF 报告结论恒为 `UNDETERMINED`，用户可能误读为「系统判不了」 | 界面已标注；下一版评估载体协议 |
| L-58-06 | 10000 行同屏渲染 20.4~28.5fps（一次性 HTML 口径，低于预算 50） | 产品每页 100 行，该场景在产品形态下不可达 | 不改产品；登记为「取消分页/上虚拟滚动」的前置输入 |
| L-58-07 | ~~FR-v5.8-07 判据④⑤未执行~~ **2026-11-14 已关闭** | 原风险：只验成功路径 | 已补四条真实请求 + 软删前后 13 项断言 |
| L-58-08 | pytest 入口写入的 metadata 只有 `RUN_ID`，`exportable=false`、`metadataMissing` 9 项 | 报告能看、结论正确，但**不能对外交付** | 与 L-55-07 同源；需补齐 `CASE_ID/QA_PROJECT/DUT/LINK/SW_BUILD`，或明确「pytest 报告仅内部可见」 |

## 6. 门禁与回写

| 项 | 状态 |
|---|---|
| v5.8.0 编码完成 | 是（W58-01~13 全部「完成」，待补项逐条标注在 `DEV-PLAN §12`） |
| 本文件（⑤→⑥ 产物） | 2026-11-15 补记 |
| `check_release_docs.py v5.8.0` | 本文件落地后由 `FAIL 缺 05` 转 PASS |
| 测试计划回写 | `TEST-PLAN §8/§9` 已于 2026-11-14 逐条回写 |
| 开发计划回写 | `DEV-PLAN §12` 已含 WBS 状态、D-58-01~13 偏差与读数 |

## 7. 放行与发版结论

**本版不判 G 门、不对外宣称已交付。** 理由：§4 四项未执行里有两项是硬约束（真机批量、真实派发
受 ADR-0013 闸），按流程 §9 未达成项不得写成完成；视觉走查归大师。

## 8. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版（补记）：汇总 `DEV-PLAN §12` 的 WBS 状态与 `TEST-PLAN §8` 的 10 条实测读数；补记 5.9 收口期复跑读数（后端 1966 / 前端 1051 / 契约 446-365） | `DEV-PLAN.md §12`、`TEST-PLAN.md §8/§9`、本轮实测 |
