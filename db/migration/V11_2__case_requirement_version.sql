-- EDIM-04 / D3：不可变判据版本。原文件 SHA 只读冻结（fixtures/ed202/README.md），
-- 判据变更时插入新版本、旧版本永不改；当前判定状态随 sha256 变化转「待重测」。
-- 【2026-11-08 修订｜被实测推翻的假设并撤销】
-- 首版在此处建了两个 BEFORE UPDATE / DELETE 触发器 SIGNAL 拒绝改写，假设「单语句触发器体
-- 不需要 DELIMITER 就能被 Flyway 执行」。实测门禁 05 报 Flyway V11_2 迁移失败：
--   java.sql.SQLException: Error Code 1419
--   You do not have the SUPER privilege and binary logging is enabled
-- 本机 robot_dev 开启 binlog 且应用账号无 SUPER 权限，CREATE TRIGGER 被直接拒绝，
-- 连带 SpringBootTest 的 ApplicationContext 起不来（RunLogWebSocketE2ETest 4 个 Error）。
-- 故本版撤销触发器，改由「应用层只发 INSERT + 唯一键 uk_crv_case_version + 单测
-- RequirementVersionImmutabilityTest 锁死」保证不可变；数据库层不引入需要 SUPER 的对象。
-- 上行迁移只增表，不含任何 TRIGGER / PROCEDURE / FUNCTION。
CREATE TABLE IF NOT EXISTS `case_requirement_version` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `test_case_id`  BIGINT       NOT NULL,
    `version_no`    INT          NOT NULL DEFAULT 1 COMMENT '同一用例判据内容变化才 +1',
    `precondition`  TEXT         NULL COMMENT '预置条件快照',
    `steps_json`    TEXT         NULL COMMENT '测试步骤快照（按换行编号拆分后 JSON）',
    `expected`      TEXT         NULL COMMENT '预期结果（判据）快照',
    `sha256`        CHAR(64)     NOT NULL COMMENT 'ED202 原表只读快照 SHA-256；变更即判据变更',
    `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_by`    BIGINT       NULL,
    `updated_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `updated_by`    BIGINT       NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_crv_case_version` (`test_case_id`, `version_no`),
    KEY `idx_crv_sha256` (`sha256`),
    CONSTRAINT `fk_crv_case` FOREIGN KEY (`test_case_id`) REFERENCES `test_case` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用例判据不可变版本，只允许 INSERT（EDIM-04/D3）';

-- 不可变性由应用层与单测保证，见文件头 2026-11-08 修订说明；本迁移不创建触发器。
