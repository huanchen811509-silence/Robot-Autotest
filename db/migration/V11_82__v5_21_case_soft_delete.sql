-- v5.21.0 / FR-v5.21-03 判据 8/9：用例改为**软删除**
--
-- 大师 2026-11-16 拍板：engineer 保留 `CASE:DELETE`，但删除必须是软删除——
-- 项目用例库不再显示，全局公共用例池仍能按编号查到，**不得物理删除**。
--
-- 为什么不能硬删（本版之前的实现就是硬删）：
-- 1. 历史运行/报告引用了 case_id，硬删后报告里的用例名变成悬空 id；
-- 2. 误删无法自救，只能从备份恢复整库；
-- 3. 台账类数据（本系统 `test_case` 就是用例台账）要求留痕。
--
-- 口径：
-- 1. 只加列 + 索引，**不改任何既有列语义**；历史行 `deleted_at` 为 NULL，天然可见；
-- 2. 可见性由服务层统一追加 `deleted_at IS NULL`（与 v5.11 的 run/report 软删同构）；
-- 3. `deleted_by` 记录操作人，便于追责与「谁删的」排查。

ALTER TABLE `test_case`
  ADD COLUMN `deleted_at` DATETIME(3) NULL COMMENT '软删时间；NULL=可见' AFTER `category_match_review`,
  ADD COLUMN `deleted_by` BIGINT NULL COMMENT '软删操作人' AFTER `deleted_at`,
  ADD KEY `idx_test_case_deleted` (`project_id`, `deleted_at`);
