-- V11_51 回滚：删基线表
--
-- 先摘外键再删表：直接 DROP TABLE 在 MySQL 8 上会连约束一起删，但显式摘掉
-- 能保证「约束名与表的依赖关系」在任何版本上都按同一顺序消失。
-- 基线是可重算的派生数据，删除不丢事实——重跑一次重算服务即可恢复。

ALTER TABLE `case_duration_baseline` DROP FOREIGN KEY `fk_case_duration_baseline_project`;
DROP TABLE IF EXISTS `case_duration_baseline`;
