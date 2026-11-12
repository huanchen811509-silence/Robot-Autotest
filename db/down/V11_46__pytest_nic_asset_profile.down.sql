-- down for V11_46：撤销资产 NIC 画像与 inventory 稳定键（v5.1.0 · W51-01）。
-- 顺序：先摘索引再删列 —— 索引先指向列，列删掉后残留索引会变孤儿（ERROR 1072）。
-- 数据面：全部是本版新增的可空列，drop 即回滚，旧版本来就不存这些字段。
ALTER TABLE `device` DROP INDEX `uk_device_project_host_key`;

ALTER TABLE `device`
  DROP COLUMN `host_key`,
  DROP COLUMN `os_family`,
  DROP COLUMN `os_version`,
  DROP COLUMN `architecture`,
  DROP COLUMN `ssh_port`;

ALTER TABLE `device_port` DROP INDEX `idx_device_port_pci`;

ALTER TABLE `device_port`
  DROP COLUMN `nic_vendor`,
  DROP COLUMN `nic_manufacturer`,
  DROP COLUMN `nic_model`,
  DROP COLUMN `nic_form_factor`,
  DROP COLUMN `pci_bdf`,
  DROP COLUMN `vendor_id`,
  DROP COLUMN `pci_device_id`,
  DROP COLUMN `driver`,
  DROP COLUMN `firmware`,
  DROP COLUMN `rdma_device`,
  DROP COLUMN `nic_port`,
  DROP COLUMN `numa_node`,
  DROP COLUMN `nic_role`,
  DROP COLUMN `capabilities_json`,
  DROP COLUMN `counter_patterns_json`;
