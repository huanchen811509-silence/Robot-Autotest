-- V11_68 编码前缀注册表 + 项目编号序列回填（v5.10.0 / FR-v5.10-08 / W60-01）
--
-- 背景：`nextCode()` 已用 `project_code_sequence` 原子分配（V11_43），但有两处真实缺口：
--   1) 序列**没有存量回填** —— 升级前建的项目不在序列里，升级后第一次取号会从 001 起，
--      撞上存量编码后靠 `codeExists` 循环跳过，跳过的号照样被序列吃掉（看起来没错，实际在漏号）；
--   2) 前缀**没有注册表** —— 「两个模板用同一前缀」只能靠人看，没有任何一处能判定
--      「这个前缀是否已被占用 / 它代表什么业务含义」。
--
-- 本迁移只做两件事，不改任何既有列、不删任何行：
--   A. 建 `project_code_prefix`（前缀 → 含义 + 归属人），并把存量编码里的前缀全部回填；
--   B. 按存量 `project.code` 反算每个 scope 的最大流水，`GREATEST` 合并进 `project_code_sequence`。
--
-- 口径：
--   - 只认合法编码 `前缀-(RF|PYT)-YYYYMM-3位以上流水`。历史手工编码（如 `TEST-V59-GROUP`、
--     `EHT-PYTEST-LIB`、`qa-e2e-...`）**不解析、不参与**：它们不是序列产物，猜出来的前缀会污染注册表。
--   - 回填是 `INSERT ... ON DUPLICATE KEY UPDATE next_no = GREATEST(...)`，**幂等**：
--     重复执行不会把序列改小，也不会把已发号退回去（号段只增不减是 FR-01 的判据）。
--   - `TPL` 是模板自动编号的兜底前缀（`ProjectTemplateService.generateCode`），一并入表，
--     否则模板向导「从注册表选前缀」的选择列表里就缺了默认项。

CREATE TABLE IF NOT EXISTS `project_code_prefix` (
    `code`       VARCHAR(8)  NOT NULL COMMENT '编码前缀，大写字母数字，2~8 位',
    `meaning`    VARCHAR(64) NOT NULL COMMENT '业务含义（人可读，例：接口自动化）',
    `owner_id`   BIGINT      DEFAULT NULL COMMENT '登记人；存量回填行为 NULL',
    `builtin`    TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1=系统兜底前缀，不可删',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目/模板编码前缀注册表（v5.10.0）';

-- A1. 存量前缀：来自项目编码
INSERT IGNORE INTO `project_code_prefix` (`code`, `meaning`)
SELECT DISTINCT UPPER(SUBSTRING_INDEX(`code`, '-', 1)), '存量回填（项目）'
  FROM `project`
 WHERE `code` REGEXP '^[A-Za-z][A-Za-z0-9]{1,7}-(RF|PYT)-[0-9]{6}-[0-9]{3,}$';

-- A2. 存量前缀：来自模板编码
INSERT IGNORE INTO `project_code_prefix` (`code`, `meaning`)
SELECT DISTINCT UPPER(SUBSTRING_INDEX(`code`, '-', 1)), '存量回填（模板）'
  FROM `project_template`
 WHERE `code` REGEXP '^[A-Za-z][A-Za-z0-9]{1,7}-(RF|PYT)-[0-9]{6}-[0-9]{3,}$';

-- A3. 兜底前缀
INSERT IGNORE INTO `project_code_prefix` (`code`, `meaning`, `builtin`)
VALUES ('TPL', '模板默认前缀', 1),
       ('PRJ', '默认项目前缀', 1);

-- B. 序列回填：scope = 前缀-内核-年月，next_no = 该 scope 已出现的最大流水
INSERT INTO `project_code_sequence` (`scope`, `next_no`)
SELECT CONCAT(prefix, '-', kernel, '-', ym) AS scope,
       MAX(CAST(SUBSTRING_INDEX(`code`, '-', -1) AS UNSIGNED)) AS max_no
  FROM (
        SELECT UPPER(SUBSTRING_INDEX(`code`, '-', 1))                          AS prefix,
               SUBSTRING_INDEX(SUBSTRING_INDEX(`code`, '-', 2), '-', -1)      AS kernel,
               SUBSTRING_INDEX(SUBSTRING_INDEX(`code`, '-', 3), '-', -1)      AS ym,
               `code`
          FROM `project`
         WHERE `code` REGEXP '^[A-Za-z][A-Za-z0-9]{1,7}-(RF|PYT)-[0-9]{6}-[0-9]{3,}$'
       ) parsed
 GROUP BY prefix, kernel, ym
    ON DUPLICATE KEY UPDATE `next_no` = GREATEST(`next_no`, VALUES(`next_no`));
