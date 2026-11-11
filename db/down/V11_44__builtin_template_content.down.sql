-- 回滚 V11_44：删除内置模板种子内容。
-- 判据：created_by IS NULL（种子标记）。应用侧写入的行一律带 operator，不会被误删。
-- 注意：若已用这些模板建过项目，删除模板内容**不影响**已生成的项目行
--       （建项目时是 copy 语义，副本已独立）。

DELETE FROM `project_template_item`
 WHERE template_id IN (2, 3, 4) AND created_by IS NULL;

DELETE FROM `project_template_asset`
 WHERE template_id IN (2, 3, 4) AND created_by IS NULL;
