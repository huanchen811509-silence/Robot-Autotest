-- V11_60 执行计划确认摘要（v5.7.0 / FR-v5.7-10、11 / W57-03）
--
-- 确认的不是「我点过同意」，而是一份具体命令清单的 sha256。派发前重算比对，
-- 用例被偷偷增删、改序、换脚本都会导致摘要变化并 409，进程根本不会启动。
--
-- 口径：confirmed_* 是「最近一次确认」，重复确认覆盖旧值（不是追加历史）；
-- 审计明细走 audit_log，不在本表堆版本。三列可空 = 历史计划从未确认过。

ALTER TABLE `execution_plan`
  ADD COLUMN `confirmed_digest` CHAR(64) NULL
      COMMENT 'sha256(v5.7-run-confirm: + 规范化命令清单)' AFTER `overlap_policy`,
  ADD COLUMN `confirmed_at` DATETIME(3) NULL
      COMMENT '最近一次确认时间' AFTER `confirmed_digest`,
  ADD COLUMN `confirmed_by` BIGINT NULL
      COMMENT '确认人 app_user.id' AFTER `confirmed_at`,
  ADD KEY `idx_execution_plan_confirmed` (`project_id`, `confirmed_at`);
