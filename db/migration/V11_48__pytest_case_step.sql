-- V11_48 pytest 步骤证据（W51-12 / FR-v5.1-08）
--
-- 为什么单开一张表，不塞进 keyword_result：
--   keyword_result 的语义是「RF 关键字树的镜像」，name 上限 256 且用 depth 表达嵌套。
--   pytest 侧的证据是「一行日志 = 一条记录」，要存明细取值原文（可能很长）、
--   命令原文与 stdout 文件相对路径，语义与 RF 关键字不同。混进 keyword_result 会让
--   RF 报告页把 pytest 的明细行当成关键字渲染，属于 C-2 类污染。
--
-- 三个真源落点（全部来自框架自己写的产物，平台不臆造）：
--   ① junit <system-out>/<system-err> —— 我们以 --capture=fd 覆盖用例库 addopts 的 -s，
--      于是每条用例的日志被 pytest 捕获并按用例切好。这是「按用例归属」的唯一可靠来源。
--   ② artifacts/<run_id>/framework.log —— 会话级全量日志（含 DEBUG），无用例边界，
--      仅在 junit 拿不到捕获时作为兜底，并标记 source='FRAMEWORK_LOG'。
--   ③ artifacts/<run_id>/cases/<node>/raw/*.commands.jsonl —— 按用例天然隔离的命令索引。
CREATE TABLE IF NOT EXISTS `pytest_case_step` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `run_id`         BIGINT       NOT NULL COMMENT 'run.id',
  `test_result_id` BIGINT       NULL     COMMENT 'test_result.id，结果未入库时为 NULL',
  `node_id`        VARCHAR(512) NOT NULL COMMENT 'pytest nodeid（斜杠形态，原样保留）',
  `case_key`       VARCHAR(160) NOT NULL DEFAULT '' COMMENT '匹配键 <文件末段>::<函数名>，与 test_result.case_key 同源',
  `seq`            INT          NOT NULL COMMENT '用例内顺序，从 1 开始',
  `kind`           VARCHAR(16)  NOT NULL COMMENT 'DETAIL/VERDICT/COMMAND/FAILURE',
  `label`          VARCHAR(256) NOT NULL DEFAULT '' COMMENT '步骤标签（[明细] a：b 里的 a；用例自写无标签时为空）',
  `content`        TEXT         NULL     COMMENT '明细取值原文 / 判定理由 / 命令原文',
  `status`         VARCHAR(16)  NULL     COMMENT 'PASS/FAIL/SKIP；非判定行为 NULL',
  `command_id`     VARCHAR(64)  NULL     COMMENT '命令证据的 command_id，对应 raw/commands/<id>.stdout.txt',
  `stdout_path`    VARCHAR(512) NULL     COMMENT 'stdout 文件相对 run 证据根的路径；大文本不重复入库',
  `log_line`       BIGINT       NULL     COMMENT 'framework.log 行号，便于溯源到原文',
  `source`         VARCHAR(24)  NOT NULL DEFAULT 'JUNIT' COMMENT 'JUNIT/FRAMEWORK_LOG/COMMANDS_JSONL',
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_pytest_case_step_run` (`run_id`),
  KEY `idx_pytest_case_step_case` (`run_id`, `node_id`, `seq`),
  KEY `idx_pytest_case_step_result` (`test_result_id`),
  CONSTRAINT `fk_pytest_case_step_run` FOREIGN KEY (`run_id`) REFERENCES `run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='pytest 步骤与命令证据（v5.1.0）';
