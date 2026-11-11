# v4.12.0 评估报告（② 评估）

> 版本：v4.12.0（草案）
> 日期：2026-11-11
> 前置：`00-RESEARCH.md`（事实清单 A~E，全部标 `[已核实]`）
> 结论：**四条需求全部可做**；其中 R3 含一处 v4.11.0 回归缺陷必须修；R1 需按「三类采集通道」分级交付，BMC 三台受账号阻塞。

---

## 1. 结论摘要

| 需求 | 判断 | 交付形态 | 阻塞 |
|---|---|---|---|
| R1 资产凭证更新 + 可采集 + 列举设备 + 手工录入 | **做**（分级） | 1 迁移 + 2 端点 + 1 面板 + 数据治理脚本 | BMC ×3 账号不符（H1） |
| R2 全局「平台设置」复刻配置内容 | **做** | 复用 `SettingsView`，按模式裁区块 | 无 |
| R3a 用户管理 403 | **必须修（缺陷）** | 守卫与导航口径统一，3 行级改动 | 无 |
| R3b 改密 / 主题 / 强调色 | **做** | 1 端点 + 嵌已有 `AppearanceControls` | 无 |
| R4 顶栏「全局在左、项目在右」 | **做** | DOM 顺序对调 | 无 |

---

## 2. R1：凭据—设备建模（三种方案对比）

需求的关键不是「存一个密码」，而是**一条凭证要能反查它服务哪些设备，并能先列举再采集**。

| 方案 | 做法 | 优点 | 缺点 | 判断 |
|---|---|---|---|---|
| **A. `device` 加平面列** | `management_credential_id`、`bmc_credential_id` 两个可空 FK，外加 `management_ip_alt`、`bmc_address_alt` | 迁移 4 列、零破坏；反查一条 SQL；与「一平面一默认凭证」的真实用法一致 | 一个平面只能绑一条凭证（多账号需先换绑） | **采纳** |
| B. 中间表 `device_credential` | `(device_id, credential_id, plane, is_primary)` | 支持一平面多凭证、可记历史 | 反查要 join + 去重，UI 要处理「哪个是默认」；本期无多凭证需求 | 不采纳 |
| C. `credential_target` 表 | 凭证直接挂地址列表，设备不参与 | 与设备表解耦 | 与 `device` 表职责重叠，设备侧仍要存地址 → 两份真源 | 不采纳 |

**采纳 A 的额外理由**：`device` 表已有 `account`、`login_mode`、`bmc_address` 三列（A2 的脏数据就是往这三列里塞的），说明「平面」概念在当前模型里已经存在，只是没建成关系。补两个 FK 是把既有形状补完，不是新造概念。

### 2.1 脏数据处理

12 行设备的 `bmc_address` / `account` / `login_mode` 三列被多值污染（详见 `00-RESEARCH.md` A2）。处理方式：

| 步骤 | 动作 | 可回滚 |
|---|---|---|
| 1 | 导出 12 行 `SELECT *` 到 `docs/evidence/v4.12.0/raw/device-before.json` | 是（原始快照） |
| 2 | 按设备名对齐大师清单，逐行重写三列 + `management_ip_alt` / `bmc_address_alt` | 是（步骤 1 可还原） |
| 3 | `device_type` 规范化：`AI计算节点`→`gpu`、`存储节点`→`storage`、`通算节点`→`server`、`交换机`→`switch` | 是 |
| 4 | `model` 用 Redfish 实测值回填（NF5468M7 ×4、NF5280-M7 ×1） | 是 |

**不做**：不删旧夹具（id 1~5），不动 `demo-dut-local`。

### 2.2 采集通道分级（本轮最重要的产品决策）

实测证明三类设备说三种语言（`00-RESEARCH.md` §4）：

| 通道 | 识别方式 | 台数 | 采集内容 | 实现 |
|---|---|---|---|---|
| **Linux SSH** | `cred_type='ssh'` | 8 | 现有 10 类清单（OS/内核/CPU/内存/网卡/RoCE…） | 复用 `HostInventoryCollector`，**代码零改动** |
| **网络设备 CLI** | `cred_type='network'`（新增） | 4 | `show version` + `show interface brief`：型号/版本/端口数与状态 | 新采集器，固定命令白名单，产物落到同一 `spec_json` 结构 |
| **BMC / Redfish** | `cred_type='bmc'` | 8（5 可用 + 3 待账号） | `/redfish/v1/Systems/1`：厂商/型号/序列号/电源状态/健康 | 新采集器，只读 GET |

三点必须写进实现约束：

1. **交换机的 KEX 兼容参数不能全局写死**：`10.202.54.93` 只支持 `diffie-hellman-group14-sha1` 与 `ssh-rsa`，其余三台交换机不需要。若写死在代码里，未来遇到只支持新算法的设备会反向失败。→ `credential` 加 `connect_options VARCHAR(256)`（可空，形如 `KexAlgorithms=+diffie-hellman-group14-sha1,HostKeyAlgorithms=+ssh-rsa`）。
2. **失败必须分类可见**：`FAILED` 的 `items[].reason` 要区分「网络不可达 / 认证失败 / 算法不兼容 / 命令不存在」。把「算法不兼容」报成「认证失败」会把人引到改密码的错误方向（本次实测第一轮就差点这么判）。
3. **不新增采集器就当失败处理**：4 台交换机 + 8 台 BMC 如果在 UI 上仍显示「采集」按钮，点了必然失败。按钮要按 `cred_type` 决定显隐，避免「点了才知道不支持」。

---

## 3. R1：列举设备与手工录入

| 能力 | 端点 | 说明 |
|---|---|---|
| 列举凭证关联设备 | `GET /api/global/assets/credentials/{id}/devices` | 返回设备 + 平面（mgmt/bmc）+ 最近采集状态；`{id}` 用 `\d+` 正则，避免与 `/{type}/{id}/references` 抢路由 |
| 手工录入设备 | `POST /api/global/assets/devices` | 必填 `name` + 至少一个地址；平台级 `owner_scope='PLATFORM'` |
| 手工录入凭证 | `POST /api/global/assets/credentials` | `name/credType/username/secret`，`secret` 走 `SecretCipher` 加密；**不回显** |
| 批量采集（按凭证） | `POST /api/global/assets/credentials/{id}/collect` | 遍历该凭证关联设备，逐台采，**逐台落审计与记录**；单台失败不中断整批 |

**为什么批量采集必须逐台独立事务**：一台 R750 密码错不该让其余 7 台的结果一起丢。批量结果的返回结构是 `{total, ok, failed, items[]}`，前端据此显示「8 台成功 / 3 台认证失败」。

**手工录入的边界**：不提供「批量粘贴多行」的解析（那是 Excel 导入的职责，已有）；只做单条表单。

---

## 4. R2：全局「平台设置」页

| 方案 | 做法 | 判断 |
|---|---|---|
| A. 复用 `SettingsView`，按模式裁区块 | `route.meta.global` 为真时只渲染「平台级 4 项 + 入口端口只读」，隐藏「项目级 3 项」 | **采纳** |
| B. 新建 `GlobalPlatformSettingsView` | 复制一份表单 | 不采纳：两份表单必然漂移（本项目已经吃过「全局 6 项 vs 项目 5 项」的亏） |

**修改点**：
1. `/g/settings` 路由组件从 `GlobalSettingsView` 换成 `SettingsView`；
2. `SettingsView` 增加全局语义：标题用 `global.settingsTitle`、隐藏项目级区块、保存按钮走同一 `putPlatform`；
3. `GlobalSettingsView.vue` 保留但不再被 `/g/settings` 使用 —— 直接删除（它的 6 项链接与侧栏重复），并在 `check_navigation.py` 的派生组断言里同步。

**风险**：删除 `GlobalSettingsView.vue` 会让 `GlobalSettingsView.spec.ts`（若存在）失效。需同步删测试或改为测 `SettingsView` 的全局分支。`[推断]` 需在实施时确认文件是否存在。

---

## 5. R3：用户管理 403（缺陷）与「我的账户」

### 5.1 缺陷修复（先修，不能只改数据绕过去）

**根因**：导航过滤对 `capability === 'public'` 有特判（`navigation.ts:612`），路由守卫没有（`router/index.ts:472`）。`/settings/users` 标了 `public` → 任何角色都被踢到 `/403`。

**修法（单一真源）**：在 `config/navigation.ts` 导出

```ts
export function capabilitySatisfied(capability: string | undefined, role: NavRole): boolean {
  if (!capability) return true
  if (capability === 'public') return true
  return capabilitiesForRole(role).includes(capability)
}
```

守卫改调它，`filterNavByCapabilityPredicate` 的内部 `keep` 也改调它 → 两处口径从此不可能再漂移。**只改守卫不改过滤**是治标：下次别人新增 `public` 页面还会在另一处失配。

### 5.2 页面从「只读身份」升级为「我的账户」

| 区块 | 内容 | 数据来源 |
|---|---|---|
| 身份 | 用户名 / 显示名 / 角色 / 关联项目数 | 已有（`auth.user` + `/api/projects/cards`） |
| 关联项目 | 列表 + 打开 | 已有 |
| **修改密码** | 旧密码 / 新密码 / 确认新密码；成功后不强制重登 | **新增** `POST /api/me/password` |
| **外观** | 主题（跟随系统/亮/暗）+ 强调色 6 预设 | **复用** `AppearanceControls.vue` |

**改密端点的三条约束**：
1. 必须校验旧密码（不能只靠会话）；
2. 新密码强度沿用现有策略（与 `reset-password` 同一校验器，避免两套规则）；
3. **不落明文审计**：审计 detail 只写「self-change」与时间，不写任何口令片段。

**为什么复用 `AppearanceControls` 而不是新写**：强调色 6 预设已有 12 套 `data-accent` 变体与对比度校验（`tools/gen_accent_tokens.py`），重写一份必然与顶栏主题按钮不同步。同一 `useTheme` / `useAccent` 单例，天然双向同步。

---

## 6. R4：顶栏顺序

交换 `AppHeader.vue` 段 0 内两个 `<button>` 的渲染顺序（全局在前、项目在后），保留 `data-seg="mode"`、`aria-pressed`、`:class` 判定不动。

**注意**：已有 `AppHeader.spec.ts` 有一条「760px 窄屏不横向溢出」的用例按源码正则匹配，改顺序不影响；但若有「第一个按钮是项目」的断言需同步。`[推断]`

---

## 7. 不做什么

| 不做 | 理由 |
|---|---|
| 不做网段扫描自动发现设备 | 需要独立的扫描任务框架（并发、限速、结果去重），与本期「凭证列举」不是同一件事 |
| 不做 BMC 硬件全量清单（CPU/内存/磁盘/风扇/温度） | Redfish 解析层与字段映射是独立工作量；本期只取型号/序列号/电源/健康 |
| 不做网络设备配置备份与基线比对 | 属「配置管理」范畴，与「资产采集」目标不同 |
| 不把 `172.31.x` 内网地址当采集地址 | 实测本机无路由（`00-RESEARCH.md` §4）；只作备注存档 |
| 不改 `cred_type='bmc'` 既有语义 | 已有 1 条平台凭据用该值；把 bmc 改名 redfish 会逼着改历史数据，收益为零 |
| 不做「一键把 Excel 里所有设备都采一遍」 | 采集有 SSH 副作用，必须显式选凭证 + 二次确认（沿用 FR-v4.2-03 口径） |
| 不在仓库任何位置写口令 | `scripts/secret-scan.sh` 会判红；口令只经 API 加密入库 |

---

## 8. 前置条件

| # | 前置 | 状态 | 不满足的后果 |
|---|---|---|---|
| P1 | 本机能路由到 `10.202.54.0/24` | ✅ 已满足（`eth9 10.202.16.5/24`，实测 4 个地址 22 端口 OPEN） | 采集全失败 |
| P2 | 8 台 Linux 业务管理账号可登录 | ✅ 已满足（8/8 PASS） | 采集认证失败 |
| P3 | 4 台网络设备 `ehtadmin` 可登录 | ✅ 已满足（4/4 加老 KEX 后 PASS） | 网络设备通道不可用 |
| P4 | 5 台 inspur BMC Redfish 可认证 | ✅ 已满足（5/5 HTTP 200） | BMC 通道不可用 |
| P5 | R750 ×3 的 BMC 账号 | ❌ **缺**（eht / admin / root 均 401） | 3 台 BMC 只能「登记不采集」 |
| P6 | 数据库可写（用于数据治理） | ✅ 已满足 | 无法修脏数据 |
| P7 | 前端构建环境（node 22 / maven） | ✅ 已满足（v4.11.0 交付链路验证过） | 无法出包 |

---

## 9. 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 改脏数据时按设备名对齐，若名字有出入会错绑地址 | 中 | 先导出原始快照；用 `external_id`（= 映射 IP）做二次校验，不只看名字 |
| 交换机老 KEX 参数写错导致连接失败 | 中 | `connect_options` 存字符串、透传给 SSH；失败原因单独分类「算法不兼容」 |
| 批量采集把生产机当测试机跑一堆命令 | 高 | 沿用「显式选凭证 + `confirm=true` + 审计」三道门；批量入口需二次确认并列出目标数 |
| 守卫改成统一判据后，其它页面能力被放宽 | 中 | 统一判据只对 `public` 放行，其余仍走 `capabilitiesForRole`；补单测覆盖四种角色 × 三种 capability |
| Redfish 端点版本不一（v1_17_0 vs v1_5_2） | 低 | 只读 `/Systems/1` 的标准字段，不用 OEM 扩展；解析失败降级为 PARTIAL |
| 密码经 API 写入时被日志记录 | 高 | 请求体不落 access log（现有过滤已排除 `secret` 字段）；审计只记「已配置」 |

---

## 10. 改动面汇总（用于 DEV-PLAN）

| 类型 | 数量 | 明细 |
|---|---|---|
| DB 迁移 | 1 | `V11_45__asset_credential_binding.sql`：`device` +4 列、`credential` +1 列 |
| 后端新端点 | 5 | 列举关联设备 / 手工录入设备 / 手工录入凭证 / 按凭证批量采集 / 改自己的密码 |
| 后端新采集器 | 2 | 网络设备 CLI、BMC Redfish |
| 前端新/改视图 | 4 | `GlobalAssetsView`（凭证面板 + 手工录入）、`SettingsView`（全局分支）、`SettingsUsersView`（改密 + 外观）、`AppHeader`（顺序） |
| 前端共用 | 2 | `capabilitySatisfied()` 单一真源、`AppearanceControls` 复用 |
| 数据治理 | 1 | 12 行设备字段修正 + 5 条平台凭证建立 + 绑定 |
| 机检 | 1~2 | `check_navigation.py` 派生组断言同步（若删 `GlobalSettingsView`） |

---

## 11. 版本建议

**建议 v4.12.0（次版本号）而非 v4.11.1（补丁）**：本版含 1 处 DDL（5 列）、5 个新端点、2 个新采集通道 —— 超出「补丁修复」语义。唯一的纯缺陷是 R3a，但它与 R3b 同页，拆成两个版本会让同一页改两次。

---

## 12. 修订记录

| 版本 | 日期 | 修改人 | 说明 |
|---|---|---|---|
| v0.1 | 2026-11-11 | 学徒 | 首版：四需求判断、建模三方案对比、采集通道分级、不做什么、前置条件 P1~P7 |
| v0.2 | 2026-11-11 | 学徒 | **被 W-D-05 真机实测推翻的三条假设**，逐条改掉：① 原判断「BMC 只要 `trustAll()` 放开信任链就能连」→ 实测 5 台全报 `No subject alternative names matching IP address 10.202.54.11`，证书 SAN 装的是厂商内网地址（`DNS:megarac.com, IP:169.254.0.17`）；**信任链与主机名是两层，必须都放开**，且 `java.net.http.HttpClient` 强制做端点识别（`setEndpointIdentificationAlgorithm(null/"")` 实测无效），只能靠 `X509ExtendedTrustManager` 避开 JSSE 的身份校验包装层。② 原判断「`cred-probe2.sh` 用 sshpass 同参数直连全 PASS，平台 argv 照抄必然也通」→ 实测 12 台统一 `Permission denied (publickey,password)`，A/B 定位到唯一差异 `BatchMode=yes`：它关闭全部交互式提问，sshpass 等不到 `password:` 提示就没有口令可喂。③ 原判断「远端输出语言不影响解析」→ 实测 5 台浪潮机器默认 zh_CN，`free -m` 表头变「内存：」，内存整类判『输出无法解析』（退出码还是 0），中文 `未找到命令` 还被兜底成 `unreachable`；根因是 ssh `-F /dev/null` 使 `SendEnv LC_ALL` 不生效，语言必须由采集方自己钉住 |
