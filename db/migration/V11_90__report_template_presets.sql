-- V11_90 / FR-v5.34-01 / W84-01：三个开箱报告模板
--
-- 现状：只有 DEFAULT(robot) 与 EHT-PYTEST(pytest) 两个平台模板，且平台模板只读。
-- 口径：再加三个平台级开箱模板（标准交付 / 简要汇报 / 审计留痕），字段集来自 DEFAULT：
--       标准交付 = 全字段；审计留痕 = 全字段；简要汇报 = 结论层子集。
--       平台模板仍只读，用户走「复制为项目模板」后编辑（既有 copy 接口）。
-- 幂等：按 name + project_id=0 判重；字段用 NOT EXISTS 防重复插入。
INSERT INTO `report_template` (`project_id`, `name`, `description`, `version`, `is_default`)
SELECT 0, '标准交付', '开箱模板：C5 白名单全字段，按交付习惯排序', 1, 0
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `report_template` WHERE `project_id` = 0 AND `name` = '标准交付');

INSERT INTO `report_template` (`project_id`, `name`, `description`, `version`, `is_default`)
SELECT 0, '简要汇报', '开箱模板：结论层与关键指标，适合周报/汇报', 1, 0
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `report_template` WHERE `project_id` = 0 AND `name` = '简要汇报');

INSERT INTO `report_template` (`project_id`, `name`, `description`, `version`, `is_default`)
SELECT 0, '审计留痕', '开箱模板：全字段 + 时间线与免责声明，适合审计', 1, 0
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `report_template` WHERE `project_id` = 0 AND `name` = '审计留痕');

-- 标准交付 / 审计留痕：照抄 DEFAULT 的全部字段与顺序
INSERT INTO `report_template_field` (`project_id`, `template_id`, `field_key`, `sort_order`, `title_override`)
SELECT 0, t.`id`, f.`field_key`, f.`sort_order`, f.`title_override`
  FROM `report_template` t
  JOIN `report_template_field` f
    ON f.`template_id` = (SELECT MIN(`id`) FROM `report_template` WHERE `project_id` = 0 AND `name` = 'DEFAULT')
 WHERE t.`project_id` = 0 AND t.`name` IN ('标准交付', '审计留痕')
   AND NOT EXISTS (SELECT 1 FROM `report_template_field` x WHERE x.`template_id` = t.`id`);

-- 简要汇报：只取结论层与时间字段
INSERT INTO `report_template_field` (`project_id`, `template_id`, `field_key`, `sort_order`, `title_override`)
SELECT 0, t.`id`, f.`field_key`, f.`sort_order`, f.`title_override`
  FROM `report_template` t
  JOIN `report_template_field` f
    ON f.`template_id` = (SELECT MIN(`id`) FROM `report_template` WHERE `project_id` = 0 AND `name` = 'DEFAULT')
 WHERE t.`project_id` = 0 AND t.`name` = '简要汇报'
   AND f.`field_key` IN ('reportId', 'title', 'version', 'frozenAt', 'conclusion',
                         'passCount', 'failCount', 'skipCount', 'passRate',
                         'projectName', 'swBuild', 'startedAt', 'finishedAt')
   AND NOT EXISTS (SELECT 1 FROM `report_template_field` x WHERE x.`template_id` = t.`id`);
