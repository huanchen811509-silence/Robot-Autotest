-- EDIM-04 / D7：导入批次「逐 sheet」表头与行数对账表。
-- 跨包顺序：D7 先建（预检先落批次元数据，再被 D3/D1/D2 的判据/字段/步骤引用场景用到）。
-- 只增表；batch_id 弱引用 import_batch.id（V8_2 已建），按 db/README「弱引用只加索引」不加外键。
CREATE TABLE IF NOT EXISTS `import_batch_sheet` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `batch_id`         BIGINT       NOT NULL COMMENT '逻辑引用 import_batch.id（V8_2）',
    `sheet_name`       VARCHAR(128) NOT NULL COMMENT '来源 sheet 名',
    `header_row`       INT          NOT NULL COMMENT '表头行号（1-based，逐 sheet 独立 findHeaderRow）',
    `column_map_json`  TEXT         NULL COMMENT '字段 → 列序号 JSON，预检报告未映射列对账用',
    `row_count`        INT          NOT NULL DEFAULT 0 COMMENT '该 sheet 解析出的数据行数',
    `created_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_by`       BIGINT       NULL,
    `updated_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `updated_by`       BIGINT       NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_import_batch_sheet` (`batch_id`, `sheet_name`),
    KEY `idx_import_batch_sheet_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入批次逐 sheet 表头/列映射/行数对账（EDIM-04/D7）';
