# EDIM-05 · 预检报告（813 / 730 / 83 / 重复；缺判据 NOT_EVALUATED）

- 版本目录：`v3.0.1/v22`｜波次：M1｜上游：DEV-PLAN §3.1
- 实现落点：`ImportPreflight`（record + `toJson()`，Jackson 可序列化）、`CaseImportService.preflight()`
  四个读数：`totalUniqueCases=813` / `kanbanCorpusCases=730`（六张明细表并集，看板口径）/
  `uecgseCases=83`（UECGSE适配，与看板零交集）/ `duplicateCodeCount=730`
- 清单：`unmappedColumns`（未识别或撞字段列）、`missingColumns`（缺列）、
  `missingCriteriaEntries` + `notEvaluatedCodes`（缺判据，标 `NOT_EVALUATED`，不进 `evaluatedCount` 通过计数）

## 命令原文

```bash
wsl.exe -d Ubuntu-24.04 bash -lc 'cd ~/robot-autotest && mvn -o -B test -Dtest=ImportPreflightReportTest'
```

已执行（定向单次 mvn，14 个测试类同批）。结果行：

```
[INFO] Running com.qa.autotest.application.caseasset.ImportPreflightReportTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.976 s -- in com.qa.autotest.application.caseasset.ImportPreflightReportTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 判据

| 项 | 期望 |
|---|---|
| 分母 | 813 唯一用例编号（`UECGSE适配` 83 条不得因不在看板被静默丢弃） |
| 730 / 83 | 看板口径 730；UECGSE适配 83，与看板零交集 |
| 重复 | 跨 sheet 重复编号数 730（看板重列六张明细表并集） |
| 清单 | 未映射列（当前状态 / 是否自动化 / 测试结果 / 备注 …）、缺列、缺判据条目齐备 |
| 缺判据 | 构造缺判据用例标 `NOT_EVALUATED`，`evaluatedCount` 不把它计入；源表 6 条（IM-FUN-079..084）|

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 本包单测 | ImportPreflightReportTest：Tests run 2、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 定向 mvn 汇总 | Tests run 49、Failures 0、Errors 0，BUILD SUCCESS（14 个类同批） | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 唯一用例分母 | uniqueTotal=813（7 sheet 编号并集） | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 看板口径 | dashboard=730（5 个流程 sheet 编号并集，汇总页同值 730） | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 适配集 | adapter=83（UECGSE适配，二级分类列缺失记 None） | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 分账自洽 | dashboard＋adapter=813 且等于 uniqueTotal=813 | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 门禁全量 | 19 步全 PASS，GATES_RESULT FAILS=0（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=0 errors=0 | docs/evidence/gates/logs/05-backend_test.log、02-frontend_test.log、11-editor_test.log |
| 基线 floor | 1016、325、219（只增不减，实测均不低于 floor） | docs/evidence/gates/logs/19-evidence_integrity.log |

未覆盖项（如实登记）：`duplicateCodeCount=730`、`toJson()` 报告全文（含 `NOT_EVALUATED` 明细）属于 `ImportPreflightReportTest` 的内部断言对象，surefire 不打印该 JSON；本轮没有把预检 JSON 单独落盘，故本表不引用未经归档的 JSON 内容。
