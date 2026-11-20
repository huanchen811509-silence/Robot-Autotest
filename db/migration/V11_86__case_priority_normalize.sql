-- V11_86 / FR-v5.31-03 / W81-06：优先级词表归一
--
-- 背景：EHT 库 469 条优先级是 T0/T1/T2/待评审，与 Priority 枚举（P0-P3）冲突；
--       导入代码注释声称「T0→P0 已归一」，库内实际没有。本次一次性归一，并保留原值。
-- 口径（大师 2026-11-20 裁定 D-05）：
--   T0→P0、T1→P1、T2→P2；「待评审」不是优先级 —— priority 置 NULL，review_status 置 pending。
-- 幂等：priority_raw 只在首次抄写；重复执行命中 0 行。
ALTER TABLE `test_case`
  ADD COLUMN `priority_raw` VARCHAR(8) NULL COMMENT '归一前原值（T0/T1/T2/待评审）'
    AFTER `priority`;

UPDATE `test_case` SET `priority_raw` = `priority`
 WHERE `priority_raw` IS NULL AND `priority` IS NOT NULL;

UPDATE `test_case` SET `review_status` = 'pending'
 WHERE `priority` = '待评审' AND `review_status` <> 'pending';

UPDATE `test_case` SET `priority` = NULL WHERE `priority` = '待评审';

UPDATE `test_case`
   SET `priority` = CASE `priority`
                       WHEN 'T0' THEN 'P0'
                       WHEN 'T1' THEN 'P1'
                       WHEN 'T2' THEN 'P2'
                       ELSE `priority`
                     END
 WHERE `priority` IN ('T0', 'T1', 'T2');
