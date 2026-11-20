-- V11_87 / W81-06：删除死字段 test_case.status
--
-- 事实：该列只在 INSERT 时写死 1，SELECT 从不读、从不更新（TestCaseRepositoryJdbc:274）。
--       保留它只会让人误以为「用例可启停」。本迁移删除；若将来要启停，另立 enabled 列。
-- 前置：代码侧 INSERT 必须同步去掉该列（同批次提交），否则插入直接报 Unknown column。
ALTER TABLE `test_case` DROP COLUMN `status`;
