-- v2.5 / FR-AI-10..13 补遗：给 ai_assistant_config 补齐审计四列与毫秒精度时间列。
--
-- 为什么单开 V11_15 而不改 V11_14：
--   V11_14 已在 flyway_schema_history 记为 `11.14 success=1`，且本机未开 out-of-order，
--   改动已应用迁移的校验和会让 `flyway:migrate` 直接报 checksum mismatch。
--   故按「增量补列」的方式追加，历史脚本保持只读。
--
-- 为什么必须补（2026-11-08 门禁第 12 步实测，3 项不符）：
--   1. 缺审计四列的表的数量：期望 0，实测 1 —— 即本表只有 updated_at / updated_by；
--   2. 时间列不是 datetime(3) 的表数量：期望 0，实测 1 —— 本表用的是 DATETIME(0)，
--      与 execution_selection / project 等表的 DATETIME(3) 不一致，同秒并发取不到先后序。
--   db-audit 与 SchemaIT 双向锁这两条口径，缺一即红。
--
-- 回填策略：created_at 取「首次可考的修改时间」= 现有 updated_at（该行建表即写入，
-- 二者在迁移前恒等），created_by 取现有 updated_by；不写假值、不回填到 1970。

ALTER TABLE `ai_assistant_config`
  ADD COLUMN `created_at` DATETIME(3) NULL COMMENT '创建时间' AFTER `enabled`,
  ADD COLUMN `created_by` BIGINT NULL COMMENT '创建人 user_id' AFTER `created_at`;

UPDATE `ai_assistant_config`
   SET `created_at` = `updated_at`,
       `created_by` = `updated_by`
 WHERE `created_at` IS NULL;

-- 先把 updated_at 放宽到能容纳毫秒，再收紧为 NOT NULL + 毫秒默认值，
-- 避免直接 MODIFY 时既存行在 STRICT 模式下被截断报错。
ALTER TABLE `ai_assistant_config`
  MODIFY COLUMN `updated_at` DATETIME(3) NULL COMMENT '最后修改时间';

UPDATE `ai_assistant_config` SET `updated_at` = CURRENT_TIMESTAMP(3) WHERE `updated_at` IS NULL;

ALTER TABLE `ai_assistant_config`
  MODIFY COLUMN `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  MODIFY COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间';
