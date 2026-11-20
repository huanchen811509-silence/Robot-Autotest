-- 回滚 V11_92：去掉后补的三列审计列（V11_85 原样不动）
ALTER TABLE `safety_level_recommendation`
  DROP COLUMN `updated_by`,
  DROP COLUMN `created_by`,
  DROP COLUMN `created_at`;
