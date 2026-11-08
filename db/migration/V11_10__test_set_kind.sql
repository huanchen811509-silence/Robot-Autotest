-- SCEN-02 / D4：用例集三态 + 来源设计指针。
-- kind 三态：case_set（源用例集）/ scenario（场景设计）/ execution_selection（执行选择快照）。
-- design_id 指向产生该条目的来源设计（可空：手工建的用例集没有来源）。
-- 只增列 / 增索引，不改既有列语义；三态取值由 TestSetService 在应用层收口，
-- 不引入 CHECK（MySQL/MariaDB 版本差异会让迁移不可复现）。
ALTER TABLE `test_set`
  ADD COLUMN `kind` VARCHAR(24) NOT NULL DEFAULT 'case_set' COMMENT 'case_set / scenario / execution_selection',
  ADD COLUMN `design_id` BIGINT NULL COMMENT '来源设计 id，可空',
  ADD INDEX `idx_test_set_kind` (`project_id`, `kind`),
  ADD INDEX `idx_test_set_design` (`design_id`);
