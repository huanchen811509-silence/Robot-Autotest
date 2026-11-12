-- V11_55 回滚：先删两张手工表，再摘列
--
-- 顺序不能反：manual_execution 有外键指向 test_case / run，
-- 先删表再摘列才不会有约束残留。
-- 数据的后果要写清：手工执行记录（含逐步骤结论与附件清单）会全部丢失且不可恢复——
-- 附件文件本身还在证据目录里，但「哪一步对应哪张图」的映射没了。
-- 因此回滚前应先导出 manual_execution 与 manual_step_result 两张表。

DROP TABLE IF EXISTS `manual_step_result`;
DROP TABLE IF EXISTS `manual_execution`;

ALTER TABLE `test_result`
  DROP COLUMN `exec_mode`;

ALTER TABLE `test_case`
  DROP COLUMN `exec_mode`;
