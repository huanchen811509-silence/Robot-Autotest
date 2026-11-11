-- down for V11_45：撤销「凭据—设备绑定」与 SSH 连接参数（v4.12.0）。
-- 顺序：先摘索引再删列 —— 索引先指向列，列删掉后残留索引会变成孤儿（ERROR 1072）。
-- 数据面：这两个 FK 与两个 alt 地址列都是本版新增的可空列，drop 即回滚，
--       不需要恢复旧值（旧版本来就不存这两条绑定关系）。
ALTER TABLE `device`
  DROP INDEX `idx_device_bmc_cred`,
  DROP INDEX `idx_device_mgmt_cred`;

ALTER TABLE `device`
  DROP COLUMN `bmc_credential_id`,
  DROP COLUMN `management_credential_id`,
  DROP COLUMN `bmc_address_alt`,
  DROP COLUMN `management_ip_alt`;

ALTER TABLE `credential`
  DROP COLUMN `connect_options`;
