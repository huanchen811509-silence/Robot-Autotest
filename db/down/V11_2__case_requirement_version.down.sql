-- D3 down：删表。2026-11-08 起 up 已不创建触发器（见 up 文件头修订说明），
-- 故不再 DROP TRIGGER；历史库若曾残留旧触发器，由 DBA 单独清理。
DROP TABLE IF EXISTS `case_requirement_version`;
