-- V11_92 / 修复 db-audit：`safety_level_recommendation` 建表时只给了 updated_at
--
-- V11_85 建这张表时按「配置小表」写的，漏了 created_at / created_by / updated_by，
-- 门禁 12-db_audit 判红（业务表必须四列齐全）。不改 V11_85：它已应用，
-- 改内容会引发 flyway checksum 冲突，只能追加。
--
-- 存量行没有真实创建时间，这里回填「迁移时刻」并写注释说明——不编一个更早的时间。
ALTER TABLE `safety_level_recommendation`
  ADD COLUMN `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    COMMENT '创建时间（V11_92 回填为迁移时刻）' AFTER `note`,
  ADD COLUMN `created_by` BIGINT NULL COMMENT '创建人；建议值是批量生成的，可为空' AFTER `created_at`,
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人' AFTER `updated_at`;

-- 存量行补齐 updated_at 为空的理论情形（正常不会出现，防御性一条）
UPDATE `safety_level_recommendation` SET `created_at` = CURRENT_TIMESTAMP(3) WHERE `created_at` IS NULL;
