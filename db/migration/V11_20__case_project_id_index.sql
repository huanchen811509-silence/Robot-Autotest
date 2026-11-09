-- v3.2.0 / EW32-10 容量压测发现的缺陷修复。
--
-- 缺陷：TestCaseRepositoryJdbc 默认 ORDER BY id DESC / keyset / sort=id 分支使用
--       FORCE INDEX (idx_case_project_id)，但 V11_19 只创建了
--       idx_case_project_updated (project_id, updated_at)，没有创建该索引。
--       结果是**每一次用例列表请求**都返回 500（ERROR 1176: key doesn't exist）。
--       该问题在 EW32-02 的单测（fake 仓储）与 EXPLAIN 临时表里都没被暴露，
--       由 EW32-10 并发压测首轮 100% 失败的 cases_list 场景发现。
--
-- 修复：补建 FORCE INDEX 引用的 (project_id, id) 复合索引，与运行表的
--       idx_run_project_id 保持同一形状。

ALTER TABLE `test_case`
    ADD INDEX `idx_case_project_id` (`project_id`, `id`);
