-- v5.7.0 / W57-02 回滚
ALTER TABLE `test_result` DROP INDEX `idx_test_result_criteria`;
ALTER TABLE `test_result`
  DROP COLUMN `criteria_missing`,
  DROP COLUMN `criteria_verdict`,
  DROP COLUMN `criteria_json`;
