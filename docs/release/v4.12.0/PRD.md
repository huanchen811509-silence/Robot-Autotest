# v4.12.0 PRD（③ 需求文档）

> 版本：v4.12.0（草案）
> 日期：2026-11-11
> 上游：`00-RESEARCH.md`（事实）、`01-ASSESSMENT.md`（判断与取舍）
> 范围：R1 资产凭证与采集、R2 全局平台设置、R3 我的账户、R4 顶栏顺序
> 判据原则：**每条 FR 必须能被一条命令或一次点击判定**，写不出判据的不进本文。

---

## 1. 版本目标

| # | 目标 | 判定 |
|---|---|---|
| G1 | 12 台现场设备的资产与凭证数据可用，且**8 台 Linux 主机都真采出清单** | 采集记录 ≥17 条（8 Linux 管理面 + 4 交换机 + 5 BMC）；8 台 Linux 各产出 ≥9 类清单，其中 6 台 `status=OK`、2 台 `status=PARTIAL`（机器本身无 NVIDIA 驱动，仅 GPU 一类不适用）。判据于 v0.2 修订，理由见 §8 |
| G2 | 凭证能反查设备并批量采集 | 选一条凭证 → 列出 ≥5 台设备 → 批量采集返回 `{total, ok, failed}` |
| G3 | 平台资产支持手工录入，不再只能靠 Excel | 页面表单提交后列表出现该行 |
| G4 | 全局「平台设置」能改平台参数 | `/g/settings` 出现 4 个可编辑字段 + 保存成功提示 |
| G5 | 项目「用户管理」全角色可达，且能改密与换主题/强调色 | 工程师账号进得去；改密成功；强调色切换后 `data-accent` 变化 |
| G6 | 顶栏「全局」在左、「项目」在右 | 取 DOM 首个子按钮文本 = 全局 |

---

## 2. 名词

| 名词 | 含义 |
|---|---|
| 平面（plane） | 设备的访问面：`mgmt`（业务管理面）或 `bmc`（带外管理面） |
| 采集通道 | 由凭证类型决定的协议族：`ssh`（Linux）、`network`（网络设备 CLI）、`bmc`（Redfish） |
| 连接参数 | 追加到 SSH 客户端的选项串（如老 KEX 兼容），按凭证配置 |
| 关联设备 | 某条凭证被绑定为「业务面凭证」或「BMC 凭证」的设备集合 |

---

## 3. 角色与权限

| 操作 | ADMIN | PM | ENGINEER | VIEWER |
|---|---|---|---|---|
| 查看平台资产 / 凭证列表 | ✅ | ✅ | ✅ | ✅ |
| 手工录入平台设备 / 凭证 | ✅ | ❌ | ❌ | ❌ |
| 修改设备与凭证绑定 | ✅ | ❌ | ❌ | ❌ |
| 发起采集（单台 / 按凭证批量） | ✅ | ✅ | ✅ | ❌ |
| 查看采集结果 | ✅ | ✅ | ✅ | ✅ |
| 修改自己的密码 | ✅ | ✅ | ✅ | ✅ |
| 修改自己的主题 / 强调色 | ✅ | ✅ | ✅ | ✅ |
| 修改平台设置 | ✅ | ❌ | ❌ | ❌ |

> 「发起采集」沿用既有口径：需要 `ASSET/UPDATE`（或平台采集的 `requireWrite`），并带 `confirm=true`。

---

## 4. FR 清单

### A. 资产建模（凭证 ↔ 设备）

#### FR-v4.12-01 设备支持双地址

- 每台设备最多存两组地址：`management_ip`（采集地址，必填一条才可采集）+ `management_ip_alt`（内网真地址，可空）；`bmc_address` + `bmc_address_alt` 同理。
- **判据**：`SHOW COLUMNS FROM device` 含 `management_ip_alt`、`bmc_address_alt`；新录入一台设备时两个 alt 字段可留空且不报错。

#### FR-v4.12-02 设备绑定业务面凭证

- `device.management_credential_id` 可空外键 → `credential.id`；为空表示「未绑定，不可采集」。
- **判据**：绑定后 `GET /api/global/assets/credentials/{id}/devices` 能返回该设备且 `plane=mgmt`；解绑后不再返回。

#### FR-v4.12-03 设备绑定 BMC 凭证

- `device.bmc_credential_id` 可空外键；语义同上，`plane=bmc`。
- **判据**：同上，`plane=bmc` 分支。

#### FR-v4.12-04 凭证支持连接参数

- `credential.connect_options VARCHAR(256)` 可空；内容原样透传给 SSH 客户端（如 `KexAlgorithms=+diffie-hellman-group14-sha1,HostKeyAlgorithms=+ssh-rsa`）。
- **判据**：对 `10.202.54.93`（只支持老 KEX）配置该串后采集成功；清空后同一台采集失败且原因为「算法不兼容」。

### B. 采集通道

#### FR-v4.12-05 按凭证类型分派采集器

| 凭据类型 | 采集器 | 命令/接口 |
|---|---|---|
| `ssh` | Linux 采集器（既有） | 既有 10 类命令白名单 |
| `network`（新增类型） | 网络设备采集器 | `show version`、`show interface brief` |
| `bmc` | BMC 采集器 | `GET /redfish/v1/Systems/1` |

- **判据**：三台设备（各一类型）采集后 `host_inventory` 各产生一条记录，`spec_json.items` 的分类名分别为 Linux / network / bmc 三套。

#### FR-v4.12-06 新增 `network` 凭据类型

- `cred_type` 合法值扩展为 `ssh | network | bmc | snmp | token`。
- **判据**：`POST /api/global/assets/credentials` 传 `credType=network` 返回 200；传 `credType=foo` 返回 400。

#### FR-v4.12-07 网络设备采集器

- 只跑两条只读命令，产物落 `spec_json`：型号/版本/端口总数/端口 up 数。
- 命令失败时 `status=PARTIAL` 并给出 `reason`，不抛 500。
- **判据**：对 `centecOS E780`（10.202.54.91）与 `Ruijie`（10.202.54.93）各采一次，`items` 中含型号与版本，`status∈{OK,PARTIAL}`。

#### FR-v4.12-08 BMC Redfish 采集器

- 只读 `GET /redfish/v1/Systems/1`（Basic Auth），取 `Manufacturer/Model/SerialNumber/PowerState/Status.Health`。
- 端点版本差异（实测 v1_5_2 与 v1_17_0）不得导致解析崩溃；缺字段记为 `null` 并标 `PARTIAL`。
- **判据**：对 5 台 inspur BMC 采集，`items` 含 `Model=NF5468M7`（GPU×4）与 `NF5280-M7-A0-R0-00`（Storage）。

#### FR-v4.12-09 失败原因分类

- `FAILED` 的 `reason` 必须落在四类之一：`unreachable` / `auth_failed` / `algorithm_mismatch` / `command_unsupported`，随附原始错误首行（口令字面不得出现）。
- **判据**：R750 BMC（账号错）→ `auth_failed`；`10.202.54.93` 去 KEX 参数 → `algorithm_mismatch`；`172.31.20.101`（无路由）→ `unreachable`。

#### FR-v4.12-10 采集按钮按通道显隐

- 设备未绑定凭证、或绑定的凭证类型不被支持时，列表的「采集」按钮不渲染（或禁用并给出原因），不允许「点了才失败」。
- **判据**：纯 BMC 交换机（未绑定 mgmt 凭证）在设备列表里没有可点的「采集」按钮。

### C. 列举与手工录入

#### FR-v4.12-11 凭证列举关联设备

- `GET /api/global/assets/credentials/{id}/devices` 返回 `[{deviceId, name, plane, address, lastStatus, lastCollectedAt}]`。
- **判据**：`inspur` 凭证（绑定 5 台）返回 5 条，`plane` 全为 `mgmt`。

#### FR-v4.12-12 按凭证批量采集

- `POST /api/global/assets/credentials/{id}/collect`，请求体含 `confirm=true`；逐台独立执行，**单台失败不中断**，返回 `{total, ok, failed, items[]}`。
- **判据**：5 台全通过时 `total=5, ok=5, failed=0`；人为把其中一台凭证改错 → `ok=4, failed=1`，且 4 条记录仍落库。

#### FR-v4.12-13 手工录入设备

- `POST /api/global/assets/devices`：`name`（必填、平台内唯一）、`deviceType`、`managementIp`、`managementIpAlt`、`bmcAddress`、`bmcAddressAlt`、`managementCredentialId`、`bmcCredentialId`、`model`、`vendor`、`serialNo`、`location`、`domainTag`。
- 至少一个地址非空，否则 400。
- **判据**：提交后 `SELECT * FROM device WHERE name=?` 有该行且 `owner_scope='PLATFORM'`；同名再提交返回 409。

#### FR-v4.12-14 手工录入凭证

- `POST /api/global/assets/credentials`：`name`、`credType`、`username`、`secret`、`connectOptions`；`secret` 加密入库，响应**不含** secret 与密文。
- **判据**：响应 JSON 里 grep 不到明文与 `cipher`；`SELECT cipher_blob IS NOT NULL` 为 1。

#### FR-v4.12-15 资产页「设备」面板

- 凭证页签每行有「关联设备」按钮 → 弹出面板列出设备（含 plane 与最近采集状态），面板内可「全部采集」。
- **判据**：点开 `inspur` 凭证 → 面板 5 行 → 点「全部采集」→ 结果摘要显示 5/5。

### D. 数据治理

#### FR-v4.12-16 修正 12 台设备脏字段

- `bmc_address` / `account` / `login_mode` 三列不得再含换行或多值；`bmc_address` 只存采集地址。
- `device_type` 规范为 `gpu | server | storage | switch`。
- **判据**：`SELECT COUNT(*) FROM device WHERE bmc_address LIKE '%\n%' OR account LIKE '%\n%' OR login_mode LIKE '%\n%'` = 0。

#### FR-v4.12-17 建立 5 条平台凭证并绑定

| 凭证名 | 类型 | 账号 | 绑定设备 |
|---|---|---|---|
| `lab-inspur-ssh` | ssh | inspur | GPU-01~04、Storage-01 的 mgmt |
| `lab-eht-ssh` | ssh | eht | R750-Server-01~03 的 mgmt |
| `lab-inspur-bmc` | bmc | admin | GPU-01~04、Storage-01 的 bmc |
| `lab-eht-bmc` | bmc | eht | R750-Server-01~03 的 bmc（**账号待确认，先登记不采集**） |
| `lab-netadmin-ssh` | network | ehtadmin | 4 台交换机的 bmc 面 |

- **判据**：`SELECT COUNT(*) FROM credential WHERE owner_scope='PLATFORM'` = 5；每台设备的 `management_credential_id` / `bmc_credential_id` 非空。

### E. 全局「平台设置」

#### FR-v4.12-18 `/g/settings` 渲染平台配置表单

- 页面包含 4 个可编辑项（`execution.maxParallel`、`execution.defaultTimeoutSeconds`、`report.retentionDays`、`ai.enabled`）+ 入口端口只读展示 + 保存按钮。
- **判据**：页面存在 4 个可编辑控件；改值保存后 `GET /api/settings/platform` 返回新值。

#### FR-v4.12-19 全局语义下隐藏项目级区块

- 全局模式下不渲染「默认内核 / 项目超时 / 标签集」三项。
- **判据**：`/g/settings` 源码/渲染结果不含 `kernel.default` 相关控件；`/settings`（项目模式）仍有 3 项。

#### FR-v4.12-20 删除与侧栏重复的导航索引页

- `GlobalSettingsView.vue` 不再作为 `/g/settings` 的组件；若删除文件，`check_navigation.py` 的派生组断言同步。
- **判据**：`grep -r "GlobalSettingsView" frontend/src` 结果为 0（或仅剩历史注释）。

### F. 我的账户

#### FR-v4.12-21 能力判据单一真源

- `config/navigation.ts` 导出 `capabilitySatisfied(capability, role)`；路由守卫与 `filterNavByCapabilityPredicate` 均调用它。
- **判据**：`public` 在四种角色下均返回 true；`user:write` 仅 admin 返回 true；两个调用点都指向同一函数（`grep` 计数 ≥2 且无第二份 `includes(capability)` 实现）。

#### FR-v4.12-22 `/settings/users` 全角色可达

- ADMIN / PM / ENGINEER / VIEWER 进入该页均不跳 `/403`。
- **判据**：工程师账号点击「用户管理」后 URL 仍为 `/settings/users`，页面出现「当前用户」区块。

#### FR-v4.12-23 修改自己的密码

- `POST /api/me/password`，请求体 `{oldPassword, newPassword}`；旧密码错 → 400 且不落任何口令；成功 → 200 并写审计 `user.password.self-change`（detail 不含口令）。
- 新密码策略与 `reset-password` 同一校验器。
- **判据**：错旧密码返回 400；正确后能用新密码登录；审计表 detail 中 grep 不到新旧口令。

#### FR-v4.12-24 页面内切换主题与强调色

- 页面嵌入 `AppearanceControls`（主题三态 + 强调色六预设），切换即时生效并持久化到 `user_preference`。
- **判据**：切到 `violet` 后 `<html data-accent="violet">`；刷新后仍为 `violet`。

### G. 顶栏

#### FR-v4.12-25 模式切换顺序「全局在左、项目在右」

- `AppHeader` 段 0 内第一个按钮为「全局」，第二个为「项目」。
- **判据**：`[data-seg="mode"] button` 的第一个 `textContent` 等于 `t('mode.global')`；点击后 `uiMode.mode` 正确切换。

---

## 5. 非功能要求

| 项 | 要求 |
|---|---|
| 口令安全 | 明文只存在于「写入」与「执行瞬间」；列表/详情/审计/日志一律不出现；仓库中不出现任何真实口令 |
| 审计 | 凭据创建/更新/删除、采集（含批量）、改密均落审计；detail 不含口令片段 |
| 幂等 | 手工录入同名设备/凭证返回 409，不静默覆盖 |
| 降级 | 采集器遇未知字段/端点版本差异 → `PARTIAL`，不抛 500 |
| 兼容 | 无破坏性变更：既有项目级凭据 CRUD、单台采集接口签名不变 |
| 迁移 | 只增列，不改既有列类型；已应用迁移不可修改 |

---

## 6. 待确认（需大师拍板）

| # | 事项 | 默认处置（若不定） |
|---|---|---|
| C1 | R750-Server-01~03 的真实 BMC 账号（实测 `eht`/`admin`/`root` 均 401） | 先登记 `lab-eht-bmc` 凭证但**不发起采集**，页面标「待确认」 |
| C2 | 交换机是否只采基础信息（不上配置备份） | 只采 `show version` + `show interface brief` |
| C3 | 「列举设备列表」是否需要网段扫描自动发现 | 不做扫描，只做「凭证 → 关联设备」反查 |
| C4 | 12 台设备是否都挂平台资产（`owner_scope='PLATFORM'`） | 维持现状（已是 PLATFORM） |
| C5 | 版本号 v4.12.0 | 采用 |

---

## 7. 验收判据汇总

| 组 | 判据条数 | 关键判据 |
|---|---|---|
| A 建模 | 4 | 两列 alt 存在；两列 FK 存在；反查返回正确 plane |
| B 采集 | 6 | 8 台 Linux 采出清单；4 台交换机采出型号；5 台 BMC 采出 Model；三类失败原因可区分 |
| C 列举/录入 | 5 | 反查 5 条；批量 5/5；同名 409；响应无明文 |
| D 治理 | 2 | 换行脏值计数 = 0；平台凭证 = 5 |
| E 平台设置 | 3 | 4 个可编辑项；项目级隐藏；索引页移除 |
| F 我的账户 | 4 | 四角色可达；改密成功；`data-accent` 生效 |
| G 顶栏 | 1 | 首个按钮=全局 |

---

## 8. 修订记录

| 版本 | 日期 | 修改人 | 说明 |
|---|---|---|---|
| v0.1 | 2026-11-11 | 学徒 | 首版：25 条 FR，覆盖四条需求；含 5 条待确认 |
| v0.2 | 2026-11-11 | 学徒 | W-D-05 真机实测回写：G1 判据由「≥8 条 `status=OK`」改为「≥17 条采集记录 + 8 台各 ≥9 类 + 6 条 OK」。原判据把「机器没装 GPU」当成「采集失败」，8 台里 2 台（server03 R750 无 NVIDIA 驱动、storage01 存储机无 GPU）按字面永远达不成；口径由大师于 2026-11-11 拍板「保持 PARTIAL、改判据」，语义不动，不引入「不适用」新状态 |
