-- v4.1.0 / FR-v4.1-01 / FR-v4.1-02：工作区导出/导入需要一张项目级任务表。
--
-- 口径（改之前先改 docs/contracts/workspace-bundle.md 与 ADR-4X-12..15）：
--   ① 任务即锁：`active_lock` 是生成列（status ∈ PENDING/RUNNING 时为 "project:type"，否则 NULL），
--      唯一索引允许多个 NULL，于是「同项目同方向同时只允许一个进行中任务」由数据库兜底，
--      不引 Redis、不复用 resource_lease 的租约语义；
--   ② 方向只有 EXPORT / IMPORT；粒度 PROJECT / DIR / FILE 对应 FR-v4.1-01 的三级传递；
--   ③ 导入的冲突策略、隔离报告、预检结果全部落在 report_json（JSON 文本），不拆表——
--      它们是任务的**输出**，不是查询维度；
--   ④ manifest_sha256 记录本次导出包清单摘要，导入端据此确认「验过的是哪一份包」；
--   ⑤ project_id 租户过滤列必带，读写一律过 TenantGuard；
--   ⑥ 审计四列齐全（db-audit 门禁口径），DATETIME(3)、utf8mb4、单列自增 PK。
--   ⑦ 已应用迁移不改字节；回滚见 db/down/V11_28__v4_1_workspace_job.down.sql。

CREATE TABLE IF NOT EXISTS `workspace_job` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       NOT NULL COMMENT '所属项目（租户过滤列）',
    `job_type`        VARCHAR(16)  NOT NULL COMMENT 'EXPORT / IMPORT',
    `granularity`     VARCHAR(16)  NOT NULL DEFAULT 'PROJECT'
                      COMMENT 'PROJECT / DIR / FILE（三级传递粒度）',
    `target_path`     VARCHAR(512) NULL COMMENT 'DIR/FILE 粒度的包内相对路径',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                      COMMENT 'PENDING / RUNNING / SUCCESS / FAILED',
    `progress`        INT          NOT NULL DEFAULT 0 COMMENT '进度 0-100（任务中心展示）',
    `bundle_path`     VARCHAR(512) NULL COMMENT '导出包/导入源相对 workspace.root 的路径',
    `strategy`        VARCHAR(16)  NULL COMMENT '导入冲突策略 SKIP / OVERWRITE / RENAME',
    `manifest_sha256` CHAR(64)     NULL COMMENT '本次导出包 manifest 的 sha256（导入端确认验的是哪份）',
    `report_json`     MEDIUMTEXT   NULL COMMENT '预检/冲突/隔离/引用映射报告（JSON 文本）',
    `error_message`   VARCHAR(1024) NULL COMMENT '失败原因（已脱敏）',
    `started_at`      DATETIME(3)  NULL COMMENT '开始执行时间',
    `finished_at`     DATETIME(3)  NULL COMMENT '进入终态时间',
    `created_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                      COMMENT '创建时间（审计四列口径，db-audit 检查）',
    `created_by`      BIGINT       NULL,
    `updated_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                      ON UPDATE CURRENT_TIMESTAMP(3),
    `updated_by`      BIGINT       NULL,
    `active_lock`     VARCHAR(96)  GENERATED ALWAYS AS (
                        CASE WHEN `status` IN ('PENDING', 'RUNNING')
                             THEN CONCAT(`project_id`, ':', `job_type`) ELSE NULL END
                      ) STORED COMMENT '活动任务互斥键；终态置 NULL 释放项目锁',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workspace_job_active` (`active_lock`),
    KEY `idx_workspace_job_project` (`project_id`, `job_type`, `status`),
    KEY `idx_workspace_job_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作区导出/导入任务与项目锁（v4.1.0）';
