-- V11_88 / FR-v5.33-02 / W83-03：执行节点软删（归档）
--
-- 现状：9 个节点里 8 个是历史验收残留，0 在线；后端无删除能力，列表永远脏。
-- 口径：只软删，不物理删 —— run 历史仍引用 node_id，物理删会让历史断链。
ALTER TABLE `execution_node`
  ADD COLUMN `deleted_at` DATETIME(3) NULL COMMENT '归档时间；非空表示已归档，不进默认列表'
    AFTER `last_heartbeat_at`,
  ADD KEY `idx_execution_node_deleted` (`deleted_at`);
