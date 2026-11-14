-- V11_58 用例集样例标记（v5.7.0 / FR-v5.7-04、05 / W57-01）
--
-- 三条口径：
-- 1) 样例集不是「一种新集合类型」，而是「由系统按一级分类派生、可被用户接管的普通集合」。
--    用标记位而不是新表，才能让手工集与样例集共用同一套 CRUD、同一套执行选择。
-- 2) 幂等键是 (sample_flag=1, sample_l1)，不是名字。名字用户可改，改完若按名字判重，
--    重建会再造一个集；user_modified=1 表示用户已接管，播种与重建都必须跳过。
-- 3) 三列全部带 DEFAULT：历史行天然是「手工集、未接管」，无需回填。

ALTER TABLE `test_set`
  ADD COLUMN `sample_flag` TINYINT(1) NOT NULL DEFAULT 0
      COMMENT '1=按一级分类派生的样例集' AFTER `description`,
  ADD COLUMN `sample_l1` VARCHAR(128) NULL
      COMMENT '派生来源的一级分类名；幂等键的一部分' AFTER `sample_flag`,
  ADD COLUMN `user_modified` TINYINT(1) NOT NULL DEFAULT 0
      COMMENT '1=用户已改名/改成员，播种与重建跳过' AFTER `sample_l1`,
  ADD KEY `idx_test_set_sample` (`sample_flag`, `sample_l1`);
