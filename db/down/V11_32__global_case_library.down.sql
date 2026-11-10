-- down for V11_32：移除用例扩展列与分类字典。
ALTER TABLE `test_case`
    DROP KEY `idx_test_case_external`,
    DROP KEY `idx_test_case_category_l1`,
    DROP COLUMN `version_no`,
    DROP COLUMN `lifecycle_status`,
    DROP COLUMN `content_sha`,
    DROP COLUMN `external_id`,
    DROP COLUMN `category_l1`;

DROP TABLE IF EXISTS `case_category`;
