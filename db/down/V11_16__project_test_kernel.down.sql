-- 回滚 V11_16：移除项目测试内核列。
ALTER TABLE project DROP COLUMN test_kernel;
