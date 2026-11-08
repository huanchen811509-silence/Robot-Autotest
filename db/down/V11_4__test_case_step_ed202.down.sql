-- D2 down：只删本版新增索引；source_row 列与 uk_test_case_step 由既有迁移负责，不在此动。
ALTER TABLE `test_case_step` DROP INDEX `idx_test_case_step_source_row`;
