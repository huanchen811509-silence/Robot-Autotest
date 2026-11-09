-- v3.3.0「聪明」：AI 工作台与运维闭环（FR-3.3-01..11）。
--
-- 原则：
--   1. 只建表 / 只加索引，不修改任何既有列，旧代码回滚后仍可读写既有表；
--   2. 项目级表一律带 project_id 并登记进 TenantGuard（第 2 层守卫）；
--   3. 内置提示词模板用 project_id=0 表示「平台内置」：MySQL 唯一索引不约束 NULL，
--      用 0 才能让 (project_id, template_key) 对内置模板也真正唯一。
--   4. 告警事件保留 detected_at / fired_at 两列：MTTD 必须能算，不能只有「发现时间」。

-- ---------------------------------------------------------------------------
-- 1. AI 项目级配置：开关、日配额、默认 agent/模型、上下文上限
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_project_config` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`        BIGINT       NOT NULL,
  `enabled`           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '项目级 AI 开关（平台级 ai.enabled 仍优先生效）',
  `daily_quota`       INT          NOT NULL DEFAULT 200 COMMENT '每个项目每日 AI 动作上限，0 表示不限',
  `default_agent`     VARCHAR(32)  NULL COMMENT '项目默认 agent，空表示用平台首选',
  `default_model`     VARCHAR(64)  NULL,
  `max_context_chars` INT          NOT NULL DEFAULT 4000 COMMENT '上下文脱敏后的最大字符数',
  `updated_by`        BIGINT       NULL,
  `created_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_project_config` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目级 AI 配置（FR-3.3-07）';

-- ---------------------------------------------------------------------------
-- 2. 提示词库：内置模板 + 项目自定义模板，带版本号（FR-3.3-03）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_prompt_template` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '0=平台内置；>0=项目自定义',
  `template_key` VARCHAR(64)  NOT NULL COMMENT '稳定键，页面快捷动作按它取词条',
  `name`         VARCHAR(128) NOT NULL,
  `body`         TEXT         NOT NULL COMMENT '支持 {{runId}} / {{evidence}} 等占位符',
  `version`      INT          NOT NULL DEFAULT 1,
  `builtin`      TINYINT(1)   NOT NULL DEFAULT 0,
  `enabled`      TINYINT(1)   NOT NULL DEFAULT 1,
  `created_by`   BIGINT       NULL,
  `created_at`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_prompt` (`project_id`, `template_key`),
  KEY `idx_ai_prompt_project` (`project_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 提示词模板（FR-3.3-03）';

-- ---------------------------------------------------------------------------
-- 3. AI 会话消息：会话检索 / 导出 / 重登恢复（FR-3.3-07）
--    citations_json 存引用数组，重登后可复原「每个结论指向哪条证据」。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_message` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`     BIGINT       NOT NULL,
  `session_id`     BIGINT       NOT NULL,
  `role`           VARCHAR(8)   NOT NULL COMMENT 'user / ai',
  `content`        MEDIUMTEXT   NOT NULL,
  `citations_json` TEXT         NULL,
  `mode`           VARCHAR(24)  NULL COMMENT 'STREAM / SYNC / DEGRADED',
  `trace_id`       VARCHAR(64)  NULL,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_ai_message_session` (`project_id`, `session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话消息（FR-3.3-07）';

-- ---------------------------------------------------------------------------
-- 4. 告警通道：Webhook（主）/ 邮件（配置存在才投递，缺失显式降级）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `alert_channel` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT       NOT NULL,
  `kind`       VARCHAR(16)  NOT NULL COMMENT 'WEBHOOK / EMAIL',
  `target`     VARCHAR(512) NOT NULL COMMENT 'Webhook URL 或收件地址',
  `secret`     VARCHAR(256) NULL COMMENT 'Webhook 签名密钥，响应体不回传',
  `enabled`    TINYINT(1)   NOT NULL DEFAULT 1,
  `created_by` BIGINT       NULL,
  `created_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_alert_channel_project` (`project_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警通道（FR-3.3-09）';

-- ---------------------------------------------------------------------------
-- 5. 告警规则：阈值 + 窗口 + 冷却，去重与抑制都在服务层按 cooldown_minutes 实现
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `alert_rule` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`      BIGINT       NOT NULL,
  `event_type`      VARCHAR(32)  NOT NULL COMMENT 'RUN_FAILURE_RATE / QUEUE_BACKLOG / P95_LATENCY / BACKUP_STALE / NODE_OFFLINE',
  `threshold`       DOUBLE       NOT NULL DEFAULT 0 COMMENT '百分比或毫秒或分钟，含义随 event_type',
  `window_minutes`  INT          NOT NULL DEFAULT 15,
  `cooldown_minutes` INT         NOT NULL DEFAULT 15 COMMENT '同 dedup_key 在该窗口内只投递一次',
  `severity`        VARCHAR(16)  NOT NULL DEFAULT 'P2' COMMENT 'P1 / P2 / P3',
  `enabled`         TINYINT(1)   NOT NULL DEFAULT 1,
  `created_by`      BIGINT       NULL,
  `created_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_alert_rule_project` (`project_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则（FR-3.3-09）';

-- ---------------------------------------------------------------------------
-- 6. 告警事件：FIRING / RESOLVED，带投递状态与尝试次数，通道故障不丢事件
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `alert_event` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`      BIGINT       NOT NULL,
  `rule_id`         BIGINT       NULL,
  `event_type`      VARCHAR(32)  NOT NULL,
  `dedup_key`       VARCHAR(160) NOT NULL,
  `severity`        VARCHAR(16)  NOT NULL,
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'FIRING',
  `summary`         VARCHAR(512) NOT NULL,
  `detected_at`     DATETIME(3)  NOT NULL COMMENT '事件真实发生时间，用于 MTTD',
  `fired_at`        DATETIME(3)  NOT NULL,
  `resolved_at`     DATETIME(3)  NULL,
  `delivery_status` VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / FAILED / DEGRADED',
  `attempts`        INT          NOT NULL DEFAULT 0,
  `last_error`      VARCHAR(512) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_alert_event_project` (`project_id`, `status`, `fired_at`),
  KEY `idx_alert_event_dedup` (`project_id`, `dedup_key`, `fired_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警事件（FR-3.3-09）';

-- ---------------------------------------------------------------------------
-- 7. SLO 日快照：可用率 / p95 / 样本数 / 错误预算消耗（FR-3.3-10）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `slo_daily` (
  `id`                  BIGINT      NOT NULL AUTO_INCREMENT,
  `project_id`          BIGINT      NOT NULL,
  `day`                 DATE        NOT NULL,
  `availability_pct`    DECIMAL(6,3) NOT NULL DEFAULT 100.000,
  `p95_ms`              INT         NOT NULL DEFAULT 0,
  `sample_count`        INT         NOT NULL DEFAULT 0,
  `error_budget_minutes` INT        NOT NULL DEFAULT 0 COMMENT '当日已消耗的错误预算（分钟）',
  `created_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slo_daily` (`project_id`, `day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLO 日快照（FR-3.3-10）';

-- ---------------------------------------------------------------------------
-- 8. 发布记录：版本戳 / 健康校验 / 回滚时间（FR-3.3-11）
--    平台级表，不按项目隔离；登记在 TenantGuard 白名单（与 system_setting 同类）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `release_record` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `version`        VARCHAR(32)  NOT NULL,
  `status`         VARCHAR(24)  NOT NULL DEFAULT 'DEPLOYED' COMMENT 'DEPLOYED / VERIFIED / ROLLED_BACK / FAILED',
  `deployed_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `verified_at`    DATETIME(3)  NULL,
  `rolled_back_at` DATETIME(3)  NULL,
  `note`           VARCHAR(512) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_release_version` (`version`, `deployed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布记录（FR-3.3-11）';

-- ---------------------------------------------------------------------------
-- 9. 内置提示词模板（7 条）：页面快捷动作与失败分诊/报告叙述/用例草稿共用
-- ---------------------------------------------------------------------------
INSERT INTO `ai_prompt_template`
  (`project_id`, `template_key`, `name`, `body`, `version`, `builtin`, `enabled`)
VALUES
  (0, 'triage_failure', '失败分诊',
   '请只依据下列证据分析本轮失败。\n证据：\n{{evidence}}\n要求：\n1) 事实与推测分开写；\n2) 每条事实标注证据编号，例如 [E1]；\n3) 给出 3–5 条可执行的排查项，不修改代码、不自动执行。', 1, 1, 1),
  (0, 'report_narrative', '报告叙述',
   '请基于下列报告数据写出结论、主要风险与建议动作。\n数据：\n{{evidence}}\n要求：数字必须与证据一致；没有证据的结论不要写。', 1, 1, 1),
  (0, 'case_draft', '用例草稿',
   '根据需求描述起草测试用例草稿。\n需求：\n{{evidence}}\n要求：只产出草稿，必须人工审阅后才能保存；标注来源行。', 1, 1, 1),
  (0, 'run_digest', '运行摘要',
   '请概括本次运行结果与关键变化，引用证据编号。\n{{evidence}}', 1, 1, 1),
  (0, 'risk_review', '风险复核',
   '请列出本轮最可能阻塞发布的风险，并说明证据。\n{{evidence}}', 1, 1, 1),
  (0, 'log_explain', '日志解释',
   '请解释这段失败日志的直接原因与常见触发条件，并给出验证步骤。\n{{evidence}}', 1, 1, 1),
  (0, 'regression_compare', '回归对比',
   '请对比本次与基线的差异，区分新增失败、修复与波动，并标注样本量。\n{{evidence}}', 1, 1, 1);
