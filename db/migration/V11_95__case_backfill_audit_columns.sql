-- FR-UIE-07 收口：case_backfill_batch 补审计两列（db-audit 要求 4 列齐全）。
-- V11_94 在开发库已执行，改它不会重跑，故单独一条迁移补齐。
ALTER TABLE case_backfill_batch
    ADD COLUMN updated_by BIGINT NULL,
    ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3);
