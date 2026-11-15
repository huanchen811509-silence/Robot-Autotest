-- v5.9.0 / W59-03 回滚：只回滚本迁移建的索引（created_by 列属既有结构，不在此删除）
DROP INDEX `idx_project_created_by` ON `project`;
