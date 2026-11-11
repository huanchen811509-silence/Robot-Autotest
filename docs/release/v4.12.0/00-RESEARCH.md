# v4.12.0 需求调研（① 调研）

> 版本：v4.12.0（草案）
> 日期：2026-11-11
> 调研人：学徒
> 状态：待大师审阅（② 评估报告见 `01-ASSESSMENT.md`）
> 标注约定：`[已核实]` = 有命令输出 / 代码位置 / 库查询为证；`[推断]` = 由已核实事实推出，未直接验证；`[假设]` = 无证据，需拍板。

---

## 1. 需求原文（2026-11-11 大师第五条需求）

| # | 原文（节选） | 关键词 |
|---|---|---|
| R1 | 参考设备清单帮我更新资产凭证，确保可以采集资产信息（另外凭证需要支持列举设备列表、手工录入） | 资产凭证 / 可采集 / 列举设备 / 手工录入 |
| R2 | 全局模式下「平台设置」二级菜单内容不对，内容要复刻原先项目模式下「平台设置」内容 | 平台设置 / 复刻 |
| R3 | 项目模式下「用户管理」提示「当前账号没有访问该页面的权限，或该项目不属于你」，需要给自己的账号一定权限，比如修改密码、定义主题、强调色 | 403 / 我的账户 / 改密 / 主题 / 强调色 |
| R4 | 顶部栏「全局 / 项目」切换按钮要「全局在左、项目在右」 | 顶栏 / 顺序 |
| R5 | 请对以上需求进行评估和完善 | 评估 / 完善 |

### 1.1 设备清单（大师提供，12 台）

| 设备名称 | 业务管理地址 | 映射假公网 IP | 业务管理账号 | BMC/带外地址 | 映射假公网 IP | BMC 账号 |
|---|---|---|---|---|---|---|
| GPU-01 | 172.31.20.101 | 10.202.54.101 | inspur | 172.31.10.11 | 10.202.54.11 | admin |
| GPU-02 | 172.31.20.102 | 10.202.54.102 | inspur | 172.31.10.12 | 10.202.54.12 | admin |
| GPU-03 | 172.31.20.103 | 10.202.54.103 | inspur | 172.31.10.13 | 10.202.54.13 | admin |
| GPU-04 | 172.31.20.104 | 10.202.54.104 | inspur | 172.31.10.14 | 10.202.54.14 | admin |
| Storage-01 | 172.31.20.105 | 10.202.54.105 | inspur | 172.31.10.15 | 10.202.54.15 | admin |
| R750-Server-01 | 172.31.20.106 | 10.202.54.106 | eht | 172.31.10.16 | 10.202.54.16 | eht |
| R750-Server-02 | 172.31.20.107 | 10.202.54.107 | eht | 172.31.10.17 | 10.202.54.17 | eht |
| R750-Server-03 | 172.31.20.108 | 10.202.54.108 | eht | 172.31.10.18 | 10.202.54.18 | eht |
| 32DE-RoCE-800-01 | — | — | — | 172.31.10.91 | 10.202.54.91 | ehtadmin |
| 32CD-RoCE-200-01 | — | — | — | 172.31.10.92 | 10.202.54.92 | ehtadmin |
| S6510-Leaf-25-01 | 172.31.20.100 | 10.202.54.200 | — | 172.31.10.93 | 10.202.54.93 | ehtadmin |
| 48T4X-OOB-01 | — | — | — | 172.31.10.100 | 10.202.54.100 | ehtadmin |

> 口令不入库、不入文档：现场口令由大师提供，实施时经 API 加密写入 `credential.cipher_blob`，脚本与本文件均不落明文（`scripts/secret-scan.sh` 会把明文判红）。

---

## 2. 术语与边界

| 术语 | 定义 | 落点 |
|---|---|---|
| 业务管理面 | 设备操作系统 / 网络设备 CLI 的管理地址与账号（SSH） | `device.management_ip` + 凭证（ssh） |
| BMC / 带外面 | 服务器管理口（IPMI / Redfish / 厂商 CLI） | `device.bmc_address` + 凭证（bmc） |
| 映射假公网 IP | NAT 映射后可路由的地址（10.202.54.x）；内网真地址（172.31.x）本机不可达 | 采集必须用映射地址 |
| 列举设备列表 | 选中一条凭证后，列出「用这条凭证的设备」并可直接发起采集 | 本期新增 |
| 手工录入 | 不走 Excel 导入，逐条新增设备 / 凭证 | 本期新增 |

**边界**：本期不实现「BMC 全量硬件清单采集」与「网络设备配置采集」，只做**连通性 + 身份核验 + 基础信息**（见 §4 与 ③ PRD 的分级）。

---

## 3. 事实清单 A：资产与凭证现状

### A1. 库内数据（`robot_dev`，2026-11-11 查询）

| 事实 | 值 | 来源 |
|---|---|---|
| 平台级设备（`owner_scope='PLATFORM'`） | 13 行，其中 12 行为本批清单（id 6~17），1 行为旧夹具 `leaf-switch-01`（id 3） | `SELECT id, project_id, owner_scope, name …` |
| 本项目私有设备 | id 1、2（`gpu-node-01/02`，project 1）、id 4/5（`demo-dut-local`） | 同上 |
| 平台级凭证 | **0 行**（`credential` 仅 2 行，均 `owner_scope='PROJECT'`、project 1：`node-ssh-root` / `node-bmc-admin`） | `SELECT … FROM credential` |
| 采集记录 | **0 行**（`host_inventory` 空表） | `SELECT COUNT(*) FROM host_inventory` |
| 导入批次 | 本批 12 台由 Excel 导入（`asset_source='excel'`，`external_id=10.202.54.x`） | `SELECT … asset_source` |

`[已核实]`

### A2. 12 行设备数据是脏的 —— 三处字段被多值挤在一列

以 `gpu01`（id 6）为例，实际入库值（`\n` 为真实换行）：

| 列 | 实际值 | 应为 |
|---|---|---|
| `bmc_address` | `172.31.10.11\n10.202.54.11\nadmin` | `10.202.54.11` |
| `account` | `inspur\n有 root/sudo\nSZ_GPU01` | `inspur`（业务面）/ `admin`（BMC 面） |
| `login_mode` | `SSH 密码认证\nTCP 22` | `ssh` |
| `management_ip` | `10.202.54.101` ✅ | 正确 |
| `device_type` | `AI计算节点` | 可用，但 `model` 为空（Redfish 可拿到 `NF5468M7`） |

`[已核实]`（`SELECT … FROM device WHERE id=6`）

**根因**：Excel 导入按「表头别名」取列（`AssetImportService.ALIASES`），源表的「BMC 地址 / 账号 / 登录方式」三列在解析时被合成了一个单元格的多行文本。`[推断]`（导入代码本身按单值截断到 64/128 字符，不会主动拼接多行，说明源文件侧就是合并单元格）

**影响**：采集按 `management_ip` 取地址因此能跑通，但 **BMC 面完全不可用**（地址列里带着账号和换行），且「设备详情」页会把多行文本原样显示。

### A3. 代码侧已有能力

| 能力 | 位置 | 现状 |
|---|---|---|
| 凭证 CRUD | `api/asset/CredentialController` `/api/projects/{id}/credentials` | 有；**只按项目**，无平台级入口 |
| 凭证加密 | `CredentialService` + `SecretCipher` | 有；列表永不回显明文/密文 |
| 设备 CRUD | `api/asset/DeviceController`（项目级） | 有；**平台级无手工录入端点** |
| 平台资产读/导入/采集 | `api/asset/GlobalAssetController` `/api/global/assets` | 有：list / import/preview / import/commit / `{id}/collect` / reference / delete / export |
| 主机清单采集 | `api/host/HostInventoryController` `/api/asset-host-inventory` | 有：collect（需 credentialId + confirm）、commands 白名单 |
| 凭证 ↔ 设备关联 | — | **不存在**：`device` 表无 `credential_id`，无中间表 |

`[已核实]`（`grep -rn "credential_id" db/migration/` 结果为空）

### A4. `device` 表已有列（22 列）

`id / project_id / owner_scope / name / device_type / management_ip / status / model / vendor / serial_no / location / rack_no / domain_tag / owner_id / last_checked_at / asset_status / asset_source / external_id / environment / operating_system / kernel_version / bmc_address / login_mode / account`

**缺**：`management_ip_alt`（内网真地址）、`bmc_address_alt`、`management_credential_id`、`bmc_credential_id`。`[已核实]`（`SHOW COLUMNS FROM device`）

---

## 4. 事实清单 B：现场连通性与凭据实测（本轮最关键）

**本机网络**：`eth9 = 10.202.16.5/24`（默认网关 10.202.16.1）；`eth8 = 100.113.225.35/32`。`[已核实]`（`ip -4 -o addr`）

| 网段 | 结果 | 结论 |
|---|---|---|
| `10.202.54.x:22`（映射假公网） | **OPEN**（.11 / .91 / .100 / .101 实测） | 可采集 ✅ |
| `172.31.20.x:22` / `172.31.10.x:22`（内网真地址） | CLOSED / timeout | **不可达**，不能作为采集地址 |
| ICMP `10.202.54.101` | OK | 路由通 |

`[已核实]` 采集必须走 `10.202.54.x`，与库内 `management_ip` 现值一致。

### B1. 业务管理面（Linux SSH）—— 8 台全部通过

| 地址 | 账号 | 结果 | 主机名 / 内核 |
|---|---|---|---|
| 10.202.54.101 | inspur | **PASS** | gpu01 / 6.17.0-23-generic |
| 10.202.54.102 | inspur | **PASS** | gpu02 |
| 10.202.54.103 | inspur | **PASS** | gpu03 |
| 10.202.54.104 | inspur | **PASS** | gpu04 |
| 10.202.54.105 | inspur | **PASS** | storage01 |
| 10.202.54.106 | eht | **PASS** | p1 / 5.15.0-119-generic |
| 10.202.54.107 | eht | **PASS** | 5468 |
| 10.202.54.108 | eht | **PASS** | p3 |

`[已核实]`（`sshpass -e ssh … 'echo "OK $(hostname) …"'`，口令经环境变量传入，未落盘）

→ **现有 `HostInventoryCollector` 的 Linux 命令集可直接用于这 8 台。**

### B2. BMC / 带外面 —— 分三种，不能一概而论

| 设备 | 地址 | 账号 | SSH | Redfish `/redfish/v1/Systems` | IPMI 623 | 结论 |
|---|---|---|---|---|---|---|
| GPU-01~04 | 10.202.54.11~14 | admin | 通（落到 BMC CLI：`bmclog/chassis/mc/diagnose`） | **200**（models：NF5468M7 ×4） | 通（`chassis status` 返回 `System Power: on`） | **可采集**（Redfish） |
| Storage-01 | 10.202.54.15 | admin | — | **200**（NF5280-M7-A0-R0-00） | — | **可采集**（Redfish） |
| R750-Server-01~03 | 10.202.54.16~18 | eht（大师清单） | **401 / Permission denied** | **401** | .16 不通 | **账号不符，待确认** |
| R750 复验 | 10.202.54.16 | admin、root/calvin | 均 denied | 均 401 | — | 用户给的两个候选都不行 |
| 32DE-RoCE-800-01 | 10.202.54.91 | ehtadmin | **通** | — | — | **可采集**（CLI：centecOS E780 7.0.5.70） |
| 32CD-RoCE-200-01 | 10.202.54.92 | ehtadmin | **通** | — | — | **可采集**（centecOS E780 7.5.4） |
| S6510-Leaf-25-01 | 10.202.54.93 | ehtadmin | **通**（需加老 KEX） | — | — | **可采集**（Ruijie 25G，`show version` 有输出） |
| 48T4X-OOB-01 | 10.202.54.100 | ehtadmin | **通** | — | — | **可采集**（centecOS E530 7.5.4） |

`[已核实]`（Redfish：`curl -sk -u …`；交换机：`ssh -o KexAlgorithms=+diffie-hellman-group14-sha1 … 'show version'`）

**三个必须写进设计的坑**：

1. **交换机只支持老 KEX**：现代 OpenSSH 默认拒绝 `diffie-hellman-group14-sha1` 与 `ssh-rsa`，不加参数会直接 `no matching key exchange method found`（10.202.54.93 首次探测即此报错）。参数必须能按凭证配置，不能全局写死。`[已核实]`
2. **网络设备不是 Linux**：`$(hostname)` 被原样回显，`uname` 不存在。现有采集器的 10 类命令（`uname/-kernel/lscpu/…`）在交换机上会全部失败。`[已核实]`
3. **BMC 不是 Linux**：SSH 登录后是厂商 CLI（`Built-in command: bmclog/chassis/mc/diagnose`），不是 bash。硬件信息只能走 Redfish 或 IPMI。`[已核实]`

### B3. 采集器现状与三类通道的匹配度

`HostInventoryCollector.COMMANDS` 是一套 Linux 命令白名单（`uname`、`/etc/os-release`、`lscpu`、`ip link`、`ethtool` 等）。`[已核实]`（`api/host/HostInventoryController#commands`）

| 通道 | 台数 | 现有采集器可否直接用 |
|---|---|---|
| Linux SSH | 8 | **可以** |
| 网络设备 CLI | 4 | **不可以**（命令集与解析全不同） |
| BMC / Redfish | 8（5 可用 + 3 待账号） | **不可以**（协议不同） |

---

## 5. 事实清单 C：全局「平台设置」

| 项 | 事实 | 来源 |
|---|---|---|
| 全局侧栏「平台设置」组 | 6 个叶子：平台设置 `/g/settings`、AI 助手、用户管理、审计日志、关于、版本迭代 | `navigation.ts:82`（`globalSettingsChildren` ← `SETTINGS_NAV`） |
| 点开 `/g/settings` 看到什么 | `GlobalSettingsView.vue`：**只有一个 6 项链接列表**（`<RouterLink>` 列表），70 行，无任何配置控件 | `views/global/GlobalSettingsView.vue` |
| 原项目模式「平台设置」是什么 | `SettingsView.vue`（201 行）：**平台级 4 项配置**（最大并发数 / 默认超时 / 报告保留天数 / AI 开关）+ 入口端口只读 + **项目级 3 项**（默认内核 / 项目超时 / 标签集），带保存与审计 | `views/SettingsView.vue` |
| 重复问题 | 侧栏已列 6 项，页面里又列同样 6 项 —— 同一份导航渲染两次 | 同上 |

`[已核实]`

**结论**：R2 的「内容不对」是**页面内容被换成了导航索引**，配置表单没了。修复方向是让 `/g/settings` 渲染平台配置表单（全局语义下只保留平台级 4 项 + 端口，隐藏项目级 3 项），而不是再列一遍菜单。

---

## 6. 事实清单 D：项目「用户管理」为什么会 403

| 环节 | 事实 | 来源 |
|---|---|---|
| 路由 | `/settings/users` → `SettingsUsersView`，`meta.capability = 'public'` | `router/index.ts:237-241` |
| 守卫判据 | `const owned = capabilitiesForRole(roleFromCodes(auth.user.roles)); if (!owned.includes(capability)) return { path: '/403' }` | `router/index.ts:472-473` |
| 角色能力集 | `capabilitiesForRole` 返回的数组**不含 `'public'`**（admin 8 项、pm 5 项、engineer 3 项、viewer 2 项，均无 public） | `config/navigation.ts:586-599` |
| 导航过滤 | 另一套口径 `filterNavByCapabilityPredicate` **有** `if (node.capability === 'public') return true` 的特判 | `config/navigation.ts:612` |

`[已核实]`

**根因**：导向过滤对 `public` 有特判，路由守卫没有 —— 两处口径不一致。v4.11.0 新增 `/settings/users` 时标了 `capability: 'public'`，**任何角色进这一页都会被踢到 `/403`**（编辑本次实测页面提示即 `forbidden.hint`）。这是 v4.11.0 引入的回归缺陷，不是权限配置问题。

**R3 的后半句**（修改密码 / 主题 / 强调色）：

| 能力 | 现状 |
|---|---|
| 修改自己的密码 | **无端点**。只有 `POST /api/users/{id}/reset-password`（管理员重置他人，无需旧密码） | 
| 主题 | 已有 `useTheme`（system/light/dark）+ 顶栏循环按钮 + `user_preference` 服务端持久化 | 
| 强调色 | 已有 `useAccent`（6 预设：crimson/blue/gold/orange/violet/jade）+ 已生成 12 套变体 + `AppearanceControls` / `AppearanceDialog` 组件 | 

`[已核实]` → 主题与强调色**能力已存在**，只是入口藏在账户菜单里，用户管理页没有暴露；改密需要新增端点。

---

## 7. 事实清单 E：顶部栏「全局 / 项目」

| 项 | 事实 | 来源 |
|---|---|---|
| DOM 顺序 | 段 0 内先 `<button>` 项目，后 `<button>` 全局（`@click="switchMode('project')"` 在前） | `components/AppHeader.vue:78-95` |
| 标签 | `t('mode.project')` / `t('mode.global')` | 同上 |
| 未登录/无权限 | `v-if="canGlobal"` 整组不渲染 | 同上 |

`[已核实]` → 只改渲染顺序（项目 ↔ 全局对调），逻辑不动。

---

## 8. 需求与事实的缺口对照

| 需求 | 现状 | 缺口 | 类型 |
|---|---|---|---|
| R1 更新资产凭证 | 12 台已入库但 3 列脏；平台凭证 0 条 | 需清脏 + 建 5 条平台凭证 + 绑定 | 数据治理 + 建模 |
| R1 确保可采集 | 8 台 Linux 可采；4 台交换机命令集不匹配；5 台 BMC 需 Redfish；3 台 BMC 账号不对 | 采集器需按通道分派 | 新增能力 |
| R1 凭证列举设备 | 无关联关系 | 需 `device` 加凭证列 + 反查端点 + UI 面板 | 新增能力 |
| R1 手工录入 | 仅 Excel 导入 | 需平台级 POST 端点 + 表单 | 新增能力 |
| R2 平台设置内容 | 页面只剩导航索引 | 复刻配置表单（平台级 4 项 + 端口） | 修复 |
| R3 用户管理 403 | `public` 能力在守卫里被误判 | 守卫加 public 特判（与导航口径统一） | **缺陷修复** |
| R3 改密 / 主题 / 强调色 | 主题、强调色已有；改密无端点 | 新增改密端点 + 页面嵌 `AppearanceControls` | 新增 + 复用 |
| R4 顶栏顺序 | 项目在前 | 对调 DOM 顺序 | 修复 |

---

## 9. 未确认事项（`[假设]`，需大师拍板）

| # | 假设 | 影响 | 若不成立 |
|---|---|---|---|
| H1 | R750-Server-01~03 的 BMC 账号不是清单里的 `eht`（实测 SSH/Redfish 均 401，admin 与 root/calvin 也不行） | 3 台 BMC 无法采集 | 需大师提供真实 BMC 账号，或本期标为「仅登记、不采集」 |
| H2 | 交换机 4 台按「只采 `show version` / `show interface brief` 等基础命令」即可，不做配置比对 | 采集器只加一个「网络设备」通道 | 若要做配置采集，工作量翻倍 |
| H3 | 「列举设备列表」= 选一条凭证 → 列出其关联设备（可多选发起采集），不需要「扫描网段自动发现」 | 新增 1 个反查端点 + 1 个面板 | 若要网段扫描，需独立的扫描任务框架 |
| H4 | 12 台设备归属「平台资产」（`owner_scope='PLATFORM'`）而非某个项目 | 与现状一致（已在 PLATFORM） | 若要挂项目，需引用关系 |
| H5 | 本期不做「BMC 硬件清单全量采集」（CPU/内存/磁盘/风扇/温度） | 只做连通性 + 型号 | 工作量与 H2 类似，另加 Redfish 解析层 |

---

## 10. 修订记录

| 版本 | 日期 | 修改人 | 说明 |
|---|---|---|---|
| v0.1 | 2026-11-11 | 学徒 | 首版：四条需求 + 12 台设备实测（连通性 / SSH / Redfish / CLI）+ 两处缺陷根因 |
