# v5.14.0 测试报告（05-TEST-REPORT）

> 流程位置：⑥ 测试执行 → 结果落定。数据源：`TEST-PLAN.md` 的 55 条 TC 与 `DEV-PLAN.md` 七波执行状态。
> **不新造用例**：TC 编号与 TEST-PLAN 一一对应，逐条写结论与证据形态。
> 结论口径：`PASS` = 有可复现证据；`PARTIAL` = 只覆盖部分证据形态（写明缺口）；`FAIL` = 未达成。
> **本版不把「单测绿 / 构建绿」当放行理由**；真实链路项在 §3 / §6 单列。
> 证据：`w7-http-probe-first-run.txt`（首轮 39 条 / 3 判红）、`w7-http-probe.txt`（43/43）、
> `w7-http-probe-extra.txt`（14/14）、`w7-template-probe.txt`（6/6），运行期 HEAD=`b663345`。
> 证据代号：P1 主探针、P2 补充探针、P3 模板分级探针、W1~W6 各波 wN-verify.txt、FE 前端 spec、UT 后端单测。

## 1. 读数总览

| 项 | 读数 | 证据 |
|---|---|---|
| 部署自校验 | **PASS=4 FAIL=0 SKIP=0**，运行期 build-info commit=b663345 与 HEAD 一致 | `bash scripts/deploy.sh` 两次（W7 修复前后各一次） |
| 真实 HTTP + DB 首轮探针 | **PASS=36 FAIL=3 TOTAL=39**，判红不隐去 | `w7-http-probe-first-run.txt` |
| 真实 HTTP + DB 主探针 | **PASS=43 FAIL=0 TOTAL=43** | `w7-http-probe.txt` |
| 真实 HTTP + DB 补充探针 | **PASS=14 FAIL=0 PARTIAL=0 TOTAL=14** | `w7-http-probe-extra.txt` |
| 模板四级回退真实探针 | **PASS=6 FAIL=0 TOTAL=6** | `w7-template-probe.txt` |
| W7 修复相关单测 | **37 例全绿**（Kernel 7 / EhtPytest 10 / TemplateService 15 / Snapshot 5） | surefire XML；较修复前 +2 例 |
| 后端全量 | `mvn -q test` 无 ERROR（W5 / W6 各一次全量；W7 修复后局部回归 37 例） | `/tmp/w513_backend_test.log` 同口径；W7 局部日志 |
| 前端全量 | **150 文件 / 1175 例**，首轮 1 例判红（旧 spec 与四格式实现不同步），修复后复跑 | `docs/evidence/gates/logs/02-frontend_test.log` |
| 类型检查 | `npx vue-tsc --noEmit` **0 error** | 门禁 03-type_check PASS |
| 语言包机检 | **5/5 PASS**；zh/en 各 3129 键，源码硬编码中文 0 处 | `tools/check_i18n.py` |
| 接口契约 | **459 endpoints / 377 paths**，`--check` PASS | `tools/gen_api_contract.py` |
| 数据库体检 | `V11_73` / `V11_74` 入 history；业务表数 109；`db-audit.sh` PASS | W1 / W4 / W6 证据 |
| 10MB 快照预览 | **1.570s ≤ 3s**（服务端 GET）；1MB 0.234s、4MB 0.732s | `w7-http-probe.txt` §W64-18 性能分级采样 |
| 全量门禁 | **GATES_RESULT FAILS=0 UNJUDGED=0** | `scripts/gates.sh`；`docs/evidence/gates/logs/` |
| 发布文档机检 | `check_release_docs.py v5.14.0` PASS（FR=11 / TC=55 逐条有结论） | §7 |
| 表格机检 | `check_md_tables.py` 对六份产物全 PASS | §7 |

## 2. 逐条结论（TC-v5.14-01 ~ 55）

| TC | 结论 | 证据 / 说明 |
|---|---|---|
| TC-v5.14-01 | PASS | P2 机检：frontend/src 内「对外报告」grep 0 命中；W1 证据同值 |
| TC-v5.14-02 | PARTIAL | i18n 菜单与页面标题三处口径由 check_i18n 5/5 钉住；3 张真视口截图未取 |
| TC-v5.14-03 | PARTIAL | zh/en 各 3129 键相等、无同值漏译；英文真视口截图未取 |
| TC-v5.14-04 | PARTIAL | 面包屑与浏览器标题跟随页面标题由代码事实佐证；真视口未取 |
| TC-v5.14-05 | PASS | P2 在 v5.13 收口 commit efbcf37 上 git grep 命中 zh-CN.ts:396 对外报告 |
| TC-v5.14-06 | PARTIAL | V11_73 落列成功、DB 543 行 NULL；R1/R2/R3 真实入库三样本未构造；UT SuiteCountTest 3 样本证明同源算法 |
| TC-v5.14-07 | PARTIAL | P1 列表与详情 API 均带 suiteCount、report-digest 200；三处列表真视口列截图未取 |
| TC-v5.14-08 | PARTIAL | P1 suiteCount=null 且 DB NULL_rows=543；「—」真视口未取 |
| TC-v5.14-09 | PASS | CaseKeyPathTest 与 SuiteCountTest 同源切分；W1 6 例全绿 |
| TC-v5.14-10 | PARTIAL | 两内核真实入库样本未构造；W1 单测覆盖 RF 多套件与 pytest 参数化 nodeid 混合 |
| TC-v5.14-11 | PARTIAL | W2 focusSuite 与 SuiteTree.highlight 由 ReportsView.spec 7 例覆盖；真视口高亮截图未取 |
| TC-v5.14-12 | PARTIAL | 目标不在首屏时自动滚动的真视口前后截图未取 |
| TC-v5.14-13 | PARTIAL | 非法 suite 参数的可解释提示由代码与空态实现；HTTP 未单独取证 |
| TC-v5.14-14 | PARTIAL | 新标签粘贴深链复现的真视口截图未取 |
| TC-v5.14-15 | PASS | 口径修订：W2 为测试报告详情新增 1 条路由，草案「未新增路由」被推翻；套件数深链复用既有 report-detail 路由，未为深链新增条目，登记于 DEV-PLAN §14 |
| TC-v5.14-16 | PARTIAL | P1 快照 1976 字节、hash 82e410cd0ea9 真实返回；真视口 iframe 截图未取 |
| TC-v5.14-17 | PARTIAL | R6 无快照草稿样本未构造；TestReportDetailView.spec 空态断言覆盖 |
| TC-v5.14-18 | PARTIAL | 草稿生成后再预览的真视口截图未取 |
| TC-v5.14-19 | PARTIAL | P1 三段锚点 HTML 与清洗器保留断言通过；左栏滚动联动真视口未取 |
| TC-v5.14-20 | PASS | FE 断言 iframe sandbox 为空；P1 清洗后无 script、on 事件与危险协议 |
| TC-v5.14-21 | PARTIAL | 列表标题按钮与详情路由由 FE 覆盖；双向可达真视口未取 |
| TC-v5.14-22 | PASS | P2 历史报告 20 快照仍是平铺 dl、hash 82e410cd 不变、无三段式 section |
| TC-v5.14-23 | PARTIAL | P1 四格式真实 HTTP 200 且带魔法头（PDF %PDF、DOCX 与 XLSX PK）；未用外部工具验证 PDF 页数与 DOCX 可解析 |
| TC-v5.14-24 | PARTIAL | Content-Disposition 文件名规则由源码与 P1 响应头佐证；实体文件名清单未取 |
| TC-v5.14-25 | PARTIAL | 导出结论层与预览逐字比对未做 |
| TC-v5.14-26 | PARTIAL | 无快照时导出提示的真视口截图未取 |
| TC-v5.14-27 | PASS | P2 grep exportBlob 命中 10 行；ReportExportBar 是四格式导出的真实消费者 |
| TC-v5.14-28 | PARTIAL | 打印页真视口截图未取 |
| TC-v5.14-29 | PASS | P1 exportable run 首次 GET /api/print 200、重放 400「打印链接无效或已过期」 |
| TC-v5.14-30 | PARTIAL | 打印页无导航按钮的真视口截图未取 |
| TC-v5.14-31 | PASS | P2 qa-e2e-viewer 取 print-token 返回 403（真实非管理员会话） |
| TC-v5.14-32 | PASS | P1 PUT 写 report.auto_generate=true 并回读成功、非 boolean 值 400 |
| TC-v5.14-33 | PARTIAL | 开启后跑一次真实执行并观察「自动报告」前缀未做 |
| TC-v5.14-34 | PARTIAL | 关闭后跑一次执行并比对报告计数未做 |
| TC-v5.14-35 | PASS | P2 qa-e2e-viewer 改项目设置返回 403；W3 白名单单测 3 例 |
| TC-v5.14-36 | PASS | P3 pytest 真样本 TemplateRef = EHT-PYTEST、9 字段、kernel=pytest、PLATFORM_KERNEL_MATCH |
| TC-v5.14-37 | PASS | P3 robot 真样本 TemplateRef = DEFAULT、21 字段、kernel=robot、PLATFORM_KERNEL_MATCH |
| TC-v5.14-38 | PASS | P3 历史报告 source=PLATFORM_FALLBACK；P1 TemplateRef 带 kernel 与 templateSource；界面提示由详情页 FE 覆盖 |
| TC-v5.14-39 | PASS | P1 fields(robot)=21、fields(pytest)=26 且含 5 个 EF 专属键、非法 kernel 400；ReportTemplateKernelTest 7 例 |
| TC-v5.14-40 | PASS | P2 历史报告可正常打开、快照平铺、hash 不变 |
| TC-v5.14-41 | PASS | P2 DB report_template 中 kernel IS NULL 行数 = 0 |
| TC-v5.14-42 | PARTIAL | P1 三个 section id 与目录 href 锚点真实 HTTP 断言；真视口锚点点击未取 |
| TC-v5.14-43 | PASS | P1 真实 70 用例四态之和 = 70；W5 两个真实库样本同断言 |
| TC-v5.14-44 | PASS | P1 20 条未定项 nodeRef 全非空（IM-FUN-020 等）；空 case_key 兜底 case# 已由 W5 修复 |
| TC-v5.14-45 | PARTIAL | P1 50 条证据路径全部相对化、无绝对部署目录；点击 3 条验 2 条存在未做 |
| TC-v5.14-46 | PASS | P2 RF 真样本 kernel=robot、快照写「不适用」且不出现「判据行原文：」 |
| TC-v5.14-47 | PASS | P1 真实差异读数 hasBaseline=true newFail=4；E1 两次真实执行截图未取 |
| TC-v5.14-48 | PASS | P2 3 条临时 run 批量删 deleted=3 failed=0、DB 软删 3、影响面回报含两类计数 |
| TC-v5.14-49 | PASS | P1 冻结测试报告 DELETE 409「已冻结，请先解冻」；W6 前端冻结行不给勾选框并给同句提示 |
| TC-v5.14-50 | PARTIAL | zh/en 两类删除文案由 i18n 与 FE 钉住（A-27 口径修订）；真视口截图未取 |
| TC-v5.14-51 | PASS | P2 软删后列表响应不再含 3 条临时 run，无空页 |
| TC-v5.14-52 | PASS | P1 copy 真实 HTTP 生成 EHT-PYTEST-副本、platform=false；DB 副本 9 字段 |
| TC-v5.14-53 | PASS | W6 单测逐字段断言键、顺序、标题与内核；P1 copyFields=9 等于 srcFields=9 |
| TC-v5.14-54 | PASS | P2 PUT 把副本改名为 W7-EXTRA-COPY 成功，平台源 9 字段与名称 EHT-PYTEST 均不变 |
| TC-v5.14-55 | PASS | P2 qa-e2e-viewer 调用 copy 返回 403；W6 DenyingAccess 单测断言无 EXECUTION:UPDATE 不落库 |

**合计**：PASS=28 / PARTIAL=27 / FAIL=0 / TOTAL=55。
`PARTIAL` 不是「差不多通过」，而是明确登记缺口；所有缺口在 §5 汇总，不冒充达成。

## 3. W7 真实链路抓到的真缺陷（首轮探针判红，不是单测发现）

| # | 缺陷 | 首轮判红读数 | 修复 | 复验 |
|---|---|---|---|---|
| 1 | `GET /api/report-templates/fields?kernel=bogus` 静默回落 21 项 RF 字段（读路径 fail-open），而写路径 create/update 严格校验 | HTTP=200 | `ReportTemplateService.selectableKernel` 作读写共用校验，非法值 400；`ReportTemplateKernelTest` +1 例 | HTTP=400；7 例 Kernel 单测全绿 |
| 2 | 三段式附录按 `MetadataGate.REQUIRED` 十项渲染，`sectionsOf` 却喂 `safeMetadata()` 的对外五键子集，另五项永远显示「未提供」 | sections metadata keys=0 | 新增 `ReportViewRenderer.safeSectionMetadata()` 按十项取真值且同样脱敏；旧对外五键口径零改动；`EhtPytestReportTest` +1 例 | sections metadata keys=1（样本只有 RUN_ID）、required=10、附录对缺项写「未提供」 |
| 3 | 首轮探针拿 `metadata_missing=STARTED_AT` 且 `exportable=0` 的 run 测 external 打印 token，撞上既有 metadata 门禁 | HTTP=400 keys=metadataMissing | **不是产品缺陷，是探针选错样本**：改用全库真实 `exportable=1` 的 run 测成功链路，并把原 400 改写成一条真实防线断言 | exportable run 200 + token 一次性；缺失 run 400 且点名 STARTED_AT；internal 200 |

**门禁另抓到 1 个旧测试与实现不同步**：`frontend/src/config/testReports.spec.ts` 仍断言
`exportFileName(9, 'xlsx')` 回退为 `report-9.html`，而 W3 已把导出扩成四格式。局部 vitest 未覆盖该
文件，全量门禁 02-frontend_test 判红。修复：把 xlsx 移到「已知格式」断言，未知格式样本改为
`markdown`；复跑全绿。这是**实现变更未同步旧断言**，不是实现回退。

## 4. 与 v5.13.0 的读数对比

| 项 | v5.13.0 收口 | v5.14.0 收口 | 变化 |
|---|---|---|---|
| 接口契约 | 456 / 374 | 459 / 377 | +3 端点 / +3 path（snapshot、sections、copy） |
| 迁移 | 零迁移（最新 11.68） | 11.73 / 11.74 | +2 追加迁移，各配 down |
| i18n 键 | 3097 | 3129 | +32 |
| 前端例数 | 147 文件 / 1160 例 | 150 文件 / 1175 例 | +3 文件 / +15 例 |
| 真实探针 | 43 条（主 32 + 补充 11） | 63 条（主 43 + 补充 14 + 模板 6） | +20 条 |
| W7 真缺陷 | 4 个（画像平台态、FAILED 分类、表格壳、探针口径） | 3 个（kernel fail-open、三段式十项、旧 spec 不同步） | 本版探针口径修正也留痕 |
| 全量门禁 | FAILS=0 UNJUDGED=0 | GATES_RESULT FAILS=0 UNJUDGED=0 | 持平 |

## 5. 未达成 / 降级项登记（不冒充达成）

| 类别 | 涉及 TC | 缺口与原因 | 后续建议 |
|---|---|---|---|
| 真视口截图未取 | 02 03 04 07 08 11 12 13 14 16 17 18 19 21 26 28 30 42 45 50 | 本轮真实链路以 HTTP / DB / 导出件 / DOM 结构断言为主，未驱动 Chrome 逐页截图；门禁 13 步有浏览器回归但不是逐 TC 截图 | 人工复核时用 CDP 逐页补图，或在隔离环境跑 UI 全链路 |
| 真实库三样本入库未构造 | 06 10 / NFR-07 | `POST /reports/{runId}/parse` 会覆盖真实 run 的 test_result、pytest_case_step 等多表；本轮选择不破坏真实数据，只用 UT 3 样本钉住同源算法 | 在隔离库或夹具 run 上构造 R1/R2/R3，重跑 TC-06/10 |
| 导出件外部打开与 50MB 样本 | 23 24 25 / NFR-05 | 四格式拿到真实字节与魔法头，但未用 PDF/DOCX 解析器打开；≥50MB 快照未构造（会显著抬高 JVM 堆压），实现为全量 byte[] 非流式 | 补 PDF 页数与 DOCX 解析；评估流式导出改造后再测 50MB |
| 自动生成真实执行样本 | 33 34 | 写入链与校验已由真实 HTTP 验证，但「开启后跑一次执行」需要真实执行器链路，未在 W7 触发 | 5.16 运维链路贯通后补真实执行回归 |
| 两次真实执行差异截图 | 47（截图部分） | `hasBaseline=true newFail=4` 是真实 DB 差异读数，截图未取 | 人工复核时补差异面板截图 |
| 真视口首屏渲染耗时 | NFR-04 | 测得的是服务端 GET 1.570s，不是浏览器首屏渲染 | 用 CDP Performance 采样首屏 |

> **降级授权**：以上 PARTIAL 全部如实登记，不放宽判据、不把 UT 写成放行理由；
> 按大师「按你的建议自动推进、全部完成再找我确认」的既定授权处理，最终人工复核时请大师逐项确认。
> **P0 判据**：FR-01 / 02 / 03 / 04 / 08 中，FR-02（套件数真实入库三样本）与 FR-03（深链真视口）
> 未拿到 UI 或 DB 一级证据的部分，明确记为未完全达成，不计入放行。

## 6. 性能与真实证据（W64-18）

| 档位 | 服务端 GET 耗时 | 响应字节 | RSS 增量 |
|---|---|---|---|
| 1MB snapshot_html | 0.234s | 1048587 | 616 KB |
| 4MB snapshot_html | 0.732s | 4194315 | 148 KB |
| 10MB snapshot_html | **1.570s（判据 ≤3s 达成）** | 10485771 | 12544 KB |
| 10MB 快照上导出 html | 0.358s | 772 | 49516 KB |

**判据 ③ 导出内存曲线说明**：`GET /api/test-reports/{id}/export` 走的是字段视图
（`render()` + 模板裁剪）而不是把 `snapshot_html` 整段拼进导出件，因此 10MB 快照的导出件仍只有
772 字节，不随快照大小线性增长；但 `ReportExportService` 的实现是把结果一次构造成 `byte[]` 返回，
**不是流式或分块**。RSS 增量含 JVM GC 波动，不能当作精确内存曲线。≥50MB 样本未构造，
NFR-05 如实标 PARTIAL，不声称「不整读内存」已达成。

## 7. 门禁与收口

| 项 | 判据 | 结果 |
|---|---|---|
| 单测 | 后端全量无 Failure / Error；前端例数 ≥ 上一版 | 达成（后端全量无 ERROR；前端 150 文件 / 1175 例 > 147 / 1160） |
| 类型 | `npx vue-tsc --noEmit` 无输出 | 达成 |
| 门禁 | `GATES_RESULT FAILS=0 UNJUDGED=0` | **GATES_RESULT FAILS=0 UNJUDGED=0** |
| 发布文档 | `check_release_docs.py v5.14.0` PASS | 六份齐全 + FR=11 / TC=55 逐条有结论 |
| 表格 | `check_md_tables.py` 对六份产物全 PASS | 列数一致 |
| 契约 | `gen_api_contract.py --check` PASS | 459 / 377 |
| 部署 | `deploy.sh --verify` 四判据 | PASS=4 FAIL=0 SKIP=0，jar commit 与 HEAD 一致 |
| 真实链路 | 两份不可降级的报告口径 | 冻结件删除 409 点名；metadata 缺失件对外打印 400 点名缺项 |
| 临时样本 | 全部还原 | 三份探针的 finally 段：测试报告 / 模板副本 / 项目设置 / 3 条 run / 大快照清零 |

## 8. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：55 条 TC 逐条结论；28 PASS / 27 PARTIAL / 0 FAIL；登记 W7 抓到的 3 个真缺陷 | 四轮真实探针 + 全量单测 + 部署自校验 |
| 2026-11-15 | W7 收口：门禁首轮 02-frontend_test 判红（旧 spec 与四格式实现不同步），修复后复跑；门禁读数落定 | `02-frontend_test.log` + `scripts/gates.sh` 第二轮全量日志 |
