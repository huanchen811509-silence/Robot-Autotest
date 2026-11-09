-- v3.9.0 · 用例级「下发命令」三列（FR-v3.9-02 / ADR-39-02）。
--
-- 为什么加在 test_result 而不是新表：命令与用例结论同生命周期、同 run/case 索引；
-- 另立一张表要为了「报告页一行」多一次 JOIN，还会出现「有结果无命令行」的半状态。
--
-- 三列全可空：历史数据不批量回填（回填只能靠猜，猜出来的命令比空更危险）。
-- 报告页对 NULL 显示「历史数据未采集（v3.9.0 之前）」，而不是显示空白让人以为没下发。
--
-- command_source 取值：RF_KEYWORD / PYTEST_MARK / STDOUT_EXTRACT / NONE。
-- 用 VARCHAR(24) 而不是 ENUM：新增来源不该再动一次表结构，合法性由
-- tools/check_case_command.py 与后端 CaseCommandExtractor 双重把关。
--
-- 命令原文上限 4KB（DEV-PLAN §9 容量口径），超长由写入侧截断并标记；
-- TEXT 列本身不设长度，截断发生在应用层，避免 MySQL 静默截断产生半个命令。
ALTER TABLE `test_result`
  ADD COLUMN `command_text` TEXT NULL COMMENT '下发命令原文（已脱敏，无命令为 NONE）' AFTER `failure_kind`,
  ADD COLUMN `command_source` VARCHAR(24) NULL COMMENT '命令来源：RF_KEYWORD/PYTEST_MARK/STDOUT_EXTRACT/NONE' AFTER `command_text`,
  ADD COLUMN `command_exit_code` INT NULL COMMENT '命令真实退出码；未知为 NULL，不写 0' AFTER `command_source`;
