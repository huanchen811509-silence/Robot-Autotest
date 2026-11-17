# v5.22.0 测试计划（TEST-PLAN）：报告可信 + 体验收尾

> 流程位置：⑤ 测试计划。上游：`PRD.md`（7 FR / **35 条判据** / 8 NFR）、`DEV-PLAN.md`（5 波 9 WBS）。
> TC 编号连续 `TC-v5.22-01 ~ 35`，与判据 1:1 对应，**禁补号**；插入用例即整体重排。
> 前置：v5.21.0 已落号（`81f5f16`）；后端 `:8081`、前端 `:8001`、CDP `:9222` 在跑。

---

## 1. 范围

| 项 | 内容 |
|---|---|
| 测 | 7 条 FR 的 35 条判据；8 条 NFR；第 68 步机检的正负向自证 |
| 不测 | v5.19/v5.20 范围内的旧缺陷（回对应版本）；字段级权限；两套报告入口合并（D-22-04 → v5.23） |
| 方式代号 | **UI**=真实浏览器；**HTTP**=接口直调；**DB**=只读 SQL；**UT**=单测；**MECH**=机检脚本 |
| 账号 | `qa-e2e-admin`（写作）/ `qa-e2e-viewer`（06-2 的权限对照） |

## 2. 用例

### 2.1 FR-v5.22-01 对外视图字段收口（6 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.22-01 | `/api/test-reports/{id}/fields` 清单 22 项；全选模板的报告 `external=true` 视图 22 键 | HTTP | 两份 JSON 键集 | P0 |
| TC-v5.22-02 | 四个假值字段（`caseCovered`/`coverageRate`/`failureDistribution`/`trend`）在视图、语言包、模板页全部消失 | HTTP + MECH | 键集 + `grep` 空 | P0 |
| TC-v5.22-03 | `METADATA_ORDER` 七键齐全，视图里 `qaProject`/`dut`/`link`/`caseId`/`runId` 取到真值 | HTTP | 视图 JSON | P0 |
| TC-v5.22-04 | `projectName` 是项目真名，不是 `project-<id>` 拼接 | HTTP | 视图 JSON + 库内项目名对照 | P0 |
| TC-v5.22-05 | 旧模板（21 字段）冻结的报告渲染时被白名单裁剪，不报错也不补假值 | HTTP | 视图键数 + 键集 | P1 |
| TC-v5.22-06 | 四种导出格式的字段集与视图一致（无第三种口径） | HTTP | xlsx 表头 / PDF 提取文本 / html / docx | P0 |

### 2.2 FR-v5.22-02 报告级导出带用例明细（5 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.22-07 | xlsx 双 sheet（`report` + `用例明细`），明细行数 = 明细条数 + 1 表头 | HTTP | sheet 名 + 行数 | P0 |
| TC-v5.22-08 | 明细内容与 `test_result` 逐条一致（编号 / 结论 / 耗时） | HTTP + DB | 行数对照 + 抽样比对 | P0 |
| TC-v5.22-09 | 冻结前的存量报告（快照无 `cases`）导出仍 200，明细 sheet 只有 1 行说明 | HTTP | 行数 + 说明文本 | P1 |
| TC-v5.22-10 | 对外明细不含命令原文（置 `—`），内部明细含命令原文 | HTTP | 两份 xlsx 对照 | P0 |
| TC-v5.22-11 | 明细自由文本命中凭据形态时拒绝冻结，报错只含用例编号不回显内容 | UT | 单测断言 + 错误文案 | P0 |

### 2.3 FR-v5.22-03 报告级 PDF 中文可读（5 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.22-12 | PDF 可提取中文（NFKC 归一化后命中项目名 / QA 项目 / DUT / LINK） | HTTP | 提取文本 | P0 |
| TC-v5.22-13 | PDF 字体不是 Helvetica（内嵌 CJK 字体） | HTTP | `pdffonts` 等价输出 | P0 |
| TC-v5.22-14 | PDF 页数与内容量可信（不为 1 页空壳） | HTTP | 字节数 + 页数 + 行数 | P1 |
| TC-v5.22-15 | PDF 与 xlsx 的结论、计数、metadata 完全一致 | HTTP | 两份产物对照表 | P0 |
| TC-v5.22-16 | PDF 渲染失败时返回结构化 503，不返回半个文件 | UT | 单测断言 | P1 |

### 2.4 FR-v5.22-04 导出闸（6 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.22-17 | `REVOKED` 报告四格式全部 409，body 带 `state` | HTTP | 四份响应 | P0 |
| TC-v5.22-18 | `ARCHIVED` 报告同样 409 | HTTP | 响应 | P1 |
| TC-v5.22-19 | `DRAFT` 且 `exportable=false`：`external` 400（带 `metadataMissing`）、`internal` 200 | HTTP | 两份响应 | P0 |
| TC-v5.22-20 | `internal` 模式不受 metadata 闸限制（内部件用途就是排查） | HTTP | 响应 200 | P1 |
| TC-v5.22-21 | 闸在导出前生效，不产生半成品文件、不写 `export_record` | UT | 单测 + 库内计数 | P1 |
| TC-v5.22-22 | 报告级与 run 级两条链路的闸口径一致（都判 state 与 metadata） | UT + HTTP | 单测 + 两处响应 | P1 |

### 2.5 FR-v5.22-05 计数口径（5 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.22-23 | 迁移后 `desc run` 含 `skipped_tests` | DB | `SHOW COLUMNS` | P1 |
| TC-v5.22-24 | 回填后抽 20 个 run（pytest 10 + robot 10）四列与 `test_result` 聚合差异 0 | DB | 对照表 | P1 |
| TC-v5.22-25 | 回填脚本连跑两次摘要一致（幂等），且蓄意改坏一行能被修回 | DB + MECH | 两次摘要 `diff` + 破坏前后读数 | P1 |
| TC-v5.22-26 | 新入库的 run 满足 `passed+failed+skipped = total` | HTTP + DB | 打回旧形状 → 入库 → 读数 | P1 |
| TC-v5.22-27 | `DISPATCHED` 不计入 `total`，可单列查出条数（SQL + 接口） | DB + HTTP | 两处读数 | P1 |

### 2.6 FR-v5.22-06 FR-06 四件体验收尾（4 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.22-28 | 新建用例集后右栏自动展开，标题 = 新集合名，无需再点一次 | UI + UT | 截图 + DOM 断言 | P1 |
| TC-v5.22-29 | 空态三个动作各自可跳转；admin 3 个 / **viewer 1 个**（写动作按 `case:write` 藏） | UI + UT + MECH | 截图 + DOM 计数 + 门禁 64 | P1 |
| TC-v5.22-30 | `runCount=0` 的项目卡显示 `—` 且有「发起第一次运行」，点击进发起页 | UI + UT | 截图 + 路由断言 | P1 |
| TC-v5.22-31 | 参数化处显示真实测试项数 | UI + UT | 截图 + DOM 断言 | P1 |

> TC-v5.22-31 **降级执行**：PRD 原期望「61 用例 → 70 测试项」，箭头左半边在库里不存在
> （`run.test_set_id` 与 `test_result.case_id` 全库为 NULL，`case_key` 是 70 个互不相同的编号），
> 大师 2026-11-17 拍板只交真实的右半边（测试项数）。判据记为**降级达成**，理由见 `05-TEST-REPORT.md` §4。

### 2.7 FR-v5.22-07 文档/单测/语言包同源（4 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.22-32 | C5 文档 §2 与 `EXTERNAL_ORDER` 逐项相等（含顺序） | MECH | `check_report_whitelist_doc.py` 输出 | P0 |
| TC-v5.22-33 | 后端 3 + 前端 1 个字段数断言单测全绿（值已更新为 22） | UT | 测试输出 | P0 |
| TC-v5.22-34 | 语言包无 `coverageRate`/`caseCovered`/`trend`/`failureDistribution` 残留键 | MECH | `grep` 输出为空 | P0 |
| TC-v5.22-35 | 新机检进 `gates.sh` 第 68 步；注入漂移判红、还原判绿；删掉该步结构自检判红 | MECH | 门禁日志 + `--self-check` 输出 | P0 |

## 3. NFR 验证方式

| NFR | 判据 | 方式 |
|---|---|---|
| NFR-v5.22-01 | 对外导出路径无明文凭据 | MECH（`check_report_sensitive.py`）+ 抽样 |
| NFR-v5.22-02 | 冻结/导出 p95 不劣于 v5.21 | HTTP 计时 |
| NFR-v5.22-03 | 回填脚本 550 run 全库 ≤ 60 s，事务可回滚 | 实测耗时 + 回滚验证 |
| NFR-v5.22-04 | 字段变更必须文档先行（先改 C5 再改常量） | MECH（第 68 步守护） |
| NFR-v5.22-05 | 前端不新增假数据分支（无数据即空态） | 代码审阅 + UT |
| NFR-v5.22-06 | 迁移只追加，不破坏旧版本回滚 | MECH（`DownScriptGuardTest` + `MigrationFileTest`） |
| NFR-v5.22-07 | 单测覆盖新增分支（闸、重算、字段裁剪） | UT 计数 |
| NFR-v5.22-08 | 门禁 68 步 `FAILS=0` | MECH |

## 4. 证据形态与落点

| 类型 | 落点 |
|---|---|
| HTTP / 导出产物 | `docs/evidence/v5.22.0/w522_*.py` + `*.json` + 样例文件 |
| DB 读数 | `docs/evidence/v5.22.0/w522_probe_counts.sh` + `w522_counts.log` |
| 单测 | `mvn test` / `npx vitest run` 输出；结果写入 `05-TEST-REPORT.md` |
| 机检 | `scripts/gates.sh` 全量日志 |

## 5. 修订记录

| 日期 | 变更 | 依据 |
|---|---|---|
| 2026-11-17 | 首版：35 条 TC 与判据 1:1；TC-31 按大师拍板降级执行 | `PRD.md` + `DEV-PLAN.md` + D-22-01~04 |
| 2026-11-17 | **TC-29 口径修正**：原文「admin 3 / viewer 2」被门禁 64 推翻——「导入用例」是写动作，viewer 必须看不到。改为「admin 3 / viewer 1」，证据方式补 MECH（门禁 64 role-buttons） | 门禁 64 判红 → 修 `2bd72ff` 后 9 组 FAILS=0，见 `05-TEST-REPORT.md` §5 |
