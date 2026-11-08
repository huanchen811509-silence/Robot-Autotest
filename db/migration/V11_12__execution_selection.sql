-- SCEN-02 / D5：执行选择冻结快照。
-- 冻结后不可 UPDATE：不建触发器（V11_2 已实测 binlog + 无 SUPER 会被 Error 1419 拒），
-- 改由「仓储只发 INSERT + 应用层拒绝任何 UPDATE/DELETE + 单测锁死」保证不可变。
-- 审计四列 / 单列 id 主键 / datetime(3) / utf8mb4 均为 db-audit 与 SchemaIT 的硬判据。
CREATE TABLE IF NOT EXISTS `execution_selection` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `design_id` BIGINT NULL COMMENT '来源设计 id',
  `payload_json` LONGTEXT NULL COMMENT '冻结时的执行选择内容',
  `resource_snapshot_json` LONGTEXT NULL COMMENT '冻结时的资源快照',
  `sha256` CHAR(64) NOT NULL COMMENT '冻结内容 SHA-256',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `created_by` BIGINT NULL,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `updated_by` BIGINT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_execution_selection_sha` (`project_id`, `sha256`),
  KEY `idx_execution_selection_design` (`design_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
