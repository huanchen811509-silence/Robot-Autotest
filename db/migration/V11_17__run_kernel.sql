-- V11_17: run 表记录本次执行使用的测试内核（v2.7 双内核接入执行链）。
-- 路由真源是 KernelSelector（system_setting kernel.default），此列是 run 级事实快照：
-- 报告入库按它分流解析器（robot → OutputXmlParser / pytest → PytestKernel.normalize）。
ALTER TABLE run
    ADD COLUMN kernel VARCHAR(16) NOT NULL DEFAULT 'robot'
        COMMENT '执行内核：robot | pytest（run 级快照，创建执行时由 KernelSelector 写入）' AFTER device_scope;
