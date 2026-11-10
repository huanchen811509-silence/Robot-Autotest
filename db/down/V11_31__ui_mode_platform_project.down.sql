ALTER TABLE `app_user` DROP COLUMN `default_ui_mode`;
DELETE FROM `project` WHERE kind = 'platform' AND code = 'PLATFORM-LIB';
ALTER TABLE `project` DROP INDEX `idx_project_kind`;
ALTER TABLE `project` DROP COLUMN `kind`;
