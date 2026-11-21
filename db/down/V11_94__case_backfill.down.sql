-- 回滚顺序：先删索引与表，再删列（列是 ALTER，表是 CREATE）。
DROP INDEX idx_case_project_code ON test_case;
DROP TABLE IF EXISTS case_backfill_batch;
ALTER TABLE test_case DROP COLUMN inherited_from;
