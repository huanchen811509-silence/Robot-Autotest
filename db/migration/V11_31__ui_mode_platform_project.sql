-- v4.5.0：双模式与平台项目（公共库载体）
ALTER TABLE `project`
  ADD COLUMN `kind` VARCHAR(16) NOT NULL DEFAULT 'standard'
    COMMENT 'standard（普通项目）/ platform（公共库载体，单例）' AFTER `test_kernel`,
  ADD KEY `idx_project_kind` (`kind`);

-- 记住上次模式（FR-v4.5-02）：无权限时前端回落 project，不改库值。
ALTER TABLE `app_user`
  ADD COLUMN `default_ui_mode` VARCHAR(16) NOT NULL DEFAULT 'project'
    COMMENT 'global / project（v4.5.0）' AFTER `status`;

-- 平台项目单例：承载公共用例/资产/模板。owner 允许为空（迁移时无管理员也不阻塞）。
INSERT INTO `project` (code, name, description, status, owner_id, visibility,
                       test_kernel, kind)
SELECT 'PLATFORM-LIB', '公共库', '平台公共用例、公共资产与模板的载体项目（不可见、不可删）',
       1, (SELECT MIN(id) FROM `app_user`), 'private', 'robot', 'platform'
 WHERE NOT EXISTS (SELECT 1 FROM `project` WHERE kind = 'platform');
