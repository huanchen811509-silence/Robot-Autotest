-- 回滚顺序：先回滚代码里对 idx_case_project_id 的 FORCE INDEX，再执行本脚本，
-- 否则保留 FORCE INDEX 的查询会因索引不存在报 ERROR 1176。

ALTER TABLE `test_case`
    DROP INDEX `idx_case_project_id`;
