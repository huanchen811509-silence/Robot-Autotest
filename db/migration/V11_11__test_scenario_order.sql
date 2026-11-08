-- SCEN-01 / D6：场景「一层编排」补强。
-- 1) fk_scenario_project：场景必须挂在真实项目上，杜绝跨项目孤儿场景；
-- 2) step_order：把原先靠 id 隐式表达的步骤顺序显式化，同一项目下按 (step_order, id) 排序。
--    场景步骤本体仍是 case_set_ids 的有序序列，本列是排序锚点，不新建步骤表。
-- 只增列 / 增索引 / 增外键，不改既有列语义。迁移前实测 test_scenario 0 行且无孤儿 project_id，
-- 故外键可直接建；不建触发器（binlog + 无 SUPER 会被 Error 1419 拒，见 V11_2 修订说明）。
ALTER TABLE `test_scenario`
  ADD COLUMN `step_order` INT NOT NULL DEFAULT 0 COMMENT '场景步骤排序锚点',
  ADD INDEX `idx_test_scenario_order` (`project_id`, `step_order`, `id`),
  ADD CONSTRAINT `fk_scenario_project`
    FOREIGN KEY (`project_id`) REFERENCES `project` (`id`);
