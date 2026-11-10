-- v4.3.0 回滚：移除端口网卡归属（FR-v4.3-03 / PRD M3）。
--
-- 注意：回退会永久丢掉已填写的 nic_name / nic_index。这两列是人工/接口补充的归属信息，
-- 无法从其他表反推；设备、端口其余字段与链路一律不动。
ALTER TABLE `device_port` DROP KEY idx_device_port_nic;

ALTER TABLE `device_port`
  DROP COLUMN nic_name,
  DROP COLUMN nic_index;
