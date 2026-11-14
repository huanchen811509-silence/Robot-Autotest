-- V11_62 执行选择快照（v5.7.0 / FR-v5.7-16~19 / W57-27）
--
-- 手工发起执行要支持「全部 / 按用例集 / 按用例 / 两者组合」四种模式。
-- 把选择冻结成不可变快照，确认时只吃 snapshotId：用户看到的那份清单与实际派发的
-- 清单必须是同一份，期间用例库发生变化会被摘要比对拦下（409），而不是静默跑别的用例。
--
-- 口径：
-- 1) selection_sha UNIQUE —— 同输入可复用同一快照，但 consumed_at 非空即不可再消费；
-- 2) ALL 模式不存 caseIds（可能十万条），只存 canonical_filter + case_count；
-- 3) 不加外键：快照是历史事实，项目删除后仍需可查。
CREATE TABLE `execution_selection_snapshot` (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  project_id      BIGINT       NOT NULL,
  selection_sha   CHAR(64)     NOT NULL COMMENT 'sha256(mode:setIds:caseIds:canonicalFilter:projectId:caseCount)',
  selection_mode  VARCHAR(16)  NOT NULL COMMENT 'ALL/SET/CASE/MIXED',
  set_ids_json    TEXT         NULL COMMENT '排序后的用例集 id 数组',
  case_ids_json   MEDIUMTEXT   NULL COMMENT '排序后的用例 id 数组；ALL 模式为 NULL',
  filter_json     TEXT         NULL COMMENT '规范化后的筛选条件 JSON',
  case_count      INT          NOT NULL DEFAULT 0 COMMENT '预览时命中条数',
  consumed_at     DATETIME(3)  NULL COMMENT '被 confirm-and-start 消费的时间，非空即拒绝复用',
  consumed_by     BIGINT       NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      BIGINT       NULL,
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by      BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY `uk_execution_selection_sha` (`selection_sha`),
  KEY `idx_execution_selection_project` (`project_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行选择冻结快照（v5.7.0）';
