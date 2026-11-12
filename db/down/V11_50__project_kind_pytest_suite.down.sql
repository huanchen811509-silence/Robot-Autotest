-- V11_50 回滚
ALTER TABLE `device`
  DROP KEY `idx_device_model_dict`,
  DROP COLUMN `model_dict_id`;

DROP TABLE IF EXISTS `nic_model_dict`;

ALTER TABLE `project`
  MODIFY COLUMN `kind` VARCHAR(16) NOT NULL DEFAULT 'standard'
    COMMENT 'standard（普通项目）/ platform（公共库载体，单例）';
