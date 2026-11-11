-- down for V11_41：按「先删场景关联列、再删分类列」回滚。
-- V11_42 的索引须由其自身 down 先摘除，故本脚本只删列；无外键约束需先摘。
ALTER TABLE `test_scenario`
  DROP COLUMN `set_id`;

ALTER TABLE `test_case`
  DROP COLUMN `category_l1_id`,
  DROP COLUMN `category_l2_id`,
  DROP COLUMN `category_match_review`;

ALTER TABLE `case_category`
  DROP COLUMN `scope`;
