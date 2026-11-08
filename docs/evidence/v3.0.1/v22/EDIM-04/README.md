# EDIM-04 · 原文件 SHA 只读冻结 + 不可变判据版本

- 版本目录：`v3.0.1/v22`｜波次：M1｜上游：DEV-PLAN §3.1、§6（D7 → D3 → D1 → D2）
- 实现落点：
  - `fixtures/ed202/ED202测试用例_V0.4.xlsx`（ext4 只读快照，权限 `444`）+ `fixtures/ed202/README.md`
  - `CaseImportService.parse()` 读全字节算 SHA-256，写入 `ParsedWorkbook.sha256` / `ImportPreflight.sha256`
  - 迁移 `V11_1__import_batch_sheet.sql`（D7）、`V11_2__case_requirement_version.sql`（D3，含 `sha256 CHAR(64)`、
    唯一键 `uk_crv_case_version(test_case_id, version_no)` 与修订说明注释）；每个 up 在 `db/down/` 有对应 down
  - 不可变性**不建触发器**：由「应用层只发 INSERT + 唯一键 + 单测 `RequirementVersionImmutabilityTest` 锁死」保证
- SHA-256：`9b897b77564eb6d9cb6843fa350b1cd085e61d613a1b047e8ce5ceaa36316e39`（E: 原表与 ext4 快照实测一致）

## 命令原文

```bash
wsl.exe -d Ubuntu-24.04 bash -lc 'cd ~/robot-autotest && mvn -o -B test -Dtest=RequirementVersionImmutabilityTest'
wsl.exe -d Ubuntu-24.04 bash -lc 'cd ~/robot-autotest && sha256sum fixtures/ed202/ED202测试用例_V0.4.xlsx'
```

已执行（不可变单测在定向单次 mvn 中实跑；SHA 由组织脚本读取同一快照实算并打印）。结果行：

```
[INFO] Running com.qa.autotest.application.caseasset.RequirementVersionImmutabilityTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in com.qa.autotest.application.caseasset.RequirementVersionImmutabilityTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

快照 SHA-256: 9b897b77564eb6d9cb6843fa350b1cd085e61d613a1b047e8ce5ceaa36316e39
```

## 判据

| 项 | 期望 |
|---|---|
| 原文件 SHA | 导入前后一致；快照与 README 冻结值相同；E: 冻结副本零字节改动 |
| 旧版本不可 UPDATE | 应用层只发 INSERT，任何改写路径不存在；唯一键 `uk_crv_case_version` 挡住重复版本；单测 `RequirementVersionImmutabilityTest` 锁死（数据库层不建触发器） |
| 判据变更 | SHA 变化即插入新版本，旧版本不改，当前判定状态转「待重测」 |
| 迁移纪律 | 每个 up 有 down；已有迁移文件零字节改动；`up → down → up` 成功；迁移体不含 TRIGGER / PROCEDURE / FUNCTION |

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 不可变单测 | RequirementVersionImmutabilityTest：Tests run 3、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 定向 mvn 汇总 | Tests run 49、Failures 0、Errors 0，BUILD SUCCESS（14 个类同批） | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 快照 SHA-256 | 9b897b77564eb6d9cb6843fa350b1cd085e61d613a1b047e8ce5ceaa36316e39 | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| V11_2 触发器 | 0 个：迁移体内无 TRIGGER / PROCEDURE / FUNCTION，仅建表与注释 | db/migration/V11_2__case_requirement_version.sql |
| V11_2 唯一键 | uk_crv_case_version(test_case_id, version_no) 存在，sha256 为 CHAR(64) | db/migration/V11_2__case_requirement_version.sql |
| 门禁全量 | 19 步全 PASS，GATES_RESULT FAILS=0（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=0 errors=0 | docs/evidence/gates/logs/05-backend_test.log、02-frontend_test.log、11-editor_test.log |
| 基线 floor | 1016、325、219（只增不减，实测均不低于 floor） | docs/evidence/gates/logs/19-evidence_integrity.log |

补充说明（如实）：本轮没有把 `make db-reset`（`up → down → up`）的完整 stdout 单独归档到 `raw-20261108/`；可核的是 `V11_2` 的 up 文件与 `db/down/` 下的同名 down 成对存在，且门禁第 05 步（Flyway 应用 + Spring 上下文）通过、无迁移失败。

## 修订记录

| 日期 | 修订内容 | 修订人 | 依据 |
|---|---|---|---|
| 2026-11-08 | 推翻「BEFORE UPDATE / BEFORE DELETE 触发器拒绝改写」方案，撤销触发器；改为应用层只发 INSERT + 唯一键 `uk_crv_case_version` + 单测 `RequirementVersionImmutabilityTest` 锁死 | 学徒 | 实测 `CREATE TRIGGER` 报 `Error Code 1419`（binlog 已开启且应用账号无 SUPER 权限），V11_2 迁移失败并连带 SpringBootTest 上下文起不来 |
| 2026-11-08 | 同步修正本文「实现落点」「判据」「验证读数」中对触发器的过时描述 | 学徒 | 同上；V11_2 现行内容只保留 `sha256 CHAR(64)`、唯一键与说明注释 |
