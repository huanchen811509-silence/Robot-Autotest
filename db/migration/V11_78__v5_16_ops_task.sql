-- V11_78 运维任务表（v5.16.0 / FR-v5.16-09 / W66-01~03）
--
-- 口径：
--  1) 互斥沿用 V11_28 workspace_job 的成熟做法：active_lock 是生成列，
--     活跃状态算出 project_id:task_type，终态为 NULL；唯一索引允许多个 NULL，
--     于是「同项目同类型同时只允许一个进行中任务」由数据库兜底，不引外部锁。
--  2) status 比 workspace_job 多一个 ABORTED：任务被杀 / 租约超时必须判 ABORTED，
--     不能停在 RUNNING 假装还在跑。lease_until 是超时对账的时间锚点。
--  3) 审计四列齐全、单列 id 主键、datetime(3)、utf8mb4（db-audit 四判据）。
--  4) 只追加，不改任何既有表；回滚见同名 .down.sql。

CREATE TABLE IF NOT EXISTS `ops_task` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `project_id`    BIGINT        NOT NULL COMMENT '租户过滤列',
  `task_type`     VARCHAR(32)   NOT NULL COMMENT 'BACKUP / RESTORE_DRILL / UPGRADE_PRECHECK / ARCHIVE / PURGE',
  `operator_id`   BIGINT        NULL,
  `params_json`   LONGTEXT      NULL COMMENT '任务入参；口令与凭据一律不进这里',
  `phase`         VARCHAR(32)   NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / PRECHECK / EXECUTE / VERIFY / DONE',
  `progress`      INT           NOT NULL DEFAULT 0 COMMENT '进度 0-100',
  `status`        VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
                  COMMENT 'PENDING / RUNNING / SUCCESS / FAILED / ABORTED',
  `log_text`      LONGTEXT      NULL COMMENT '阶段日志（追加，不覆盖）',
  `result_json`   LONGTEXT      NULL COMMENT '阶段结论与本版只读产物',
  `lease_until`   DATETIME(3)   NULL COMMENT '租约到期；超时由 OpsTaskSweeper 对账判 ABORTED',
  `started_at`    DATETIME(3)   NULL,
  `finished_at`   DATETIME(3)   NULL,
  `created_at`    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `created_by`    BIGINT        NULL,
  `updated_at`    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                  ON UPDATE CURRENT_TIMESTAMP(3),
  `updated_by`    BIGINT        NULL,
  `active_lock`   VARCHAR(96)   GENERATED ALWAYS AS (
                    CASE WHEN `status` IN ('PENDING', 'RUNNING')
                         THEN CONCAT(`project_id`, ':', `task_type`) ELSE NULL END
                  ) STORED COMMENT '活跃任务互斥键；终态置 NULL 释放项目锁',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ops_task_active` (`active_lock`),
  KEY `idx_ops_task_project` (`project_id`, `task_type`, `status`),
  KEY `idx_ops_task_lease` (`status`, `lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维任务与项目锁（v5.16.0）';
