# 设计契约：命令证据入报告 + 物理删除

> 日期：2026-11-20
> 对应：`docs/plan/EHT命令证据与报告清理-20261120/01-PRD.md`
> 状态：大师已批准开工，四项裁决已落地

---

## 1. 命令证据契约

### 1.1 数据源（唯一真源）

用例库 `artifacts/<业务run_id>/cases/<sanitized_nodeid>/raw/<endpoint>.commands.jsonl`，每一行：

| JSON 字段 | 平台落库字段 | 处理 |
|---|---|---|
| `command` | `test_result.command_text` | `CaseCommandExtractor.scrubAndTruncate` 脱敏截断 |
| 固定来源标记 | `test_result.command_source` | `COMMAND_INDEX` |
| `exit` / 执行结果 | `test_result.command_exit_code` | 索引暂无 exit 时留 NULL，不编造 |
| `stdout_file` | 报告证据层相对路径 | `EhtSections.relativePath` 脱敏 |

**禁止**：从 junit 猜命令、硬编码命令、用 fake 数据造绿。

### 1.2 方案选择：B（入库前补录）

| 方案 | 描述 | 判定 |
|---|---|---|
| A | 把执行侧已补录的 `NormalizeResult` 传给报告入库 | **不采用**：执行（异步线程）与报告解析（独立 HTTP）是两个生命周期，跨边界传对象需新增持久化载体，改动面大 |
| **B** | `ReportIngestService.parsePytest` 归一后，用 `<repo>/artifacts/<run_id>/` 调 `PytestCommandEnricher` 补录 | **采用**：单一改动点，复用执行侧同一套补录逻辑，不碰 junit 步骤/判据链路 |

B 的 scriptPath 来源：用 `PytestResultNormalizer.normalize(...)` 产出的 `mappings[].scriptPath`（二次归一只取映射，不替换老内核的归一结果）。二次归一失败时**跳过命令补录**，不阻断报告生成。

### 1.3 节点目录名对齐

框架与平台都用 `re.sub(r"[^A-Za-z0-9_.-]+", "_", node_id)`（`PytestCommandIndex.NODE_ID_SANITIZER`）。
候选顺序：绑定脚本路径 + `::` + 函数名 → nodeid 派生 → 原 nodeid。诊断测试已验证首个候选命中真实产物。

---

## 2. 物理删除契约

### 2.1 run 子表删除顺序（外键无 CASCADE）

```
run_metadata (有 CASCADE，可不显式删)
pytest_case_step
manual_execution
run_artifact
keyword_result
test_result
run
```

每一步幂等；全部在事务内；写审计 `report.run.delete`。

### 2.2 test_case 子表删除顺序

```
test_set_item          (现有代码已先删)
test_case_step
case_requirement
case_requirement_version
case_script_binding
manual_execution
test_case
```

口径变更：2026-11-20 大师裁决推翻 V11_82 的"不得物理删除"；**不修改历史迁移**，只在代码注释与 PRD 记录。

### 2.3 test_report 与审计

- `DELETE FROM test_report WHERE id=?`，FROZEN / ARCHIVED 一并允许（大师裁决）。
- `test_report_export` / `test_report_audit` 标记为 append-only（见 `db/schema-contract.json:6-8`）：
  **默认保留审计行**，接受其 `report_id` 成为悬空引用；报告页面不查询这两张表，不影响展示。
- 若后续要求级联删除审计，须先修改 `schema-contract.json` 并另立评审。

### 2.4 前端删除态

移除 `RunsView.vue` 的本地 `deletedRunIds` Set，列表完全由后端返回驱动。物理删后后端自然不再返回该行。

---

## 3. 历史数据分批清除

- 每批前 `mysqldump` 备份 `run` / `test_report` 及相关子表到 `/tmp/`，记录路径与 sha256。
- 第一批：最近 30 天的软删记录；每批 ≤ 10 条。
- 每批后查 `SELECT COUNT(*) ... WHERE deleted_at IS NULL`，确认未误删。
- 全部清完后软删数归零；保留备份可恢复。

---

## 4. 不变量

1. 命令证据必须与真实 `commands.jsonl` 逐字一致（脱敏除外）。
2. 物理删除不得留下有外键子表的孤儿行。
3. 历史数据清除不得误删 `deleted_at IS NULL` 的活动记录。
4. 批 1/2 已绿的能力（步骤、判据、装配三态、计划消费、fake 设备排除）不得回归。
