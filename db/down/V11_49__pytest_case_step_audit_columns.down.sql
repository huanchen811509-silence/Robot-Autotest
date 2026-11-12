-- V11_49 回滚：按加入顺序倒着删。
ALTER TABLE `pytest_case_step`
  DROP COLUMN `updated_by`,
  DROP COLUMN `updated_at`,
  DROP COLUMN `created_by`;
