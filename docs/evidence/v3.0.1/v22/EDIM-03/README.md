# EDIM-03 ·「测试步骤」按换行编号拆入 test_case_step

- 版本目录：`v3.0.1/v22`｜波次：M1｜上游：DEV-PLAN §3.1、D2
- 实现落点：`CaseImportService.splitSteps()`（编号行起新步骤、续行并入当前步骤）、`ImportRow.steps` /
  `sourceRow`；`TestCaseRepository.writeImportMetadata()` / `insertImportedSteps()`；
  `commit()` 在插入用例后写步骤；迁移 `V11_4__test_case_step_ed202.sql`
- 既有事实：`source_row` 列由 `V2_3` 已建、`uk_test_case_step(test_case_id, step_no)` 由 `V1` 已建，
  本包只补 `idx_test_case_step_source_row` 索引，不重复加列/加键（否则 `up→down→up` 会失败）

## 命令原文

```bash
wsl.exe -d Ubuntu-24.04 bash -lc 'cd ~/robot-autotest && mvn -o -B test -Dtest=CaseStepIngestTest'
```

已执行（定向单次 mvn，14 个测试类同批）。结果行：

```
[INFO] Running com.qa.autotest.application.caseasset.CaseStepIngestTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.663 s -- in com.qa.autotest.application.caseasset.CaseStepIngestTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 判据

| 项 | 期望 |
|---|---|
| 拆分 | 按换行 + 行首编号（`1.` / `1、` / `1)` / `1．`）拆步，续行并入当前步骤 |
| 源表比对 | 抽查 5 条用例，步骤数 = 源表编号行数；`sourceRow` 与 Excel 行号一致 |
| 全表 | `test_case_step` 写入条数 `count(*) > 0`（本包单测以 `ImportRow.steps` 总数 `>0` 代理） |
| 兼容 | 226 行台账表头为「测试步骤（按序执行）」不命中别名 → 步骤为空 → `commit` 不写步骤，旧 IT 清理路径不变 |

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 本包单测 | CaseStepIngestTest：Tests run 2、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 定向 mvn 汇总 | Tests run 49、Failures 0、Errors 0，BUILD SUCCESS（14 个类同批） | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 门禁全量 | 19 步全 PASS，GATES_RESULT FAILS=0（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=0 errors=0 | docs/evidence/gates/logs/05-backend_test.log、02-frontend_test.log、11-editor_test.log |
| 基线 floor | 1016、325、219（只增不减，实测均不低于 floor） | docs/evidence/gates/logs/19-evidence_integrity.log |

未覆盖项（如实登记，不补猜）：本轮定向日志只打印到用例级，没有 `test_case_step` 的 `count(*)` 与「5 条用例逐条对步骤数」的独立输出行；这条判据当前由 `CaseStepIngestTest` 在内存对象层（`ImportRow.steps` 总数 `>0`、拆步规则）代理，数据库落库条数未在本轮取得可归档读数。需要该读数时须另跑一次带 SQL 抽查的验证，本 README 不预填。
