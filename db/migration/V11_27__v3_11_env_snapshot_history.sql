-- v3.11.0 / FR-v3.11-02：环境快照要能留历史，同一环境必须能存多份快照。
--
-- 现场（2026-11-09 真实 HTTP 实测）：`POST /api/projects/29/snapshots` 第一次 200、
-- 同名第二次 500（Duplicate entry for key 'uk_env_profile_project_name'）。
-- 于是 EnvProfileService.list(projectId, name) 最多只能回 1 行，而 diff(before, after)
-- 要求「同一个环境的前后两份快照」——判据②「两快照对比」在这个约束下不可能成立。
--
-- 唯一键是 V1 基线建的：那时 env_profile 被当成「一个环境一行配置」；
-- AST-02 之后它已经是「一次快照一行」（EnvSnapshot 带 snapshot_at），语义早变了，
-- 约束没跟着改。这里把它换成普通联合索引：按环境名查历史仍然走索引，
-- 但允许同名多行。旧数据不受影响（一个环境名仍只有一行）。
ALTER TABLE `env_profile`
  DROP INDEX `uk_env_profile_project_name`,
  ADD INDEX `idx_env_profile_project_name_time` (`project_id`, `name`, `snapshot_at`);
