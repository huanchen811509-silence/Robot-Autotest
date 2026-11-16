-- V11_79 交换机 / BMC 探针包（v5.18.0 / FR-v5.18-05 / W68-10）
--
-- 口径：
--  1) **命令文本不入库**。交换机与 BMC 的命令在 ProbeCommandCatalog（Linux 那份仍引用
--     HostInventoryCollector.COMMANDS，受门禁 29 的「恰好 10 类」约束）。表里只登记
--     包、顺序与 required。存两份命令必然漂移。
--  2) required 按类独立（判据 4）：
--     - 交换机：版本 + 端口状态必采；LLDP 可能未启用、MAC 表可能为空 → 非必采。
--     - BMC：mc info 必采；温度/SEL/LAN 视机型与权限 → 非必采。
--  3) 真机采集无样本，本版记 BLOCKED（D-3 拍板）；这里只保证「包存在且命令 ⊆ 白名单」。

INSERT IGNORE INTO `probe_pack`
  (`project_id`, `code`, `device_type`, `name`, `enabled`)
VALUES
  (0, 'NETWORK_SWITCH_DEFAULT', 'NETWORK_SWITCH', '交换机只读采集包', 1),
  (0, 'BMC_DEFAULT', 'BMC', 'BMC 只读采集包', 1);

INSERT IGNORE INTO `probe_pack_command`
  (`project_id`, `pack_id`, `command_key`, `required`, `sort_order`)
SELECT 0, p.id, k.command_key, k.required, k.sort_order
  FROM `probe_pack` p
  CROSS JOIN (
        SELECT 'SW_VERSION'    AS command_key, 1 AS required, 0 AS sort_order UNION ALL
        SELECT 'SW_INTERFACES', 1, 1 UNION ALL
        SELECT 'SW_LLDP',       0, 2 UNION ALL
        SELECT 'SW_MAC',        0, 3
       ) k
 WHERE p.project_id = 0 AND p.code = 'NETWORK_SWITCH_DEFAULT';

INSERT IGNORE INTO `probe_pack_command`
  (`project_id`, `pack_id`, `command_key`, `required`, `sort_order`)
SELECT 0, p.id, k.command_key, k.required, k.sort_order
  FROM `probe_pack` p
  CROSS JOIN (
        SELECT 'BMC_MC_INFO' AS command_key, 1 AS required, 0 AS sort_order UNION ALL
        SELECT 'BMC_SDR',    0, 1 UNION ALL
        SELECT 'BMC_SEL',    0, 2 UNION ALL
        SELECT 'BMC_LAN',    0, 3
       ) k
 WHERE p.project_id = 0 AND p.code = 'BMC_DEFAULT';
