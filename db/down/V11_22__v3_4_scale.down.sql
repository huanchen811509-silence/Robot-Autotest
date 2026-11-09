-- V11_22 down：删归档表并回退新增索引。
-- 注意：down 之前必须先 restore 所有 archived_run，否则冷表里的明细会随删表丢失。

DROP TABLE IF EXISTS `archived_keyword_result`;
DROP TABLE IF EXISTS `archived_test_result`;
DROP TABLE IF EXISTS `archived_run`;
DROP TABLE IF EXISTS `archive_policy`;

ALTER TABLE `run` DROP KEY `idx_run_project_created`;
