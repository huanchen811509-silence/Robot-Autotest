-- v5.7.0 / W57-03 回滚
ALTER TABLE `execution_plan` DROP INDEX `idx_execution_plan_confirmed`;
ALTER TABLE `execution_plan`
  DROP COLUMN `confirmed_by`,
  DROP COLUMN `confirmed_at`,
  DROP COLUMN `confirmed_digest`;
