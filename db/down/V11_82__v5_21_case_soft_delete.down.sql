-- V11_82 回滚：去掉软删两列与索引
--
-- 注意：回滚会**丢掉**「哪些行被软删过」的信息（列没了），
-- 被软删的行会重新变成可见行。这是有意为之——回滚到 v5.20 的代码不认这两列，
-- 留着列只会让「删了还在」的差异更难解释。真要保留历史请先导出：
--   SELECT id, project_id, case_code, deleted_at, deleted_by FROM test_case
--    WHERE deleted_at IS NOT NULL;
ALTER TABLE `test_case`
  DROP KEY `idx_test_case_deleted`,
  DROP COLUMN `deleted_by`,
  DROP COLUMN `deleted_at`;
