-- V11_65 演示数据标记（v5.9.0 / FR-v5.9-09 / W59-01）
--
-- 背景：开发/演示期造的账号、项目、用例、报告与真实数据混在一起，没有任何字段能
-- 区分。评估 06 §2.2 的结论是「按名字猜」不可靠（改个名就漏），必须有显式标记。
--
-- 口径：
--   1) is_demo 只表示**这条记录由开发/演示创建**，不改变任何权限与可见性逻辑；
--   2) 默认 0，存量行全为 0（不猜测历史数据是演示还是真实，PRD FR-v5.9-09 判据 3）；
--   3) 删除只认这一位（scripts/ops/purge-demo.sh），不按 code/name 前缀匹配。
--
-- project.kind 不在此迁移改：它已是 VARCHAR(16)，'dev' 天然可写（V11_31 定义），
-- 真正要改的是应用层 ProjectKind 枚举，属代码改动不属库结构改动。

ALTER TABLE `app_user`
  ADD COLUMN `is_demo` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=开发/演示账号（v5.9.0）';

ALTER TABLE `project`
  ADD COLUMN `is_demo` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=开发/演示项目（v5.9.0）';

-- 机检（tools/check_demo_data.py）与清理脚本都要按这一位扫全表，建索引避免全表扫。
CREATE INDEX `idx_project_demo` ON `project` (`is_demo`);
CREATE INDEX `idx_app_user_demo` ON `app_user` (`is_demo`);
