-- V11_55 非自动化用例编辑与手工执行（FR-v5.4-04 / W54-04）
--
-- 设计要点（三条，都直接对应 PRD 规则）：
--
-- 1) exec_mode 落在 test_case 与 test_result 两处，不是一处。
--    test_case.exec_mode 是「这条用例打算怎么执行」（意图）；
--    test_result.exec_mode 是「这一次实际怎么执行的」（快照）。
--    只存前者，用例从 manual 改成 auto 之后，历史手工结果会被算成自动结果——
--    那会让「自动/手工分离统计」在改配置的瞬间失真，而这种失真没有任何报错。
--
-- 2) 手工执行单独两张表，不塞进 run/artifact 体系。
--    手工 run 也会建一条 run（trigger_type=manual、kernel=manual），这样报告、台账、
--    趋势都能复用同一套查询；但「谁在什么时候确认了哪一步」这种细粒度事实属于
--    manual_step_result，塞进 test_result 会让一行结果带上 N 份步骤说明。
--
-- 3) 步骤结果必须能挂附件，所以附件走 JSON 列而不是再开一张表。
--    一条手工用例的步骤数是几十量级，附件是几张图；为它开第三张表，
--    查询时就要三表 join，而收益只是「附件可以单独被查询」——没有人会这样查。

ALTER TABLE `test_case`
  ADD COLUMN `exec_mode` VARCHAR(8) NOT NULL DEFAULT 'auto'
    COMMENT 'auto/manual/hybrid：打算怎么执行；有绑定默认 auto，可人工改'
    AFTER `source_type`;

-- 回填：已绑脚本的用例一定是 auto
UPDATE `test_case` tc
   SET tc.exec_mode = 'auto'
 WHERE EXISTS (SELECT 1 FROM `case_script_binding` b WHERE b.case_id = tc.id);

ALTER TABLE `test_result`
  ADD COLUMN `exec_mode` VARCHAR(8) NOT NULL DEFAULT 'auto'
    COMMENT '本次结果的实际执行方式快照（auto/manual）；统计分列的依据'
    AFTER `verdict`;

-- 历史结果全部来自自动执行（手工执行是本版新增能力），默认值即事实
UPDATE `test_result` SET `exec_mode` = 'auto' WHERE `exec_mode` IS NULL OR `exec_mode` = '';

CREATE TABLE IF NOT EXISTS `manual_execution` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `project_id`    BIGINT      NOT NULL,
  `run_id`        BIGINT      NOT NULL COMMENT '对应一条 kernel=manual 的 run',
  `case_id`       BIGINT      NOT NULL,
  `executor_id`   BIGINT      NULL COMMENT '执行人（账号 id）',
  `executor_name` VARCHAR(64) NULL COMMENT '执行人姓名快照：账号改名后历史记录仍要能看懂',
  `started_at`    DATETIME(3) NOT NULL,
  `finished_at`   DATETIME(3) NULL,
  `env_note`      VARCHAR(512) NULL COMMENT '环境备注（借用哪台设备、什么固件）',
  `verdict`       VARCHAR(16) NULL COMMENT 'PASS/FAIL/SKIP/BLOCKED；未提交前为 NULL',
  `note`          VARCHAR(2048) NULL,
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `created_by`    BIGINT      NULL,
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `updated_by`    BIGINT      NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_manual_execution_run_case` (`run_id`, `case_id`),
  KEY `idx_manual_execution_project` (`project_id`, `started_at`),
  CONSTRAINT `fk_manual_execution_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`),
  CONSTRAINT `fk_manual_execution_run` FOREIGN KEY (`run_id`) REFERENCES `run` (`id`),
  CONSTRAINT `fk_manual_execution_case` FOREIGN KEY (`case_id`) REFERENCES `test_case` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手工执行记录（v5.4.0）';

CREATE TABLE IF NOT EXISTS `manual_step_result` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT,
  `execution_id`   BIGINT      NOT NULL COMMENT 'manual_execution.id',
  `step_no`        INT         NOT NULL COMMENT '对应 test_case_step.step_no',
  `verdict`        VARCHAR(16) NULL COMMENT 'PASS/FAIL/SKIP；NULL=未确认',
  `note`           VARCHAR(2048) NULL,
  `attachments`    JSON        NULL COMMENT '附件清单 [{name,path,sizeBytes}]；图/日志',
  `confirmed_at`   DATETIME(3) NULL COMMENT '确认时间；NULL=未确认，提交结论前必须全非空',
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `created_by`     BIGINT      NULL,
  `updated_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `updated_by`     BIGINT      NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_manual_step_result` (`execution_id`, `step_no`),
  CONSTRAINT `fk_manual_step_result_execution` FOREIGN KEY (`execution_id`)
    REFERENCES `manual_execution` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手工执行逐步骤结果（v5.4.0）';
