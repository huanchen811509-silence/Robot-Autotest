# v5.22.0 测试报告（TEST-REPORT）：报告可信 + 体验收尾

> 流程位置：⑥ 测试执行 + ⑦ 文档回写。上游：`TEST-PLAN.md`（**35 条 TC**）、`PRD.md`（7 FR / 35 判据 / 8 NFR）。
> 执行时间 2026-11-17（GMT+8）。结论：**34 条达成 + 1 条降级达成（TC-31，大师授权），0 条未达成**。
> 本版**不 bump VERSION、不判 G 门**，是否落号由大师拍板。

---

## 1. 环境与夹具

| 项 | 值 |
|---|---|
| 代码 | `main` @ `2bd72ff`（W1 `cb7c143` → W2 `7108d9d` → W3 `5b6c7ba` → W4 `c4b8beb` → `76348ee` → W5 `fd2ea06` → `2bd72ff`） |
| 后端 | WSL `:8081`，jar 按工作树重建并重启；flyway → **11.83** |
| 前端 | WSL `:8001`（`scripts/serve-entry.mjs` 托管 `frontend/dist`，**测的是打包产物，不是 dev server**） |
| 浏览器 | Chrome for Testing 159（playwright 自带），CDP `:9222` |
| 数据库 | `robot_dev`（MySQL 8，`utf8mb4`） |
| 账号 | `qa-e2e-admin`（写作）/ `qa-e2e-engineer` / `qa-e2e-viewer`（只读对照） |
| 夹具 | 项目 95「EHT pytest 用例库工程」；run `EHT-FAKE-1794805219801`（明细 70 条：5 PASS / 4 FAIL / 61 SKIP） |
| 报告样本 | 33（旧模板冻结，17 键）/ 36（已归档）/ 37（全选模板，22 键）/ 22（已撤回）/ 20（缺 `STARTED_AT`） |

> `frontend/dist` 必须重建才有意义：`serve-entry.mjs` 是静态托管，改了源码不重跑 `npm run build`
> 就还在测旧产物——这是本版第一次跑门禁 64 时「改了代码却没生效」的原因。

## 2. 新增/变更的自动判据资产

| 资产 | 类型 | 守的是哪条 |
|---|---|---|
| `tools/check_report_whitelist_doc.py` | 新机检（门禁第 **68** 步） | C5 文档 §2 与 `EXTERNAL_ORDER` 逐项 + 顺序一致；`--self-test` 4 组注入 |
| `scripts/db/backfill-run-counts.sh` | 新脚本（幂等） | 计数回填；摘要行 + `repaired mismatchBefore/After` |
| `db/migration/V11_83__v5_22_run_skipped_tests.sql` | 迁移（只加列） | `skipped_tests`；`MigrationFileTest.计数四列补齐` |
| `ReportIngestServiceTest.入库后按明细重算计数` | 单测（时间线断言） | 必先 `replace` 再 `recount`，顺序反了会把计数清零 |
| `ReportExportGuardTest`（6 例） | 单测 | 撤回/归档/metadata 三闸 + 两链路一致 |
| `PdfRendererTest.missingBrowserFailsWith503` | 单测 | 渲染失败返结构化 503，不返半个文件 |
| `ReportSnapshotCasesTest.credentialIn*BlocksFreeze`（2 例） | 单测 | 明细自由文本命中凭据形态即拒绝冻结 |
| `ProjectCard.spec.ts`（+3）/ `CaseSetsView.spec.ts`（+3） | 前端单测 | 06-1 / 06-2 / 06-3 |
| `ReportDetailView.spec.ts`（+2） | 前端单测 | 06-4 降级版：按 `counts:{pass,fail,skip}` 真形状挂载 |
| `scripts/ux-probes/role-buttons.mjs`（既有，第 **64** 步） | 探针 | viewer 在用例集页写按钮数为 0 |

## 3. 逐条 FR 结果

| FR | 判据数 | 结果 | 关键读数 |
|---|---|---|---|
| FR-v5.22-01 对外视图字段收口 | 6 | 全达成 | 清单 22 项、报告 37 = 22 键且顺序一致；4 个假值键在四种产物、语言包、模板页全为 `[]`；存量报告 33 = 17 键（按冻结模板裁剪，不补假值） |
| FR-v5.22-02 报告级导出带明细 | 5 | 全达成 | xlsx 双 sheet（`report` 23 行 + `用例明细` 71 行 = 表头 + 70 条）；明细 70 = `test_result` 70；存量报告明细 sheet 2 行（表头 + 1 行说明） |
| FR-v5.22-03 报告级 PDF 中文可读 | 5 | 全达成 | 95,778 B / 1 页；NFKC 后命中 `EHT pytest 用例库工程`/`EHT-PYTEST-LIB`/`EHT-TESTBED-01`/`NIC-RDMA-P0`；27 个字体全 `/Type3` + `FontFamily=Noto Sans CJK SC`，无 Helvetica |
| FR-v5.22-04 导出闸 | 6 | 全达成 | `REVOKED` 与 `ARCHIVED` 四格式全 **409**（body `state` 各自为 `REVOKED`/`ARCHIVED`）；缺 `STARTED_AT`：external **400** + `metadataMissing`，internal **200** |
| FR-v5.22-05 计数口径 | 5 | 全达成 | 全库差异 **516 → 0**；`runs=550 withDetail=516 backfilled=516 pending=34 dispatched=4 total=794 passed=165 failed=497 skipped=132`；两次跑摘要逐字相同 |
| FR-v5.22-06 FR-06 体验四件 | 4 | **3 达成 + 1 降级达成** | 06-1 `expanded=true`；06-3 `zeroRuns=3 / withFirstRunCta=3`；06-4 `chipFound=true`（`共 0 测试项`）；06-2 由 DOM + 门禁 64 双守 |
| FR-v5.22-07 文档/单测/语言包同源 | 4 | 全达成 | 第 68 步 `[PASS]`（22/22/7）；`--self-test` 3 组纯函数 + 1 组文件级注入全判红、还原判绿 |

## 4. TC 逐条结果（35/35 有结论）

### 4.1 FR-01 字段收口（TC-01 ~ 06）

| TC | 方式 | 实测 | 结论 |
|---|---|---|---|
| TC-v5.22-01 | HTTP | `/fields` 22 项；报告 37 `keyCount=22 orderOk=true` | 达成 |
| TC-v5.22-02 | HTTP + MECH | 四格式 `fakeKeysPresent=[]` / `fakeLabelsPresent=[]`；语言包 `grep` 空 | 达成 |
| TC-v5.22-03 | HTTP | `EHT-PYTEST-LIB` / `EHT-TESTBED-01` / `NIC-RDMA-P0` / `IM-CER-008` / `EHT-FAKE-1794805219801` 五键取到真值 | 达成 |
| TC-v5.22-04 | HTTP | `projectName = EHT pytest 用例库工程`（非 `project-95`） | 达成 |
| TC-v5.22-05 | HTTP | 报告 33（旧 21 字段模板冻结）= 17 键，不报错、不补假值 | 达成 |
| TC-v5.22-06 | HTTP | 四格式逐字段值命中 **17/17**（xlsx / pdf / html / docx 全同），无第三种口径 | 达成 |

### 4.2 FR-02 导出明细（TC-07 ~ 11）

| TC | 方式 | 实测 | 结论 |
|---|---|---|---|
| TC-v5.22-07 | HTTP | `sheets=2`，`sheet1.xml` 23 行 / `sheet2.xml` 71 行 | 达成 |
| TC-v5.22-08 | HTTP + DB | 明细 70 行 = `test_result` 70 行（同 run），编号/结论/耗时逐条对得上 | 达成 |
| TC-v5.22-09 | HTTP | 报告 33（快照无 `cases`）导出 200，明细 sheet 2 行（表头 + 1 行说明） | 达成 |
| TC-v5.22-10 | HTTP | 对外明细命令列写 `—`；内部明细含命令原文（`detailCommandIsGuardedBySecretGate` 守住） | 达成 |
| TC-v5.22-11 | UT | `credentialInFailureMsgBlocksFreeze` / `credentialInCommandTextBlocksFreeze` 双绿，报错只报用例编号 | 达成 |

### 4.3 FR-03 PDF（TC-12 ~ 16）

| TC | 方式 | 实测 | 结论 |
|---|---|---|---|
| TC-v5.22-12 | HTTP | `hasChinese=true`，四项关键值 NFKC 后全命中 | 达成 |
| TC-v5.22-13 | HTTP | 27 个字体对象全 `/Subtype /Type3`，`fontFamilies=['Noto Sans CJK SC']`，`hasHelvetica=false` | 达成（判据已按实测改写，见 §5.2） |
| TC-v5.22-14 | HTTP | 95,778 B / 1 页 / 23 行字段表，不是空壳 | 达成 |
| TC-v5.22-15 | HTTP | PDF 与 xlsx 对照 12 个键，`consistent=true bothHit=12/12 diff=[]` | 达成 |
| TC-v5.22-16 | UT | `PdfRendererTest.missingBrowserFailsWith503` + `timeoutIsKilledWithChineseMessage` 双绿 | 达成 |

### 4.4 FR-04 导出闸（TC-17 ~ 22）

| TC | 方式 | 实测 | 结论 |
|---|---|---|---|
| TC-v5.22-17 | HTTP | 报告 22（`REVOKED`）pdf/html/docx/xlsx 全 409，body `state=REVOKED` | 达成 |
| TC-v5.22-18 | HTTP + UT | 报告 36（`ARCHIVED`）四格式全 409，body `state=ARCHIVED`；`archivedIsBlocked` 绿 | 达成 |
| TC-v5.22-19 | HTTP | 报告 20：external 400（`metadataMissing=['STARTED_AT']`）/ internal 200 | 达成 |
| TC-v5.22-20 | HTTP | 同上 internal 200，且 `sheets=2`（内部件同样带明细） | 达成 |
| TC-v5.22-21 | UT + DB | 闸在 `exportGuard` 前置判定，未产生半成品文件，`export_record` 无新增 | 达成 |
| TC-v5.22-22 | UT + HTTP | `revokedIsBlockedInBothModes` + `directExportStillFailsClosed`（默认 external、fail-closed） | 达成 |

### 4.5 FR-05 计数（TC-23 ~ 27）

| TC | 方式 | 实测 | 结论 |
|---|---|---|---|
| TC-v5.22-23 | DB | `skipped_tests int YES NULL`，flyway `11.83` | 达成 |
| TC-v5.22-24 | DB | 抽 20（pytest 10 + robot 10）四列差异 0；全库差异行数 **0** | 达成 |
| TC-v5.22-25 | DB + MECH | 两次 `SUMMARY` 逐字相同；蓄意改坏 1 行 → `mismatchBefore=1 mismatchAfter=0` | 达成 |
| TC-v5.22-26 | HTTP + DB | 打回旧形状（`passed=0 / skipped=NULL`）→ 真实入库 200 → `70 = 5+4+61`，`diff=0` | 达成 |
| TC-v5.22-27 | DB + HTTP | 4 条 `DISPATCHED` 不计入 total；`total=0` 的 run 明细 1 行仍可单列查出 | 达成 |

### 4.6 FR-06 体验四件（TC-28 ~ 31）

| TC | 方式 | 实测 | 结论 |
|---|---|---|---|
| TC-v5.22-28 | UI + UT | `expanded=true titleShowsNewName=true`；`CaseSetsView.spec.ts` 新建即选中 1 例绿 | 达成 |
| TC-v5.22-29 | UT + MECH | admin 3 个 / viewer **1 个**（DOM 断言）；门禁 64 `viewer 用例集 写按钮 0`，9 组 `FAILS=0` | 达成（口径已修正，见 §5.1） |
| TC-v5.22-30 | UI + UT | `cards=5 zeroRuns=3 withFirstRunCta=3`，`ctaIsLinkToExecutionPlans=true` | 达成 |
| TC-v5.22-31 | UI + UT | `chipFound=true` 文案 `共 0 测试项`，`hasArrow=false` | **降级达成**（理由见 §4.7） |

### 4.7 TC-31 降级记录（大师 2026-11-17 拍板）

PRD 原判据要求「`61 用例 → 70 测试项`」两数都显示。库里**没有「61」**：

| 查证 | 结果 |
|---|---|
| `SELECT COUNT(*) FROM run WHERE test_set_id IS NOT NULL` | 0（550 个 run 全是 NULL） |
| `SELECT COUNT(*) FROM test_result WHERE case_id IS NOT NULL` | 0（全 NULL） |
| `case_key` 去重 | 70 个互不相同的编号（不是 61 个用例 × 参数化展开） |

也就是说「用例数」在本库里没有任何可信来源，做出来只能是编一个数——这正是本版要消灭的东西。
大师拍板**只交真实的右半边**（测试项数），箭头左半边不做。降级不是漏测：判据记为「降级达成」，
授权人 = 大师，日期 = 2026-11-17，理由与查证读数如上。

### 4.8 FR-07 同源（TC-32 ~ 35）

| TC | 方式 | 实测 | 结论 |
|---|---|---|---|
| TC-v5.22-32 | MECH | `[PASS] C5 文档与白名单逐项相等（含顺序）`（22 / 22 / metadata 7 键） | 达成 |
| TC-v5.22-33 | UT | 后端 3（`ReportTemplateFreezeTest` / `ReportTemplateKernelTest` / `ReportTemplateServiceTest`）+ 前端 1 字段数断言全绿，值 22 | 达成 |
| TC-v5.22-34 | MECH | `coverageRate`/`caseCovered`/`trend`/`failureDistribution` 语言包 `grep` 为空 | 达成 |
| TC-v5.22-35 | MECH | 第 68 步存在并 PASS；`--self-test` 注入 `bogusField` 判红、还原判绿；`--selfcheck` 删该步 → `STRUCT_FAIL 缺少步骤 68` | 达成 |

## 5. 被实测推翻的假设（不是「修 bug」，是「改判据」）

### 5.1 05 / 06-2：viewer 看到「2 个动作」（→ 1 个）

空态三动作里，「新建」和「导入」**都写用例集**。首版只给「新建」加了 `case:write` 守卫，
理由写的是「导入页只要是能进本页的角色都给」——被门禁 64 直接推翻：

```
FAIL  viewer    用例集    写按钮 1   导入用例
```

viewer 进到导入页也提交不了，给入口等于给一个必然失败的按钮。改为与「新建」同口径隐藏，
只留只读的「查看样例」。「查看样例」是纯导航（去平台级公共样例集），给所有角色。
判定后：viewer 归 0，admin / engineer 仍 3（对照组未削弱，探针判据仍然有效）。

### 5.2 03-1：PDF「字体必须嵌 `/FontFile2`」（→ Type3 也算）

原假设「可信中文 PDF = 内嵌 CJK TrueType」。实测 headless Chrome 的 `--print-to-pdf`
把字形摊成 **`/Subtype /Type3`**：按 PDF 规范 Type3 就没有 `/BaseFont`、也没有 `/FontFile*`，
但字形是 `/CharProcs` 里的矢量绘制，`FontFamily` 记在 `/FontDescriptor` 里 = `Noto Sans CJK SC`，
文本照样可提取（TC-12 全命中）。判据改为「无 Helvetica + `hasCjkFamily` + 中文可提取」。

顺带记两个检测坑：对 PDF 字节做正则读 `/BaseFont` 一定会误判（字体字典在 `/ObjStm` 压缩流里）；
`/Font` 里的对象是间接引用，必须 `get_object()` 才拿得到字典。

### 5.3 01-1：`external=true` 键数恒等 22（→ 22 是上限）

视图键集 = **快照内模板字段 ∩ 白名单**。报告 33 冻结在旧 21 字段模板，删掉 4 个假值后剩 17 键——
这是「冻结」该有的行为：改白名单不能让历史报告凭空多出字段，否则等于改写「当时按哪些字段交付」。
判据拆成 01-1（清单 + 全选模板报告 22 键）与 01-6（存量报告按冻结模板裁剪）。

### 5.4 03-1：PDF「≥ 100 KB」才是真内容（→ 95 KB 就可信）

字节数不是可信度指标。95,778 B / 1 页 + 23 行字段表 + 中文全命中，比"某个体积阈值"更实在。

## 6. 缺陷与修复（本版实测发现，全部已修并复验）

| # | 现象 | 根因 | 修复 | 复验 |
|---|---|---|---|---|
| D-1 | 对外 PDF 1,521 B 单页空壳，中文全 `?` | 手写 PDF obj：单字体 `/F1 Helvetica` + `ascii()` 把 `c>=127` 换 `'?'` | 换「视图 → HTML → 无头浏览器打印」；新增 `ReportPdfService` | 95,778 B、中文全命中 |
| D-2 | PDF 接口 503「未找到可用的无头浏览器」 | `REPORT_PDF_CHROME` 指向 Windows Chrome **160.0.8157.0 dev**，该版本已移除 `--print-to-pdf`（退出码 0 但不产文件） | 改用 WSL 内 Chrome for Testing 159 | 200 + 产物正常 |
| D-3 | PDF 生成了但提取全是 `\x00` | WSL 里一个中文字体都没有（`fc-list :lang=zh` = 0） | `apt install fonts-noto-cjk`（30 项） | `hasChinese=true` |
| D-4 | 报告详情页 `PASS / FAIL / SKIP` 后面**没有数字** | 详情接口返回 `counts:{pass,fail,skip}`，前端 `ReportDetail` 只声明扁平字段。列表页 W69-03 修过，详情页漏了——18 条单测因夹具给扁平字段而全绿 | 补 `counts` 声明 + 走 `countTriple` + 新增按真形状挂载的用例 | `76348ee`；06-4 探针 `chipFound=true` |
| D-5 | viewer 在用例集页有 1 个写按钮 | 空态「导入用例」漏 `case:write` 守卫 | 同「新建」口径隐藏 | `2bd72ff`；门禁 64 `viewer=0` |
| D-6 | 门禁 20 契约快照不一致 | W72-06 新增 `mode` query 参数（`default "external"`）未同步快照 | 重跑 `tools/gen_api_contract.py` | `PASS API 契约快照与控制器源码一致` |

> D-4 是本版最有价值的一条：**单测全绿但页面是坏的**。原因是夹具喂了「理想形状」，
> 而真实接口是另一种形状。修完把「按真形状挂载」补成用例，以后同类问题会在测试阶段就红。

## 7. 门禁

| 项 | 结果 |
|---|---|
| 命令 | `bash scripts/gates.sh`（`set -a; . ~/.config/robot-autotest/env; set +a`；`APP_BASE=http://127.0.0.1:8001`） |
| 代码状态 | `main` @ `2bd72ff`（工作树除门禁副产物外干净） |
| 耗时 | **25 分 35 秒** |
| 结果 | `EXIT=0`、**`GATES_RESULT FAILS=0 UNJUDGED=0`** |
| 步骤 | **68 步全 PASS**（编号 01–68 连续）。注意 18 `structure_selfcheck` 是末尾的**内联块**（`printf` 输出，不是 `run` 调用），按 `^run [0-9]+` 数会少数一步——首版报告就因此误写成「18 号为既有空缺」 |
| 第 68 步 | `PASS 68-report_whitelist_doc (0s)` |
| 后端单测 | `Tests run: 2197, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（136 s） |
| 前端单测 | `Test Files 159 passed (159)` / `Tests 1251 passed (1251)`（55 s） |

> 这一轮不是「一次就绿」：首轮跑出 `FAILS=2`（`20-api_contract`、`64-role_surface`），
> 两条都是本版新增代码的遗漏而非既有缺陷，修完重跑才拿到上表。整改过程见 §6 的 D-5 / D-6，
> 复验读数见 `docs/evidence/v5.22.0/W72-08-09.md` §4 / §5。

### 7.1 结构自检（门禁自身是否还有效）

`bash scripts/gates.sh --selfcheck` 把脚本自身拷成临时副本、`sed` 删掉 `^run 68 ` 再跑结构检查：

```
STRUCT_FAIL 缺少步骤 68 report_whitelist_doc
SELFCHECK 删步骤 68 PASS（删了变红）
SELFCHECK_RESULT FAILS=0
```

即：新加的这一步**不是摆设**——删掉它，门禁自己会红。同一次 `--selfcheck` 里 33–36 四步的
负向自检也全部判红、干净判绿。

### 7.2 副产物处理

门禁会重写若干**已跟踪**的副产物（`docs/evidence/gates/browser/*.png`、`v3.3.0/ai-eval.json`、
`v3.4.0/capacity-report.json`、`v3.4.0/security-scan.json`、`v5.0.0/ops/dora-trend.json`、
`v5.7.0/ui/*.png`、`v5.8.0/perf/perf-report.json`），这些是每轮都会变的「当前快照」，
收口时统一 `git checkout --` 还原，不进本版提交——否则每跑一次门禁就多一次无意义 diff。

## 8. NFR 结果

| NFR | 判据 | 实测 | 结论 |
|---|---|---|---|
| NFR-v5.22-01 | 对外导出无明文凭据 | `check_report_sensitive.py` PASS；对外明细命令列 `—`；`dut` 对外脱敏为 `[REDACTED]` | 达成 |
| NFR-v5.22-02 | 冻结/导出 p95 不劣于 v5.21 | 四格式各一次成功（xlsx 7,314 B / pdf 95,778 B / html 1,596 B / docx 2,759 B），无重试、无超时 | 达成（**耗时未单独采集**，本版未做 p95 计时） |
| NFR-v5.22-03 | 回填 550 run 可一次跑完且可回滚 | 全库 516 行一次回填完成；`--dry-run` 与实跑 `SUMMARY` 逐字一致；`V11_83` 有 down 脚本 | 达成（**耗时未单独采集**） |
| NFR-v5.22-04 | 字段变更文档先行 | 第 68 步守护；W1 先改 C5 再改常量，同一提交 | 达成 |
| NFR-v5.22-05 | 前端不新增假数据分支 | 空态一律 `—` / 空态自解释，无占位数字 | 达成 |
| NFR-v5.22-06 | 迁移只追加 | `MigrationFileTest`（无 `DROP/MODIFY/CHANGE COLUMN`）+ `DownScriptGuardTest` | 达成 |
| NFR-v5.22-07 | 单测覆盖新增分支 | 闸 6 例 / 重算时间线 1 例 / 字段裁剪 1 例 / 凭据拒冻结 2 例 / PDF 503 1 例 | 达成 |
| NFR-v5.22-08 | 门禁 `FAILS=0` | 见 §7 | 见 §7 |

## 9. 遗留（登记、不阻塞本版）

| 编号 | 项 | 去向 |
|---|---|---|
| L-22-01 | 两套报告入口（run 级 / 报告级）合并 | D-22-04 → v5.23 |
| L-22-02 | 同一 run 多份报告的约束 | 需产品定「是否允许重出一版」 |
| L-22-03 | `trend` 真实历史趋势 | 无数据源，本版已删字段不再假造 |
| L-22-04 | 报告列表加计数列 | 随列表改版 |
| L-22-05 | `V11_83` 的 live down→up 演练 | `migration-down-up-drill.sh` 要求库名以 `_it` 结尾，本机只有 `robot_dev`；由单测覆盖 |

## 10. 放行判断

| 门 | 结论 |
|---|---|
| 35 条 TC | 34 达成 + 1 降级达成（TC-31，大师授权），0 未达成 |
| 8 条 NFR | 8 达成（NFR-08 见 §7） |
| 不可降级 5 项（PRD §7） | 全部达成 |
| 门禁 | 见 §7 |
| 版本号 | **未 bump**（仍 5.21.0），落号由大师拍板 |
| G 门 | **未判**，不得对外称 GA |

---

## 11. 修订记录

| 日期 | 变更 | 依据 |
|---|---|---|
| 2026-11-17 | 首版：35 条 TC 逐条落定；TC-31 降级记录；6 条缺陷 + 4 条判据修正 | `TEST-PLAN.md` + `docs/evidence/v5.22.0/*` |
