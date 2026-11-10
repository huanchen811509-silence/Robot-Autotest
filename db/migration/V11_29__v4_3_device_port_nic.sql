-- v4.3.0 · 端口网卡归属（FR-v4.3-03 R1 / PRD M1、M2）。
--
-- 为什么只加两个可空列、不建「网卡」实体表：网卡在现网是设备自带属性，
-- 独立建表会引入「网卡属于哪台设备」的第二真源，且历史端口无法补齐归属。
-- 可空列的语义是「已知则填、未知归入未归属分组」，不阻塞旧数据。
--
-- expand-contract：仅 ADD COLUMN ... NULL + ADD KEY，不重建表、不改既有列，
-- 因此在 v4.2.0 二进制上启动不会失败（旧代码不引用新列）。
ALTER TABLE `device_port`
  ADD COLUMN nic_name  VARCHAR(64) NULL COMMENT '网卡名（如 eth0/ens1f0），未填写归入未归属',
  ADD COLUMN nic_index INT         NULL COMMENT '网卡序号，用于同设备排序（升序）';

ALTER TABLE `device_port` ADD KEY idx_device_port_nic (device_id, nic_name, nic_index);
