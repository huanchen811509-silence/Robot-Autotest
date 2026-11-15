-- V11_74 回滚：先删种子模板（字段随外键级联），再删列。
DELETE FROM `report_template` WHERE `project_id` = 0 AND `name` = 'EHT-PYTEST';
ALTER TABLE `report_template` DROP COLUMN `kernel`;
