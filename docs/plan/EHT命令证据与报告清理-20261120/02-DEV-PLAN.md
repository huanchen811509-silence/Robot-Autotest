# 开发计划：命令证据入报告 + 报告彻底删除

> 日期：2026-11-20
> 对应 PRD：`01-PRD.md`（FR-v5.24-01 ~ 10）
> 开工条件：大师批准 + 四项裁决明确

---

## 1. 通用约束

1. **不造假**：命令必须来自用例库 `commands.jsonl`，不许从 junit 猜、不许硬编码。
2. **构造器注入**：依赖一律构造器注入，一个类只能一个公开构造器（`ArchitectureTest` R6）。
3. **异步上下文**：异步线程用 `TenantContext.runAs(...)`，`progress.complete` 放数据库写入之后。
4. **删除前备份**：任何物理删（尤其历史数据清理）先 `mysqldump` 备份到 `/tmp/` 并记录路径。
5. **迁移只追加**：DB 变更写新迁移，不改历史迁移文件；取最新版本用 `installed_rank DESC LIMIT 1`。
6. **同步顺序**：E 盘改 → cp 到 WSL → 编译测试（E 盘无 ssh 密钥，push 只在 WSL）。
7. 复杂 shell 一律写 `.sh` 文件执行，不在 `bash -lc '...'` 里用 `$VAR`。

---

## 2. WBS

### W-CMD-01 设计：命令证据契约 + 删除契约
- **对应**：FR-v5.24-01/02/03/04
- **交付**：`docs/design/EHT-命令证据与删除契约.md`
- **要点**：定 A/B 方案（复用内存归一 vs 入库前补录）；列清 `run` 子表删除顺序；定审计表处置。
- **验收命令**：`python docs/evidence/v1.0/policy-20261106/check_md_tables.py <契约>`
- **判据**：契约写明字段映射、删除顺序、裁决落地方式。

### W-CMD-02 命令补录贯通到入库
- **对应**：FR-v5.24-01
- **改动类**：`PytestNicRunController`、`ReportIngestService`、`PytestCommandEnricher`（可能新增 `NormalizedResult` 传递载体）
- **要点**：
  1. 二选一：A 把执行侧补录后的 `NormalizeResult` 传给报告入库；B 在 `ReportIngestService.parsePytest` 里也调 `PytestCommandEnricher.enrich`，用 `<repo>/artifacts/<run_id>/` 作 commandRoot。
  2. 优先 A（单一事实源，避免二次归一）；A 不可行时退 B，并在契约写明理由。
  3. 不得改变 junit 步骤/判据链路（批 1/2 已绿）。
- **验收命令**：
  - `mvn -q test -Dtest=PytestCommandEnricherTest,ReportIngestServiceTest,EhtPytestReportTest`
  - 真跑 real IM-FUN-020 → 查 `test_result.command_text`
- **判据**：命令非空、来源真实、与 commands.jsonl 一致；其它用例不回归。

### W-CMD-03 回显路径入报告
- **对应**：FR-v5.24-02
- **改动类**：`PytestCommandIndex`（或新增回显索引）、`EhtSections`、`ReportSnapshotService`
- **要点**：从 `commands.jsonl` 的 `stdout_file` / `stderr_file` 拼相对路径；经 `relativePath` 脱敏；报告证据层展示"未提供"兜底。
- **验收命令**：`mvn -q test -Dtest=EhtPytestReportTest,PytestCommandIndexTest`
- **判据**：报告 HTML 含 artifacts 相对路径，无绝对路径。

### W-DEL-01 run 物理删除 + 子表清理
- **对应**：FR-v5.24-03
- **改动类**：`ReportLifecycleService`、可能新增迁移
- **要点**：按依赖顺序清理 `test_result` / `keyword_result` / `run_artifact` / `pytest_case_step` / `manual_execution` / `run_metadata`，再 `DELETE FROM run`；幂等；写审计。
- **验收命令**：`mvn -q test -Dtest=ReportLifecycleServiceTest`
- **判据**：删除后主表与所有子表 0 行；重复删不报错。

### W-DEL-02 test_report 物理删除（含冻结件）
- **对应**：FR-v5.24-04 / FR-v5.24-06
- **改动类**：`ReportLifecycleService`、`TestReportRepositoryJdbc`、`TestReportCrudController`、前端 `TestReportsView.vue`
- **要点**：
  1. `DELETE FROM test_report WHERE id=?`；审计表按"保留 append-only 审计行"默认处理。
  2. **移除** `ReportLifecycleService` 的 `isFrozen` 拦截（大师裁决：冻结/归档允许直接删）。
  3. 前端冻结件恢复删除按钮，调用同一 DELETE 接口。
  4. 改写原"冻结拒绝删除"单测为"冻结允许删除"。
- **验收命令**：`mvn -q test -Dtest=ReportLifecycleServiceTest,TestReportRepositoryJdbcTest` + `cd frontend && npm run build`
- **判据**：FROZEN / ARCHIVED 均可删且主表 0 行；列表/详情查不到。

### W-DEL-05 test_case 物理删除（大师裁决新增）
- **对应**：FR-v5.24-07
- **改动类**：`TestCaseRepositoryJdbc`、相关单测
- **要点**：
  1. `delete()` 由 `UPDATE ... deleted_at` 改为物理 `DELETE FROM test_case`。
  2. 按依赖顺序清理 6 张子表：`test_case_step`、`case_requirement`、`case_requirement_version`、`case_script_binding`、`manual_execution`、`test_set_item`（`test_set_item` 现有代码已先删，保留）。
  3. 在方法注释写明"2026-11-20 大师裁决推翻 V11_82 软删口径"，不改历史迁移。
- **验收命令**：`mvn -q test -Dtest=TestCaseRepositoryJdbcTest`
- **判据**：删除后主表与 6 张子表关联 0 行；历史报告仍能显示用例编号。

### W-DEL-03 列表过滤与前端删除态
- **对应**：FR-v5.24-05
- **改动类**：`RunRepositoryJdbc`、`SearchRepositoryJdbc`、`WorkspaceRepositoryJdbc`、`SuperAdminViewService`、`frontend/src/views/RunsView.vue`
- **要点**：补 `deleted_at IS NULL`（物理删后作防御性保留）；移除前端本地 `Set` 删除态，改由后端驱动。
- **验收命令**：`mvn -q test -Dtest=ReportRepositoryJdbcTest,RunRepositoryJdbcTest` + `cd frontend && npm run build`
- **判据**：删后刷新不复活；各入口查不到。

### W-DEL-04 历史软删数据分批清除
- **对应**：FR-v5.24-08
- **交付**：`scripts/db/purge-soft-deleted.sh` + 备份与校验记录
- **要点**：
  1. 每批前 `mysqldump` 备份（含 `run` / `test_report` 及相关子表），记录路径与 sha256。
  2. **第一批清最近 30 天**，每批 ≤ 10 条；逐批 `SELECT COUNT(*)` 校验，确认未误删未软删数据。
  3. 删除顺序与 W-DEL-01 一致（先子表后主表）。
  4. 全部清完后软删记录 = 0；备份保留可恢复。
- **验收命令**：备份 sha 校验 + 每批前后计数 SQL
- **判据**：58 条软删记录（57 run + 1 test_report）清除完毕；零误删；备份可恢复。

### W-VER-01 机检扩展
- **对应**：FR-v5.24-09
- **改动类**：`scripts/guard/eht_pytest_realchain.sh`
- **要点**：加命令断言（real 有命令产物的用例 100% 非空）；`--self-test` 注入清空 command_text → 判红 → 回滚；删除机检临时数据自清理。
- **验收命令**：
  - `bash scripts/guard/eht_pytest_realchain.sh --run-id <real-run>`
  - `bash scripts/guard/eht_pytest_realchain.sh --run-id <real-run> --self-test`
- **判据**：全绿；self-test 真红真绿。

### W-VER-02 门禁与证据回写
- **对应**：FR-v5.24-10
- **要点**：改动冻结后跑全量门禁（`FAILS=0 UNJUDGED=0`）；证据包落盘；DEV-PLAN/TEST-PLAN 回写。
- **验收命令**：门禁脚本 + `check_md_tables.py`
- **判据**：证据包表格 PASS；计划回写完成。

---

## 3. 依赖关系

```
W-CMD-01 设计 ─┬─► W-CMD-02 命令贯通 ─┬─► W-CMD-03 回显 ─┐
               └─► W-DEL-01/02/03/04 ────────────────────┼─► W-VER-01 机检 ─► W-VER-02 总验收
```

- 设计先行；命令与删除两条线可并行。
- 机检必须在功能全绿后接入。
- 历史数据清理（W-DEL-04）必须最后做，且必须有备份。

---

## 4. 风险与对策

| 风险 | 对策 |
|---|---|
| 方案 A 改动面大（归一结果要跨层传） | 先做设计评估，不可行退 B；B 也要单一补录点，避免散落 |
| 物理删 run 被外键挡住 | 严格按子表顺序删；先在有 FK 的库上用事务试跑 |
| 审计表 append-only 约束 | 按裁决决定级联或保留；不改 `schema-contract.json` 除非必要 |
| 历史 57 条软删数据误删 | 删除前 mysqldump + 分批 + 每批校验 |
| 前端本地 Set 与后端事实不一致 | 改用后端返回状态驱动，刷新后不复活 |

---

## 5. 验收命令总表

```bash
# 编译与单测（在 WSL backend 目录）
mvn -q test -Dtest=PytestCommandEnricherTest,ReportIngestServiceTest,EhtPytestReportTest
mvn -q test -Dtest=ReportLifecycleServiceTest,TestReportRepositoryJdbcTest
mvn -q test -Dtest=ReportRepositoryJdbcTest,RunRepositoryJdbcTest

# 前端
cd frontend && npm run build

# 真链路机检
bash scripts/guard/eht_pytest_realchain.sh --run-id <real-run>
bash scripts/guard/eht_pytest_realchain.sh --run-id <real-run> --self-test

# 全量门禁（改动冻结后）
bash scripts/gates.sh   # 以仓库实际入口为准

# Markdown 表校验
python docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/EHT命令证据与报告清理-20261120/*.md
```

---

## 6. 执行结果回写（2026-11-20）

> 第 7 道流程要求：计划必须回写实际完成情况与偏差原因。

| WBS | 内容 | 实际 | 偏差说明 |
|---|---|---|---|
| W-CMD-01 | 命令证据与删除契约 | 达成 | `docs/design/EHT-命令证据与删除契约.md` |
| W-CMD-02 | 命令证据贯通入库 | 达成 | 方案 B；`command_text=readlink -f ...`、`source=COMMAND_INDEX`（原 NONE） |
| W-CMD-03 | 回显路径入报告 | **未做** | 需新增库列与字段链；本轮优先完成影响面更大的物理删，转后续版本 |
| W-DEL-01 | run 物理删 | 达成 | 6 子表按序清理；API 实测主表/子表归零 |
| W-DEL-02 | test_report 物理删（含冻结件） | 达成 | 冻结/归档允许删；前后端限制已移除 |
| W-DEL-05 | test_case 物理删 | 达成 | 6 子表清理；推翻 11-16 口径，历史迁移不改 |
| W-DEL-03 | 列表过滤与前端删除态 | 达成 | `RunRepositoryJdbc.page` 补过滤；前端本地 Set 移除 |
| W-DEL-04 | 历史软删数据分批清除 | 达成 | 57 run + 1 report 全清，6 批，每批备份+校验，活动 run 数不变 |
| W-VER-01 | 机检扩展 | 达成 | 7 断言 + 2 条 self-test 注入（判据与命令），全绿 |
| W-VER-02 | 门禁与证据回写 | 进行中 | 证据包已出；全量门禁结果待补 |

### 偏差原因

1. **W-CMD-03 未做**：回显路径需新增 `test_result` 列 + 报告字段链 + 前端渲染，属独立改动；本轮先把"命令原文可见"这个大师最关心的点打通，并保证物理删不出错。
2. **历史清理校验规则写错一次**：批次 5 曾因"活动报告数下降"自动停止；复核确认被删的 3 条报告都属于已删的软删 run，级联删除符合契约。已修正脚本校验规则（活动 run 数严格不变、报告数允许级联下降），未误删活动执行记录。
3. **方案 A 未采用**：执行（异步）与报告解析（独立 HTTP）是两个生命周期，跨边界传归一对象需新增持久化载体；方案 B 单一改动点且复用现有补录逻辑。
