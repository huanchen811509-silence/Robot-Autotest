-- V11_49 给 pytest_case_step 补审计三列（created_by / updated_at / updated_by）
--
-- 为什么是追加而不是改 V11_48：V11_48 已在 robot_dev / robot_it 应用过，
-- 改已应用的迁移会触发 checksum mismatch，只能靠 flyway repair 掩盖——那条路一旦
-- 用顺手，就会有人改「已经上过生产」的脚本。按项目规矩追加一版。
--
-- 表本身仍是「按 run 覆盖写」：updated_at 只在覆盖时由 MySQL 自动刷新，
-- 代码不写 updated_by（与 keyword_result 同一口径），不会有人为造数据。
ALTER TABLE `pytest_case_step`
  ADD COLUMN `created_by` BIGINT NULL AFTER `created_at`,
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3) AFTER `created_by`,
  ADD COLUMN `updated_by` BIGINT NULL AFTER `updated_at`;
