-- V11_83 回滚：去掉 `run.skipped_tests`
--
-- 注意：回滚会**丢掉**已回填的 SKIP 数，`total_tests = passed + failed + skipped`
-- 这条等式随之失效（回到「凑不出 total」的 v5.21 状态）。
-- 真要保留历史先导出：
--   SELECT project_id, run_id, total_tests, passed_tests, failed_tests, skipped_tests
--     FROM run WHERE skipped_tests IS NOT NULL;
ALTER TABLE `run`
  DROP COLUMN `skipped_tests`;
