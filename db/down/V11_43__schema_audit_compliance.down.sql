-- down for V11_43：按「先还原主键、再删列」逆序回滚。
-- 注意：project_member_role 若已存在 id 重复值，还原联合主键会失败——本脚本假定
-- 回滚前未发生过 (project_id,user_id,role_code) 之外的重复插入；id 列本身不承载业务含义。
ALTER TABLE `project_member_role`
  DROP PRIMARY KEY,
  DROP COLUMN `id`,
  ADD PRIMARY KEY (`project_id`,`user_id`,`role_code`),
  DROP INDEX `uk_project_member_role_unique`,
  DROP COLUMN `updated_by`,
  DROP COLUMN `updated_at`;

ALTER TABLE `project_code_sequence`
  DROP PRIMARY KEY,
  DROP COLUMN `id`,
  ADD PRIMARY KEY (`scope`),
  DROP INDEX `uk_project_code_sequence_scope`,
  DROP COLUMN `updated_by`,
  DROP COLUMN `created_by`,
  DROP COLUMN `created_at`;

ALTER TABLE `sync_job_item`
  DROP COLUMN `updated_by`,
  DROP COLUMN `updated_at`,
  DROP COLUMN `created_by`;

ALTER TABLE `sync_job`
  DROP COLUMN `updated_by`,
  DROP COLUMN `updated_at`;

ALTER TABLE `project_template_asset`
  DROP COLUMN `updated_by`,
  DROP COLUMN `updated_at`,
  DROP COLUMN `created_by`;

ALTER TABLE `project_template_item`
  DROP COLUMN `updated_by`,
  DROP COLUMN `updated_at`,
  DROP COLUMN `created_by`;

ALTER TABLE `import_item`
  DROP COLUMN `updated_by`,
  DROP COLUMN `updated_at`,
  DROP COLUMN `created_by`;
