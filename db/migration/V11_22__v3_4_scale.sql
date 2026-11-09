-- v3.4.0「规模化」：归档与保留（FR-3.4-01）。
--
-- 关键约束（与既有 RetentionService 的「绝不删除 run / test_result」不同）：
--   1. 本包的目标是「主库体积可控且结论可恢复」，所以允许把**明细行**从热表移到冷表，
--      但 run 主记录、报告聚合数字、审计记录一律保留；
--   2. 冷表保留原始主键（id），恢复时按原 id 插回，引用关系（keyword_result.test_result_id）
--      不会错位；原 id 若已被新数据占用则恢复失败并显式报错，不静默丢数据；
--   3. 默认 enabled=0、dry_run 默认 true，与产物清理保持「默认不动数据」的口径。

CREATE TABLE IF NOT EXISTS `archive_policy` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT,
  `project_id`     BIGINT      NOT NULL,
  `enabled`        TINYINT(1)  NOT NULL DEFAULT 0,
  `retention_days` INT         NOT NULL DEFAULT 180,
  `batch_size`     INT         NOT NULL DEFAULT 200 COMMENT '单次最多归档多少个运行',
  `updated_by`     BIGINT      NULL,
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_archive_policy` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目归档保留策略（FR-3.4-01）';

CREATE TABLE IF NOT EXISTS `archived_run` (
  `id`                 BIGINT      NOT NULL AUTO_INCREMENT,
  `project_id`         BIGINT      NOT NULL,
  `run_id`             VARCHAR(64) NOT NULL COMMENT 'run.run_id 业务键',
  `run_db_id`          BIGINT      NOT NULL COMMENT 'run.id，恢复时校验',
  `test_result_count`  INT         NOT NULL DEFAULT 0,
  `keyword_result_count` INT       NOT NULL DEFAULT 0,
  `archived_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `restored_at`        DATETIME(3) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_archived_run` (`project_id`, `run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已归档运行的索引（FR-3.4-01）';

CREATE TABLE IF NOT EXISTS `archived_test_result` (
  `id`          BIGINT      NOT NULL COMMENT '原始 test_result.id，恢复时按原主键插回',
  `archive_id`  BIGINT      NOT NULL,
  `run_id`      BIGINT      NOT NULL,
  `case_id`     BIGINT      NULL,
  `case_key`    VARCHAR(160) NOT NULL DEFAULT '',
  `round_no`    INT         NOT NULL DEFAULT 1,
  `verdict`     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `duration_ms` BIGINT      NULL,
  `log_offset`  BIGINT      NULL,
  `log_span`    VARCHAR(64) NULL,
  `failure_msg` TEXT        NULL,
  `failure_kind` VARCHAR(16) NULL,
  `created_at`  DATETIME(3) NOT NULL,
  `created_by`  BIGINT      NULL,
  `updated_at`  DATETIME(3) NULL,
  `updated_by`  BIGINT      NULL,
  PRIMARY KEY (`id`),
  KEY `idx_archived_test_result_archive` (`archive_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归档用例结果明细（FR-3.4-01）';

CREATE TABLE IF NOT EXISTS `archived_keyword_result` (
  `id`             BIGINT       NOT NULL COMMENT '原始 keyword_result.id',
  `archive_id`     BIGINT       NOT NULL,
  `run_id`         BIGINT       NOT NULL,
  `test_result_id` BIGINT       NULL,
  `parent_id`      BIGINT       NULL,
  `kw_name`        VARCHAR(256) NOT NULL,
  `kw_status`      VARCHAR(16)  NULL,
  `depth`          INT          NOT NULL DEFAULT 0,
  `arg_count`      SMALLINT     NOT NULL DEFAULT 0,
  `log_span`       VARCHAR(64)  NULL,
  `log_offset`     BIGINT       NULL,
  `created_at`     DATETIME(3)  NOT NULL,
  `created_by`     BIGINT       NULL,
  `updated_at`     DATETIME(3)  NULL,
  `updated_by`     BIGINT       NULL,
  PRIMARY KEY (`id`),
  KEY `idx_archived_keyword_result_archive` (`archive_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归档关键字结果明细（FR-3.4-01）';

-- 归档候选按「项目 + 创建时间」倒序扫描；既有 idx_run_project_started 用不上 created_at。
ALTER TABLE `run` ADD KEY `idx_run_project_created` (`project_id`, `created_at`);
