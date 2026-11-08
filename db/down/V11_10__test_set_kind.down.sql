-- SCEN-02 / D4 回滚：只删本迁移新增的列与索引。
ALTER TABLE `test_set`
  DROP INDEX `idx_test_set_design`,
  DROP INDEX `idx_test_set_kind`,
  DROP COLUMN `design_id`,
  DROP COLUMN `kind`;
