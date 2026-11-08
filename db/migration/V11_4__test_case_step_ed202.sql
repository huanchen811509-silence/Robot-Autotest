-- EDIM-03 / D2：test_case_step 补对账索引。
-- 既有事实（不重复添加，否则空库 up → down → up 会因「列/键已存在」失败）：
--   · source_row 列已由 V2_3__case_fields.sql 加入；
--   · (test_case_id, step_no) 唯一键 uk_test_case_step 已由 V1__baseline.sql 建立。
-- 故本迁移只补一个真正缺的「按源行对账」索引，配合 EDIM-03 写 source_row 后按行回查。
ALTER TABLE `test_case_step`
  ADD KEY `idx_test_case_step_source_row` (`test_case_id`, `source_row`);
