-- FR-AI-03 / FR-AI-04：为 AI 运行时补列，不改 V8_4 任何字节。
-- 只增列 / 索引；session_uuid 允许 NULL，存量行不受影响，新行由 Java 侧写 UUID。

ALTER TABLE `ai_session`
    ADD COLUMN `session_uuid` VARCHAR(36) NULL DEFAULT NULL,
    ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    ADD UNIQUE KEY `uk_ai_session_uuid` (`session_uuid`);

ALTER TABLE `ai_action`
    ADD COLUMN `stdout` TEXT NULL,
    ADD COLUMN `stderr` TEXT NULL,
    ADD COLUMN `exit_code` INT NULL DEFAULT NULL,
    ADD COLUMN `duration_ms` BIGINT NULL DEFAULT NULL,
    ADD COLUMN `cli_name` VARCHAR(32) NULL DEFAULT NULL,
    ADD COLUMN `timed_out` TINYINT(1) NOT NULL DEFAULT 0;
