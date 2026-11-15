-- V11_75 设备快照对象化（v5.15.0 / FR-v5.15-01~03 / W65-01）
--
-- 口径：
--  1) 新增两张表，不改 env_profile。旧环境快照（snapshot_json）链路保持只读兼容，
--     采集链路从来不写它；本支建立的是「按项可查」的独立事实链。
--  2) 内容寻址：uk(project_id, snapshot_hash)。同一份清单重复采集复用同一快照行，
--     不产生第二个时点、不覆盖既有项 —— 只填空不覆盖非空。
--  3) append-only：与 device_host_spec 同口径，只给 created_by/created_at，
--     不写「更新人」这种假值；db/schema-contract.json 同步登记。
--  4) 每张表都是单列 id 主键、datetime(3)、utf8mb4，满足 db-audit 四判据。

CREATE TABLE `device_snapshot` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`       BIGINT       NOT NULL,
  `device_id`        BIGINT       NULL COMMENT '关联 device.id；按主机名直接采时为空',
  `host`             VARCHAR(128) NOT NULL COMMENT '采集目标（IP 或主机名）',
  `source`           VARCHAR(32)  NOT NULL DEFAULT 'HOST_INVENTORY' COMMENT '事实来源',
  `snapshot_hash`    CHAR(64)     NOT NULL COMMENT '按项规范化后的 sha256（内容寻址）',
  `spec_hash`        CHAR(64)     NULL COMMENT '关联 device_host_spec.spec_hash',
  `source_record_id` BIGINT       NULL COMMENT '关联 device_host_spec.id',
  `collected_at`     DATETIME(3)  NOT NULL COMMENT '采集时点（与主清单同一个时点）',
  `item_count`       INT          NOT NULL DEFAULT 0 COMMENT '按项条目数',
  `created_by`       BIGINT       NULL,
  `created_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_snapshot_hash` (`project_id`, `snapshot_hash`),
  KEY `idx_device_snapshot_project_device` (`project_id`, `device_id`, `collected_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备快照（内容寻址，v5.15.0）';

CREATE TABLE `device_snapshot_item` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`  BIGINT       NOT NULL,
  `snapshot_id` BIGINT       NOT NULL COMMENT '关联 device_snapshot.id',
  `item_key`    VARCHAR(128) NOT NULL COMMENT '按项键：10 类 category，重复时追加序号',
  `category`    VARCHAR(32)  NOT NULL COMMENT 'OS/KERNEL/CPU/MEMORY/DISK/GPU/NIC/IP/ROCE/ROUTE',
  `command`     VARCHAR(512) NULL COMMENT '该项实际下发的只读命令（原样留痕）',
  `value_json`  LONGTEXT     NULL COMMENT '采集值的 JSON；未采到为 NULL，不写占位值',
  `value_hash`  CHAR(64)     NULL COMMENT 'value_json 的 sha256，供按项快速比较',
  `collected`   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否真采到；未采到必须带 reason',
  `reason`      VARCHAR(512) NULL COMMENT '未采到的原因',
  `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_item_key` (`snapshot_id`, `item_key`),
  KEY `idx_snapshot_item_project_key` (`project_id`, `item_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备快照按项事实（v5.15.0）';
