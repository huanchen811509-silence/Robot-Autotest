-- V11_54 回滚：摘掉 P99 列
--
-- 先删列再无需处理索引：p99_ms 上没有索引，也没有约束引用它。
-- 数据的后果要写清：回滚后所有 P99 值丢失，重算才能恢复（值本身可由 test_result 重算，
-- 不构成不可恢复损失）。

ALTER TABLE `case_duration_baseline`
  DROP COLUMN `p99_ms`;
