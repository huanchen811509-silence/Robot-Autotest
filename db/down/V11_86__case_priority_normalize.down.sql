-- V11_86 回滚：用 priority_raw 还原 priority（含「待评审」），再删列。
-- 注意：「待评审」行的 review_status 保持 pending —— 归一后它本就是评审态，不还原成别的值。
UPDATE `test_case` SET `priority` = `priority_raw` WHERE `priority_raw` IS NOT NULL;
ALTER TABLE `test_case` DROP COLUMN `priority_raw`;
