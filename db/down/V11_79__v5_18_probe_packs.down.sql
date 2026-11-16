-- V11_79 回滚：只删本迁移新增的两个包与其命令（HOST_LINUX_DEFAULT 不动）
DELETE FROM `probe_pack_command`
 WHERE `pack_id` IN (SELECT id FROM `probe_pack`
                      WHERE `project_id` = 0 AND `code` IN ('NETWORK_SWITCH_DEFAULT', 'BMC_DEFAULT'));
DELETE FROM `probe_pack`
 WHERE `project_id` = 0 AND `code` IN ('NETWORK_SWITCH_DEFAULT', 'BMC_DEFAULT');
