-- v3.9.0 回退：摘掉 test_result 上的「下发命令」三列（FR-v3.9-02 / ADR-39-02）。
--
-- 注意：回退会永久丢掉已采集的命令原文、来源与退出码——这三列全是从原始
-- output.xml / pytest-report.json 派生出来的，丢了只能重新入库拿回来（重跑用例不补历史）。
-- 用例结论、失败分类、耗时等既有列一律不动。
--
-- 删除顺序与 up 严格相反：先退出码、再来源、最后命令原文。
-- 中途失败时残留的列始终是「前一级还在、后一级已缺」的可解释状态，
-- 不会出现「有来源却没有原文」这种半截结构骗过读取侧。
ALTER TABLE `test_result` DROP COLUMN `command_exit_code`;
ALTER TABLE `test_result` DROP COLUMN `command_source`;
ALTER TABLE `test_result` DROP COLUMN `command_text`;
