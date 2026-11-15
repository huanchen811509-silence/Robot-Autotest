-- V11_68 down（v5.10.0 / W60-01）
--
-- 只回滚**结构**：DROP 注册表。`project_code_sequence` 的回填**不回滚**，理由与 ADR-0003 一致：
--   1) 回填是数据合并（`GREATEST`），不是结构变更；删掉这些行等于把「已发过的号」忘掉，
--      再建项目就会从 001 重新发号，直接违反 FR-v5.10-08 判据 1（不复用）；
--   2) 回滚结构后应用层不再读注册表，序列多出来的行对旧代码是无害的（只影响取号起点）；
--   3) 若要彻底回退，需要先 `DELETE FROM project_code_sequence WHERE scope LIKE '%-%-%'`
--      再人工比对存量编码——这属于**数据迁移**，不该由一次结构性 down 悄悄做掉。
--
-- 演练（scripts/migration-down-up-drill.sh）判据：down 后 `project_code_prefix` 不存在，
-- `project.code` 与 `project_code_sequence` 行数不变。

DROP TABLE IF EXISTS `project_code_prefix`;
