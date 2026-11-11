-- OPT-02 / FR-49-05..06：14 列「详细测试流程」四列补录 + 测试资源拆列。
-- 阶段一：只加可空列，不改既有列语义，存量 1798 行不受影响。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，故本脚本作为增量迁移只执行一次；
-- 五个列名均为本次新增，不与已应用迁移（V11_3 / V11_32 / V11_39）里的列冲突。
-- TEXT / VARCHAR 继承 test_case 表默认 utf8mb4 字符集，不显式写 COLLATE 以免与表不一致。
ALTER TABLE `test_case`
  ADD COLUMN `a_side_command` TEXT         NULL COMMENT 'A端命令（FR-49-05）',
  ADD COLUMN `b_side_command` TEXT         NULL COMMENT 'B端命令（FR-49-05）',
  ADD COLUMN `switch_config`  TEXT         NULL COMMENT '交换机配置（FR-49-05）',
  ADD COLUMN `test_result`    VARCHAR(512) NULL COMMENT '测试结果（FR-49-05）',
  ADD COLUMN `test_resource`  TEXT         NULL COMMENT '测试资源（原误并入 ed202_coverage，FR-49-06 拆为独立列）';
