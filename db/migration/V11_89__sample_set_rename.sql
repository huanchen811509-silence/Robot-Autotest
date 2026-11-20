-- V11_89 / FR-v5.31-06 / W81-09：样例集编号与命名
--
-- 现状：32 个样例集编号是 SAMPLE-1…SAMPLE-32，名称是「分类名 · 样例」。
-- 口径（大师）：编号用三位序号 001-032（按一级分类 sort_no 稳定），名称用纯中文分类名。
-- 幂等：以 sample_l1 关联分类，重复执行得到同一结果；不再带「· 样例」后缀。
UPDATE `test_set` s
  JOIN (
        SELECT c.`name` AS l1,
               LPAD(ROW_NUMBER() OVER (ORDER BY c.`sort_no`, c.`id`), 3, '0') AS new_code
          FROM `case_category` c
         WHERE c.`level` = 1 AND c.`status` = 'active'
           AND (c.`scope` IS NULL OR c.`scope` = 'platform')
       ) t ON t.l1 = s.`sample_l1`
   SET s.`set_code` = t.new_code,
       s.`name` = s.`sample_l1`
 WHERE s.`sample_flag` = 1 AND s.`project_id` IS NULL;
