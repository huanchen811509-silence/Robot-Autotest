-- v3.4.0 · 补齐 v3.3 新增可变表的审计列（db-audit 口径：业务表四列齐全）。
--
-- 为什么单独一个迁移而不是改 V11_21：V11_21 已应用，Flyway 不允许改写历史迁移；
-- 而「补列」本身是独立事实，回滚时也应独立撤销。
--
-- 只补**可变表**：append-only 表（ai_message / alert_event / slo_daily /
-- release_record / archived_run）按设计只增不改，补 updated_by 只会逼业务写假值，
-- 已在 scripts/db-audit.sh 的历史表白名单中说明。
ALTER TABLE `ai_project_config`
  ADD COLUMN `created_by` BIGINT NULL COMMENT '创建人' AFTER `updated_by`;

ALTER TABLE `ai_prompt_template`
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人' AFTER `updated_at`;

ALTER TABLE `alert_channel`
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人' AFTER `updated_at`;

ALTER TABLE `alert_rule`
  ADD COLUMN `updated_by` BIGINT NULL COMMENT '最后修改人' AFTER `updated_at`;

ALTER TABLE `archive_policy`
  ADD COLUMN `created_by` BIGINT NULL COMMENT '创建人' AFTER `updated_by`;
