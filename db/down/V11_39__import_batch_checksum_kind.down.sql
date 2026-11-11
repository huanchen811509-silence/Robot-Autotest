-- 回滚 V11_39：恢复 (project_id, checksum) 唯一键。
-- 注意：若同一文件已分别产生用例批次与资产批次，直接加回旧键会失败，需先清理其中一类批次。
ALTER TABLE `import_batch`
    DROP INDEX `uk_batch_checksum_kind`,
    ADD UNIQUE KEY `uk_batch_checksum` (`project_id`, `checksum`);
