-- V11_84 回滚：删除 project.origin 列（索引随列自动删除）。
ALTER TABLE `project` DROP COLUMN `origin`;
