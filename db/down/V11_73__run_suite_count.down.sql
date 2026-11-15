-- V11_73 回滚：套件数是派生展示值，删列不影响原始事实（test_result 不动）。
ALTER TABLE `run` DROP COLUMN `suite_count`;
