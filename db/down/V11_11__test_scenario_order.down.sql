-- SCEN-01 / D6 回滚：先删外键，再删索引与列。
ALTER TABLE `test_scenario`
  DROP FOREIGN KEY `fk_scenario_project`,
  DROP INDEX `idx_test_scenario_order`,
  DROP COLUMN `step_order`;
