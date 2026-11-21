-- FR-UIE-07：公共库 → 项目用例的内容回填（一次性快照）
--
-- 背景：pytest 工程的项目用例是从台账 sheet 导入的，只带编号/名称/分类/优先级，
-- 预置条件、步骤、预期结果一直为空；而公共库同编号的用例这些字段是齐的。
-- 两库物理隔离、case_code 不是外键，所以「继承」只能显式做一次，并把来源记下来。

-- 来源标记：被回填的用例指向公共库的源用例 id。NULL = 从未回填。
ALTER TABLE test_case
    ADD COLUMN inherited_from BIGINT NULL COMMENT 'FR-UIE-07 内容回填来源（公共库 test_case.id）';

-- 回填批次：一次 dry-run/apply 的留痕，便于回答「这批内容哪来的、什么时候补的」。
CREATE TABLE IF NOT EXISTS case_backfill_batch (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    source_project_id BIGINT       NOT NULL COMMENT '内容来源项目（公共库 platform 项目）',
    target_project_id BIGINT       NOT NULL COMMENT '被回填的项目',
    mode              VARCHAR(16)  NOT NULL COMMENT 'PREVIEW / APPLY',
    scanned_count     INT          NOT NULL DEFAULT 0 COMMENT '参与比对的用例数',
    filled_count      INT          NOT NULL DEFAULT 0 COMMENT '实际填充（或预计填充）的用例数',
    skipped_count     INT          NOT NULL DEFAULT 0 COMMENT '跳过数（含原因分布）',
    reasons_json      VARCHAR(1000) NULL COMMENT '跳过原因分布，如 {"NO_SOURCE":71}',
    created_by        BIGINT       NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_backfill_target (target_project_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'FR-UIE-07 用例内容回填批次';

-- 回填按 (project_id, case_code) 定位源用例，公共库有 730 行；没有索引时是全表扫。
CREATE INDEX idx_case_project_code ON test_case (project_id, case_code);
