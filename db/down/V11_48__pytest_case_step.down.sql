-- V11_48 回滚：先摘外键再删表（MySQL 不允许留着指向已删表的外键）。
ALTER TABLE `pytest_case_step` DROP FOREIGN KEY `fk_pytest_case_step_run`;
DROP TABLE IF EXISTS `pytest_case_step`;
