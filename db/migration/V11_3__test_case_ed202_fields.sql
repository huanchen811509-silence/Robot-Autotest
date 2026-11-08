-- EDIM-02 / D1：test_case 增 ED202 扩展列。只增列、不改既有列语义。
-- priority 存归一值（T0/T1/T2 → P0/P1/P2），source_priority 保留源表原值。
ALTER TABLE `test_case`
  ADD COLUMN `category_l2`     VARCHAR(128) NULL COMMENT '二级分类',
  ADD COLUMN `source_priority` VARCHAR(16)  NULL COMMENT '源表优先级原值（T0/T1/T2 或 P0/P1/P2），保留不覆盖',
  ADD COLUMN `case_class`      VARCHAR(64)  NULL COMMENT '用例分类',
  ADD COLUMN `test_category`   VARCHAR(64)  NULL COMMENT '测试归类',
  ADD COLUMN `ed202_coverage`  TEXT         NULL COMMENT 'ED202 覆盖情况 / CX7 结果记录 / 测试资源',
  ADD COLUMN `source_sheet`    VARCHAR(128) NULL COMMENT '来源 sheet 名（UECGSE适配 83 条可追溯）';

ALTER TABLE `test_case` ADD KEY `idx_test_case_source_sheet` (`project_id`, `source_sheet`);
