-- V11_51 用例时长基线（W53-03 / FR-v5.3-03）
--
-- 为什么单独一张表而不是在 test_case 上加列：
-- 1) 基线按 **(项目, 用例, 内核)** 三元组独立——同一个用例在 RF 与 pytest 下的耗时
--    完全不同（RF 有 pabot 启动开销，pytest 有 conftest 收集开销），合成一条没有意义；
-- 2) 重算会整体替换，不该去 UPDATE 业务表（那会让 test_case 的 updated_at 天天变，
--    资产变更审计里全是噪音）；
-- 3) 样本不足时要保留「算过但不够」这个事实（insufficient 标记），而不是干脆没有行——
--    没有行与「样本不足」在页面上是两种完全不同的提示。
--
-- 分位数在应用层算（Java），不用 SQL 的窗口函数：MySQL 8 的 PERCENTILE 需要
-- 窗口函数 + 子查询，重算 469 个用例时开销远大于「取原始耗时列表在内存排序」，
-- 而且算法写在 SQL 里改不动、测不了。

CREATE TABLE IF NOT EXISTS `case_duration_baseline` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT,
  `project_id`     BIGINT      NOT NULL,
  `case_id`        BIGINT      NOT NULL,
  `kernel`         VARCHAR(16) NOT NULL DEFAULT 'robot' COMMENT 'robot / pytest，两者耗时基线不通用',
  `sample_count`   INT         NOT NULL DEFAULT 0 COMMENT '参与计算的样本数（已剔除离群与 SKIP）',
  `p50_ms`         BIGINT      NULL COMMENT '中位数；样本不足时仍写，但 insufficient=1',
  `p90_ms`         BIGINT      NULL COMMENT 'P90：软超时阈值的基数（p90 × slow_factor）',
  `max_ms`         BIGINT      NULL COMMENT '剔除离群后的最大值，供人工判断',
  `excluded_count` INT         NOT NULL DEFAULT 0 COMMENT '被剔除的样本数（离群 + 环境失败粗筛）',
  `insufficient`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1=样本 <5，基线仅供参考，不参与软超时判定',
  `window_start`   DATETIME(3) NULL COMMENT '统计窗口起点（默认近 30 天）',
  `window_end`     DATETIME(3) NULL COMMENT '统计窗口终点',
  `computed_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本次重算时间',
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `created_by`     BIGINT      NULL,
  `updated_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `updated_by`     BIGINT      NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_case_duration_baseline` (`project_id`, `case_id`, `kernel`),
  KEY `idx_case_duration_baseline_project` (`project_id`, `computed_at`),
  CONSTRAINT `fk_case_duration_baseline_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用例时长基线（v5.3.0）';
