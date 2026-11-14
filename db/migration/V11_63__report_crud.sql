-- V11_63 报告与运行记录的可编辑 / 软删列（v5.7.0 / FR-v5.7-24、25 / W57-31）
--
-- 表名复核（U-07 结论）：本库**没有**独立的「执行报告展示表」。
--   * 执行报告 = `run` 表（ReportController 按 runId 取数，展示层无单独表）；
--   * 用户报告 = `test_report` 表（V8_1 建，V8_5 已补 updated_*）。
-- 所以软删加在这两张表，test_result / run_artifact 等原始事实表一律不动。
--
-- 口径：
-- 1) 软删只加列，不改任何既有查询语义；由服务层统一追加 `deleted_at IS NULL`；
-- 2) 历史行 deleted_at 为 NULL，天然可见，无需回填；
-- 3) display_name / remark / tags 是展示层元数据，不参与判定、不进对外口径数字。

ALTER TABLE `run`
  ADD COLUMN `display_name` VARCHAR(200) NULL COMMENT '用户可改的显示名，不改 run_id' AFTER `trigger_type`,
  ADD COLUMN `remark` VARCHAR(1000) NULL COMMENT '备注' AFTER `display_name`,
  ADD COLUMN `tags` VARCHAR(512) NULL COMMENT '逗号分隔标签' AFTER `remark`,
  ADD COLUMN `deleted_at` DATETIME(3) NULL COMMENT '软删时间；NULL=可见' AFTER `tags`,
  ADD COLUMN `deleted_by` BIGINT NULL COMMENT '软删操作人' AFTER `deleted_at`,
  ADD KEY `idx_run_deleted` (`project_id`, `deleted_at`);

ALTER TABLE `test_report`
  ADD COLUMN `remark` VARCHAR(1000) NULL COMMENT '备注' AFTER `title`,
  ADD COLUMN `tags` VARCHAR(512) NULL COMMENT '逗号分隔标签' AFTER `remark`,
  ADD COLUMN `deleted_at` DATETIME(3) NULL COMMENT '软删时间；NULL=可见' AFTER `tags`,
  ADD COLUMN `deleted_by` BIGINT NULL COMMENT '软删操作人' AFTER `deleted_at`,
  ADD KEY `idx_report_deleted` (`project_id`, `deleted_at`);
