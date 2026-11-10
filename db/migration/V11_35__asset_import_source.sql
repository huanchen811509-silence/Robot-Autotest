-- v4.7.0 / FR-v4.7-01：公共资产来源、业务键与软失效。
-- 只增列、不改既有列语义；owner_scope='PLATFORM' 时 project_id 为空（沿用 V7_3）。
ALTER TABLE `device`
    ADD COLUMN `asset_status` VARCHAR(16) NOT NULL DEFAULT 'active'
        COMMENT 'active / inactive（软失效，不删本体）',
    ADD COLUMN `asset_source` VARCHAR(32) NULL COMMENT 'manual / excel / topology',
    ADD COLUMN `external_id` VARCHAR(128) NULL COMMENT '源表业务键（同名设备去重）',
    ADD COLUMN `environment` VARCHAR(64) NULL COMMENT '环境名（当前测试环境等）',
    ADD KEY `idx_device_asset_external` (`owner_scope`, `external_id`);

ALTER TABLE `credential`
    ADD COLUMN `asset_status` VARCHAR(16) NOT NULL DEFAULT 'active',
    ADD COLUMN `asset_source` VARCHAR(32) NULL,
    ADD COLUMN `external_id` VARCHAR(128) NULL,
    ADD COLUMN `cred_ref` VARCHAR(256) NULL COMMENT '凭据引用（不落明文）',
    ADD COLUMN `cred_masked` VARCHAR(128) NULL COMMENT '掩码后的展示值',
    ADD KEY `idx_credential_asset_external` (`owner_scope`, `external_id`);
