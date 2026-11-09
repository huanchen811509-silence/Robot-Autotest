-- V11_21 down：按依赖倒序删除 v3.3.0 新建的 8 张表。
--
-- 说明：本迁移未改动任何既有表/列，因此回滚只有「删新表」一步；
-- 但新表里有运行时产生的数据（AI 消息、告警事件、SLO、发布记录），
-- down 会一并删除。生产回滚前必须先做一次全库备份（scripts/rollback.sh 默认会做）。

DROP TABLE IF EXISTS `release_record`;
DROP TABLE IF EXISTS `slo_daily`;
DROP TABLE IF EXISTS `alert_event`;
DROP TABLE IF EXISTS `alert_rule`;
DROP TABLE IF EXISTS `alert_channel`;
DROP TABLE IF EXISTS `ai_message`;
DROP TABLE IF EXISTS `ai_prompt_template`;
DROP TABLE IF EXISTS `ai_project_config`;
