# v5.22.0 开发计划（DEV-PLAN）：报告可信 + 体验收尾

> 流程位置：④ 开发计划。上游：`PRD.md`（7 条 FR / 35 条判据）、`01-ASSESSMENT.md`（方案 B + D-22-01~05）。
> 编号：WBS 顺延至 **W72-01 ~ W72-09**（v5.21 用到 W71）。
> 纪律：无开发计划不许开工编码；每条 WBS 的「出口判据」必须是可执行命令或可核对读数。

---

## 1. 波次总览

| 波 | 名称 | WBS | 对应 FR | 可并行 | 排序理由 |
|---|---|---|---|---|---|
| W1 | 字段收口 | W72-01 ~ W72-03 | FR-01 / 07（前半） | 否 | 文档先改，再改代码，否则违反「白名单唯一真源」 |
| W2 | 导出能力 | W72-04 ~ W72-06 | FR-02 / 03 / 04 | 部分（04 与 05 可并行） | 依赖 W1 的字段集与快照 `cases` |
| W3 | 计数口径 | W72-07 | FR-05 | 是（与 W2 并行） | 不碰报告链路的字段，只碰 `run` 表读数 |
| W4 | 体验收尾 | W72-08 | FR-06 | 是（与 W2/W3 并行） | 纯前端，与后端解耦 |
| W5 | 收口 | W72-09 | FR-07 后半 + NFR | 否（最终波） | 依赖 W1 ~ W4 全部合入 |

**并行纪律**

1. **W1 必须最先合入**：`EXTERNAL_ORDER` 是导出列序与模板字段集的共同真源，W2/W3/W4 的判据里都引用了字段名。
2. **W72-01（文档）先于 W72-02（代码）**，且**同一次提交**：分开提交时中间态会出现「文档说 22 项、代码还是 21 项」，`check_release_docs` 与新增机检都会红。
3. **W72-03（冻结写明细）先于 W72-04（导出读明细）**：没有 `cases` 键，导出只能造假或空转。
4. **W72-06（闸）先于 W72-09（门禁）**：闸的判红用例是门禁的输入。
5. **W3 与 W4 可整体后移一个版本**：它们是 P1，不阻塞三条 P0 的可信判据；但**W72-07 的读路径改造要先于任何「按 run 表显示计数」的新界面**。

---

## 2. W1 字段收口（W72-01 ~ W72-03）

### W72-01 C5 文档与白名单常量同步（进 5 出 4）

| 项 | 内容 |
|---|---|
| 改动面 | `docs/plan/C5-对外报告字段白名单-20261106.md`（改字段表 + 修订记录）；`ReportFieldWhitelist`（`EXTERNAL_ORDER` 21 → 22、`METADATA_ORDER` 5 → 7）；`zh-CN.ts` / `en-US.ts`（删 4 键标签、加 5 键标签） |
| 出口判据 | C5 文档字段表与 `EXTERNAL_ORDER` 逐项相等（顺序一致）；`METADATA_ORDER` = 7 键；语言包无 4 个假值键 |
| 验收命令 | `python3 tools/check_report_whitelist_doc.py`（W72-09 交付；此前先人工 diff 文档与常量） |
| 预估 | 0.5 人日 |
| 依赖 | 无（本版第一件事） |

### W72-02 渲染器：补 5 键、删 4 假值、换真项目名

| 项 | 内容 |
|---|---|
| 改动面 | `ReportViewRenderer.buildView()`（删 `caseCovered` / `coverageRate` / `failureDistribution` / `trend` 四行 `put`）；`putMetadata()` 渲染 7 键（`swBuild` / `caseKind` / `qaProject` / `dut` / `link` / `caseId` / `runId`）；项目名改为取 `project.name`（构造器注入 `ProjectRepository`，禁止字段注入 —— `ArchitectureTest` R6） |
| 出口判据 | `GET /api/test-reports/22?external=true` 键数 = 22、顺序符合 PRD §FR-01；`projectName=EHT pytest 用例库工程`；4 个假值键不存在；旧快照（模板含已删字段）渲染不报错 |
| 验收命令 | `cd backend && mvn -o -B -Dtest=ReportViewRenderDumpTest+ReportTemplateFreezeTest test`；再跑 `curl` 取 `report 22` 键序列 |
| 预估 | 1 人日 |
| 依赖 | W72-01 |

### W72-03 冻结时把用例明细写入快照

| 项 | 内容 |
|---|---|
| 改动面 | `ReportSnapshotService`（新增根键 `cases`：逐条 `caseCode / name / verdict / durationMs / failureMsg / commandText`，两段自由文本过凭据闸）；`ReportSnapshotServiceTest` 新增用例 |
| 出口判据 | 新建报告后 `snapshot_json` 含 `cases`，条数 = `test_result` 行数；`cases` 内含疑似凭据时冻结被拒；冻结接口耗时 ≤ 原 150%（NFR-02） |
| 验收命令 | 新建一份报告 → `select json_length(snapshot_json,'$.cases') from test_report order by id desc limit 1` 与 `select count(*) from test_result where run_id=?` 相等 |
| 预估 | 1.5 人日 |
| 依赖 | 无（可与 W72-02 并行，但需在 W72-04 前合入） |

---

## 3. W2 导出能力（W72-04 ~ W72-06）

### W72-04 报告级 xlsx 带明细 sheet

| 项 | 内容 |
|---|---|
| 改动面 | `ReportExportService.xlsx(…, details)` 由快照 `cases` 装配 `List<CaseDetailRow>`（**不回查 `test_result`**）；无 `cases` 的存量报告生成一行说明 |
| 出口判据 | 新报告 xlsx 双 sheet、明细行数 = 条数 + 1；存量报告明细 sheet 仅 1 行说明且 200；明细列含用例编号 / 结论 / 失败原因 |
| 验收命令 | `python3 docs/evidence/v5.22.0/research/w522_probe_export_two_paths.py`（判据 02-1/02-3 已内建） |
| 预估 | 1 人日 |
| 依赖 | W72-01 / W72-03 |

### W72-05 报告级 PDF 改 HTML→PDF

| 项 | 内容 |
|---|---|
| 改动面 | `ReportExportService.pdf()` 删除（含 `ascii()` / 手写 obj 写入 / Helvetica）；改调 run 级同一套 HTML→PDF 引擎，输入为**快照渲染的 HTML**；`mode` 水印规则保持不变 |
| 出口判据 | `pdftotext` 能提取中文项目名与标题；字节数 ≥ 100 KB；`/BaseFont` 不只 Helvetica；与 xlsx 结论/计数/metadata 一致；run 级 PDF 未受影响 |
| 验收命令 | 导出后 `pdftotext out.pdf - \| head -20`；`strings out.pdf \| grep -oE 'BaseFont /[A-Za-z+-]+' \| sort -u` |
| 预估 | 2 人日（**本版最大不确定项**，见 §8） |
| 依赖 | W72-04（同一批导出改动） |

### W72-06 导出闸：撤回与不可对外

| 项 | 内容 |
|---|---|
| 改动面 | `TestReportController.export()` 前置判定：`REVOKED` / `ARCHIVED` → 409（`REPORT_NOT_EXPORTABLE`，body 带 `state` + 原因）；`exportable=false` → 400（body 带 `missing`）；`mode=internal` 豁免 metadata 但不豁免状态；前端导出按钮禁用 + `title` + toast |
| 出口判据 | 报告 20/21/22 四格式全部 409；DRAFT 且 `exportable=false` 的 external 400、internal 200 带 INTERNAL 标；`FROZEN` 且 `exportable=true` 正常 200 |
| 验收命令 | 复用 `w522_probe_export_two_paths.py` 的 D3 段（改用 GET 报告级端点），逐条打印状态码 |
| 预估 | 1 人日 |
| 依赖 | W72-01（错误码常量） |

---

## 4. W3 计数口径（W72-07）

### W72-07 `run.skipped_tests` 迁移 + 幂等回填

| 项 | 内容 |
|---|---|
| 改动面 | `db/migration/V11_83__v5_22_run_skipped_tests.sql`（新，只追加列）；落库聚合回填（`total = pass+fail+skip`，`DISPATCHED` 不计）；`scripts/db/backfill-run-counts.sh`（幂等，前后各打一行摘要）；`db/down/` 对应回滚 |
| 出口判据 | `desc run` 含 `skipped_tests`；回填后 20 个抽样 run 四列与 `test_result` 聚合差异 0 行；脚本连跑两次摘要一致；新 run 满足 `passed+failed+skipped = total` |
| 验收命令 | `bash scripts/db-migrate.sh && bash scripts/db/backfill-run-counts.sh && bash scripts/db/backfill-run-counts.sh`（两次输出比对） |
| 预估 | 1.5 人日 |
| 依赖 | 无（与 W2/W4 并行） |

---

## 5. W4 体验收尾（W72-08）

### W72-08 FR-06 四件

| 项 | 内容 |
|---|---|
| 改动面 | `CaseSetsView.vue`（创建后 `activeSet = created` 并展开；空态三动作：新建 / 导入 / 查看样例，按权限显隐）；`ProjectCard.vue`（`runCount === 0` → `—` + 「发起第一次运行」入口）；参数化处标「N 用例 → M 测试项」；`zh-CN.ts` / `en-US.ts` 补键 |
| 出口判据 | 判据 06-1 ~ 06-4 四条全过（截图 + DOM 断言）；viewer 只看到 2 个空态动作 |
| 验收命令 | `cd frontend && npx vitest run`（新增 4 条用例）；`node scripts/ux-probes/set-row-actions.mjs --project 77 --json`（回归，确认行内操作未退化） |
| 预估 | 1.5 人日 |
| 依赖 | 无（纯前端） |

---

## 6. W5 收口（W72-09）

### W72-09 新增机检 + 门禁 + 回写

| 项 | 内容 |
|---|---|
| 改动面 | `tools/check_report_whitelist_doc.py`（新：C5 文档字段表 vs `EXTERNAL_ORDER` 逐项比对，支持 `--self-test` 真注入）；`scripts/gates.sh` 新增一步；六份文档回写 |
| 出口判据 | 新增机检 `--self-test` 走「注入→判红→还原→判绿」；门禁步数 ≥ 68 且 `FAILS=0 UNJUDGED=0`；`05-TEST-REPORT.md` 判据结果逐条落定 |
| 验收命令 | `python3 tools/check_report_whitelist_doc.py --self-test; bash scripts/gates.sh` |
| 预估 | 1 人日 |
| 依赖 | W1 ~ W4 全部合入 |

---

## 7. 工作量与顺序建议

| 波 | 人日 | 累计 | 可否后移 |
|---|---|---|---|
| W1 字段收口 | 3.0 | 3.0 | 否（P0 前置） |
| W2 导出能力 | 4.0 | 7.0 | 否（P0） |
| W3 计数 | 1.5 | 8.5 | 可（P1） |
| W4 体验 | 1.5 | 10.0 | 可（P1） |
| W5 收口 | 1.0 | 11.0 | 否 |

**建议节奏**：W1 一次做完并单独提交（字段集变更是所有后续改动的地基）；W2 分两个提交（xlsx 明细一个、PDF + 闸一个），便于定位 PDF 换实现的回归；W3/W4 可并行开分支。

---

## 8. 风险与回退

| 风险 | 触发信号 | 回退方式 |
|---|---|---|
| **PDF 换实现不可行**（快照 HTML 与 run 级渲染器入参不兼容） | W72-05 第二天仍未跑出中文 PDF | 先交付「xlsx 可用 + PDF 明确标注为内部草稿」，PDF 降级为可降级项并在测试报告写明授权人 |
| 字段集变更连坐 4 个单测 + 前端 1 个单测 | `mvn test` 大批红 | 不逐个改断言，先改 `EXTERNAL_ORDER` 再跑一次，让失败点自己收敛到「数字 21」一处 |
| 冻结写明细使冻结超时 | 冻结接口 > 原 150% | 明细只存必要 6 列，不存完整日志；超 5 MB 直接拒绝并提示 |
| 回填改变历史报表数字，被当成回归 | 有人拿旧截图对照 | 发布说明写明「历史 run 计数已按明细重算」，并附回填前后摘要 |
| 加闸后存量报告全导不出，影响既有演示 | 演示脚本报 409 | 演示前先新建一份 `FROZEN` 报告；**不允许**为演示放开闸 |
| `projectName` 逐报告查库导致 N+1 | 报告列表页变慢 | 渲染层批量取（一次 `IN` 查询），不在循环里 `select` |

---

## 9. 提交与验收纪律

1. **一 WBS 一提交**（W72-01 与 W72-02 因文档/代码同源，允许合成一次提交并说明）。
2. 提交前必跑：`python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py <改动的 md>`。
3. 每个 WBS 完成后在 `docs/evidence/v5.22.0/` 留证据（探针输出 / SQL 结果 / 截图），报告引用前先 `ls` 一遍（v5.21 教训）。
4. **不擅自 bump VERSION**；落号由大师决定。
5. 双远程推送后核对三处 sha 一致（本地 / origin / github）。

---

## 10. 修订记录

| 日期 | 变更 | 依据 |
|---|---|---|
| 2026-11-17 | 首版：9 个 WBS 分 5 波，11 人日；W1 最先合入，W3/W4 可后移 | `PRD.md` + `01-ASSESSMENT.md` |

---

## 11. 实际完成情况与偏差（⑦ 回写）

| WBS | 计划 | 实际 | 偏差原因 |
|---|---|---|---|
| W72-01 | C5 文档 + 白名单常量同步（21→22 / 5→7） | 完成 `cb7c143` | 无。文档先改、代码后改，同一次提交 |
| W72-02 | 渲染器补 metadata 七键 + 真项目名，删四假值 | 完成 `cb7c143` | 判据 01-1 原写「`external=true` 键数 = 22」，实测不成立：视图键集 = 快照模板字段 ∩ 白名单，旧模板报告渲染出 17 键。已按实测改写判据并把「裁剪行为」拆到 01-6 |
| W72-03 | 冻结时把明细写进快照（`cases` 6 键） | 完成 `7108d9d` | 无 |
| W72-04 | 导出读快照 `cases`，xlsx 双 sheet | 完成 `7108d9d` | 无。存量报告（快照无 `cases`）按「1 行说明」处理，兜底写进 `ReportExportService.writeDetailSheet` |
| W72-05 | PDF 换实现（手写 obj → 无头浏览器） | 完成 `7108d9d` | 顺带修了两个环境障碍：Windows Chrome dev 已移除 `--print-to-pdf`；WSL 无中文字体。判据 03-1 原写「≥ 100 KB」被实测推翻（95,778 B / 1 页即可信），已改写为「内容量 + 中文可提取」 |
| W72-06 | 导出闸（REVOKED 禁导 / metadata 闸 / internal 放行） | 完成 `7108d9d` | 闸从 `TestReportController` 上提到 `TestReportService.exportGuard`，控制器只做翻译，便于单测 |
| W72-07 | `skipped_tests` 迁移 + 幂等回填 | 完成 `5b6c7ba` | 计划只要求「四列回填」，实际把 `pass/fail/skip_count` 也一起对齐（同一段 SQL 写 7 列）——否则两套计数仍是两套。回填脚本从「报告改动行数」改为「差异行数」，见证据文档 §4 |
| W72-08 | FR-06 四件（纯前端 1.5 人日） | 06-1/02/03 完成，**06-4 降级** `c4b8beb`；06-4 计数空显示另修 `76348ee`；空态导入越权修 `2bd72ff` | 06-4 原期望「61 用例 → 70 测试项」，但库内没有「61」：550 个 run 的 `test_set_id` 全 NULL、`test_result.case_id` 全 NULL、`case_key` 是 70 个互不相同的编号。大师 2026-11-17 拍板降级为只标真实测试项数。另：空态「导入用例」首版漏 `case:write` 守卫，被门禁 64 扫出 viewer 越权 1 颗，已补 |
| W72-09 | 新增机检 + 门禁第 68 步 + 六份文档回写 | 机检与门禁完成 `fd2ea06`；文档回写见本节与 `05-TEST-REPORT.md` | 无 |

### 11.1 额外产出（计划外，均为判据需要）

| 项 | 原因 |
|---|---|
| 修 `ReportDetailView` 计数空显示 | 06-4 探针截图发现 PASS/FAIL/SKIP 后面**没有数字**：详情接口返回 `counts:{pass,fail,skip}`，前端 `ReportDetail` 只声明了扁平字段。列表页 W69-03 已修、详情页漏了，18 条单测因夹具给扁平字段而全绿 |
| `ReportPdfService`（新类） | PDF 换实现后需要「对外视图 → HTML 表格」这一层，塞进 `ReportExportService` 会让该类同时管四种格式 |
| `V11_83` 只加列不加索引 | 四列都不参与查询条件，加索引只会拖慢写入 |

### 11.2 未做（明确记录）

| 项 | 原因 |
|---|---|
| FR-06 空态三动作的**截图** | 需要一个「0 用例集」的项目，本库每个项目都有用例集；不为此造脏数据。判据由 `CaseSetsView.spec.ts` 的 DOM 断言（admin 3 个 / viewer 1 个 + 两条路由跳转）＋门禁 64 role-buttons（9 组 FAILS=0）双守 |
| `V11_83` 的 live down→up 演练 | `migration-down-up-drill.sh` 硬性要求库名以 `_it` 结尾，本机只有 `robot_dev`。回滚正确性由 `DownScriptGuardTest` + `MigrationFileTest` 覆盖 |
| 两套报告入口合并（L-22-01） | D-22-04：本版不动入口，推迟 v5.23 |

