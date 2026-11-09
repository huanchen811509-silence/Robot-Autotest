-- v3.1.0「顺手」：保存视图 / 批量撤销 / 站内通知已读与偏好。
--
-- 为什么需要四张新表：
--   1. saved_view：列表筛选、列顺序、页大小需要跨会话保留（FR-3.1-03）。
--      只存 localStorage 无法跨设备/跨浏览器，且无法审计谁改了什么。
--   2. batch_undo：批量操作 5 分钟内可撤销（FR-3.1-04）。
--      撤销凭据必须落库：进程重启后仍有效，且能按 token 精确消费一次。
--   3. notification_preference：通知中心按类型订阅（FR-3.1-08）。
--   4. notification_read：已读状态按用户隔离。
--
-- 为什么已读要单独一张表，而不是在 notification 上加 read_at：
--   notification 是项目级通知本体（唯一键 uk_notification_dedup 保证「同一运行同一类型只一条」），
--   若把 read_at 挂在本体上，项目里任何人读一次，其他人都变成已读——这是错误的产品语义。
--   已读是「某人对某条」的关系，必须 (notification_id, user_id) 唯一，故拆表。

CREATE TABLE IF NOT EXISTS `saved_view` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `project_id` BIGINT NOT NULL COMMENT '所属项目',
  `user_id` BIGINT NOT NULL COMMENT '所属用户（视图按人隔离）',
  `view_scope` VARCHAR(24) NOT NULL COMMENT '作用域：case/run/report/asset/plan',
  `name` VARCHAR(80) NOT NULL COMMENT '视图名称',
  `payload` JSON NOT NULL COMMENT '筛选/排序/列/页大小快照',
  `is_default` TINYINT NOT NULL DEFAULT 0 COMMENT '是否该作用域默认视图',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `created_by` BIGINT NULL COMMENT '创建人 user_id',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
  `updated_by` BIGINT NULL COMMENT '最后修改人 user_id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_saved_view_owner` (`project_id`, `user_id`, `view_scope`, `name`),
  KEY `idx_saved_view_scope` (`project_id`, `view_scope`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='列表保存视图（按项目+用户+作用域隔离）';

CREATE TABLE IF NOT EXISTS `batch_undo` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `project_id` BIGINT NOT NULL COMMENT '所属项目',
  `token` CHAR(36) NOT NULL COMMENT '撤销凭据（一次性）',
  `action` VARCHAR(32) NOT NULL COMMENT '批量动作名',
  `target_type` VARCHAR(32) NOT NULL COMMENT '对象类型：test_case 等',
  `payload` JSON NOT NULL COMMENT '逐条前像快照',
  `item_count` INT NOT NULL DEFAULT 0 COMMENT '快照条数',
  `expires_at` DATETIME(3) NOT NULL COMMENT '撤销截止时间',
  `consumed_at` DATETIME(3) NULL COMMENT '实际撤销时间；非空即已消费',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `created_by` BIGINT NULL COMMENT '创建人 user_id',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
  `updated_by` BIGINT NULL COMMENT '最后修改人 user_id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_undo_token` (`token`),
  KEY `idx_batch_undo_expiry` (`project_id`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量操作撤销凭据（5 分钟窗口，一次性消费）';

CREATE TABLE IF NOT EXISTS `notification_preference` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `project_id` BIGINT NOT NULL COMMENT '所属项目',
  `user_id` BIGINT NOT NULL COMMENT '所属用户',
  `notification_type` VARCHAR(32) NOT NULL COMMENT '通知类型：RUN_FINISHED/RUN_FAILED/NODE_OFFLINE/PLAN_CONFLICT',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否订阅',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `created_by` BIGINT NULL COMMENT '创建人 user_id',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
  `updated_by` BIGINT NULL COMMENT '最后修改人 user_id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_pref` (`project_id`, `user_id`, `notification_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知订阅偏好（按项目+用户+类型）';

CREATE TABLE IF NOT EXISTS `notification_read` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `project_id` BIGINT NOT NULL COMMENT '所属项目',
  `notification_id` BIGINT NOT NULL COMMENT '通知本体 notification.id',
  `user_id` BIGINT NOT NULL COMMENT '阅读人 user_id',
  `read_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '该用户的首次阅读时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `created_by` BIGINT NULL COMMENT '创建人 user_id',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
  `updated_by` BIGINT NULL COMMENT '最后修改人 user_id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_read` (`notification_id`, `user_id`),
  KEY `idx_notification_read_inbox` (`project_id`, `user_id`, `read_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知已读记录（已读按用户隔离）';
