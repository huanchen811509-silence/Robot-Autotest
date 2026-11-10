-- v4.1.0 回滚：工作区任务表只承载任务状态与报告，无外部数据依赖（ADR-0003 手写 down）。
DROP TABLE IF EXISTS `workspace_job`;
