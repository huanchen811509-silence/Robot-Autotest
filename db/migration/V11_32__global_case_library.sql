-- v4.6.0 / FR-v4.6-01：公共用例库基础模型。
-- 分类字典 + 用例生命周期/业务键/内容指纹。只增列、不改既有列语义。
CREATE TABLE IF NOT EXISTS `case_category` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT       NOT NULL COMMENT '作用域：平台项目 = 公共库',
    `level`      TINYINT      NOT NULL COMMENT '1 一级分类 / 2 二级分类',
    `parent_id`  BIGINT       NULL COMMENT '二级分类指向一级分类',
    `name`       VARCHAR(128) NOT NULL,
    `sort_no`    INT          NOT NULL DEFAULT 0,
    `status`     VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / disabled',
    `created_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_by` BIGINT       NULL,
    `updated_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `updated_by` BIGINT       NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_case_category` (`project_id`, `level`, `parent_id`, `name`),
    KEY `idx_case_category_scope` (`project_id`, `level`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用例分类字典（v4.6.0）';

ALTER TABLE `test_case`
    ADD COLUMN `category_l1`       VARCHAR(128) NULL COMMENT '一级分类（公共库筛选维度）' AFTER `source_sheet`,
    ADD COLUMN `external_id`       VARCHAR(128) NULL COMMENT '源表用例编号（对外业务键，幂等依据）' AFTER `case_code`,
    ADD COLUMN `content_sha`       CHAR(64)     NULL COMMENT '内容指纹（sha256，幂等与导出缓存依据）',
    ADD COLUMN `lifecycle_status`  VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / deprecated',
    ADD COLUMN `version_no`        INT          NOT NULL DEFAULT 1 COMMENT '内容版本号，更新即 +1',
    ADD KEY `idx_test_case_category_l1` (`project_id`, `category_l1`, `lifecycle_status`),
    ADD KEY `idx_test_case_external` (`project_id`, `external_id`);

-- 存量回填：一级分类沿用旧的 case_kind（历史台账唯一的分类列）；业务键沿用 case_code。
UPDATE `test_case` SET `category_l1` = `case_kind`
 WHERE `category_l1` IS NULL AND `case_kind` IS NOT NULL;
UPDATE `test_case` SET `external_id` = `case_code` WHERE `external_id` IS NULL;
