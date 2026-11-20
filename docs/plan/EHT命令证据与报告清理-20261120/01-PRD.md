# PRD：命令证据入报告 + 报告彻底删除

> 日期：2026-11-20
> 版本：v5.24.0（未落号，G 门归大师）
> 编号规范：`FR-v5.24-xx`（`detect_prefix` 只认 `v<数字>` 前缀）

---

## 1. 目标

1. 让 real 执行的**测试命令执行过程**（下发命令原文、来源、退出码、回显路径）在平台报告里可见，与步骤/判据同层展示。
2. 把执行报告（`run`）与用户报告（`test_report`）的删除从软删改为**物理删除**，消除"删了还陈列、删不掉"。

---

## 2. 需求

### FR-v5.24-01 命令证据贯通到报告入库
- **需求**：`test_result.command_text` / `command_source` / `command_exit_code` 不再恒为 `NONE`；命令来源与实际产物一致。
- **方案边界**：二选一，开发计划阶段定（见 W-02）：
  - A：报告入库复用执行侧已补录的归一结果（避免二次归一）；
  - B：`ReportIngestService` 入库前也调 `PytestCommandEnricher`，用 `<repo>/artifacts/<run_id>/` 补录。
- **验收判据**：
  1. 用复核样本 `IM-FUN-020`（run 17083 同款 real 执行）重跑，`test_result.command_text` 非空且等于 `readlink -f /sys/class/net/ens17np0/device` 或 `sudo -n lspci -Dnnvv -s 0000:45:00.0` 之一。
  2. `command_source=COMMAND_INDEX`（或等价的真实来源标记），不得是 `NONE` 或伪造值。
  3. 命令原文与用例库 `commands.jsonl` 逐字一致（脱敏规则除外）；**禁止从 junit 猜命令**。
  4. 没有命令产物的用例（如框架自测）保持 `NONE`，不得编造。

### FR-v5.24-02 命令回显可追溯
- **需求**：报告能定位到该命令的原始 stdout/stderr 回显文件（相对路径，不泄漏绝对路径）。
- **验收判据**：
  1. 每条有命令的用例，报告证据层能点开或至少显示对应 `*.stdout.txt` 的相对路径（`artifacts/...` 开头）。
  2. 绝对路径经 `EhtSections.relativePath` 处理，报告 HTML 中不出现 `/mnt/e/`、`/home/evergreen/`。
  3. 回显文件缺失时显示"未提供"，不是空白标签。

### FR-v5.24-03 run 物理删除
- **需求**：`DELETE /api/reports/{runId}` 与 `DELETE /api/runs/{runId}` 从 `UPDATE ... deleted_at` 改为物理删除，且清理有外键约束的子表。
- **验收判据**：
  1. 删除后 `SELECT COUNT(*) FROM run WHERE id=?` = 0。
  2. 依赖子表（`test_result`、`keyword_result`、`run_artifact`、`pytest_case_step`、`manual_execution`、`run_metadata`）无孤儿行。
  3. 被 `report` 引用的 run 不允许直接删（要么先删报告，要么级联删报告——按裁决定）。
  4. 操作写审计（沿用 `report.run.delete`），审计表保留。

### FR-v5.24-04 test_report 物理删除
- **需求**：`DELETE /api/test-reports/{id}` 改为物理删除。
- **验收判据**：
  1. 删除后 `SELECT COUNT(*) FROM test_report WHERE id=?` = 0。
  2. 列表 `GET /api/test-reports` 与详情 `GET /api/test-reports/{id}` 均 404/空。
  3. `test_report_export` / `test_report_audit` 的处理符合裁决（级联删除或保留孤儿行，二选一，需写明）。

### FR-v5.24-05 已删记录不再出现在任何列表
- **需求**：修复 `RunRepositoryJdbc.page()` 漏过滤，以及 `SearchRepositoryJdbc` / `WorkspaceRepositoryJdbc` / `SuperAdminViewService` 等绕过点。
- **验收判据**：
  1. 物理删后，执行记录列表、报告列表、搜索、工作台汇总均查不到该记录（物理删后自然满足）。
  2. 新增单测：构造一条 run，删除后逐一查各入口，均返回空。
  3. 前端不再用本地 `Set` 维护删除态（改为后端事实），刷新后不复活。

### FR-v5.24-06 冻结件允许直接删除
- **需求**：大师 2026-11-20 裁决——物理删不经过状态机，FROZEN / ARCHIVED 报告允许直接删除。
- **验收判据**：
  1. `DELETE /api/test-reports/{id}` 对 FROZEN / ARCHIVED 均成功，主表 0 行。
  2. 移除 `ReportLifecycleService` 中 `if (isFrozen(row)) throw ...` 拦截。
  3. 前端冻结件显示删除按钮并调用同一接口。
  4. 原"冻结拒绝删除"单测改写为"冻结允许删除"。

### FR-v5.24-07 test_case 物理删除
- **需求**：大师 2026-11-20 裁决——推翻 2026-11-16「不得物理删除」决定，`test_case` 也改为物理删。
- **验收判据**：
  1. `TestCaseRepositoryJdbc.delete` 改为物理删，并清理 6 张子表（`test_case_step`、`case_requirement`、`case_requirement_version`、`case_script_binding`、`manual_execution`、`test_set_item`）。
  2. 删除后主表与子表关联均 0 行。
  3. **不修改历史迁移** `V11_82`；在代码注释与本文档记录口径变更时间与裁决人。
  4. 历史报告引用已删 case_id 时仍能显示用例编号（不出现悬空 id）。

### FR-v5.24-08 历史软删数据分批清除
- **需求**：大师 2026-11-20 裁决——分批清除 57 条软删 run 与 1 条软删 test_report。
- **验收判据**：
  1. 每批删除前 `mysqldump` 备份到 `/tmp/` 并记录路径与 sha256。
  2. 第一批清最近 30 天，每批 ≤ 10 条，逐批校验。
  3. 每批后执行计数 SQL，确认未误删未软删数据。
  4. 全部清除后软删记录 = 0，备份可恢复。

### FR-v5.24-09 真链路机检扩展
- **需求**：`scripts/guard/eht_pytest_realchain.sh` 增加命令证据断言；新增删除行为机检。
- **验收判据**：
  1. 机检对 real run 断言 `command_text` 非空（有命令产物的用例 100% 非空）。
  2. `--self-test` 真注入（清空 command_text）→ 判红 → 回滚 → 绿。
  3. 删除机检：创建临时 run → 物理删 → 断言各表无残留 → 清理。

### FR-v5.24-10 可复核证据包
- **需求**：交付前后对比、SQL、run id、报告快照、机检日志。
- **验收判据**：证据落 `docs/evidence/v5.24.0/command-evidence-and-hard-delete/`，表格校验 PASS。

---

## 3. 验收总表

| 编号 | 判据 | 对应需求 |
|---|---|---|
| D1 | real 重跑命令列非空且与 commands.jsonl 一致 | FR-v5.24-01 |
| D2 | 回显路径可见且无绝对路径泄漏 | FR-v5.24-02 |
| D3 | run 物理删除，子表无孤儿 | FR-v5.24-03 |
| D4 | test_report 物理删除，列表详情均查不到 | FR-v5.24-04 |
| D5 | 所有列表入口不再显示已删记录 | FR-v5.24-05 |
| D6 | 冻结件行为按裁决且被测试固定 | FR-v5.24-06 |
| D7 | test_case 策略按裁决且迁移与测试一致 | FR-v5.24-07 |
| D8 | 历史软删数据按裁决处置且有备份 | FR-v5.24-08 |
| D9 | 机检含命令断言与删除断言，self-test 真红真绿 | FR-v5.24-09 |
| D10 | 证据包完整、表格校验 PASS | FR-v5.24-10 |

**十条全达成，才允许宣布完成。缺一不可。**

---

## 4. 非目标

- 不改用例库脚本（命令证据本来就写得很完整）。
- 不改 fake 模式的判据口径（Plan-only 无判定是诚实行为）。
- 不做全量 66 步门禁（本轮改动冻结后单独跑）。
- 不擅自 bump 版本、不判 G 门。
