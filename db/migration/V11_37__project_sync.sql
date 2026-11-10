-- v4.9.0 / FR-v4.9-01：存量项目同步作业与逐项明细。
CREATE TABLE IF NOT EXISTS `sync_job` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT      NOT NULL,
    `source_kind`     VARCHAR(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL / TEMPLATE',
    `scope`           VARCHAR(16) NOT NULL DEFAULT 'CASES',
    `mode`            VARCHAR(16) NOT NULL DEFAULT 'track-latest'
        COMMENT 'track-latest / pinned / local_override',
    `status`          VARCHAR(16) NOT NULL DEFAULT 'PREVIEW'
        COMMENT 'PREVIEW / DONE / ROLLED_BACK / FAILED',
    `total`           INT NOT NULL DEFAULT 0,
    `new_count`       INT NOT NULL DEFAULT 0,
    `updated_count`   INT NOT NULL DEFAULT 0,
    `conflict_count`  INT NOT NULL DEFAULT 0,
    `unchanged_count` INT NOT NULL DEFAULT 0,
    `skipped_count`   INT NOT NULL DEFAULT 0,
    `preview_sha`     CHAR(64) NULL,
    `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_by`      BIGINT NULL,
    `executed_at`     DATETIME(3) NULL,
    `executed_by`     BIGINT NULL,
    `rolled_back_at`  DATETIME(3) NULL,
    PRIMARY KEY (`id`),
    KEY `idx_sync_job_project` (`project_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存量同步作业（v4.9.0）';

CREATE TABLE IF NOT EXISTS `sync_job_item` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `job_id`        BIGINT      NOT NULL,
    `item_type`     VARCHAR(16) NOT NULL DEFAULT 'CASE',
    `external_id`   VARCHAR(128) NOT NULL,
    `local_id`      BIGINT      NULL COMMENT '项目内用例 id；NEW 执行后回填',
    `remote_id`     BIGINT      NULL COMMENT '公共库用例 id',
    `action`        VARCHAR(16) NOT NULL COMMENT 'NEW / UPDATE / CONFLICT / UNCHANGED / SKIP',
    `local_sha`     CHAR(64)    NULL,
    `remote_sha`    CHAR(64)    NULL,
    `resolution`    VARCHAR(16) NULL COMMENT 'KEEP_LOCAL / TAKE_REMOTE / DUPLICATE',
    `message`       VARCHAR(512) NULL,
    `before_json`   MEDIUMTEXT  NULL COMMENT '执行前本地快照，用于回滚',
    `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sync_item` (`job_id`, `external_id`),
    KEY `idx_sync_item_action` (`job_id`, `action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步作业逐项明细（v4.9.0）';
