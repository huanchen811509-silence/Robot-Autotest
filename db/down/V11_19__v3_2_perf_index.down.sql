-- v3.2.0 / EW32-02 回滚：只删本迁移新增的索引，不动数据与表结构。
ALTER TABLE `test_case`
    DROP INDEX `idx_case_project_updated`;

ALTER TABLE `run`
    DROP INDEX `idx_run_project_enq`,
    DROP INDEX `idx_run_project_status_enq`,
    DROP INDEX `idx_run_project_id`;

ALTER TABLE `test_report`
    DROP INDEX `idx_report_project_id`;
