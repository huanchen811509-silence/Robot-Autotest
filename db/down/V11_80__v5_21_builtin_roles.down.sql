-- V11_80 回滚：只删本迁移补建的三条内置角色
--
-- 注意：`admin` 那一行由基线迁移创建，本迁移只是在 `built_in = 0` 时补标志位，
-- 回滚**不**把它改回 0（那不是本迁移引入的状态，改了就是制造偏差）。
-- 删除顺序按外键依赖：先删 `user_role` 里引用这三条角色的绑定，再删角色本身，
-- 否则有账号绑定时回滚会因外键失败而半途中断。
DELETE FROM `user_role`
 WHERE `role_id` IN (SELECT id FROM `role` WHERE `code` IN ('pm', 'engineer', 'viewer'));

DELETE FROM `role` WHERE `code` IN ('pm', 'engineer', 'viewer');
