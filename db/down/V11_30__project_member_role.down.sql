-- 回滚 v4.4.0：先核对 project_member_role 与 project_member.member_role 差异（应为 0）再执行。
ALTER TABLE `project` DROP COLUMN `code_changed_at`;
DROP TABLE IF EXISTS `project_code_sequence`;
DROP TABLE IF EXISTS `project_member_role`;
