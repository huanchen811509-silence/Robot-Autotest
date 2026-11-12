-- V11_52 失败处置闭环（W53-06 / FR-v5.3-06）
--
-- 解决的问题：失败分析现在做到「归因五层」，但归因之后没有去处——谁认领、
-- 定了什么结论、是不是已知问题，全靠群聊记录。结果是同一个环境问题每天被
-- 重新分析一遍，而真正的产品缺陷混在噪音里没人看。
--
-- 设计要点：
-- 1) **指纹** `fingerprint` = 归因分类 + 位置 + 归一化原文的 hash。用处是
--    「同一类失败认一次就够」：429 条用例都因为同一个 SSH 配置错而红，
--    处置一次应当覆盖全部，而不是逐条点 429 次。
-- 2) **不删行**：`reopen` 只改状态并记 `reopen_reason`。删掉历史等于删掉
--    「这个问题被反复打开过几次」这个最有价值的信号。
-- 3) 状态机在应用层把关（`FailureDispositionService`），库里不加 CHECK 约束：
--    状态迁移规则会随流程演进，写进 DDL 就得再来一次迁移才能改。
-- 4) `known_issue` 豁免不是「让用例变绿」，而是把它从「待处置」里摘出去；
--    verdict 仍然是 FAIL，报告里单列——否则就是拿处置动作刷通过率。

CREATE TABLE IF NOT EXISTS `case_failure_disposition` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`     BIGINT       NOT NULL,
  -- 用 0 表示「未定位到具体用例」，不用 NULL：唯一键里 NULL 不参与冲突判定，
  -- 一旦可空，同一指纹的「批量处置」行就能无限重复插入，处置状态立刻失去唯一性。
  `case_id`        BIGINT       NOT NULL DEFAULT 0 COMMENT '0 = 按指纹批量处置、未定位到具体用例',
  `case_code`      VARCHAR(64)  NULL COMMENT '库内编号快照，用例改名后仍可读',
  `kernel`         VARCHAR(16)  NOT NULL DEFAULT 'robot',
  `fingerprint`    VARCHAR(64)  NOT NULL COMMENT '归因指纹（分类+位置+原文摘要的 SHA-256 前 32 位）',
  `category`       VARCHAR(32)  NULL COMMENT '归因分类 ENV/CONFIG/SCRIPT/DATA/ASSERT/UNKNOWN',
  `location`       VARCHAR(160) NULL COMMENT '归因位置（文件:行 或 步骤名）',
  `status`         VARCHAR(16)  NOT NULL DEFAULT 'open'
                   COMMENT 'open/assigned/decided/closed（reopen 时回到 open）',
  `assignee_id`    BIGINT       NULL,
  `decision`       VARCHAR(32)  NULL
                   COMMENT 'product_bug/env_issue/case_defect/known_issue/wont_fix',
  `note`           TEXT         NULL,
  `occurrences`    INT          NOT NULL DEFAULT 1 COMMENT '同一指纹累计出现次数',
  `reopen_count`   INT          NOT NULL DEFAULT 0,
  `reopen_reason`  TEXT         NULL,
  `last_run_id`    VARCHAR(64)  NULL COMMENT '最近一次命中的 RUN_ID，便于跳转证据',
  `decided_at`     DATETIME(3)  NULL,
  `closed_at`      DATETIME(3)  NULL,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `created_by`     BIGINT       NULL,
  `updated_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `updated_by`     BIGINT       NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_case_failure_disposition` (`project_id`, `fingerprint`, `case_id`),
  KEY `idx_case_failure_disposition_status` (`project_id`, `status`),
  KEY `idx_case_failure_disposition_case` (`project_id`, `case_id`, `kernel`),
  CONSTRAINT `fk_case_failure_disposition_project` FOREIGN KEY (`project_id`)
    REFERENCES `project` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='失败处置闭环（v5.3.0）';
