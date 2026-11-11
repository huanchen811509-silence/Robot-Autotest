-- 回滚 V11_38：状态值改回 inactive，注释改回旧口径。
ALTER TABLE `device`
    DROP COLUMN `operating_system`,
    DROP COLUMN `kernel_version`,
    DROP COLUMN `bmc_address`,
    DROP COLUMN `login_mode`,
    DROP COLUMN `account`;

UPDATE `device` SET `asset_status` = 'inactive' WHERE `asset_status` = 'deprecated';
UPDATE `credential` SET `asset_status` = 'inactive' WHERE `asset_status` = 'deprecated';

ALTER TABLE `device`
    MODIFY COLUMN `asset_status` VARCHAR(16) NOT NULL DEFAULT 'active'
        COMMENT 'active / inactive（软失效，不删本体）';

ALTER TABLE `credential`
    MODIFY COLUMN `asset_status` VARCHAR(16) NOT NULL DEFAULT 'active'
        COMMENT 'active / inactive（软失效，不删本体）';
