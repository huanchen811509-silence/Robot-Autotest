# ADR-3X-04 · 统一分页与深分页键集

**状态** 已采纳 ｜ **日期** 2026-11-08 ｜ **约束范围** v3.2.0 起全部列表接口
**关联** FR-3.2-02、ADR-3X-01、`docs/evidence/v3.2.0/EW32-02/`

## 背景

v3.1 及以前的列表接口各写各的分页：

- 用例列表 `Math.min(size, 200)`，运行列表 `size > 100` 直接抛错，审计列表没有上限；
- 排序字段由前端传入字符串，部分仓储直接拼进 `ORDER BY`；
- 深翻页一律 `LIMIT ? OFFSET ?`，页号越大越慢，且随数据量线性劣化。

接口越多，这些差异越难在评审时看出来——前端要为每个列表写一套边界处理。

## 决策

1. **参数归一化只做一次**：新增 `common/query/PageQuery`，`page<1→1`、`size<1→20`、
   `size>100→100`，`offset()` 只在此处计算。
2. **排序白名单**：`sort` 只接受调用方显式登记的「请求字段 → SQL 列」映射；未登记抛
   `INVALID_ARGUMENT`（40001），绝不把外部字符串拼进 SQL。方向支持 `-field` / `field,asc`。
3. **键集分页**：高频且随运行数据线性增长的三张表（`test_case`、`run`、`audit_log`）
   提供 `beforeId` / `cursor`，走 `id < ?`（审计为 `occurred_at,id` 的 OR 展开式），
   彻底去掉深翻 OFFSET。其余基数有上界的表（项目/计划/用例集/设备/连线/用户）保留页码模式。
4. **索引配合**：`V11_19` 新增 `(project_id,updated_at)`、`(project_id,id)`、
   `(project_id,enqueued_at,id)`、`(project_id,run_status,enqueued_at,id)`、
   `(project_id,id)`（报告）；审计复用既有 `idx_audit_log_project_time`。
5. **访问路径钉死**：实测 MySQL 8.0.46 在索引变多后会把 `ORDER BY id DESC` 误选到
   `(project_id,updated_at)` 并触发 `Using filesort`；因此按 id 排序分支使用
   `FORCE INDEX (idx_<table>_project_id)`，避免「加索引反而变慢」。

## 备选方案

| 方案 | 否决原因 |
|---|---|
| 继续各仓储自己 `Math.min` | 边界不一致，评审不可见 |
| 引入 Spring Data `Pageable` | 需要改接口签名与依赖，收益仅是少写几行 |
| 全部列表上键集 | 小表收益为 0，前端「跳页」能力反而丢失 |
| 全部加 `FORCE INDEX` | 按时间排序时优化器选择更优，强制反而限制它 |

## 后果

- 正面：新增列表接口只需给白名单 + 默认列；越界、深翻、任意排序都有统一护栏。
- 负面：`PageQuery` 的 `keyset(size,beforeId)` 只支持 id 倒序；按时间键集的列表需要
  自己拼 OR 展开式（审计已实现，见 `AuditRepositoryJdbc`）。
- 兼容：既有 URL 参数（`page`/`size`/`sort`）语义不变；`beforeId` 为可选增量参数。
- 证据：`EW32-02` 的 13 行 OFFSET 清单、前后 EXPLAIN、FORCE INDEX 修复记录。

## 修订记录

| 日期 | 变更 |
|---|---|
| 2026-11-08 | 首版；含 EXPLAIN 实测发现的 filesort 回退与修复 |
