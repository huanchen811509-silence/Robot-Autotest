-- v5.9.0 / W59-01 回滚：去掉演示标记与索引
ALTER TABLE `project` DROP INDEX `idx_project_demo`;
ALTER TABLE `app_user` DROP INDEX `idx_app_user_demo`;
ALTER TABLE `project` DROP COLUMN `is_demo`;
ALTER TABLE `app_user` DROP COLUMN `is_demo`;
