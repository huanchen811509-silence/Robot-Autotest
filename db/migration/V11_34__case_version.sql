-- v4.6.0 / FR-v4.6-08：用例内容版本快照（更新即新增一版，旧版不可变）。
CREATE TABLE IF NOT EXISTS `case_version` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `project_id`    BIGINT      NOT NULL,
    `case_id`       BIGINT      NOT NULL,
    `version_no`    INT         NOT NULL,
    `content_sha`   CHAR(64)    NOT NULL,
    `snapshot_json` MEDIUMTEXT  NOT NULL COMMENT '该版本的完整字段快照',
    `source`        VARCHAR(32) NOT NULL DEFAULT 'import' COMMENT 'import / manual',
    `batch_id`      BIGINT      NULL,
    `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_by`    BIGINT      NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_case_version` (`case_id`, `version_no`),
    KEY `idx_case_version_content` (`case_id`, `content_sha`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用例内容版本（v4.6.0，只增不改）';
