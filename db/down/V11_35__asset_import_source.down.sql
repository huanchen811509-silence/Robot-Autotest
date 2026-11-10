-- down for V11_35。
ALTER TABLE `credential`
    DROP KEY `idx_credential_asset_external`,
    DROP COLUMN `cred_masked`,
    DROP COLUMN `cred_ref`,
    DROP COLUMN `external_id`,
    DROP COLUMN `asset_source`,
    DROP COLUMN `asset_status`;

ALTER TABLE `device`
    DROP KEY `idx_device_asset_external`,
    DROP COLUMN `environment`,
    DROP COLUMN `external_id`,
    DROP COLUMN `asset_source`,
    DROP COLUMN `asset_status`;
