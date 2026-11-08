-- v2.5 / FR-AI-10..13：AI 助手逐个配置端点、凭据与模型。
--
-- 为什么单开一张表而不塞进 system_setting：
--   1. system_setting 是全局 k/v，导出 / 审计摘要会把整张表带出去，密钥不能进；
--   2. 这里每个 CLI 一行（7 行），有结构（base_url / model / enabled），k/v 表达不了。
--
-- 凭据一律只存 AES-256-GCM 密文（SecretCipher，CRED_MASTER_KEY 派生），
-- 接口只回显掩码（**** + 尾 4 位），明文永不落库、永不回显、永不进日志。
CREATE TABLE IF NOT EXISTS `ai_assistant_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `cli_key` VARCHAR(32) NOT NULL COMMENT 'CLI 标识：hermes/dsh/claude/codex/gemini/aider/pi',
  `base_url` VARCHAR(512) NULL COMMENT '自定义 API 端点；NULL 表示用 CLI 自带配置',
  `api_key_enc` VARCHAR(2048) NULL COMMENT 'API Key 的 AES-GCM 密文（base64）',
  `model` VARCHAR(128) NULL COMMENT '首选模型 id；NULL 表示用 CLI 默认模型',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=参与候选选择，0=跳过',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
      COMMENT '最后修改时间',
  `updated_by` BIGINT NULL COMMENT '最后修改人 user_id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_assistant_cli` (`cli_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 助手（CLI）端点与凭据配置';
