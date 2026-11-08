-- D1 down：先删索引，再按 up 的逆序收回 6 个新增列。
ALTER TABLE `test_case` DROP INDEX `idx_test_case_source_sheet`;
ALTER TABLE `test_case`
  DROP COLUMN `source_sheet`,
  DROP COLUMN `ed202_coverage`,
  DROP COLUMN `test_category`,
  DROP COLUMN `case_class`,
  DROP COLUMN `source_priority`,
  DROP COLUMN `category_l2`;
