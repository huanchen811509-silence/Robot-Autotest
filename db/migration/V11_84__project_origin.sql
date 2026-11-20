-- V11_84 / FR-ORIGIN-01~02：项目来源标记（程序自带 BUILTIN / 用户自建 USER）
--
-- 背景：现网删除项目只允许空项目（busyCounts 有数据一律 409），用户自建的临时测试项目
--       带数据就删不掉。加 origin 位区分来源：USER 走级联真删，BUILTIN 维持老规则。
--
-- 口径：
--   1) 只加列 + 索引，不改既有列语义；默认 USER（新建项目即用户自建）。
--   2) 存量严格按大师裁定 D3 打标：id=157 网卡测试01=USER，其余 5 个存量项目=BUILTIN。
--      不按 code/name 前缀猜来源。
--   3) 打标 UPDATE 带 WHERE origin<>'BUILTIN'，重复执行命中 0 行（幂等）。
--   4) 不写 ADD COLUMN IF NOT EXISTS（目标库 MariaDB 不支持，ERROR 1064）。
ALTER TABLE `project`
  ADD COLUMN `origin` VARCHAR(16) NOT NULL DEFAULT 'USER'
    COMMENT '项目来源：USER=用户自建(可级联彻底删除) / BUILTIN=程序自带(有数据只可停用)'
    AFTER `kind`,
  ADD KEY `idx_project_origin` (`origin`);

-- 存量打标：157 保持默认 USER（网卡测试01）；其余 5 个按 D3 置 BUILTIN。
UPDATE `project` SET `origin` = 'BUILTIN'
 WHERE id IN (1, 77, 80, 88, 95) AND `origin` <> 'BUILTIN';
