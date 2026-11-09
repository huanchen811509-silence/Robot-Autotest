-- v3.11.0 · 测试资产主机清单（FR-v3.11-01 / ADR-311-01）。
--
-- 为什么一整条清单塞 JSON 而不是拆 10 张子表：这 10 类信息是**一次采集的整体**
-- （同一个时间点、同一台主机、同一批命令），拆表之后「这台的 CPU 是 10:00 采的、
-- 磁盘是 11:00 采的」这种半新半旧状态就合法了，而报告里必须是一个时点的事实。
-- 需要按项过滤的（RoCE 口数、网卡数）单独提成列，见下。
--
-- spec_json 每项都带 command 与 collectedAt，取不到的项带 reason 而不是空值——
-- 判据③「拿不到写未采集到并给原因，不填假值」的落地方式。
--
-- 保留策略：不在这里做，沿用 v3.4.0 的归档保留（archive_policy）口径，避免两处各判一次。
CREATE TABLE `device_host_spec` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`    BIGINT       NOT NULL,
  `device_id`     BIGINT       NULL COMMENT '关联 device.id；按主机名直接采时为空',
  `host`          VARCHAR(128) NOT NULL COMMENT '采集目标（IP 或主机名）',
  `collected_at`  DATETIME(3)  NOT NULL,
  `status`        VARCHAR(16)  NOT NULL COMMENT 'OK=全部采到 / PARTIAL=部分缺失 / FAILED=整体失败',
  `spec_json`     LONGTEXT     NOT NULL COMMENT '10 类清单结构化结果（含每项 command/collectedAt/reason）',
  `spec_hash`     CHAR(64)     NULL COMMENT 'spec_json 的 sha256，供两次采集做 diff',
  `nic_count`     INT          NOT NULL DEFAULT 0 COMMENT '网口数（提列只为列表筛选）',
  `roce_ports`    INT          NOT NULL DEFAULT 0 COMMENT 'RoCE 端口数（同上）',
  `roce_max_gbps` INT          NULL COMMENT 'RoCE 最高速率 Gb/s；未采到为 NULL 而不是 0',
  `created_by`    BIGINT       NULL,
  `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_host_spec_project_device` (`project_id`, `device_id`, `collected_at`),
  KEY `idx_host_spec_project_host` (`project_id`, `host`, `collected_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试资产主机清单采集结果（FR-v3.11-01）';
