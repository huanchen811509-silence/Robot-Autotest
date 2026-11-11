-- OPT-07 / FR-49-27 / FR-49-28：存量按字符串唯一匹配幂等回填分类 id；
-- 非唯一匹配不写入、标记人工复核；回填后加索引收紧（不直接 NOT NULL，存量不失败）。
-- 全部语句可重复执行：只回填仍为 NULL 的列，AMBIGUOUS 标记幂等。
-- 逻辑外键由应用层 validateCategoryPair 校验，不建物理 FK / 不改 NOT NULL，
-- 避免存量 1798 行里尚无分类字典的记录导致迁移失败或回滚顺序问题。

-- 一级：按 (project_id, level=1, name=category_l1) 分组，仅 COUNT(*)=1 的组回填。
UPDATE `test_case` t
  JOIN (SELECT c.project_id, c.name, MIN(c.id) AS cat_id
          FROM `case_category` c WHERE c.`level` = 1
         GROUP BY c.project_id, c.name HAVING COUNT(*) = 1) m
    ON m.project_id = t.project_id AND m.name = t.category_l1
   SET t.category_l1_id = m.cat_id
 WHERE t.category_l1_id IS NULL;

-- 二级：按 (project_id, level=2, name=category_l2) 分组，仅唯一匹配回填。
UPDATE `test_case` t
  JOIN (SELECT c.project_id, c.name, MIN(c.id) AS cat_id
          FROM `case_category` c WHERE c.`level` = 2
         GROUP BY c.project_id, c.name HAVING COUNT(*) = 1) m
    ON m.project_id = t.project_id AND m.name = t.category_l2
   SET t.category_l2_id = m.cat_id
 WHERE t.category_l2_id IS NULL;

-- 非唯一匹配：上面 HAVING = 1 已排除，绝不写 id；只标记人工复核，不制造脏数据。
UPDATE `test_case` t
  JOIN (SELECT c.project_id, c.name
          FROM `case_category` c WHERE c.`level` = 1
         GROUP BY c.project_id, c.name HAVING COUNT(*) > 1) m
    ON m.project_id = t.project_id AND m.name = t.category_l1
   SET t.category_match_review = 'AMBIGUOUS'
 WHERE t.category_l1_id IS NULL;

UPDATE `test_case` t
  JOIN (SELECT c.project_id, c.name
          FROM `case_category` c WHERE c.`level` = 2
         GROUP BY c.project_id, c.name HAVING COUNT(*) > 1) m
    ON m.project_id = t.project_id AND m.name = t.category_l2
   SET t.category_match_review = 'AMBIGUOUS'
 WHERE t.category_l2_id IS NULL AND t.category_match_review IS NULL;

-- 收紧：加索引。索引名避开 V11_32 已有的 idx_case_category_scope / idx_test_case_category_l1。
ALTER TABLE `test_case`
  ADD KEY `idx_test_case_category_ids` (`project_id`, `category_l1_id`, `category_l2_id`),
  ADD KEY `idx_test_case_category_review` (`project_id`, `category_match_review`);

ALTER TABLE `case_category`
  ADD KEY `idx_case_category_scope_v42` (`scope`, `project_id`, `level`);
