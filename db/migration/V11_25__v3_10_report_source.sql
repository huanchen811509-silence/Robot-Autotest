-- v3.10.0 · 对外报告草稿的「来源」两列（FR-v3.10-03 / ADR-310-07）。
--
-- 为什么加在 test_report 而不是新表：一份报告只有一个来源，独立表换来的是一次 JOIN
-- 和「有报告无来源」的半状态。这与 ADR-39-02（命令三列直接进 test_result）同一取舍。
--
-- source_type 取值：run = 按执行记录（旧路径，默认）/ case_set = 按用例集。
-- 用 VARCHAR(16) 而不是 ENUM：新增来源不该再动表结构；合法性由
-- TestReportService 单点把关（未知值直接 400，不静默回落）。
--
-- 旧行由 DEFAULT 'run' 直接回填，**不留 NULL**：留 NULL 会让列表出现空白来源，
-- 等于把「这一列是新加的」这种实现细节漏到界面上。
--
-- case_set_id 可空：source_type=run 时为 NULL。不加外键约束——用例集删除后
-- 历史报告仍要能打开（快照自包含，来源只是元信息），外键会挡住删除。
ALTER TABLE `test_report`
  ADD COLUMN `source_type` VARCHAR(16) NOT NULL DEFAULT 'run'
      COMMENT '来源类型：run=按执行记录 / case_set=按用例集' AFTER `run_id`,
  ADD COLUMN `case_set_id` BIGINT NULL
      COMMENT '来源用例集 id（source_type=case_set 时有值）' AFTER `source_type`;

-- 「某个用例集的报告有哪些」是列表页的取数路径，走这条联合索引。
CREATE INDEX `idx_report_source_case_set` ON `test_report` (`project_id`, `case_set_id`);
