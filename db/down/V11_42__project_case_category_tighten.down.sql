-- down for V11_42：先摘索引，再撤销回填数据；列本身由 V11_41 down 删除。
-- 顺序：索引必须先于列删除（V11_41 down 会删列），否则残留索引指向已删列导致 ERROR 1072。
ALTER TABLE `test_case`
  DROP INDEX `idx_test_case_category_ids`,
  DROP INDEX `idx_test_case_category_review`;

ALTER TABLE `case_category`
  DROP INDEX `idx_case_category_scope_v42`;

-- 撤销 V11_42 的数据变更，保证 down→up 可对账。
UPDATE `test_case`
   SET category_l1_id = NULL, category_l2_id = NULL, category_match_review = NULL
 WHERE category_l1_id IS NOT NULL OR category_l2_id IS NOT NULL
    OR category_match_review IS NOT NULL;
