-- v4.4.0 / FR-v4.4-01：项目成员多角色
-- 一个用户在同一项目内可拥有 1~3 个项目角色；project_member.member_role 保留为主角色（展示/排序）。
CREATE TABLE `project_member_role` (
  project_id    BIGINT      NOT NULL,
  user_id       BIGINT      NOT NULL,
  role_code     VARCHAR(32) NOT NULL COMMENT 'admin/pm/engineer/viewer',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by    BIGINT      NULL,
  PRIMARY KEY (project_id, user_id, role_code),
  KEY idx_project_member_role_user (user_id, project_id),
  CONSTRAINT fk_project_member_role_project FOREIGN KEY (project_id) REFERENCES `project` (id),
  CONSTRAINT fk_project_member_role_user FOREIGN KEY (user_id) REFERENCES `app_user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员多角色（v4.4.0）';

-- 旧数据回填：member_role 逐行写入；异常值按最小权限落到 viewer（幂等，可重复执行）。
INSERT IGNORE INTO `project_member_role` (project_id, user_id, role_code, created_by)
SELECT project_id,
       user_id,
       CASE
         WHEN LOWER(TRIM(member_role)) IN ('admin', 'pm', 'engineer', 'viewer')
           THEN LOWER(TRIM(member_role))
         ELSE 'viewer'
       END,
       created_by
  FROM `project_member`;

-- 编码只读（FR-v4.4-08）：记录唯一一次改码的时间，防止二次变更。
ALTER TABLE `project`
  ADD COLUMN `code_changed_at` DATETIME(3) NULL COMMENT '编码最近一次变更时间；非空即视为已使用唯一改码机会' AFTER `visibility`;

-- 编码建议（FR-v4.4-07）：按「前缀-引擎-年月」原子分配序号，避免并发撞号。
CREATE TABLE `project_code_sequence` (
  scope       VARCHAR(128) NOT NULL COMMENT '前缀-引擎-年月',
  next_no     INT          NOT NULL DEFAULT 0,
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目编码序号（v4.4.0）';
