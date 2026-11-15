-- V11_67 项目创建人索引（v5.9.0 / FR-v5.9-03 / W59-03）
--
-- **评估修正（实测推翻原假设）**：`project.created_by` 列**早已存在**（V11_23 起的
-- 「审计列补齐」批次已覆盖 project 表）。原计划的「加列」是多余动作——首版迁移因此在
-- 开发库上判红（Duplicate column name），实测读数：`SHOW COLUMNS FROM project` 有
-- created_by BIGINT NULL，且 14 行里 13 行为 NULL、1 行有值（历史行）。
--
-- 于是本迁移只做真正缺的那一半：**索引**。三处需要按 created_by 反查
-- （删除用户的影响面、项目列表的创建人列、「我建的」筛选），14 行时不建也行，
-- 但这是唯一一次能低成本补上的机会。
--
-- 口径不变：
--   1) 列可空，**不回填存量**（历史项目无从考证，猜一个 created_by 等于造假事实）；
--   2) 新建项目必须写（ProjectRepositoryJdbc.insert 已补列）；
--   3) 可见性三源的 OWNER 一源仍以 owner_id 为准，created_by 只用于展示与影响面。

CREATE INDEX `idx_project_created_by` ON `project` (`created_by`);
