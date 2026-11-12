-- V11_50 工程类型扩值 + 网卡型号字典（W52-01 / FR-v5.2-01、FR-v5.2-08）
--
-- 1) project.kind 增加 'pytest-suite'。列宽 16 够放（'pytest-suite' 12 字符），
--    只改注释不改列定义——改类型会动到 MySQL 的隐式转换行为，收益为零。
-- 2) nic_model_dict：型号字典。资产里的 nic_model 是自由文本，同一个型号在不同
--    录入批次里会写成 'ConnectX-7' / 'cx7' / 'MT2910'，按型号做基线比对必然对不上。
--    字典把「厂商 + 型号」收敛成一行，并挂上 PCI ID 与 capabilities 模板。
-- 3) device.model_dict_id：设备可选地指向字典行。**可空**且不加外键级联删除：
--    字典行被停用不该让资产消失，资产侧继续用自己录的 nic_model 字符串。
--
-- 三步走（加可空 → 回填 → 收紧）这里只做第一步：回填要人工确认型号映射，
-- 平台不猜——猜错就是「基线比对了另一张卡」，比不比对更危险。

ALTER TABLE `project`
  MODIFY COLUMN `kind` VARCHAR(16) NOT NULL DEFAULT 'standard'
    COMMENT 'standard（普通项目）/ platform（公共库载体，单例）/ pytest-suite（pytest 用例库工程）';

CREATE TABLE IF NOT EXISTS `nic_model_dict` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `vendor`            VARCHAR(64)  NOT NULL COMMENT '归一化厂商名（nvidia/broadcom/intel/mellanox…）',
  `model`             VARCHAR(128) NOT NULL COMMENT '归一化型号（connectx-7/…）',
  `display_name`      VARCHAR(160) NOT NULL COMMENT '界面展示名',
  `form_factor`       VARCHAR(32)  NULL     COMMENT 'pcie-ocp3/pcie-standard…',
  `vendor_id`         VARCHAR(16)  NULL     COMMENT 'PCI Vendor ID，如 15b3',
  `device_id`         VARCHAR(16)  NULL     COMMENT 'PCI Device ID，如 1021',
  `capabilities_json` JSON         NULL     COMMENT '能力模板：rdma/sriov/tsn… 供渲染器与用例预检使用',
  `enabled`           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0=停用（历史资产仍可引用）',
  `created_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `created_by`        BIGINT       NULL,
  `updated_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `updated_by`        BIGINT       NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_nic_model_dict_vendor_model` (`vendor`, `model`),
  KEY `idx_nic_model_dict_pci` (`vendor_id`, `device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='网卡型号字典（v5.2.0）';

ALTER TABLE `device`
  ADD COLUMN `model_dict_id` BIGINT NULL
    COMMENT '指向 nic_model_dict.id；为空表示资产未做型号归并（仍以 nic_model 文本为准）'
    AFTER `host_key`,
  ADD KEY `idx_device_model_dict` (`model_dict_id`);
