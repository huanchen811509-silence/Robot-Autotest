-- V11_85 / FR-v5.31-02 / W81-04：安全级按分类的建议值
--
-- 目的：1953 条 LX 里绝大多数是「没人判过」，不是「判不了」。给出按一级分类的建议级别，
--       让人工确认有起点，而不是从 0 开始逐条想。
-- 口径：建议值只是建议——UI 必须人工确认后才落库；L2 建议不代表已批准执行。
-- 幂等：重复执行命中 ON DUPLICATE KEY，不产生重复行；不写 IF NOT EXISTS（MariaDB 不支持）。
CREATE TABLE `safety_level_recommendation` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `category_l1`     VARCHAR(128) NOT NULL COMMENT '一级分类名',
  `suggested_level` VARCHAR(4)   NOT NULL COMMENT 'L0/L1/L2',
  `note`            VARCHAR(255) NULL,
  `updated_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slr_category` (`category_l1`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全级分类建议（v5.31 W81-04）';

INSERT INTO `safety_level_recommendation` (`category_l1`, `suggested_level`, `note`)
SELECT c.`name`,
       CASE
         WHEN c.`name` LIKE '%只读%' OR c.`name` LIKE '%查询%' OR c.`name` LIKE '%信息%'
           OR c.`name` LIKE '%基础管理%' OR c.`name` LIKE '%兼容适配%' THEN 'L0'
         WHEN c.`name` LIKE '%安全%' OR c.`name` LIKE '%故障%' OR c.`name` LIKE '%恢复%'
           OR c.`name` LIKE '%硬件%' OR c.`name` LIKE '%认证%' THEN 'L2'
         ELSE 'L1'
       END,
       '按分类关键词生成的建议值，执行前必须人工确认'
  FROM `case_category` c
 WHERE c.`level` = 1 AND c.`status` = 'active'
   AND (c.`scope` IS NULL OR c.`scope` = 'platform')
ON DUPLICATE KEY UPDATE `suggested_level` = VALUES(`suggested_level`);
