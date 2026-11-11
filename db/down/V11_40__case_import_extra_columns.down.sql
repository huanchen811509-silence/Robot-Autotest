-- down for V11_40：回滚五列补录。无索引/外键依赖，直接 DROP COLUMN。
-- 目标库不支持 DROP COLUMN IF EXISTS，故按「列必然存在」直接删；顺序与 up 相反。
ALTER TABLE `test_case`
  DROP COLUMN `a_side_command`,
  DROP COLUMN `b_side_command`,
  DROP COLUMN `switch_config`,
  DROP COLUMN `test_result`,
  DROP COLUMN `test_resource`;
