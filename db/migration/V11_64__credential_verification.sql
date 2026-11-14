-- V11_64 凭证验证记录与设备检查的凭证溯源（v5.7.0 / FR-v5.7-28~30 / W57-38）
--
-- 现状：credential 表只有 secret_ref，没有「哪条口令在这台设备上真的通」。
-- 设备选凭证的下拉因此只能全量平铺，用户靠记忆挑。本迁移补两件事：
--   1) credential_verification：只增不改的验证事实（成功/失败都记，失败分四类）；
--   2) device_check.credential_id：采集成功时补记用的是哪条凭证，形成闭环。
--
-- 安全口径：本表**不存任何明文**。detail 只允许写失败分类与耗时，禁止写口令、私钥、
-- 命令原文中的敏感片段；一次性凭证（POST body 传入、不落库）不写本表。

CREATE TABLE `credential_verification` (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  credential_id  BIGINT       NOT NULL,
  device_id      BIGINT       NOT NULL,
  channel        VARCHAR(16)  NOT NULL COMMENT 'MGMT/BMC/SSH',
  result         VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED',
  failure_kind   VARCHAR(32)  NULL COMMENT 'AUTH/TIMEOUT/UNREACHABLE/REJECTED',
  duration_ms    INT          NULL,
  detail         VARCHAR(512) NULL COMMENT '脱敏后的说明，禁止写口令',
  verified_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by     BIGINT       NULL,
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by     BIGINT       NULL,
  PRIMARY KEY (id),
  KEY `idx_cred_verification_cred` (`credential_id`, `verified_at`),
  KEY `idx_cred_verification_device` (`device_id`, `channel`, `verified_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='凭证连通性验证记录（v5.7.0）';

ALTER TABLE `device_check`
  ADD COLUMN `credential_id` BIGINT NULL
      COMMENT '本次检查使用的 credential.id；一次性凭证为 NULL' AFTER `check_result`,
  ADD KEY `idx_device_check_credential` (`credential_id`);
