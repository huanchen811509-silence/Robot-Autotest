-- OPT-07 / FR-49-26：case_category 作用域扩到「平台 + 项目」；
-- test_case 加可空两级分类逻辑外键列与人工复核标记；test_scenario 关联场景集。
-- 阶段一（加可空列）：不回填、不加 NOT NULL、不加索引，存量 1798 行不受影响。
-- 说明：test_set_item.item_source 已由 V2_3__case_fields.sql 建立（VARCHAR(32) NULL），
-- 无需在本次迁移重复添加；批量加入时直接写 'category_filter'（FR-49-28）。
ALTER TABLE `case_category`
  ADD COLUMN `scope` VARCHAR(16) NULL DEFAULT 'platform'
      COMMENT 'platform / project；NULL 或 platform 均按平台域处理';

ALTER TABLE `test_case`
  ADD COLUMN `category_l1_id` BIGINT NULL COMMENT '逻辑外键 → case_category.id（一级分类）',
  ADD COLUMN `category_l2_id` BIGINT NULL COMMENT '逻辑外键 → case_category.id（二级分类）',
  ADD COLUMN `category_match_review` VARCHAR(16) NULL
      COMMENT 'AMBIGUOUS = 字符串非唯一匹配，待人工复核；唯一匹配回填后保持 NULL';

-- FR-49-29 降级：场景复用 test_set.kind=scenario 三态、仍限一层，
-- 用可空 set_id 关联其场景集；不改 case_set_ids 为中间表、不新建表。
ALTER TABLE `test_scenario`
  ADD COLUMN `set_id` BIGINT NULL
      COMMENT '关联 test_set.id（kind=scenario）；一层编排，parent_scenario_id 恒为 NULL';
