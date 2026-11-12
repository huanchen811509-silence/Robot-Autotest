-- V11_52 回滚：删处置表
--
-- 处置状态是人工劳动成果，不是派生数据。回滚前应当先导出：
--   mysqldump ... case_failure_disposition > disposition-backup.sql
-- 这里只做结构回滚，不做数据保全——那是运维步骤，不是迁移该干的事。

ALTER TABLE `case_failure_disposition`
  DROP FOREIGN KEY `fk_case_failure_disposition_project`;
DROP TABLE IF EXISTS `case_failure_disposition`;
