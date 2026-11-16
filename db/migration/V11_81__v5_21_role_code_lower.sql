-- v5.21.0 / FR-v5.21-06：角色代码统一小写（幂等）
--
-- 背景：`project_member_role.role_code` 里 `viewer` 与 `ENGINEER` 并存（v5.21 调研 F-21-17）。
-- 同一个接口两种写法，判权侧靠 `RoleCode.from()` 的 toLowerCase 兜住了，但
-- 「角色代码」本身在库内不是单一形态：按 role_code 分组统计会分裂成两行，
-- 导出/对账时同一个角色看起来像两个。
--
-- 口径：
-- 1. 三个持有 role_code 的表一起收口：project_member_role / project_group_role / role；
-- 2. 只改「与大写化结果不同」的行，天然幂等（连跑两次第二次 0 行）；
-- 3. 不改列定义（不换 collation）——换 collation 会影响唯一键判定，
--    本版只做数据收口，写入路径在 ProjectRepositoryJdbc / GroupService 统一 toLowerCase。
-- 4. 校验：三表 `role_code <> LOWER(role_code)` 计数必须为 0。

UPDATE `project_member_role`
   SET `role_code` = LOWER(`role_code`)
 WHERE `role_code` <> LOWER(`role_code`);

UPDATE `project_group_role`
   SET `role_code` = LOWER(`role_code`)
 WHERE `role_code` <> LOWER(`role_code`);

UPDATE `role`
   SET `code` = LOWER(`code`)
 WHERE `code` <> LOWER(`code`);
