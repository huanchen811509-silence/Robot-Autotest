-- V11_16：项目标注测试内核（robot framework / pytest）。
-- 背景：执行链是 RF 中心（pabot / .robot），pytest 目前支持到导入侧（PytestImportAdapter）。
-- 本列是**项目级标注**：建卡牌 / 项目选择器 / 管理页展示用，缺省 robot 与存量项目一致。
-- 2026-11-08 大师指令：「每个项目都要支持显示测试内核 是pytest 还是 robot Framework」。
ALTER TABLE project
    ADD COLUMN test_kernel VARCHAR(16) NOT NULL DEFAULT 'robot' AFTER visibility;
