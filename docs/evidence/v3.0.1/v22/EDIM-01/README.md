# EDIM-01 · 多 sheet 遍历 + 逐 sheet 独立 findHeaderRow()

- 版本目录：`v3.0.1/v22`｜波次：M1｜上游：`docs/plan/v2.2-DEV-PLAN-增补草案-待评审-20261107.md` §3.1
- 实现落点：`CaseImportService.parse()` / `parseSheet()` / `findHeaderRow()`；`HEADER_SCAN_LIMIT=30`（原 10 行窗口无法覆盖看板 R15 表头）
- 关联迁移：`V11_1__import_batch_sheet.sql`（D7，逐 sheet 表头行号 / 列映射 / 行数对账）
- 固件：`fixtures/ed202/ED202测试用例_V0.4.xlsx`（只读快照，SHA `9b897b77564eb6d9cb6843fa350b1cd085e61d613a1b047e8ce5ceaa36316e39`）

## 命令原文

```bash
wsl.exe -d Ubuntu-24.04 bash -lc 'cd ~/robot-autotest && mvn -o -B test -Dtest=Ed202MultiSheetImportTest'
```

已执行（定向单次 mvn，14 个测试类同批）。结果行：

```
[INFO] Running com.qa.autotest.application.caseasset.Ed202MultiSheetImportTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.356 s -- in com.qa.autotest.application.caseasset.Ed202MultiSheetImportTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 判据

| 项 | 期望 |
|---|---|
| 工作表数 | 7（测试用例看板 / Import / XCCL / AI参数网 / 存储 / HCI / UECGSE适配） |
| 逐 sheet 表头行号 | 看板 `R15`、Import `R3`、其余 `R1`；各 sheet 独立 `findHeaderRow()` |
| 日志 | 逐 sheet 打印表头行号与命中列数 |
| 数据行总数 | 1543 = 730（看板）+ 730（六张明细并集）+ 83（UECGSE适配） |
| 硬判据 | `UECGSE适配` 83 条不得因「不在看板」被静默丢弃 |

## 实测输出（逐 sheet 原文，7 行）

```
[EDIM-01] sheet=测试用例看板 headerRow=R15 mappedColumns=8 rows=730
[EDIM-01] sheet=Import详细测试流程 headerRow=R3 mappedColumns=10 rows=414
[EDIM-01] sheet=XCCL详细测试流程 headerRow=R1 mappedColumns=10 rows=61
[EDIM-01] sheet=AI参数网_详细测试流程 headerRow=R1 mappedColumns=9 rows=67
[EDIM-01] sheet=存储_详细测试流程 headerRow=R1 mappedColumns=9 rows=86
[EDIM-01] sheet=HCI详细测试流程 headerRow=R1 mappedColumns=8 rows=102
[EDIM-01] sheet=UECGSE适配 headerRow=R1 mappedColumns=6 rows=83
```

对账：明细 414＋61＋67＋86＋102＝730，与看板 730 一一对应；适配集 83 独立保留，未被并入看板口径。表头行号实测值与判据完全吻合（看板 R15、Import R3、其余 R1）。

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 本包单测 | Ed202MultiSheetImportTest：Tests run 1、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 定向 mvn 汇总 | Tests run 49、Failures 0、Errors 0，BUILD SUCCESS（14 个类同批） | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 门禁全量 | 19 步全 PASS，GATES_RESULT FAILS=0（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=0 errors=0 | docs/evidence/gates/logs/05-backend_test.log、02-frontend_test.log、11-editor_test.log |
| 基线 floor | 1016、325、219（只增不减，实测均不低于 floor） | docs/evidence/gates/logs/19-evidence_integrity.log |
| 看板行数 | rows=730（headerRow=R15、mappedColumns=8） | 上述逐 sheet 原文首行 |
| 明细合计 | 414＋61＋67＋86＋102＝730 | 上述逐 sheet 原文第 2 至 6 行 |
| 适配集行数 | rows=83（headerRow=R1、mappedColumns=6，独立保留） | 上述逐 sheet 原文第 7 行 |
