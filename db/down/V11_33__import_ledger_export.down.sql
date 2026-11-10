-- down for V11_33：删除导入明细与导出记录，移除批次扩展列。
DROP TABLE IF EXISTS `export_record`;
DROP TABLE IF EXISTS `import_item`;

ALTER TABLE `import_batch`
    DROP COLUMN `committed_at`,
    DROP COLUMN `deprecated_count`,
    DROP COLUMN `unchanged_count`,
    DROP COLUMN `updated_count`,
    DROP COLUMN `new_count`,
    DROP COLUMN `total_rows`,
    DROP COLUMN `kind`;
