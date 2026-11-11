-- W-D-02 / W-D-04：修正 12 台现场设备的脏字段并绑定平台凭据（v4.12.0 / FR-v4.12-16、17）。
--
-- 背景：这批设备是 v4.7 从 Excel 导入的，导入器把「地址 / 映射地址 / 账号」三行塞进了
-- `bmc_address`，把「账号 / 权限说明 / 资产标签」塞进了 `account`，把「认证方式 / 端口」
-- 塞进了 `login_mode`（多值 + 换行）。FR-v4.12-11 的批量采集要靠这两个地址列取对端，
-- 换行会让「采集地址」变成一整段文本，SSH 直接连不上，所以必须先治数据。
--
-- 列语义（本脚本之后的唯一口径）：
--   management_ip     业务管理面的**可达真地址**（现场实测 10.202.54.x 通，172.31.x 无路由）
--   management_ip_alt 业务管理面的映射地址（原值，保留用于对照）
--   bmc_address       带外可达地址（10.202.54.11~18）
--   bmc_address_alt   带外映射地址（172.31.10.11~18）
--   account           只放登录账号（inspur / eht / ehtadmin）
--   login_mode        SSH_PASSWORD（Linux）/ SSH_CLI（网络设备 CLI）
--   domain_tag        原 `account` 第 3 行的资产标签（SZ_*，无专用列，按「标签」语义落这里）
--
-- 凭据 id（W-D-03 经 API 建立）：3=lab-inspur-ssh 4=lab-eht-ssh 5=lab-inspur-bmc
--                              6=lab-eht-bmc 7=lab-netadmin-ssh
--
-- 为什么交换机绑 management_credential_id 而不是 bmc_credential_id：
--   现场这 4 台没有独立的带外 IP，10.202.54.91~93/100 就是它们的**可登录管理地址**
--   （实测 SSH 进去是 centecOS/Ruijie CLI）。若再抄一份进 bmc_address，同一台机器会被
--   「全部采集」按两个地址面各采一次，白跑一遍还互相覆盖时间线。PRD FR-v4.12-17 表里
--   写的是「bmc 面」，此处按实测数据修正，已记入 TEST-REPORT 的偏差项。

UPDATE `device` SET device_type = 'switch' WHERE id = 3 AND owner_scope = 'PLATFORM';

-- ---- GPU 01~04（inspur 账号，业务 .101~104 / 带外 .11~14）----
UPDATE `device` SET device_type='gpu', account='inspur', login_mode='SSH_PASSWORD',
       vendor='Inspur', management_ip_alt='172.31.20.101',
       bmc_address='10.202.54.11', bmc_address_alt='172.31.10.11',
       domain_tag='SZ_GPU01', management_credential_id=3, bmc_credential_id=5
 WHERE id = 6 AND owner_scope = 'PLATFORM';
UPDATE `device` SET device_type='gpu', account='inspur', login_mode='SSH_PASSWORD',
       vendor='Inspur', management_ip_alt='172.31.20.102',
       bmc_address='10.202.54.12', bmc_address_alt='172.31.10.12',
       domain_tag='SZ_GPU02', management_credential_id=3, bmc_credential_id=5
 WHERE id = 7 AND owner_scope = 'PLATFORM';
UPDATE `device` SET device_type='gpu', account='inspur', login_mode='SSH_PASSWORD',
       vendor='Inspur', management_ip_alt='172.31.20.103',
       bmc_address='10.202.54.13', bmc_address_alt='172.31.10.13',
       domain_tag='SZ_GPU03', management_credential_id=3, bmc_credential_id=5
 WHERE id = 8 AND owner_scope = 'PLATFORM';
UPDATE `device` SET device_type='gpu', account='inspur', login_mode='SSH_PASSWORD',
       vendor='Inspur', management_ip_alt='172.31.20.104',
       bmc_address='10.202.54.14', bmc_address_alt='172.31.10.14',
       domain_tag='SZ_GPU04', management_credential_id=3, bmc_credential_id=5
 WHERE id = 9 AND owner_scope = 'PLATFORM';

-- ---- Storage-01（inspur 账号，业务 .105 / 带外 .15）----
UPDATE `device` SET device_type='storage', account='inspur', login_mode='SSH_PASSWORD',
       vendor='Inspur', management_ip_alt='172.31.20.105',
       bmc_address='10.202.54.15', bmc_address_alt='172.31.10.15',
       domain_tag='SZ_STORAGE01', management_credential_id=3, bmc_credential_id=5
 WHERE id = 10 AND owner_scope = 'PLATFORM';

-- ---- R750 Server 01~03（eht 账号，业务 .106~108 / 带外 .16~18）----
-- BMC 账号待确认（实测 eht/admin/root/calvin 全 401）：仍绑 lab-eht-bmc 占位登记，
-- 但 W-D-05 不对这三台发起 BMC 采集（FR-v4.12 决策 C1）。
UPDATE `device` SET device_type='server', account='eht', login_mode='SSH_PASSWORD',
       vendor='Dell', management_ip_alt='172.31.20.106',
       bmc_address='10.202.54.16', bmc_address_alt='172.31.10.16',
       domain_tag='SZ_SERVER01', management_credential_id=4, bmc_credential_id=6
 WHERE id = 11 AND owner_scope = 'PLATFORM';
UPDATE `device` SET device_type='server', account='eht', login_mode='SSH_PASSWORD',
       vendor='Dell', management_ip_alt='172.31.20.107',
       bmc_address='10.202.54.17', bmc_address_alt='172.31.10.17',
       domain_tag='SZ_SERVER02', management_credential_id=4, bmc_credential_id=6
 WHERE id = 12 AND owner_scope = 'PLATFORM';
UPDATE `device` SET device_type='server', account='eht', login_mode='SSH_PASSWORD',
       vendor='Dell', management_ip_alt='172.31.20.108',
       bmc_address='10.202.54.18', bmc_address_alt='172.31.10.18',
       domain_tag='SZ_SERVER03', management_credential_id=4, bmc_credential_id=6
 WHERE id = 13 AND owner_scope = 'PLATFORM';

-- ---- 4 台交换机（ehtadmin / 网络 CLI；地址即管理地址，不复制到 bmc_address）----
UPDATE `device` SET device_type='switch', account='ehtadmin', login_mode='SSH_CLI',
       vendor='centec', management_ip_alt=NULL, bmc_address=NULL, bmc_address_alt=NULL,
       domain_tag='SZ_SW800G', management_credential_id=7, bmc_credential_id=NULL
 WHERE id = 14 AND owner_scope = 'PLATFORM';
UPDATE `device` SET device_type='switch', account='ehtadmin', login_mode='SSH_CLI',
       vendor='centec', management_ip_alt=NULL, bmc_address=NULL, bmc_address_alt=NULL,
       domain_tag='SZ_SW200G', management_credential_id=7, bmc_credential_id=NULL
 WHERE id = 15 AND owner_scope = 'PLATFORM';
UPDATE `device` SET device_type='switch', account='ehtadmin', login_mode='SSH_CLI',
       vendor='Ruijie', management_ip_alt=NULL, bmc_address=NULL, bmc_address_alt=NULL,
       domain_tag='SZ_SW25G', management_credential_id=7, bmc_credential_id=NULL
 WHERE id = 16 AND owner_scope = 'PLATFORM';
UPDATE `device` SET device_type='switch', account='ehtadmin', login_mode='SSH_CLI',
       vendor='centec', management_ip_alt=NULL, bmc_address=NULL, bmc_address_alt=NULL,
       domain_tag='SZ_SWOOB', management_credential_id=7, bmc_credential_id=NULL
 WHERE id = 17 AND owner_scope = 'PLATFORM';
