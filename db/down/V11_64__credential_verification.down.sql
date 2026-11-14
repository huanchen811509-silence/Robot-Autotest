-- v5.7.0 / W57-38 回滚
ALTER TABLE `device_check` DROP INDEX `idx_device_check_credential`;
ALTER TABLE `device_check` DROP COLUMN `credential_id`;
DROP TABLE IF EXISTS `credential_verification`;
