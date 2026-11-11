-- v4.12.0 / FR-v4.12-01~04：资产凭证与设备的绑定（凭证「列举设备」的前提）。
--
-- 背景：现场 12 台设备已入库，但设备与凭证之间没有任何关系列 —— 采集时只能靠人记
-- 「这台机器用哪条口令」，凭证页也回答不了「这条凭证服务哪些设备」。本迁移补两件事：
--   1) 一台设备在「业务管理面」和「BMC/带外面」各有一个默认凭证 → 两个可空 FK 列；
--   2) 映射前的内网真地址（172.31.x）本机不可达，只作存档 → 两个 alt 列。
--
-- 处置口径：
--   * 只增可空列，不改既有列类型与语义，历史行无需回填（NULL = 未绑定 / 未登记）；
--   * 不加外键约束：凭证软失效（asset_status='deprecated'）与硬删走应用层「被引用不可删」
--     校验（同 project_asset 逻辑引用的既有做法），加 FK 会让删除顺序与软失效语义互相打架；
--   * 加普通索引：反查「这条凭证服务哪些设备」是高频读，两个方向都要走索引。
--
-- 回滚：三条 DROP COLUMN + 两个 DROP INDEX，均为纯新增，不影响既有查询。

ALTER TABLE `device`
    ADD COLUMN `management_ip_alt` VARCHAR(64) NULL
        COMMENT '内网真地址（NAT 映射前），只作存档，不作采集目标',
    ADD COLUMN `bmc_address_alt` VARCHAR(128) NULL
        COMMENT 'BMC / 带外内网真地址（NAT 映射前），只作存档',
    ADD COLUMN `management_credential_id` BIGINT NULL
        COMMENT '业务管理面默认凭证 credential.id；NULL = 未绑定，不可采集',
    ADD COLUMN `bmc_credential_id` BIGINT NULL
        COMMENT 'BMC / 带外面默认凭证 credential.id；NULL = 未绑定',
    ADD KEY `idx_device_mgmt_cred` (`management_credential_id`),
    ADD KEY `idx_device_bmc_cred` (`bmc_credential_id`);

ALTER TABLE `credential`
    ADD COLUMN `connect_options` VARCHAR(256) NULL
        COMMENT 'SSH 客户端附加参数（如老 KEX 兼容串）；只允许 -o 形式的键值，禁止 shell 片段';
