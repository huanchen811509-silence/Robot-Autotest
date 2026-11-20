-- V11_87 回滚：加回 status 列（默认 1，与原建表一致）。
ALTER TABLE `test_case`
  ADD COLUMN `status` TINYINT NOT NULL DEFAULT 1 AFTER `safety_level`;
