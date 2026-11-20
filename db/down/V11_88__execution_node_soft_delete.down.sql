-- V11_88 回滚：去掉归档列（已归档节点会重新出现在默认列表）。
ALTER TABLE `execution_node` DROP COLUMN `deleted_at`;
