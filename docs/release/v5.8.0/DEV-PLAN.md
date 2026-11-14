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
| 验证命令 | `python3 tools/perf_dataset.py --scale standard --out /tmp/perf-ds && cat /tmp/perf-ds/manifest.json` |
| 依赖 | W58-05（列表筛选稳定，避免量测受筛选 bug 干扰） |

### W58-10 10000 行 fps 量测与证据

| 项 | 内容 |
|---|---|
| 目标 | `perf_longlist.json` 的 `status` 为 PASS 或 FAIL，`sample_size ≥ 3` |
| 改动面 | `tools/perf_longlist.py`（改为读真实测量结果，不再写「未测量」）+ 量测脚本 + 证据 |
| 出口判据 | ① ≥3 轮、每轮 fps 与机器信息；② `check_perf_budget` 判 PASS/FAIL 且理由可读；③ `--selfcheck` 仍判红「0 轮冒充」 |
| 验证命令 | `python3 tools/perf_longlist.py && python3 tools/check_perf_budget.py --report docs/evidence/v3.2.0/EW32-01/perf/perf-report.json --require` |
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
| 改动面 | 零代码（发现缺口再补）；执行 1 条带判据的用例 |
| 出口判据 | ① 执行前基线 `count=0`；② 执行后 ≥1 且 `verdict` 与声明一致；③ 页面结论 = 该行 verdict（截图）；④ 安全闸四条守卫各一条请求日志 |
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
