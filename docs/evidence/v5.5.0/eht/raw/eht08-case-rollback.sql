-- 回滚：删除本项目导入的用例台账（绑定会随之失效，需重跑 sync）
DELETE FROM test_case WHERE project_id=95;
