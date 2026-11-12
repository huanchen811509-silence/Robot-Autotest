-- v5.1.0 · W51-01 · 资产 NIC 画像补齐（FR-v5.1-02 / 验收 A3）。
--
-- 背景：自研 pytest 框架的 `load_inventory()` 要求 inventory 的 `devices.nics.<netdev>`
-- 提供 NIC 级字段（product_model / pci_bdf / driver / rdma_device / capabilities ...）。
-- 主程序现状：`device.vendor/model` 是**主机级**，`device_port` 只有 `nic_name/nic_index`
-- （V11_29），渲染出来的 inventory 会被框架在加载阶段直接拒绝。
--
-- 设计取舍：
--   1. **不新建网卡实体表**。沿用 V11_29 的判断——网卡是设备自带属性，独立建表会引入
--      「网卡属于哪台设备」的第二真源，且历史端口无法补齐。全部走可空列，
--      语义是「已知则填、未知留空」，渲染器遇到空值走 `reason` 显式说明而不是编假值。
--   2. `device.host_key` 是 inventory `hosts` 的**稳定键**（`hostname@management_ip`
--      或纯 `hostname`，由工程配置 `inventory_key_rule` 决定）。它同时是 5.1.2 的
--      「升版存续 / 重命名跟随」锚点（H3/H4）——没有稳定键，重命名就等于换设备。
--   3. 主机级 OS 三件套（os_family / os_version / architecture）与 ssh_port 也在此补齐：
--      inventory 的 `host` 段需要它们，而 `device` 表此前只有 management_ip。
--      凭据**不在此表**，走 `credential` + `asset_credential_binding`（V11_45），
--      渲染期只写 `${ENV:XXX}` 引用（D5）。
--
-- expand-contract：全部是 ADD COLUMN ... NULL 与 ADD KEY，不重建表、不改既有列，
-- 因此 v5.0.0 二进制在新库上启动不失败（旧代码不引用新列）。

-- 1) 设备：inventory 稳定键 + 主机 OS 画像 + SSH 端口
ALTER TABLE `device`
  ADD COLUMN host_key     VARCHAR(128) NULL COMMENT 'inventory hosts 的稳定键（hostname 或 hostname@ip），执行期按此匹配',
  ADD COLUMN os_family    VARCHAR(32)  NULL COMMENT 'ubuntu/centos/kylin/...，对应 inventory host.os_family',
  ADD COLUMN os_version   VARCHAR(32)  NULL COMMENT '如 24.04，对应 inventory host.os_version',
  ADD COLUMN architecture VARCHAR(32)  NULL COMMENT 'x86_64/aarch64，对应 inventory host.architecture',
  ADD COLUMN ssh_port     INT          NULL COMMENT 'SSH 端口，空按 22';

-- host_key 项目内唯一：同一项目不允许两台设备抢同一个 inventory 键。
-- MySQL 的 UNIQUE 允许多行 NULL，历史设备未填不受影响。
ALTER TABLE `device` ADD UNIQUE KEY uk_device_project_host_key (project_id, host_key);

-- 2) 端口：NIC 级画像（对齐 inventory devices.nics.<netdev> 的字段名）
ALTER TABLE `device_port`
  ADD COLUMN nic_vendor          VARCHAR(64)  NULL COMMENT 'inventory vendor，如 nvidia/broadcom/intel',
  ADD COLUMN nic_manufacturer    VARCHAR(64)  NULL COMMENT 'inventory manufacturer，如 NVIDIA',
  ADD COLUMN nic_model           VARCHAR(128) NULL COMMENT 'inventory product_model，如 ConnectX-7',
  ADD COLUMN nic_form_factor     VARCHAR(32)  NULL COMMENT 'inventory form_factor，如 1x400G',
  ADD COLUMN pci_bdf             VARCHAR(32)  NULL COMMENT 'inventory pci_bdf，如 0000:31:00.0',
  ADD COLUMN vendor_id           VARCHAR(16)  NULL COMMENT 'inventory vendor_id，如 0x15b3',
  -- 注意：列名必须带 pci_ 前缀。`device_id` 在本表已被「所属设备外键」占用，
  -- 不加前缀会与既有列同名，MySQL 直接拒绝（同表不允许两个同名同义不明的列）。
  ADD COLUMN pci_device_id       VARCHAR(16)  NULL COMMENT 'inventory device_id（PCI Device ID），与所属设备外键无关',
  ADD COLUMN driver              VARCHAR(64)  NULL COMMENT 'inventory driver，如 mlx5_core',
  ADD COLUMN firmware            VARCHAR(64)  NULL COMMENT 'inventory firmware',
  ADD COLUMN rdma_device         VARCHAR(64)  NULL COMMENT 'inventory rdma_device，如 mlx5_0',
  ADD COLUMN nic_port            INT          NULL COMMENT 'inventory port（网卡物理口序号，不是端口表主键）',
  ADD COLUMN numa_node           INT          NULL COMMENT 'inventory numa_node',
  ADD COLUMN nic_role            VARCHAR(32)  NULL COMMENT 'inventory role：rx-data/tx-data/mgmt/...',
  ADD COLUMN capabilities_json   TEXT         NULL COMMENT 'inventory capabilities 数组，JSON 字符串数组',
  ADD COLUMN counter_patterns_json TEXT       NULL COMMENT 'inventory counter_patterns 对象，JSON';

-- 渲染期按 PCI 地址反查网卡（BDF 是全网唯一的物理寻址），也用于「型号字典」归一前的定位。
ALTER TABLE `device_port` ADD KEY idx_device_port_pci (device_id, pci_bdf);
