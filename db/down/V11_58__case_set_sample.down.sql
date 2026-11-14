-- v5.7.0 / W57-01 回滚
ALTER TABLE `test_set` DROP INDEX `idx_test_set_sample`;
ALTER TABLE `test_set`
  DROP COLUMN `user_modified`,
  DROP COLUMN `sample_l1`,
  DROP COLUMN `sample_flag`;
