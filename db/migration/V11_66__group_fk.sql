-- V11_66 用户组三表外键与级联删除（v5.9.0 / FR-v5.9-02 / W59-02）
--
-- 背景：V8_3 建表时只写了主键，没有外键。于是「删用户 / 删组 / 删项目」会在三张表里
-- 留下孤儿行——用户组页因此显示「已授权 3 个项目」，点开只有 1 个真实项目。
-- 评估 01 §3.2 与 06 §2.1 都指到这一处。
--
-- 选择：**ON DELETE CASCADE**（不是服务层逐表清理）。理由：
--   1) 三张表都是纯关联表，没有任何业务字段（user_group_member 只有两列主键，
--      project_group_role 只有 role_code），父行不存在时这些行**没有任何语义**；
--   2) 服务层逐表清理要求每个删除入口都记得清（现有 4 个入口：用户 / 组 / 项目 / 组授权），
--      漏一个就重新长出孤儿，而这正是现状；
--   3) 级联是库级保证，新加的删除入口自动正确。
-- 反例即不选 CASCADE 的场景：子表带独立业务语义（如审计日志）。这三张表都不是。
--
-- 前置清理：存量孤儿 FK 建不上。孤儿行按定义不可达，直接删，且**只删孤儿**。
-- 盘点证据见 docs/evidence/v5.9.0/sql/orphan-before.sql（迁移前后各跑一次）。

DELETE m FROM `user_group_member` m
  LEFT JOIN `app_user` u ON u.id = m.user_id
  LEFT JOIN `user_group` g ON g.id = m.group_id
 WHERE u.id IS NULL OR g.id IS NULL;

DELETE r FROM `project_group_role` r
  LEFT JOIN `project` p ON p.id = r.project_id
  LEFT JOIN `user_group` g ON g.id = r.group_id
 WHERE p.id IS NULL OR g.id IS NULL;

ALTER TABLE `user_group_member`
  ADD CONSTRAINT `fk_ugm_user`  FOREIGN KEY (`user_id`)  REFERENCES `app_user` (`id`)  ON DELETE CASCADE,
  ADD CONSTRAINT `fk_ugm_group` FOREIGN KEY (`group_id`) REFERENCES `user_group` (`id`) ON DELETE CASCADE;

ALTER TABLE `project_group_role`
  ADD CONSTRAINT `fk_pgr_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`)   ON DELETE CASCADE,
  ADD CONSTRAINT `fk_pgr_group`   FOREIGN KEY (`group_id`)   REFERENCES `user_group` (`id`) ON DELETE CASCADE;
