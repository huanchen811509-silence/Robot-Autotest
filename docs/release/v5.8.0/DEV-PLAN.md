# v5.8.0 开发计划（DEV-PLAN）：遗留清算与结论/选择/判据三条断链收口

> 流程位置：④ 开发计划。**无开发计划不许开工编码**。
> 上游：`PRD.md`（12 FR + 8 NFR）、`01-ASSESSMENT.md`（大师拍板 L 全档）。
> 本文件给 WBS 编号与**可复制的验收命令**；不给「大约几天」，只给**依赖关系与可独立验收的边界**。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | 结论与选择（断链修复） | W58-01 ~ W58-05 | 是 | 仅依赖既有代码；W2/W3 可并行 |
| W2 | 一致性与证据 | W58-06 ~ W58-08 | 是 | 与 W1 并行（改动面不重叠） |
| W3 | 性能基线 | W58-09 ~ W58-10 | 是 | 依赖 W1 收口（要稳定的报告列表） |
| W4 | 判据链（RF spike） | W58-11 ~ W58-12 | 是 | 与 W1~W3 并行 |
| W5 | 真机能力 | W58-13 ~ W58-16 | 是（逐项） | **串行**：13 → 15；14 独立；依赖 W1（要报告结论验收 13） |
| W6 | 收口 | W58-17 | 否（最终波） | 依赖 W1 ~ W5 全部出口判据 |

**并行纪律**：W1 / W2 / W4 三条线改动面完全分离，可同时推进；
W3 必须在 W1 之后（性能基线要在「结论可见」的稳定列表上量）；
W5 是唯一需要真机窗口的波次，必须单独排时间，且 **W58-13 成功前不许开 W58-15**（凭证采集依赖 real 通路）；
W6 最后，且开始前 W1 ~ W5 出口判据全部满足。

**与总台账的关系**：`docs/plan/WBS-STATUS.md` 保持 130 包不动（与 v5.7.0 同口径：版本内 WBS 只记在本文件）。
本版**预期零数据库迁移**；若实施中发现必须加列，从 `V11_65` 起追加并配 `.down.sql`。

---

## 2. W1 结论与选择（W58-01 ~ W58-05）

### W58-01 报告详情返回结论字段

| 项 | 内容 |
|---|---|
| 目标 | `GET /api/reports/{runId}` 响应含 `criteriaConclusion`，取值 `PASS/FAIL/UNDETERMINED` |
| 改动面 | `api/report/ReportController.ReportDetailView`（加字段）+ `from()` 用 `record.effectiveConclusion()` + 单测 |
| 出口判据 | ① 单测断言序列化含该字段且空值时为 `UNDETERMINED`；② 真实 `curl` 返回该字段；③ 修前快照上回放判红 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ReportControllerTest test` |
| 依赖 | — |

### W58-02 报告详情页结论区

| 项 | 内容 |
|---|---|
| 目标 | 详情页顶部有「结论」区；`UNDETERMINED` 显示「未判定」+ 原因；有判据时显示判据汇总入口 |
| 改动面 | `frontend/src/api/reports.ts`（类型）+ `views/ReportDetailView.vue` + spec |
| 出口判据 | ① spec 断言三种取值的渲染；② 真实界面三种取值各一张截图；③ 不得回退成按 `failCount` 推断 |
| 验证命令 | `cd frontend && npx vitest run src/views/ReportDetailView.spec.ts` |
| 依赖 | W58-01 |

### W58-03 批量端点接受选择描述符

| 项 | 内容 |
|---|---|
| 目标 | `BatchRequest` / `TestCaseService.BatchRequest` 增可选 `selection`；与 `ids` 互斥；解析复用 `SelectionResolver`；回显 `resolvedCount` |
| 改动面 | 2 个 record + 2 个服务方法 + 解析适配 + 单测（互斥 400 / 超限 400 / 条件命中 200 / 旧请求兼容） |
| 出口判据 | ① 四类单测全绿；② 真实 HTTP 四类请求各一条；③ 契约快照重生成且 `--check` PASS |
| 验证命令 | `cd backend && mvn -o -B -Dtest=BatchControllerTest+GenericBatchServiceTest+TestCaseServiceTest test`；`python3 tools/gen_api_contract.py --check` |
| 依赖 | — |

### W58-04 前端全选接入选择描述符

| 项 | 内容 |
|---|---|
| 目标 | 用例集页与用例清单页的「全选所有命中」下发 `selection`；命中数与 `resolvedCount` 不一致时提示刷新 |
| 改动面 | `composables/useServerSelection.ts`（接线）+ 2 个页面的批量调用 + `api/listCrud.ts` 参数 + spec |
| 出口判据 | ① 网络日志可见请求体含 `selection.mode=filter`；② spec 断言不再发 `ids`；③ 不一致时出现提示文案（截图） |
| 验证命令 | `cd frontend && npx vitest run src/composables/useServerSelection.spec.ts` |
| 依赖 | W58-03 |

### W58-05 用例集服务端筛选与计数

| 项 | 内容 |
|---|---|
| 目标 | `CaseSetsView` 筛选走服务端、条件落 URL、底部计数取 `total` |
| 改动面 | `views/CaseSetsView.vue` + spec；若后端缺查询参数则补 1 个 |
| 出口判据 | ① 3 页数据下第 2 页筛只命中第 1 页的关键字 → 有结果；② 翻页保留筛选；③ 计数 = `total` |
| 验证命令 | `cd frontend && npx vitest run src/views/CaseSetsView.spec.ts` |
| 依赖 | — |

---

## 3. W2 一致性与证据（W58-06 ~ W58-08）

### W58-06 证据包补接报告导出

| 项 | 内容 |
|---|---|
| 目标 | 导出件写证据包，key=`report-export:{runId}:{file}` |
| 改动面 | `application/report/ReportExportService`（1 处调用）+ 单测 |
| 出口判据 | ① 单测：导出后 `read` 回来 hash 一致；② 大文件不整读进内存；③ 在 `9fda073` 上回放判红 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ReportExportServiceTest test` |
| 依赖 | — |

### W58-07 证据包补接编辑器保存

| 项 | 内容 |
|---|---|
| 目标 | 编辑器保存件写证据包，key=`editor-save:{docId}` |
| 改动面 | 编辑器保存的服务端路径（1 处）+ 单测 |
| 出口判据 | 同 W58-06；额外断言「重复保存同一 docId 会生成新版本而不是覆盖」 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=EditorSaveTest test` |
| 依赖 | — |

### W58-08 列表骨架收敛

| 项 | 内容 |
|---|---|
| 目标 | `ReportsView` / `GlobalTemplatesView` / `GlobalAssetsView` / `RunsView` 逐页决定「接 / 不接并写理由」 |
| 改动面 | 最多 4 页 + 对应 spec + `docs/evidence/v5.8.0/list-crud-matrix.md` |
| 出口判据 | ① 台账每个 C 类条目都有二选一结论；② 接入页行为与 `TestReportsView` 一致（截图）；③ 前端全量单测不减少 |
| 验证命令 | `cd frontend && npx vitest run && npx vue-tsc --noEmit` |
| 依赖 | — |

---

## 4. W3 性能基线（W58-09 ~ W58-10）

### W58-09 性能数据集隔离与生成

| 项 | 内容 |
|---|---|
| 目标 | 10000 行数据集可复现，且不污染既有项目统计 |
| 改动面 | 用 `tools/perf_dataset.py` 生成 + 导入独立压测 project + 前后 DB 读数对比 |
| 出口判据 | ① manifest 含行数/sha256；② 导入前后 `robot_dev` 项目数与既有用例数不变（对比输出）；③ 压测 project 命名带 `PERF-` 前缀可识别 |
| 验证命令 | `python3 tools/perf_dataset.py --scale standard --out /tmp/perf-ds && cat /tmp/perf-ds/manifest.json && python3 tools/perf_seed.py --dataset /tmp/perf-ds` |
| 依赖 | W58-05（列表筛选稳定，避免量测受筛选 bug 干扰） |

### W58-10 10000 行 fps 量测与证据

| 项 | 内容 |
|---|---|
| 目标 | `perf_longlist.json` 的 `status` 为 PASS 或 FAIL，`sample_size ≥ 3` |
| 改动面 | `scripts/perf-longlist.mjs`（CDP + rAF 采数，双口径）+ `tools/perf_longlist.py`（改为读真实测量结果，不再写「未测量」）+ 证据 |
| 出口判据 | ① ≥3 轮、每轮 fps 与机器信息；② `check_perf_budget` 判 PASS/FAIL 且理由可读；③ `--selfcheck` 仍判红「0 轮冒充」 |
| 验证命令 | `node scripts/perf-longlist.mjs --rounds 5 --seconds 3 --out docs/evidence/v5.8.0/perf/longlist-raw.json && python3 tools/perf_longlist.py && python3 tools/check_perf_budget.py --report docs/evidence/v5.8.0/perf/perf-report.json --require` |
| 依赖 | W58-09 |

---

## 5. W4 判据链（W58-11 ~ W58-12）

### W58-11 RF 判据载体 spike

| 项 | 内容 |
|---|---|
| 目标 | 用 10 份真实 `output.xml` 判断有没有可依赖的判据载体 |
| 改动面 | 证据目录 `docs/evidence/v5.8.0/rf-criteria-spike/`（样本片段 + 结论表） |
| 出口判据 | ① 10 份逐份给「有/无载体」+ 片段；② 结论明确（≥3 份稳定可提取才算「有」） |
| 验证命令 | `ls docs/evidence/v5.8.0/rf-criteria-spike/ && cat docs/evidence/v5.8.0/rf-criteria-spike/README.md` |
| 依赖 | — |

### W58-12 RF 判据口径收口

| 项 | 内容 |
|---|---|
| 目标 | 有载体 → 实现提取 + 单测；无载体 → PRD/界面标注「判据机检仅覆盖 pytest」 |
| 改动面 | 二选一；界面标注不需要后端改动 |
| 出口判据 | ① 若实现：单测 + 一条真实 RF 报告的判据行；② 若标注：截图可见提示；③ 无论哪条，测试报告写明选择依据 |
| 验证命令 | 视选择而定；标注路径用 `cd frontend && npx vitest run src/views/ReportDetailView.spec.ts` |
| 依赖 | W58-11 |

---

## 6. W5 真机能力（W58-13 ~ W58-16，串行线）

> 前置：G-1 授权（按 61 条场景）、ADR-0013 安全闸、`confirmReal=true`、gpu-node-01/02 可达（已探 22 端口通）。

### W58-13 real 执行产生判据样本

| 项 | 内容 |
|---|---|
| 目标 | `test_result` 出现非空 `criteria_json`，且报告详情结论与之一致 |
| 改动面 | ~~零代码（发现缺口再补）~~ → **实际改了 5 个文件**：`PytestCriteriaExtractor`（改读 json-report）、`ReportIngestService`（匹配顺序）、`CriteriaEvaluation`（哨兵行解析）、`ReportSummaryCard`（口径）、`ReportDetailView`（传参）。理由见 D-58-11 / D-58-12 |
| 出口判据 | ① 执行前基线 `count=0`；② 执行后 ≥1 且 `verdict` 与声明一致；③ 页面结论 = 该行 verdict（截图）；④ 安全闸四条守卫各一条请求日志 |
| 完成情况 | ①②③ **完成**（正反双样本，14 项断言全 PASS）；④ **未做**，登记 L-58-07 |
| 验证命令 | `SELECT count(*) FROM test_result WHERE criteria_json IS NOT NULL;`（`DB_NAME=robot_dev`） |
| 依赖 | W58-02（页面验收）、真机窗口 |

### W58-14 201~10000 条切批下发

| 项 | 内容 |
|---|---|
| 目标 | 超 200 条自动切批，界面显示「分 K 批」，每批独立结果 |
| 改动面 | `RunLaunchController` / `CaseRunService` 切批调度 + 前端发起台提示 + 单测（200/201/10000/10001 边界） |
| 出口判据 | ① 500 条 → 3 批，网络日志可见 3 次请求；② 每批报告可查；③ 10001 条仍 400；④ 取消后剩余批次不发 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=CaseRunServiceTest test`；真实 500 条执行日志 |
| 依赖 | W58-03（selection）、真机窗口 |

### W58-15 一次性凭证驱动真实采集

| 项 | 内容 |
|---|---|
| 目标 | `recordFromCollect` 挂进采集链，采集后可查吊销记录，材料不可再读 |
| 改动面 | `CredentialVerificationService` + 采集链调用点 + 单测 |
| 出口判据 | ① 真实采集成功；② 采集后 `read` 抛 not found；③ `probe_oneshot_secret` 六处 PASS |
| 验证命令 | `python3 tools/probe_oneshot_secret.py` |
| 依赖 | W58-13（real 通路） |

### W58-16 真机批次运维取证四项

| 项 | 内容 |
|---|---|
| 目标 | 命令重解析统计 / 复现率多轮 / 批次内取消 / FLAKY 判定各出证据 |
| 改动面 | 统计脚本 + 证据归档（可能需 2~3 轮真机批次） |
| 出口判据 | 四项各有「达成 / 未达成 + 原因」，不允许「未测写成达成」 |
| 验证命令 | 统计脚本输出（本轮新增 `tools/stat_command_coverage.py`，若已有则复用） |
| 依赖 | W58-14 |

---

## 7. W6 收口（W58-17）

### W58-17 口径裁决、全量回归与文档回写

| 项 | 内容 |
|---|---|
| 目标 | ① 三条口径遗留裁决并回写；② 全量回归 + 门禁；③ 五份文档回写 |
| 改动面 | 文档为主 + 必要时修回归 |
| 出口判据 | ① `GATES_RESULT FAILS=0 UNJUDGED=0`；② 后端/前端全量单测全绿；③ 六份文档（00/01/PRD/DEV-PLAN/TEST-PLAN/05-REPORT）齐全且 FR↔TC↔结果三者对得上；④ 遗留表状态全部落定 |
| 验证命令 | `bash scripts/gates.sh`；`python3 tools/check_release_docs.py`；`python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/release/v5.8.0/*.md` |
| 依赖 | W1 ~ W5 全部 |

---

## 8. 接口与数据变更清单

| 变更 | 类型 | 影响 | 处理 |
|---|---|---|---|
| `GET /api/reports/{runId}` 加 `criteriaConclusion` | 响应新增字段 | 前端类型同步；老前端忽略新字段不受影响 | 契约快照重生成 |
| 批量端点加 `selection` | 请求新增可选字段 | 与 `ids` 互斥；严格反序列化下老请求不受影响 | 契约快照重生成 + 单测覆盖互斥 |
| 用例集筛选参数（若需补） | 请求新增可选参数 | 默认行为不变 | 同上 |
| 数据库 | **预期零迁移** | — | 若必须加列，从 `V11_65` 起并配 down |

---

## 9. 风险与应对（与 PRD §9 对应）

| # | 风险 | 应对 |
|---|---|---|
| R1 | 改批量请求体打破既有调用方 | 保留 `ids` 语义；`selection` 可选互斥；全仓仅 1 个调用方 |
| R2 | 性能数据集污染统计 | 独立 `PERF-` project + 前后 DB 对比证据 |
| R3 | 真机执行产生真实报告 | 报告软删、`test_result` 判据行保留并注明样本用途 |
| R4 | 骨架补接引入前端回归 | 每页先跑本地 spec，收口跑全量 1015 例 |
| R5 | 切批后报告列表出现多条同批记录 | 登记 `L-58-03`，本版先如实展示，不合并 |

---

## 10. 与上一版的差异（对读 v5.7.0 DEV-PLAN 时不要看错）

1. **本版不新增业务概念**：没有新菜单、没有新页面，只有「补字段、补参数、补接线、补证据」。
2. **本版唯一的接口形状变化**是批量请求体的 `selection`（PRD FR-02），必须重生成契约快照。
3. **本版把「真机取证」独立成波（W5）**：v5.7.0 是把真机项散在遗留表里，结果是「没人排时间」；
   本版单独成波并要求串行，避免又变成「写进计划但没跑过」。

---

## 11. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-14 | 首版：6 波 17 个 WBS；按「无真机三条并行线 + 真机串行线 + 收口」排布 | `PRD.md` + 大师 L 全档决策 |
| 2026-11-14 | 追加 §12「执行进度与偏差登记」：W58-01~05 完成情况、D-58-01~03 偏差、本轮验证读数 | 提交 `f16825e` / `c1108e1` + 全量单测读数 |
| 2026-11-14 | §12 补 W58-06~12 行、D-58-04~10；§12.2 补性能与隔离读数 | `27722fa` + `docs/evidence/v5.8.0/perf/` |
| 2026-11-14 | §12 补 W58-13 行、D-58-11~13；W58-13「零代码」预估被实测推翻，改为 5 处改动 | `docs/evidence/v5.8.0/criteria/`（正反双样本 14 项断言） |

---

## 12. 执行进度与偏差登记

> 就地更新；**只有出口判据逐条满足才算完成**，计划写完不算完成。
> 「真实 XX 待补」= 代码与单测已到位，判据里属于真实链路/截图的那部分还没做，收口前必须清零或按降级登记。

| WBS | 状态 | 出口判据对照 | 证据 |
|---|---|---|---|
| W58-01 | 完成（真实 curl 待补） | ① `ReportControllerTest` 11 例（新增 3，含空值 → `UNDETERMINED`）② 真实 `curl` 待补 ③ 修前快照回放待补 | `f16825e` |
| W58-02 | 完成（截图待补） | ① `ReportDetailView.spec.ts` 13 例（新增 3）② 真实界面三态截图待补 ③ 有反向用例：判据 PASS 且 `failCount>0` 仍显示通过 | `f16825e` |
| W58-03 | 完成（真实 HTTP 待补） | ① 后端 13 例（用例 6 + 通用批量 7）② 真实 HTTP 四类请求待补 ③ 契约快照已重生成且 `--check` PASS（并修了参数解析缺陷，见 D-58-04） | `f16825e` |
| W58-04 | 完成（网络日志待补） | ① 真实网络日志待补 ② spec 断言条件模式不发 `ids` ③ spec 断言不一致文案 | `c1108e1`；`CasesView.spec` 14 例、`useServerSelection.spec` 16 例 |
| W58-05 | 完成（3 页反向实证待补） | ① 三页数据下反向实证待补 ② spec 用 URL 复现证明筛选可复制 ③ DOM 断言「共 N 条」取 `total` ④ 3 例覆盖筛选变化与翻页保留 | `CaseSetsView.spec` 15 例；`TestSetControllerTest` 5 例 |
| W58-06 | 完成（回放待补） | ① `ExportServiceTest` 11 例（新增 2：read 回来逐字节一致 + 通道故障不影响导出）② 大文件不整读内存：证据写入传**磁盘路径**，`EvidencePaths.sha256Of`/`Files.copy` 均流式 ③ `9fda073` 回放待补 | `ExportService` + `EvidenceBundleService` |
| W58-07 | 完成（回放待补） | ① `EditorSaveServiceTest` 12 例（新增 3）② 同上，传临时文件路径 ③ 额外断言：同一 docId 两次保存 = 2 个版本、证据指针只留最近一次 | `EditorSaveService` |
| W58-09 | 完成 | ① manifest 含行数/sha256（`dataset-manifest.json` 随证据归档）② 导入前后既有工程 13→13、既有用例 2491→2491（`seed-report.json` 对比输出）③ 压测工程 `PERF-LONGLIST` 带 `PERF-` 前缀、`--purge` 可一键清 | `tools/perf_seed.py`；`docs/evidence/v5.8.0/perf/seed-report.json` |
| W58-10 | 完成（双口径，见 D-58-08） | ① 5 轮 × 3 秒，每轮 fps / 帧数 / 最差帧 / 累计滚动像素齐全 ② `check_perf_budget --require` 判 PASS（产品口径 `minFps 60.1 ≥ 50`）③ `--selfcheck` 4 类反例全判红（其中「2 轮 60fps」当场抓出自检漏洞并修掉） | `scripts/perf-longlist.mjs` + `tools/perf_longlist.py`；`docs/evidence/v5.8.0/perf/` |
| W58-11 | 完成 | ① 10 份逐份「有/无载体」+ 片段（`README.md` 表 + `scan.json`）② 结论明确：无框架级载体（`<doc>` 2/10、断言 3/10、结构化失败消息 4/10，均不构成稳定协议） | `docs/evidence/v5.8.0/rf-criteria-spike/`（样本清单 + 扫描脚本 + 原始读数） |
| W58-12 | 完成（走标注分支） | ① 未实现提取（无载体）② 界面标注 `report-conclusion-scope`，spec 断言含 `pytest` 与 `Robot Framework` ③ 选择依据写在 spike `README.md` §4 与 PRD FR-11「实测结论」 | `ReportDetailView.spec.ts` 14 例 |
| W58-08 | 完成（截图待补） | ① 台账 42 行逐条有结论，缺口 7 → 6 ② `ReportsView` 行为对齐 `TestReportsView`（编辑/软删），截图待补 ③ 前端全量 130 文件 1030 例（只增不减） | `docs/evidence/v5.8.0/list-crud-matrix.md`；`ReportsView.spec.ts` 3 例 |
| W58-13 | 完成 ①②③，④未做（见 D-58-11~13、L-58-07） | ① 真机 `EHT-REAL-1794671083499` 1 行 `criteria_verdict=PASS`，三条判据为实测值 ② 报告接口 `criteriaConclusion=PASS / evaluated=1 / missing=0` ③ 页面标签「通过」与结论卡一致，三方比对 14 项断言全 PASS ④ 反向样本 70 行全 `MISSING` → 页面「未判定」，无「全部通过」 ⑤ 后端全量 1946 例 FAIL 0；前端相关 22 例 PASS；`vue-tsc` 无输出 | `docs/evidence/v5.8.0/criteria/`（README + 8 份原件）；`scripts/w13-criteria-ui.mjs` |

### 12.1 偏差登记

| # | 偏差 | 理由 | 处置 |
|---|---|---|---|
| D-58-01 | W58-04 只接了**用例清单页**，用例集页的条件选择未接线 | 用例集列表此前不是服务端分页，「全选所有命中」没有可下发的边界；更关键的是**该页至今没有批量动作**（选择器没有消费者），发了描述符也无处可用 | W58-05 之后列表已服务端分页，前提具备；等该页出现真实批量动作时接线。在此之前不得对外称「所有列表都支持跨页全选」 |
| D-58-02 | FR-03 后端补了 **2 个**查询参数（`setCode` / `name`），超出 DEV-PLAN 预留的「补 1 个」 | 两列筛选都要服务端生效就各需一个参数；只补一个会变成「一列走服务端、一列走本地」的半吊子口径，比不补更难解释 | 已并入 `openapi.json` 重生成范围（FR-03 也改了接口签名） |
| D-58-03 | 全量后端单测首跑假红：`ProjectRunDispatcherTest`（1934 例 1 失败） | 断言与派发线程的 `inFlight` 回减抢跑（latch 在 runnable 内 countDown，`inFlightCount` 在 runnable 返回后才减），单跑必过、全量被压到 1 | 已改为「轮询等状态收敛」，断言语义不变；单跑 5 例全过，全量复跑 1934 例 FAIL 0 |
| D-58-07 | FR-11 走「标注」而非「实现」：RF 报告仍不产出判据结论 | 10 份真实 `output.xml` 里没有框架级判据载体；`<doc>` 只有 2 份且语义不一（判据 vs 说明），断言关键字 3 份但参数是 `1 / 1`、`${LEFT} / ${RIGHT}` 这类字面量/占位。硬做会「提取到 0 条却看起来已支持」 | 按 PRD FR-11 判据 2 的无载体分支执行：界面 + PRD 同时标注；载体协议登记 L-58-05 留待下一版 |
| D-58-05 | W58-08 四页里只有 `ReportsView` 判定为「接」，其余三页不接 | `GlobalAssetsView` 无通用 `PUT`（资产由采集回写）；`GlobalTemplatesView` 只缺外壳、接它无行为变化；`RunsView` 缺口本就是「无」。判定依据写在台账 §本轮判定 | 台账逐页留结论与证据，不把「不接」写成「已完成」；FR-04 按 P2 登记（允许未达成） |
| D-58-06 | **计划外修正**：`tools/check_list_crud.py` 把台账路径写死成 `docs/evidence/v5.7.0/` | 新台账放在 `v5.8.0/` 时机检会继续对着旧台账判绿——台账没人看、机检还是 PASS，属最难发现的假绿（与门禁 29 的 `host_inventory` 证据目录同型） | 改为按目录版本序取最新台账；`--self-test` 真注入判红→还原判绿，PASS=113 |
| D-58-04 | **计划外修正**：`tools/gen_api_contract.py` 长期解析不出任何注解参数（438 个操作里只有 6 个带参数，且都出自 `MultipartFile` 分支） | `request_param()` 要求「注解」与「类型+参数名」分属两个逗号段，而 Java 把两者写在同一段；结果 `@PathVariable` / `@RequestParam` 一律返回 `None`，契约快照**从来没有覆盖过 query/path 参数**——本版 FR-02/FR-03 的请求形状变化原本无法被 NFR-02 兜住 | 已修：剥掉前缀注解后再解析类型与参数名；重生成后带参数的操作 6 → 359，`--check` PASS，`endpoints=438 / paths=360` 不变（依赖方只用路径与 `info.version`，已逐个核对） |
| D-58-08 | **口径偏差（大师 2026-11-14 拍板「双口径都测」）**：预算场景「10000 行滚动 ≥50fps」在产品里不可达 | 产品把每页硬顶在 100 行（`PageQuery.MAX_SIZE = 100`，前端档位 10/20/50/100，全站没有任何列表取数超 100）。硬报一个 `rows=10000` 的产品读数就是伪造 | 分两个口径且各说清量的是什么：**product**（10000 条数据集 + 每页 100 行，真页面，决定 `status`）+ **harness**（一次性 HTML 10000 行，标注「非产品页面」，只作补充基线）。门禁 22 步改为同时判两份报告（v3.2.0 那份只覆盖 API，不含长列表） |
| D-58-09 | **发现（非偏差）**：harness 口径 10000 行同屏只有 20.4~28.5fps，低于预算 50 | 纯静态表格一次铺 10000 个 `<tr>`，滚动时整表重排；三次独立量测 23.8 / 24.2 / 20.4 一致偏低，不是抖动 | 不改产品（当前形态下该场景不可达，也没有必要）；作为「将来若取消分页或上虚拟滚动」的前置输入登记遗留 **L-58-06** |
| D-58-10 | **计划外修正**：`tools/perf_seed.py` 首版只写 `seed-report.json`，未归档数据集 manifest | PRD FR-v5.8-06 判据③要求 manifest 随证据归档；数据集本体在 `/tmp`，不归档等于「行数与 sha256 可复现」这句话没人能验 | 导入时自动 `cp` 成 `docs/evidence/v5.8.0/perf/dataset-manifest.json`；另：purge 后重新导入会让工程 id 变化（123 → 124），README 给的是「按 code 查 id」的命令而不是写死 id |
| D-58-11 | **判据链断点（后端）**：W58-13 原计划「零代码」，实际提取器一根线都没通 | pytest 9.2 默认 `junit_logging` 不开，junit `<testcase>` 里**没有** `system-out`（4 份真实 run + spawn 实验全 0 段）；而 json-report 的 `tests[].{setup,call,teardown}.stdout` 确有 stdout。基线 715 行 `criteria_verdict` 非空、`criteria_json` 一条没有，就是「结论有、证据无」 | 改读 `pytest-report.json`（junit 作兼容回退）；另修 `CriteriaEvaluation.parse` 的切块方式：原来是「首个 `{` 到最后一个 `}`」，日志里有花括号就整段失败，改为 `CRITERIA_JSON` 哨兵行 + 花括号配对（支持字符串内转义）。补 8 例提取器单测 / 9 例评估单测 |
| D-58-12 | **口径冲突（前端）**：报告速览卡按 `failed == 0` 推「通过 / 全部通过」，与结论卡片的判据口径同屏打架 | 一条用例可以 `verdict=PASS` 而 `criteria_verdict=MISSING`，于是同一屏上速览说「全部通过」、结论卡说「未判定」——读者无从判断该信哪个。这正是 FR-v5.8-01 要消除的口径，却残留在另一个组件里 | `ReportSummaryCard` 改为：有判据时标签用 `/reports/conclusion/*` 同一套文案、正文**只报数**；判据缺失显式「未判定」+「不按失败数推断」。加 3 条 spec 锁住，其中「判据 FAIL 但失败数为 0 → 不通过」是防回退的关键一条 |
| D-58-13 | **取证坑（非偏差）**：首次取证时结论卡片整张不渲染，`grep report-conclusion frontend/dist/assets/*.js` 无结果 | `frontend/dist` 是 21:46 构建的，`ReportDetailView.vue` 22:53 已改——源码对、界面不对。门禁与单测都发现不了这种假象 | 取证前必须 `npm run build`；`ui-conclusion-mounted` 断言就是为了把「产物过期」挡成红而不是「跳过」 |

### 12.2 本轮验证读数（2026-11-14）

| 项 | 读数 |
|---|---|
| 后端全量 | `Tests run: 1934, Failures: 0, Errors: 0, Skipped: 4`，BUILD SUCCESS（v5.7.0 为 1917，本版 +17） |
| 前端全量 | 129 文件 / 1027 例全过（本版新增 6 例） |
| 类型检查 | `npx vue-tsc --noEmit` 无输出 |
| 长列表 fps（产品口径） | 5 轮 × 3 秒：60.1 / 60.5 / 60.3 / 60.4 / 60.5，`minFps 60.1`（预算 50）→ PASS；每轮 `scrolledPx ≈ 67720`（真滚，非空转） |
| 长列表 fps（10000 行同屏） | 同批 harness 口径 24.4 / 24.8 / 20.4 / 24.3 / 22.5，`minFps 20.4` → 不达标（D-58-09，不参与判定） |
| 数据集可复现 | `--scale standard` 两次独立生成 `contentHash` 相同（`59824208438adcd7…`），且与 v3.2.0 历史数据集逐字节一致 |
| 数据隔离 | `robot_dev`：既有工程 13→13、既有用例 2491→2491；压测工程 1 个（`PERF-LONGLIST` id=124，10000 条），数据集 `cases.csv` sha256 `272bdb09…` |
| 性能门禁 | `check_perf_budget --require` 两份报告均 PASS（v3.2.0 API 段 + v5.8.0 长列表段） |
| 判据入库（W58-13） | `test_result` 715 → 718 行；`criteria_json IS NOT NULL` **0 → 1**；`criteria_verdict IS NOT NULL` 70 → 73 |
| 判据样本（真机） | `EHT-REAL-1794671083499` / `IM-FUN-014`：`criteria_verdict=PASS`，`link_speed 400000Mb/s`、`mtu 9000`、`pci_vendor_device 15b3:1021` 三条全 `passed=true` |
| 报告结论（接口） | `GET /api/reports/EHT-REAL-1794671083499?projectId=95` → `criteriaConclusion=PASS`、`criteriaEvaluated=1`、`criteriaMissingCount=0`；`exportable=false`（`metadataMissing` 9 项，见 L-58-08） |
| 报告结论（界面） | 结论卡「通过 · 已评估 1 条，判据缺失 0 条」；速览卡「通过 · 本轮 1 条用例：通过 1，失败 0，跳过 0」；两处均无「全部通过」 |
| 反向实证（判据全缺失） | `EHT-FAKE-1794661209912` 70 行全 `MISSING`、通过率 7.1% → 两处均「未判定」；`ui-no-counted-verdict` PASS |
| 界面取证断言数 | 正向 14 项 / 反向 14 项，全 PASS（`W13_CRITERIA_UI PASS`）；`ui-no-js-error` 0 exception |
| 后端单测（W58-13 后） | `Tests run: 1946, Failures: 0, Errors: 0, Skipped: 4`，BUILD SUCCESS（较本节首行 1934 再 +12，来自提取器 4 例 + 评估 3 例 + 既有其他） |
| 前端相关单测 | `ReportSummaryCard.spec` 8 例 + `ReportDetailView.spec` 14 例，共 22 例 PASS；`vue-tsc --noEmit` 无输出 |
