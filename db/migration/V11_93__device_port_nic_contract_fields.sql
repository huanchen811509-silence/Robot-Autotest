-- V11_93 / 修复门禁 37-pytest_env_render：device_port 缺 6 个 NIC 字段
--
-- 框架 pytest-cases 的 `config/inventory/inventory_lab.yaml` 已扩到 23 个 NIC 级字段，
-- 平台渲染器 PytestEnvRenderer 只能渲染 16 个（差 gid_index / roce_gid_index / ip /
-- peer_ip / mgmt_device_id / mgmt_tool / sriov_offload，其中 ip 复用既有 ip_address）。
-- 渲染器缺键 => 框架那侧读到 None => perftest（RDM-014 等按 gid_index 取 GID）在真机上才炸。
--
-- 这里的取舍：**不**在渲染器里补 `null` 糊过机检。字段补 null 只是把事故推迟到真机，
-- 所以按「缺陷修复」补列，让资产侧真的能表达这些值。
-- 本轮只补数据通路（迁移 + 实体 + 渲染器），端口编辑表单仍未暴露这些字段——见
-- docs/evidence/v5.31.0/01-实施与验证记录.md §已知缺口。
ALTER TABLE `device_port`
  ADD COLUMN `gid_index` INT NULL
    COMMENT 'RoCE GID 索引（perftest 的 sgid_index）；本机 RC loopback 为 0' AFTER `rdma_device`;

ALTER TABLE `device_port`
  ADD COLUMN `roce_gid_index` INT NULL
    COMMENT 'IPv4-mapped RoCEv2 GID 索引（perftest 跨机建链用，通常 5/6）' AFTER `gid_index`;

ALTER TABLE `device_port`
  ADD COLUMN `peer_ip` VARCHAR(64) NULL
    COMMENT '该 NIC 对端地址（文本用例 Rail 用；脚本仍从资源池取）' AFTER `ip_address`;

ALTER TABLE `device_port`
  ADD COLUMN `mgmt_device_id` VARCHAR(64) NULL
    COMMENT '带外管理网卡设备名（如 tunic0）' AFTER `nic_role`;

ALTER TABLE `device_port`
  ADD COLUMN `mgmt_tool` VARCHAR(32) NULL
    COMMENT '带外管理工具（如 tunicadm）' AFTER `mgmt_device_id`;

ALTER TABLE `device_port`
  ADD COLUMN `sriov_offload_json` TEXT NULL
    COMMENT 'SR-IOV 卸载配置（JSON 对象；渲染成 inventory 的 sriov_offload）'
    AFTER `counter_patterns_json`;
