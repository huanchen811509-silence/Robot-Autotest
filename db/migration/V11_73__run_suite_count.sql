-- V11_73 执行报告一级套件数落列（v5.14.0 / FR-v5.14-02 / W64-02）
--
-- 背景：详情套件树是查询期用 test_result.case_key 现场拼的（ReportTreeService），
-- run 表本身没有套件维度；列表页想知道「这次跑分几个套件」只能进详情页数节点。
--
-- 口径：
--  1) 只加 1 列、不改既有列、不动历史行。历史 run 的 suite_count 恒为 NULL，
--     界面显示「—」（未知），**不是 0**；用新算法批量回填会让历史数字与当时的树不一致。
--  2) 值由入库链路写：取实际写入 test_result 的 case_key 集合，用与套件树同一份
--     CaseKeyPath 切分函数算一级子节点数。空集合 / 不可靠 key 写 NULL，不猜数字。
--  3) 这是列追加，不是新表，表数不变（db-audit 的 expectedTables 无需调整）。
--
-- 编号说明（见 00-RESEARCH R-1）：V11_69 ~ V11_72 从未落过文件，属编号约定；
-- 本支从 V11_73 起跳号追加，Flyway 只要求版本递增，不要求连续。

ALTER TABLE `run`
  ADD COLUMN `suite_count` INT NULL
      COMMENT '一级套件数；NULL=历史未采集或切分不可靠，界面显示「—」而非 0';
