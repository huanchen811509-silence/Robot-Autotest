-- EVID-01 / EVID-03 / D8 回滚：先删索引，再删列。
ALTER TABLE `run`
  DROP INDEX `idx_run_round`,
  DROP INDEX `uk_run_idempotency`,
  DROP COLUMN `source_selection_id`,
  DROP COLUMN `round_no`,
  DROP COLUMN `idempotency_key`,
  DROP COLUMN `freeze_sha256`;
