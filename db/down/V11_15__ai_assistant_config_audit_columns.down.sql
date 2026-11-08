-- 回滚 V11_15：只撤本迁移新增的 created_at / created_by 两列，并把 updated_at 退回 DATETIME(0)。
-- 注意：精度回退会丢掉毫秒位（截断，不四舍五入），这是 DATETIME(0) 的固有限制，不是 bug。
ALTER TABLE `ai_assistant_config`
  MODIFY COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
      ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间';

ALTER TABLE `ai_assistant_config`
  DROP COLUMN `created_by`,
  DROP COLUMN `created_at`;
