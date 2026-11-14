-- v5.7.0 / W57-31 回滚
ALTER TABLE `run` DROP INDEX `idx_run_deleted`;
ALTER TABLE `run`
  DROP COLUMN `deleted_by`,
  DROP COLUMN `deleted_at`,
  DROP COLUMN `tags`,
  DROP COLUMN `remark`,
  DROP COLUMN `display_name`;

ALTER TABLE `test_report` DROP INDEX `idx_report_deleted`;
ALTER TABLE `test_report`
  DROP COLUMN `deleted_by`,
  DROP COLUMN `deleted_at`,
  DROP COLUMN `tags`,
  DROP COLUMN `remark`;
