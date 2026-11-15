-- V11_74 报告模板内核维度 + EHT 预置模板（v5.14.0 / FR-v5.14-08 / W64-10/13）
--
-- 口径：
--  1) 只加 1 列，不改既有行语义。存量模板全部落 'robot'（列默认值即回填），
--     标准 RF 工程的选择结果与迁移前完全一致。
--  2) kernel 取值 robot / pytest / any；'any' 表示通用模板，只参与最后一级回退。
--  3) EHT-PYTEST 平台模板用 INSERT IGNORE + 唯一键 (project_id, name) 保证幂等：
--     重复执行迁移不会产生第二份模板，也不会把字段重复插一遍
--     （report_template_field 有 uk(template_id, field_key)）。
--  4) 9 个字段 = 6 个必填（reportId/title/version/frozenAt/disclaimer/watermark）
--     + 结论与两个计数；不是 21 项全量，符合「EHT 报告只给结论层」的定位。
--
-- 编号：R-1 已核 V11_69~72 从未落文件，本支紧接 W1 的 V11_73 追加，不跳号。

ALTER TABLE `report_template`
  ADD COLUMN `kernel` VARCHAR(32) NOT NULL DEFAULT 'robot'
      COMMENT '适用内核：robot / pytest / any；存量行默认 robot' AFTER `is_default`;

INSERT IGNORE INTO `report_template`
  (`project_id`, `name`, `description`, `version`, `is_default`, `kernel`)
VALUES (0, 'EHT-PYTEST', 'EHT pytest 预置模板：结论层 9 字段（v5.14.0）', 1, 1, 'pytest');

INSERT IGNORE INTO `report_template_field`
  (`project_id`, `template_id`, `field_key`, `sort_order`, `title_override`)
SELECT 0, t.id, f.field_key, f.sort_order, NULL
  FROM `report_template` t
  CROSS JOIN (
        SELECT 'reportId'   AS field_key, 0 AS sort_order UNION ALL
        SELECT 'title',       1 UNION ALL
        SELECT 'version',     2 UNION ALL
        SELECT 'frozenAt',    3 UNION ALL
        SELECT 'conclusion',  4 UNION ALL
        SELECT 'passCount',   5 UNION ALL
        SELECT 'failCount',   6 UNION ALL
        SELECT 'disclaimer',  7 UNION ALL
        SELECT 'watermark',   8
       ) f
 WHERE t.project_id = 0 AND t.name = 'EHT-PYTEST';
