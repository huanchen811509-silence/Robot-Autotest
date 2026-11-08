-- V11_9 回退：只删本版新增的列与索引，不动 V8_4 建的三张表本身。
-- 顺序：先删唯一索引，再删列；与 up 严格成对。

ALTER TABLE `ai_session` DROP INDEX `uk_ai_session_uuid`;

ALTER TABLE `ai_session`
    DROP COLUMN `session_uuid`,
    DROP COLUMN `status`;

ALTER TABLE `ai_action`
    DROP COLUMN `stdout`,
    DROP COLUMN `stderr`,
    DROP COLUMN `exit_code`,
    DROP COLUMN `duration_ms`,
    DROP COLUMN `cli_name`,
    DROP COLUMN `timed_out`;
