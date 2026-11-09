-- V11_27 回滚：与 up 逆序（先删新索引，再恢复唯一键）。
-- 注意：如果已经采集了同一环境的多份快照，恢复唯一键会失败——这是**预期**的，
-- 说明数据已经用上了新语义，不能悄悄把历史删掉来迁就旧约束。
-- 演练脚本 scripts/migration-down-up-drill.sh 应在「还没有同名多快照」时执行。
ALTER TABLE `env_profile`
  DROP INDEX `idx_env_profile_project_name_time`,
  ADD UNIQUE KEY `uk_env_profile_project_name` (`project_id`, `name`);
