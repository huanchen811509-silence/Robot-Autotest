-- V11_75 回滚：先删子表再删主表，顺序不可倒置。
DROP TABLE IF EXISTS `device_snapshot_item`;
DROP TABLE IF EXISTS `device_snapshot`;
