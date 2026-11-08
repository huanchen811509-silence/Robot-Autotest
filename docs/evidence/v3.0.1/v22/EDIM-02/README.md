# EDIM-02 · 补齐别名 + T0/T1/T2 ↔ P0/P1/P2 归一（保留原值）

- 版本目录：`v3.0.1/v22`｜波次：M1｜上游：DEV-PLAN §3.1
- 实现落点：`CaseImportService.HEADER_ALIASES`（10 类别名）、`mappedField()`、`normalizePriority()`；
  `ImportRow.sourcePriority` 保留源表原值，`priority` 存归一值；D1 迁移 `V11_3__test_case_ed202_fields.sql`
- 别名类别：测试编号 / 测试用例编号 / 测试用例 / 测试名称 / 二级分类 / 用例分类 / 测试归类 /
  CX7测试结果记录 / ED202覆盖情况 / 测试资源（另补 `测试步骤` 供 EDIM-03、`测试分类`/`测试用例名称` 变体保证命中）

## 命令原文

```bash
wsl.exe -d Ubuntu-24.04 bash -lc 'cd ~/robot-autotest && mvn -o -B test -Dtest=CaseHeaderAliasTest'
wsl.exe -d Ubuntu-24.04 bash -lc 'cd ~/robot-autotest && mvn -o -B test -Dtest=PriorityNormalizeTest'
```

已执行（两类的用例均在本轮定向单次 mvn 中实跑，同一批 14 个类）。结果行：

```
[INFO] Running com.qa.autotest.application.caseasset.PriorityNormalizeTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.184 s -- in com.qa.autotest.application.caseasset.PriorityNormalizeTest
[INFO] Running com.qa.autotest.application.caseasset.CaseHeaderAliasTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.326 s -- in com.qa.autotest.application.caseasset.CaseHeaderAliasTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 判据

| 项 | 期望 |
|---|---|
| 10 类别名 | 全部映射到正确字段（`mappedField()` 逐项断言） |
| 编号 / 名称列 | 7 个 sheet 100% 命中 `caseCode` + `title` |
| 二级分类 | `categoryL2` 不再丢弃，`Import详细测试流程` 首行有值 |
| 优先级归一 | `T0→P0 / T1→P1 / T2→P2`（T3→P3 兼容），`P0/P1/P2` 原样 |
| 保留原值 | `sourcePriority` 为 `"T0"` / `"P0"` 原串，`Priority.from(归一值)` 不抛错 |

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 别名单测 | CaseHeaderAliasTest：Tests run 2、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 归单单测 | PriorityNormalizeTest：Tests run 2、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 定向 mvn 汇总 | Tests run 49、Failures 0、Errors 0，BUILD SUCCESS（14 个类同批） | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 命中列数佐证 | Import详细测试流程 headerRow=R3、mappedColumns=10（7 sheet 中最高） | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 门禁全量 | 19 步全 PASS，GATES_RESULT FAILS=0（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=0 errors=0 | docs/evidence/gates/logs/05-backend_test.log、02-frontend_test.log、11-editor_test.log |
| 基线 floor | 1016、325、219（只增不减，实测均不低于 floor） | docs/evidence/gates/logs/19-evidence_integrity.log |

说明：两个类的断言数（各 2 条）是「用例数」而非「别名条数」；10 类别名逐项断言发生在 `CaseHeaderAliasTest` 内部，surefire 不逐条打印断言文本，故本表只引打印到日志的用例级读数，不另行拆数。
