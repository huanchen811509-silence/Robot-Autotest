-- v4.9.3 / FR-v4.7-03：资产软失效状态统一为 deprecated（对齐 PRD 口径）。
-- 只改状态值与注释，不动业务键与索引；历史 inactive 就地迁为 deprecated，可回滚。
ALTER TABLE `device`
    MODIFY COLUMN `asset_status` VARCHAR(16) NOT NULL DEFAULT 'active'
        COMMENT 'active / deprecated（软失效，不删本体）';

ALTER TABLE `credential`
    MODIFY COLUMN `asset_status` VARCHAR(16) NOT NULL DEFAULT 'active'
        COMMENT 'active / deprecated（软失效，不删本体）';

UPDATE `device` SET `asset_status` = 'deprecated' WHERE `asset_status` = 'inactive';
UPDATE `credential` SET `asset_status` = 'deprecated' WHERE `asset_status` = 'inactive';

-- 资产采集字段：来自「当前测试环境」的服务器/交换机节，导入或采集后补全；
-- 只存事实（OS/内核/BMC/登录方式/账户名），口令一律走凭据库，不进本表。
ALTER TABLE `device`
    ADD COLUMN `operating_system` VARCHAR(64) NULL COMMENT '操作系统（采集/导入）',
    ADD COLUMN `kernel_version` VARCHAR(64) NULL COMMENT '内核版本（采集/导入）',
    ADD COLUMN `bmc_address` VARCHAR(128) NULL COMMENT 'BMC / 带外地址（采集/导入）',
    ADD COLUMN `login_mode` VARCHAR(64) NULL COMMENT '登录方式 / 端口（采集/导入）',
    ADD COLUMN `account` VARCHAR(128) NULL COMMENT '管理账户名（不含口令）';

