-- v4.9.3：同一份工作簿既有用例区又有资产区，两类导入共用 import_batch 时
-- 原唯一键 (project_id, checksum) 会让资产批次撞车；把 kind 纳入唯一键，按业务线各自幂等。
ALTER TABLE `import_batch`
    DROP INDEX `uk_batch_checksum`,
    ADD UNIQUE KEY `uk_batch_checksum_kind` (`project_id`, `kind`, `checksum`);
