-- v4.6.0 / FR-v4.6-13：导入批次台账与逐行明细；v4.6.1 导出记录缓存。
ALTER TABLE `import_batch`
    ADD COLUMN `kind`              VARCHAR(16) NOT NULL DEFAULT 'project' COMMENT 'global / project',
    ADD COLUMN `total_rows`        INT NOT NULL DEFAULT 0,
    ADD COLUMN `new_count`         INT NOT NULL DEFAULT 0,
    ADD COLUMN `updated_count`     INT NOT NULL DEFAULT 0,
    ADD COLUMN `unchanged_count`   INT NOT NULL DEFAULT 0,
    ADD COLUMN `deprecated_count`  INT NOT NULL DEFAULT 0,
    ADD COLUMN `committed_at`      DATETIME(3) NULL;

CREATE TABLE IF NOT EXISTS `import_item` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `batch_id`     BIGINT      NOT NULL COMMENT '逻辑引用 import_batch.id',
    `row_no`       INT         NOT NULL COMMENT '批内行序号（1-based，稳定）',
    `case_code`    VARCHAR(128) NULL,
    `action`       VARCHAR(16) NOT NULL COMMENT 'NEW / UPDATE / UNCHANGED / DEPRECATE',
    `message`      VARCHAR(512) NULL,
    `payload_json` MEDIUMTEXT  NULL COMMENT '原始行快照，提交时据此落库',
    `case_id`      BIGINT      NULL COMMENT '提交后指向 test_case.id',
    `created_at`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_import_item` (`batch_id`, `row_no`),
    KEY `idx_import_item_batch` (`batch_id`, `action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入批次逐行明细（v4.6.0）';

CREATE TABLE IF NOT EXISTS `export_record` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `project_id`   BIGINT      NOT NULL,
    `kind`         VARCHAR(16) NOT NULL COMMENT 'cases / assets',
    `content_sha`  CHAR(64)    NOT NULL COMMENT '导出内容指纹（缓存键）',
    `file_name`    VARCHAR(255) NOT NULL,
    `size_bytes`   BIGINT      NOT NULL DEFAULT 0,
    `row_count`    INT         NOT NULL DEFAULT 0,
    `created_at`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_by`   BIGINT      NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_export_record` (`project_id`, `kind`, `content_sha`),
    KEY `idx_export_record_created` (`project_id`, `kind`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导出记录与缓存（v4.6.1）';
