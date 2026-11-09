-- v3.10.0 回退：摘掉 test_report 上的来源两列与联合索引（FR-v3.10-03 / ADR-310-07）。
--
-- 注意：回退会永久丢掉「这份报告是按哪个用例集建的」这层信息。报告本体与
-- 快照（snapshot_*）不受影响——快照自包含，来源只是元信息，所以历史报告
-- 回退后仍能打开、仍能导出，只是列表页不再区分来源。
--
-- 删除顺序与 up 严格相反：先删索引、再删 case_set_id、最后删 source_type。
-- 中途失败时残留结构始终是「前一级还在、后一级已缺」的可解释状态，
-- 不会出现「有用例集 id 却没有来源类型」这种半截结构骗过读取侧。
DROP INDEX `idx_report_source_case_set` ON `test_report`;
ALTER TABLE `test_report` DROP COLUMN `case_set_id`;
ALTER TABLE `test_report` DROP COLUMN `source_type`;
