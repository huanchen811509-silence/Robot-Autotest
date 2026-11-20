# 测试计划：命令证据入报告 + 报告彻底删除

> 日期：2026-11-20
> 对应 PRD：`01-PRD.md`（D1~D10）
> 原则：只认真链路；单测绿/构建绿不是放行理由。

---

## 1. 测试范围

| 维度 | 范围 |
|---|---|
| 命令证据 | real 真跑 → 命令源文/来源/退出码/回显路径 → 落库 → 报告渲染 |
| 物理删除 | run / test_report 删除 → 主表、子表、列表、搜索、工作台 |
| 数据清理 | 历史软删数据按裁决处置，备份与回滚 |
| 回归 | 批 1/2 已有能力（步骤、判据、装配三态、计划消费、fake 设备排除） |

---

## 2. 用例

### TC-v5.24-01 real 命令证据贯通
- **对应**：D1 / FR-v5.24-01 / W-CMD-02
- **前置**：DUT 可达（gpu01@10.202.54.101），real 模式，nodeid `tests/nic/base/test_im_fun_020.py::test_im_fun_020_pci_device_info`。
- **步骤**：执行 → 等终态 → 查 `test_result.command_text/command_source/command_exit_code`。
- **期望**：
  1. `command_text` 非空，且等于 `commands.jsonl` 里两条命令之一。
  2. `command_source` 是真实来源标记（如 `COMMAND_INDEX`），不是 `NONE`。
  3. 步骤 8 条、判据 PASS、log_span 非空（批 1/2 不回归）。
- **证据形态**：SQL 输出 + `commands.jsonl` 原文对照 + run id。

### TC-v5.24-02 回显路径可见且脱敏
- **对应**：D2 / FR-v5.24-02 / W-CMD-03
- **步骤**：生成报告 → `GET /api/test-reports/{id}/sections` 与 snapshot HTML → 搜 `artifacts/`。
- **期望**：
  1. 证据层含 `*.stdout.txt` 相对路径。
  2. HTML 无 `/mnt/e/`、`/home/evergreen/`。
  3. 无回显文件时显示"未提供"。
- **证据形态**：sections JSON + HTML 片段。

### TC-v5.24-03 run 物理删除与子表清理
- **对应**：D3 / FR-v5.24-03 / W-DEL-01
- **步骤**：创建临时 run（带步骤/判据/产物）→ 调删除 → 逐表查。
- **期望**：`run` 主表 0 行；`test_result`/`keyword_result`/`run_artifact`/`pytest_case_step`/`manual_execution`/`run_metadata` 中该 run 关联 0 行；审计有 `report.run.delete`。
- **证据形态**：删除前后 SQL 计数对比。

### TC-v5.24-04 test_report 物理删除
- **对应**：D4 / FR-v5.24-04 / W-DEL-02
- **步骤**：建报告 → 删除 → 查列表与详情。
- **期望**：主表 0 行；列表无该 id；详情 404/空；审计表按裁决处置。
- **证据形态**：SQL + API 响应。

### TC-v5.24-05 已删记录不复活
- **对应**：D5 / FR-v5.24-05 / W-DEL-03
- **步骤**：删除一条 run → 依次查执行记录列表、报告列表、搜索、工作台汇总 → F5 刷新 → 再查。
- **期望**：所有入口均无该记录；前端不再靠本地 Set 隐藏。
- **证据形态**：API 响应 + 前端截图（可选）。

### TC-v5.24-06 冻结件允许直接删除
- **对应**：D6 / FR-v5.24-06 / W-DEL-02
- **前置**：构造 FROZEN 与 ARCHIVED 报告各一条。
- **期望**：两条都能 `DELETE /api/test-reports/{id}` 成功，主表 0 行；前端冻结件显示删除按钮；不再报"已冻结，请先解冻"。
- **证据形态**：API 响应 + 单测 + 前端截图（可选）。

### TC-v5.24-07 test_case 物理删除
- **对应**：D7 / FR-v5.24-07 / W-DEL-05
- **步骤**：建一条用例（带步骤、需求、脚本绑定）→ 删除 → 逐表查 6 张子表。
- **期望**：`test_case` 主表 0 行；`test_case_step`/`case_requirement`/`case_requirement_version`/`case_script_binding`/`manual_execution`/`test_set_item` 关联 0 行；历史报告仍显示用例编号。
- **证据形态**：删除前后 SQL 计数对比 + 单测。

### TC-v5.24-08 历史软删数据分批清除
- **对应**：D8 / FR-v5.24-08 / W-DEL-04
- **步骤**：备份（记录路径与 sha256）→ 第一批清最近 30 天（≤10 条/批）→ 每批校验 → 逐批清完。
- **期望**：57 条 run + 1 条 test_report 全部清除；未软删数据 0 误删；备份可恢复。
- **证据形态**：备份文件 sha + 每批前后计数 SQL + 执行日志。

### TC-v5.24-09 机检与 self-test
- **对应**：D9 / FR-v5.24-09 / W-VER-01
- **步骤**：
  1. `bash scripts/guard/eht_pytest_realchain.sh --run-id <real-run>`
  2. `bash ... --self-test`（注入 command_text 清空 → 判红 → 回滚 → 绿）
  3. 删除机检：临时 run 删除后各表无残留。
- **期望**：全绿；self-test 注入后确有一项红，回滚后恢复。
- **证据形态**：机检日志全文。

### TC-v5.24-10 全量门禁与证据包
- **对应**：D10 / W-VER-02
- **步骤**：改动冻结 → 全量门禁 → 证据包落盘 → 表格校验 → 计划回写。
- **期望**：`FAILS=0 UNJUDGED=0`；证据包含前后对比、run id、SQL、机检日志；表格校验 PASS。
- **证据形态**：门禁日志 + 证据包目录清单。

---

## 3. 放行判据

**全部满足才放行**：

1. D1~D10 全部达成（逐条有证据）。
2. 全量门禁 `FAILS=0 UNJUDGED=0`。
3. `--self-test` 真注入 → 判红 → 还原 → 绿（日志保留）。
4. 命令证据与真实 `commands.jsonl` 逐字一致（非伪造、非猜测）。
5. 物理删除后主表与子表均无残留，备份可恢复。
6. 证据包与计划回写完成，表格校验 PASS。

**禁止放行情形**：

- 命令列仍为 `NONE` 却宣布"完成"。
- 用假 fixture 让命令断言变绿。
- 未备份就清理历史数据。
- 冻结件在裁决未明确前被删除。

---

## 4. 编号核对

- FR：`FR-v5.24-01` ~ `10`，连续无缺口。
- TC：`TC-v5.24-01` ~ `10`，连续无缺口。
- DoD：`D1` ~ `D10`，连续无缺口。
