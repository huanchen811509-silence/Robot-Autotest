-- v5.21.0 / FR-v5.21-01：四内置角色落库（幂等，不覆盖已有行）
--
-- 背景：`RoleCode` 枚举有 4 个角色（ADMIN / PM / ENGINEER / VIEWER），但 `role` 表
-- 一直只有 admin 一行 → 用户管理页「新建用户」的角色下拉只有"管理员"一项，
-- 界面上**根本建不出任何非管理员账号**（v5.21 调研 F-01）。
--
-- 写法要点：
-- 1. 每条都用 `INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS(...)`，
--    连跑两次仍然是 4 条（幂等）；
-- 2. **不用** `ON DUPLICATE KEY UPDATE name=...`：那会在每次启动/迁移时
--    把管理员手工改过的名称覆盖回默认值；
-- 3. `built_in = 1` 表示内置角色，`IamRolesView` 据此隐藏删除入口；
-- 4. admin 那行由基线迁移创建，本脚本不碰它（已存在即跳过）。

INSERT INTO `role` (`code`, `name`, `description`, `built_in`)
SELECT 'pm', '项目经理', '可管理自己项目的用例、资产与执行；可建项目', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'pm');

INSERT INTO `role` (`code`, `name`, `description`, `built_in`)
SELECT 'engineer', '工程师', '可编写与执行用例、维护资产；不能建项目、不能管用户', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'engineer');

INSERT INTO `role` (`code`, `name`, `description`, `built_in`)
SELECT 'viewer', '只读访客', '只读：可看用例、报告与环境，不能做任何写操作', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'viewer');

-- admin 若因历史原因 `built_in` 为 0，这里补成 1（只补标志位，不改名称/描述）
UPDATE `role` SET `built_in` = 1 WHERE `code` = 'admin' AND `built_in` = 0;
