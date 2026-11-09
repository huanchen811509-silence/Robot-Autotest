-- V11_18 回滚：先删已读记录表，再删其余三张新表。
-- notification 本体未改结构，回滚时无需 ALTER。

DROP TABLE IF EXISTS `notification_read`;

DROP TABLE IF EXISTS `notification_preference`;
DROP TABLE IF EXISTS `batch_undo`;
DROP TABLE IF EXISTS `saved_view`;
