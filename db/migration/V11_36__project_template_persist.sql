-- v4.8.0 / FR-v4.8-01：模板持久化与共享范围。
-- 现有 project_template 只有 name/config_json/archived；这里补编号、内核、可见性、内置标记与生命周期。
ALTER TABLE `project_template`
    ADD COLUMN `code`        VARCHAR(64)  NULL COMMENT '模板编号：前缀-引擎-年月-序号',
    ADD COLUMN `kernel`      VARCHAR(16)  NULL COMMENT 'robot / pytest',
    ADD COLUMN `visibility`  VARCHAR(16)  NOT NULL DEFAULT 'team' COMMENT 'private / team / public',
    ADD COLUMN `builtin`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=内置只读',
    ADD COLUMN `status`      VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / disabled',
    ADD COLUMN `owner_id`    BIGINT       NULL,
    ADD COLUMN `description` VARCHAR(512) NULL,
    ADD COLUMN `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ADD COLUMN `created_by`  BIGINT       NULL,
    ADD COLUMN `updated_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    ADD COLUMN `updated_by`  BIGINT       NULL,
    ADD UNIQUE KEY `uk_project_template_code` (`code`),
    ADD KEY `idx_project_template_visibility` (`visibility`, `status`);

CREATE TABLE IF NOT EXISTS `project_template_item` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `template_id`   BIGINT      NOT NULL,
    `item_type`     VARCHAR(16) NOT NULL COMMENT 'CASE / CASE_SET / SCENARIO',
    `ref_id`        BIGINT      NULL COMMENT '引用对象 id（不复制正文）',
    `mode`          VARCHAR(16) NOT NULL DEFAULT 'reference' COMMENT 'reference / copy',
    `sort_no`       INT         NOT NULL DEFAULT 0,
    `snapshot_json` TEXT        NULL COMMENT '创建时的名称等快照，源删除后可提示失效',
    `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_template_item` (`template_id`, `item_type`, `sort_no`),
    CONSTRAINT `fk_template_item_template` FOREIGN KEY (`template_id`)
        REFERENCES `project_template` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模板内容项（v4.8.0，引用不复制）';

CREATE TABLE IF NOT EXISTS `project_template_asset` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `template_id` BIGINT     NOT NULL,
    `asset_type` VARCHAR(16) NOT NULL COMMENT 'DEVICE / CREDENTIAL / LINK',
    `asset_id`   BIGINT      NOT NULL,
    `mode`       VARCHAR(16) NOT NULL DEFAULT 'reference',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_template_asset` (`template_id`, `asset_type`, `asset_id`),
    CONSTRAINT `fk_template_asset_template` FOREIGN KEY (`template_id`)
        REFERENCES `project_template` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模板引用的公共资产（v4.8.0）';

-- 内置 4 个模板迁为只读记录：按 code 幂等，不覆盖用户改过的同名模板。
INSERT INTO `project_template` (`name`, `code`, `kernel`, `visibility`, `builtin`, `status`,
                                `description`, `config_json`, `archived`)
SELECT '空白项目', 'PRJ-RF-202611-001', 'robot', 'private', 1, 'active',
       '不含任何预置内容的最小基线', '{"key":"blank","codePrefix":"PRJ"}', 0
 WHERE NOT EXISTS (SELECT 1 FROM `project_template` WHERE `code` = 'PRJ-RF-202611-001');

INSERT INTO `project_template` (`name`, `code`, `kernel`, `visibility`, `builtin`, `status`,
                                `description`, `config_json`, `archived`)
SELECT '接口回归', 'API-RF-202611-001', 'robot', 'team', 1, 'active',
       '接口回归默认参数与目录结构', '{"key":"api-regression","codePrefix":"API"}', 0
 WHERE NOT EXISTS (SELECT 1 FROM `project_template` WHERE `code` = 'API-RF-202611-001');

INSERT INTO `project_template` (`name`, `code`, `kernel`, `visibility`, `builtin`, `status`,
                                `description`, `config_json`, `archived`)
SELECT 'Web UI 巡检', 'UI-RF-202611-001', 'robot', 'team', 1, 'active',
       'Web UI 冒烟与巡检基线', '{"key":"ui-web","codePrefix":"UI"}', 0
 WHERE NOT EXISTS (SELECT 1 FROM `project_template` WHERE `code` = 'UI-RF-202611-001');

INSERT INTO `project_template` (`name`, `code`, `kernel`, `visibility`, `builtin`, `status`,
                                `description`, `config_json`, `archived`)
SELECT 'Pytest 服务', 'PY-PYT-202611-001', 'pytest', 'private', 1, 'active',
       'Pytest 服务级用例模板', '{"key":"pytest-service","codePrefix":"PY"}', 0
 WHERE NOT EXISTS (SELECT 1 FROM `project_template` WHERE `code` = 'PY-PYT-202611-001');
