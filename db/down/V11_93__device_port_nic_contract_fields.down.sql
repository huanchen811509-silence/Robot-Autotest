-- 回滚 V11_93：去掉补的 6 个 NIC 字段
ALTER TABLE `device_port`
  DROP COLUMN `sriov_offload_json`,
  DROP COLUMN `mgmt_tool`,
  DROP COLUMN `mgmt_device_id`,
  DROP COLUMN `peer_ip`,
  DROP COLUMN `roce_gid_index`,
  DROP COLUMN `gid_index`;
