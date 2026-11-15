-- V11_77 拓扑三源分级 + 执行前闸门留痕列（v5.15.0 / FR-v5.15-05 / 09 / W65-09,17）
--
-- 口径：
--  1) 只追加两列到 link，存量行一律 PLANNED/MANUAL；不改既有行语义、不删规划链路。
--     「实测盖规划」是展示与判定的优先级（MEASURED > PLANNED），不是把历史覆盖掉。
--  2) run.env_check_note 是 W65-17「无声明也要留痕」的落点；只填空不覆盖非空
--     （更新语句带 env_check_note IS NULL 条件）。
--  3) 两列都可空/有默认，配 down 回滚。

ALTER TABLE `link`
  ADD COLUMN `confidence` VARCHAR(16) NOT NULL DEFAULT 'PLANNED'
      COMMENT 'PLANNED=规划 / MEASURED=实测（LLDP）/ INFERRED=推断';

ALTER TABLE `link`
  ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT 'MANUAL'
      COMMENT 'MANUAL / LLDP / TOPOLOGY_NODE';

ALTER TABLE `run`
  ADD COLUMN `env_check_note` VARCHAR(512) NULL
      COMMENT '执行前环境闸门留痕：无声明也要能查到为什么没拦';
