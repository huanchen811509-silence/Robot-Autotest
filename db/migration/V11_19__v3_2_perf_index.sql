-- v3.2.0 / EW32-02：核心列表复合索引。
-- 只加索引、不改表结构：迁移可回滚，SchemaIT 表数保持不变。
--
-- 依据（EXPLAIN 前后对比见 docs/evidence/v3.2.0/EW32-02/README.md）：
--   * test_case 默认 ORDER BY id DESC、可选 updated_at 排序 → (project_id, updated_at)
--   * run 默认 WHERE project_id ORDER BY enqueued_at DESC,id DESC → (project_id, enqueued_at, id)
--   * run 状态筛选 + 时间排序 → (project_id, run_status, enqueued_at, id)
--   * run 键集分页 WHERE project_id AND id < ? ORDER BY id DESC → (project_id, id)
--   * test_report 列表 WHERE project_id ORDER BY id DESC → (project_id, id)
-- audit_log 已有 idx_audit_log_project_time(project_id, occurred_at)，本迁移不重复添加。

ALTER TABLE `test_case`
    ADD INDEX `idx_case_project_updated` (`project_id`, `updated_at`);

ALTER TABLE `run`
    ADD INDEX `idx_run_project_enq` (`project_id`, `enqueued_at`, `id`),
    ADD INDEX `idx_run_project_status_enq` (`project_id`, `run_status`, `enqueued_at`, `id`),
    ADD INDEX `idx_run_project_id` (`project_id`, `id`);

ALTER TABLE `test_report`
    ADD INDEX `idx_report_project_id` (`project_id`, `id`);
