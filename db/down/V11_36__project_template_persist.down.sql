-- down for V11_36。
DROP TABLE IF EXISTS `project_template_asset`;
DROP TABLE IF EXISTS `project_template_item`;

DELETE FROM `project_template`
 WHERE `builtin` = 1
   AND `code` IN ('PRJ-RF-202611-001', 'API-RF-202611-001', 'UI-RF-202611-001', 'PY-PYT-202611-001');

ALTER TABLE `project_template`
    DROP KEY `idx_project_template_visibility`,
    DROP KEY `uk_project_template_code`,
    DROP COLUMN `description`,
    DROP COLUMN `owner_id`,
    DROP COLUMN `status`,
    DROP COLUMN `builtin`,
    DROP COLUMN `visibility`,
    DROP COLUMN `kernel`,
    DROP COLUMN `code`;
