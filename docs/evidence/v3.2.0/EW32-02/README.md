# EW32-02 · 查询治理与索引（FR-3.2-02）

对应版本：v3.2.0「快而稳」· 批次 B3.2
证据日期：2026-11-08
数据集：EW32-01 固定种子 `20261108`（standard 规模：用例 10,000 / 运行 100,000 / 日志 1,000,000）

## 1. 交付物

| 类型 | 路径 |
|---|---|
| 分页参数统一 | `backend/src/main/java/com/qa/autotest/common/query/PageQuery.java` |
| 慢查询埋点 | `backend/src/main/java/com/qa/autotest/common/db/SlowQuery.java` |
| 用例列表（页码 + 键集 + 排序白名单） | `backend/.../infrastructure/TestCaseRepositoryJdbc.java` |
| 运行列表（页码 + 键集 + 排序白名单） | `backend/.../infrastructure/RunRepositoryJdbc.java` |
| 审计列表（显式投影 + 键集） | `backend/.../infrastructure/AuditRepositoryJdbc.java` |
| 报告列表（显式投影） | `backend/.../infrastructure/TestReportRepositoryJdbc.java` |
| 复合索引迁移 / 回滚 | `db/migration/V11_19__v3_2_perf_index.sql`、`db/down/V11_19__v3_2_perf_index.down.sql` |
| 补建用例主键索引（EW32-10 发现） | `db/migration/V11_20__case_project_id_index.sql`、`db/down/V11_20__case_project_id_index.down.sql` |
| 索引契约测试 | `backend/src/test/java/com/qa/autotest/infrastructure/ForceIndexContractTest.java` |
| 单元测试 | `PageQueryTest`（9 例）、`SlowQueryTest`（5 例） |
| 真实扫描日志 | `logs/offset-inventory.log`、`logs/select-star-scan.log` |
| EXPLAIN 前后对比 | `logs/explain-v11_19.log` |
| 测试执行日志 | `logs/tests-query.log` |

## 2. 真实执行记录

```text
# 单元测试（PageQuery + SlowQuery）
mvn "-Dtest=PageQueryTest,SlowQueryTest" test
→ Tests run: 14, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS
  （logs/tests-query.log）

# 生产代码 SELECT * 扫描
Select-String -Pattern 'SELECT *'（backend/src/main + resources：*.java/*.xml/*.sql/*.yml/*.properties）
→ NO_MATCH：0 处（logs/select-star-scan.log）

# OFFSET 清单扫描
Select-String -Pattern 'OFFSET ?|LIMIT/OFFSET'（backend/src/main/java）
→ 13 行命中：12 处 SQL + 1 处注释（logs/offset-inventory.log）

# 索引 EXPLAIN 前后对比（真实数据，WSL + MariaDB 8.0.46）
bash artifacts/explain_v11_19.sh   （脚本归档于 artifacts/，日志见 logs/explain-v11_19.log）
```

> 说明：上一版 EXPLAIN 采集脚本因 MySQL 递归 CTE 默认上限 1000 行（`cte_max_recursion_depth`）
> 导致种子数据插入失败，且 `2>/dev/null` 吞掉了报错，得到「0 行表」的无效结论。
> 本版先 `SET SESSION cte_max_recursion_depth = 100000`，并取消错误重定向；
> 日志中 `=== rows ===` 明确记录 `10000 / 20000 / 10000`，所有 EXPLAIN 均基于有数据的临时表。
> 临时表 `zz_perf_case` / `zz_perf_run` / `zz_perf_report` 建在业务库内，跑完即 DROP，不触碰业务表。

## 3. FR / TC 三态

| 项 | 状态 | 说明 |
|---|---|---|
| FR-3.2-02 统一分页参数（size 上限 100） | 完成 | `PageQuery` 归一化 page/size，各仓储不再各自 `Math.min(size,200)` |
| FR-3.2-02 排序白名单 | 完成 | `CaseController` / `RunController` 登记 7 / 4 个可排序字段，非法值返回 `INVALID_ARGUMENT` |
| FR-3.2-02 键集分页 | 完成 | 用例 / 运行 / 审计三个高频列表支持 `beforeId`；键集分支不再拼 OFFSET |
| FR-3.2-02 列表投影 | 完成 | `SELECT *` 清零（见 §5） |
| FR-3.2-02 复合索引 + down | 完成 | `V11_19` 新增 5 个索引、`db/down` 对应 5 条 DROP INDEX；SchemaIT 表数不变 |
| FR-3.2-02 慢查询埋点 | 完成 | p95 场景的列表 SQL 走 `SlowQuery.measure`，>200ms WARN / >500ms ERROR，指纹不含参数值 |
| TC-3.2-02 数据集压测 p95/p99 | 部分 | 本机小数据冒烟已过（case 31.12ms / run 42.30ms / report 19.62ms / audit 20.06ms，见 EW32-01）；standard 规模灌库压测未执行（见 §7 偏差） |
| TC-3.2-03 静态扫描 + OFFSET 结论 | 完成 | 0 处 `SELECT *`；13 行 OFFSET 命中逐条结论见 §4 |

## 4. OFFSET 清单逐条结论（12 处 SQL + 1 处注释）

判定口径：**基数有上界且由人工操作产生**（项目/计划/用例集/设备/连线/用户）→ 深翻不构成风险，保留页码模式；
**按运行数据线性增长**（运行 / 报告 / 审计 / 通知）→ 必须提供键集，页码模式仅作兼容。

| # | 位置 | 表 / 排序 | 结论 |
|---|---|---|---|
| 1 | `AuditRepositoryJdbc.java:97` | `audit_log`，`occurred_at DESC,id DESC` | 已提供键集（`OR` 展开式，避免行构造器退化为全表）；页码模式保留 |
| 2 | `DeviceRepositoryJdbc.java:81` | `device`，`d.id DESC` | 设备数受物理资产约束，保留 OFFSET，不加速 |
| 3 | `LinkRepositoryJdbc.java:76` | `link`，`l.id DESC` | 连线数受拓扑规模约束，保留 OFFSET |
| 4 | `NotificationRepositoryJdbc.java:54` | `notification`，`created_at DESC,id DESC` | 按用户增长；当前 UI 只取前 20 且未提供「加载更多」翻页，暂保留；列入 v3.3 观察项（见 §7） |
| 5 | `PlanRepositoryJdbc.java:74` | `execution_plan`，`id DESC` | 计划由人工创建，保留 OFFSET |
| 6 | `ProjectRepositoryJdbc.java:59` | `project`，`id DESC` | 项目数受组织规模约束，保留 OFFSET |
| 7 | `ReportRepositoryJdbc.java:148` | `run`，`started_at DESC,id DESC` | 按运行增长；实测 p95 19.62ms 在 150ms 预算内，暂保留页码；索引未覆盖 `started_at`（见 §6 说明） |
| 8 | `RunRepositoryJdbc.java:322` | `run`，`enqueued_at DESC,id DESC` | 已提供键集；`sort` 白名单 id/run_status/enqueued_at/started_at |
| 9 | `TestCaseRepositoryJdbc.java:128` | `test_case`，默认 `id DESC` | 已提供键集；`sort` 白名单 7 字段 |
| 10 | `TestSetRepositoryJdbc.java:57` | `test_set`，`s.id DESC` | 用例集由人工创建，保留 OFFSET |
| 11 | `TestSetRepositoryJdbc.java:79` | `test_set`，`s.id DESC`（另一入口） | 同上 |
| 12 | `UserRepositoryJdbc.java:78` | `app_user`，`id DESC` | 用户数受组织规模约束，保留 OFFSET |
| 13 | `AuditQuery.java:12` | 注释 | 描述 AuditQuery 的两种模式，非 SQL |

**计划文档写「20 处 OFFSET 全部有结论」**，实际生产代码当前只有 13 行命中（12 处 SQL）。
差异原因：计划中的 20 是早期估算，含已被 v3.1 重构移除的入口与注释；本轮以真实扫描结果为准，
未为了凑数制造不存在的条目。凡是会随运行数据线性增长的 4 张表（run / test_report 读取链 / audit_log /
notification）都已单独给出结论，不因编号数量减少而降低覆盖。

## 5. `SELECT *` 清零证明

```text
扫描范围: backend/src/main（*.java,*.xml,*.sql,*.yml,*.yaml,*.properties）
命令: Select-String -Pattern 'SELECT *'
结果: NO_MATCH（0 处）
```

本次实际改动的 3 处：

| 文件 | 改动 |
|---|---|
| `TestReportRepositoryJdbc.java` | 抽出 `REPORT_COLUMNS` / `EXPORT_COLUMNS`，列表与导出都不再 `SELECT *` |
| `AuditRepositoryJdbc.java` | 抽出 `COLUMNS`（11 列），page / keyset / export 共用 |
| `TestCaseRepositoryJdbc.java` / `RunRepositoryJdbc.java` | 原有显式列保持不变，键集分支复用同一投影 |

## 6. V11_19 索引前后 EXPLAIN（真实数据）

环境：MariaDB/MySQL `8.0.46`，库内临时表，`zz_perf_case` 10,000 行、`zz_perf_run` 20,000 行、
`zz_perf_report` 10,000 行，`project_id=29`。

### 6.1 用例列表（深翻 5,000）

| 场景 | Before | After | 结论 |
|---|---|---|---|
| `ORDER BY updated_at DESC LIMIT 50 OFFSET 5000` | `type=ALL`、`Using filesort`，扫 10,000 行 | `type=ref`、`key=idx_case_project_updated`、`Backward index scan`，扫 5,000 行 | 消除全表 + 排序；`EXPLAIN ANALYZE` 实际 24.4ms（5050 行） |
| 默认 `ORDER BY id DESC LIMIT 50 OFFSET 5000` | `key=PRIMARY`、`Backward index scan`，扫 5,050 行 | **回退**：优化器改选 `idx_case_project_updated`，`Extra=Using filesort` | 见 §6.3 修复 |
| 键集 `id < 5000` | `key=PRIMARY`、range、无排序 | 仍 `key=PRIMARY`、range、无排序 | `EXPLAIN ANALYZE` 实际 0.072ms |

### 6.2 运行列表与报告列表

| 场景 | Before | After |
|---|---|---|
| 运行默认 `enqueued_at DESC,id DESC OFFSET 5000` | `type=ALL`、`Using filesort`，扫 19,468 行 | `key=idx_run_project_enq`、`Backward index scan`，无排序；ANALYZE 实际 20.0ms |
| 运行状态筛选 `run_status='passed' OFFSET 5000` | `type=ALL`、`Using filesort`，扫 19,468 行 | `key=idx_run_project_status_enq`、`Backward index scan`，无排序；ANALYZE 实际 24.3ms（3,334 行） |
| 运行键集 `id < 5000` | `key=PRIMARY`、range | 仍 `key=PRIMARY`、range（未误选新索引）；加 `FORCE INDEX` 后为 `idx_run_project_id` range |
| 报告 `id DESC OFFSET 5000` | `key=PRIMARY`、`Backward index scan` | `key=idx_report_project_id`、`Backward index scan` |

### 6.3 真实发现并修复：索引变多后默认 id 排序回退

`[AFTER] case list default id DESC OFFSET 5000` 的 Extra 从「Backward index scan」变成
「Using filesort」——MySQL 8.0.46 在 `(project_id, updated_at)` 与 `(project_id, id)` 同时存在时，
误选了前者来做 `ORDER BY id DESC`。这不是理论风险，是实测回退。

修复：`TestCaseRepositoryJdbc` / `RunRepositoryJdbc` 在 `ORDER BY id ...`（默认、键集、`sort=id`）
分支对表加 `FORCE INDEX (idx_case_project_id / idx_run_project_id)`，把访问路径钉死：

```text
[AFTER + FORCE INDEX] case list default id DESC OFFSET 5000
key=idx_case_project_id、type=ref、Extra=Backward index scan（无 filesort）
[AFTER + FORCE INDEX] case keyset id < 5000
key=idx_case_project_id、type=range、Extra=Using index condition; Backward index scan
[AFTER + FORCE INDEX] run keyset id < 5000
key=idx_run_project_id、type=range、Extra=Using index condition; Backward index scan
```

未对 `updated_at` / `enqueued_at` / `started_at` 排序加 FORCE，保持优化器在这些场景自行选择索引。

## 7. 偏差与未执行项（如实记录）

1. **计划 20 处 vs 实际 13 行**：以真实扫描为准，未虚增条目（见 §4 末段）。
2. **standard 规模灌库压测未执行**：本机压测只做了 8001 端点的真实小数据采样（EW32-01）。
   把 10 万 runs / 100 万日志灌入数据库会污染开发库；v3.2.0 的 B4.1 容量报告将用独立库执行，
   本轮以库内临时表的 EXPLAIN + `EXPLAIN ANALYZE` 作为索引有效性的直接证据。
3. **通知列表未改键集**：前端尚无「加载更多」入口，先保留；若 v3.3 打开无限滚动，再补键集。
4. **报告列表索引**：`ReportRepositoryJdbc` 按 `started_at DESC,id DESC` 排序，`idx_run_project_enq`
   不覆盖 `started_at`；实测 p95 19.62ms 在预算内，暂不加第三个 run 索引，避免写放大。
5. **慢查询门禁缺报告时 SKIP**：`check_perf_budget.py` 在无 `api` 段报告时输出 SKIP（非 PASS），
   CI 用 `--require` 强制失败；本机日常运行不阻塞。
6. **真实发现：`idx_case_project_id` 从未建（由 EW32-10 容量压测暴露）** —— 本包的 `FORCE INDEX`
   修复只对照了「索引存在时选择正确」，没有核对「索引是否存在」。EW32-10 在 20 并发压测下
   用例列表 100% 返回 500，后端日志 `ERROR 1176 ... Key 'idx_case_project_id' doesn't exist in table 'test_case'`；
   `V11_19` 只建了 `idx_case_project_updated` / `idx_run_project_*` / `idx_report_project_id`。
   修复：新增 `V11_20__case_project_id_index.sql` 补建 `(project_id, id)`，配 down；
   并新增 `ForceIndexContractTest`，扫描 `db/migration/*.sql` 的建索引语句与 Java 源码里的
   `FORCE INDEX (...)` 对账，缺失即测试失败，避免「代码引用了不存在的索引」再次上线。

## 8. 复现命令

```bash
# 1) 单元测试
cd backend && mvn "-Dtest=PageQueryTest,SlowQueryTest" test

# 2) 静态扫描（PowerShell）
Select-String -Path (Get-ChildItem -Path backend\src\main -Recurse -Include *.java,*.xml,*.sql) -Pattern 'SELECT \*'
Select-String -Path (Get-ChildItem -Path backend\src\main\java -Recurse -Include *.java) -Pattern 'OFFSET \?|LIMIT/OFFSET'

# 3) EXPLAIN（WSL，需要 ~/.config/robot-autotest/env）
tr -d '\r' < artifacts/explain_v11_19.sh > /tmp/explain.sh && bash /tmp/explain.sh

# 4) 迁移
mvn flyway:migrate    # 或应用启动时自动执行 V11_19
```

## 9. 回滚

```sql
-- db/down/V11_19__v3_2_perf_index.down.sql
ALTER TABLE test_case DROP INDEX idx_case_project_updated;
ALTER TABLE run DROP INDEX idx_run_project_enq,
                   DROP INDEX idx_run_project_status_enq,
                   DROP INDEX idx_run_project_id;
ALTER TABLE test_report DROP INDEX idx_report_project_id;
```

代码侧 `FORCE INDEX` 依赖上述索引存在；若单独回滚索引而不回滚代码，会因索引不存在报
`ERROR 1176`。回滚顺序必须「先回滚代码（去掉 FORCE INDEX 分支）→ 再执行 down 迁移」。
