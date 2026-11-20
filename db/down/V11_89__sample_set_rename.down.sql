-- V11_89 回滚：编号还原为 SAMPLE-<序号>（按 id 升序，与原生成顺序一致），名称加回「· 样例」。
UPDATE `test_set` s
  JOIN (
        SELECT `id`,
               ROW_NUMBER() OVER (ORDER BY `id`) AS n
          FROM `test_set`
         WHERE `sample_flag` = 1 AND `project_id` IS NULL
       ) t ON t.`id` = s.`id`
   SET s.`set_code` = CONCAT('SAMPLE-', t.n),
       s.`name` = CONCAT(s.`sample_l1`, ' · 样例')
 WHERE s.`sample_flag` = 1 AND s.`project_id` IS NULL;
