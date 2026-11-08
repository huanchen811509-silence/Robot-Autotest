-- EVID-01 / EVID-03 / D8：运行的冻结、幂等与轮次归属。
-- freeze_sha256     提交时冻结的执行计划 SHA（与请求携带值不符则拒收）
-- idempotency_key   同一 key 只允许产生 1 个 run（project_id + key 唯一；NULL 表示未启用幂等）
-- round_no          轮次号 1/2/3；三轮各一个独立 RUN_ID，缺轮不判完整通过
-- source_selection_id 关联的 execution_selection 冻结快照
-- 只增列 / 增索引，不改既有列语义；不回填历史 run 的 round_no（历史与当前分离）。
ALTER TABLE `run`
  ADD COLUMN `freeze_sha256` VARCHAR(64) NULL COMMENT '冻结计划 SHA-256',
  ADD COLUMN `idempotency_key` VARCHAR(128) NULL COMMENT '幂等键',
  ADD COLUMN `round_no` INT NULL COMMENT '轮次号 1/2/3，历史 run 为 NULL',
  ADD COLUMN `source_selection_id` BIGINT NULL COMMENT '执行选择冻结快照 id',
  ADD UNIQUE KEY `uk_run_idempotency` (`project_id`, `idempotency_key`),
  ADD INDEX `idx_run_round` (`project_id`, `source_selection_id`, `round_no`);
