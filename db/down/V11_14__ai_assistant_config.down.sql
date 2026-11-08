-- 回滚 V11_14：删除 AI 助手配置表（仅删本版本新增对象，不动既有表）。
DROP TABLE IF EXISTS `ai_assistant_config`;
