-- V11_76 探针包配置化（v5.15.0 / FR-v5.15-04 / W65-06~08）
--
-- 口径：
--  1) 本支**不复制采集命令文本**。命令唯一真源仍是 HostInventoryCollector.COMMANDS
--     （门禁 29 直接从那份 Java 字面量抽取并与真实证据逐字比对）；
--     表里只登记「哪个包、按什么顺序、哪些项 required」，运行时由服务把两者合起来。
--     存两份命令必然漂移，那是证据失效，不是配置化。
--  2) project_id=0 表示平台预置包；两表带完整审计四列、单列 id 主键、datetime(3)。
--  3) 10 类按 HostInventory.CATEGORIES 顺序登记；GPU / DISK / ROCE / ROUTE 允许缺失，
--     所以 required=0 —— 现场机器没 GPU 或没 RoCE 是正常结果，不是闸门失败。

CREATE TABLE `probe_pack` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`  BIGINT       NOT NULL DEFAULT 0 COMMENT '0=平台预置包',
  `code`        VARCHAR(64)  NOT NULL,
  `device_type` VARCHAR(32)  NOT NULL COMMENT 'HOST_LINUX / NETWORK_SWITCH / BMC',
  `name`        VARCHAR(128) NOT NULL,
  `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
  `created_by`  BIGINT       NULL,
  `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_by`  BIGINT       NULL,
  `updated_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_probe_pack_code` (`project_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集探针包（v5.15.0）';

CREATE TABLE `probe_pack_command` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `project_id`  BIGINT      NOT NULL DEFAULT 0,
  `pack_id`     BIGINT      NOT NULL,
  `command_key` VARCHAR(32) NOT NULL COMMENT '对应 HostInventoryCollector.COMMANDS 的 key',
  `required`    TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '缺该项是否算闸门失败',
  `sort_order`  INT         NOT NULL DEFAULT 0,
  `created_by`  BIGINT      NULL,
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_by`  BIGINT      NULL,
  `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_probe_pack_command` (`pack_id`, `command_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探针包按类配置（v5.15.0，命令文本不入库）';

INSERT IGNORE INTO `probe_pack`
  (`project_id`, `code`, `device_type`, `name`, `enabled`)
VALUES (0, 'HOST_LINUX_DEFAULT', 'HOST_LINUX', 'Linux 主机只读清单', 1);

INSERT IGNORE INTO `probe_pack_command`
  (`project_id`, `pack_id`, `command_key`, `required`, `sort_order`)
SELECT 0, p.id, k.command_key, k.required, k.sort_order
  FROM `probe_pack` p
  CROSS JOIN (
        SELECT 'OS'     AS command_key, 1 AS required, 0 AS sort_order UNION ALL
        SELECT 'KERNEL',   1, 1 UNION ALL
        SELECT 'CPU',      1, 2 UNION ALL
        SELECT 'MEMORY',   1, 3 UNION ALL
        SELECT 'DISK',     0, 4 UNION ALL
        SELECT 'GPU',      0, 5 UNION ALL
        SELECT 'NIC',      1, 6 UNION ALL
        SELECT 'IP',       1, 7 UNION ALL
        SELECT 'ROCE',     0, 8 UNION ALL
        SELECT 'ROUTE',    0, 9
       ) k
 WHERE p.project_id = 0 AND p.code = 'HOST_LINUX_DEFAULT';
