-- V11_90 回滚：删除三个开箱模板及其字段（先字段后模板）。
DELETE f FROM `report_template_field` f
  JOIN `report_template` t ON t.`id` = f.`template_id`
 WHERE t.`project_id` = 0 AND t.`name` IN ('标准交付', '简要汇报', '审计留痕');

DELETE FROM `report_template`
 WHERE `project_id` = 0 AND `name` IN ('标准交付', '简要汇报', '审计留痕');
