# v4.12.0 发布说明

| 项 | 内容 |
|---|---|
| 版本 | `4.12.0`（在 `4.11.0` 基础上的功能版） |
| 日期 | 2026-11-11 |
| 变更规模 | 25 条 FR / 7 个工作包 `W-A`~`W-G` / **1 个迁移 `V11_45`（加 5 列，可回滚）** / 新增 4 个端点 |
| 兼容性 | 接口**只增不改**；既有端点签名与语义不变；行为层面 2 处变化见「破坏性变更」 |
| 交付包 | `releases/robot-autotest-4.12.0-<sha>-linux-x64.tar.gz` |

## 用户能拿到什么

**1. 资产从「填表」变成「采集」**

以前资产页是一张要靠 Excel 维护的表，凭据和机器之间没有关系，采不采集全凭人记。现在：

- 每条凭据能反查出**它管着哪些设备、管的是哪个地址面**（业务管理面 / 带外 BMC），两个面分别成行，一台机器最多两行；
- 选中一条凭据点「全部采集」，一次采完整组，返回 `总数 / 成功 / 失败` 与逐台失败原因；
- 三类通道各采各的：Linux 10 类（系统/内核/CPU/内存/磁盘/GPU/网卡/IP/RoCE/路由）、交换机 6 类（厂商/型号/版本/序列号/运行时长/端口）、BMC 6 类（厂商/型号/序列号/电源/健康/BIOS）；
- 失败原因分四类说清：**算法不兼容**（老交换机要加 KEX 参数）、**认证失败**（账号口令不对）、**命令不支持**（机器没这条命令）、**连不上**。分类是按「算法 → 认证 → 命令 → 不可达」的顺序判的，不会把「没装驱动」说成「网络不通」。

**2. 现场 17 台设备已经真的采通了**

12 台设备 + 4 台交换机 + 5 台 BMC，共 17 条采集记录：**16 条 `OK`**，2 条 `PARTIAL` 都是同一类原因——机器本身没有 NVIDIA 显卡/驱动（`server03` 是没装驱动的 R750，`storage01` 是存储机），GPU 这一类确实没东西可采。

**3. 手工录入**

设备与凭据两个表单，不用再拼 Excel 列。口令框输入即掩码、提交后清空；类型字段服务端归一，写错直接 400，不会静默落库成一条脏数据。

**4. 自己的账号能改密码、能换主题**

`我的账户` 页里可以改自己的口令（需验原口令、走同一套口令强度策略、新旧不得相同），主题三态与强调色控件直接嵌在页内。

**5. 全局「平台设置」终于是设置页了**

以前点进去只是把侧栏 6 项又列一遍。现在是真正的设置页：并发、默认超时、报告保留天数、AI 开关四项可改，入口端口只读展示（由部署决定）。

## 破坏性变更

| # | 变更 | 影响面 | 应对 |
|---|---|---|---|
| 1 | 新增迁移 `V11_45__asset_credential_binding.sql`：`device` 加 `management_ip_alt` / `bmc_address_alt` / `management_credential_id` / `bmc_credential_id`，`credential` 加 `connect_options` | 需跑迁移 | `bash scripts/db-migrate.sh`；回滚用配对 `db/down/V11_45__…down.sql`（已过两轮 down/up 演练） |
| 2 | SSH 口令采集不再用 `BatchMode=yes` | 依赖「不弹任何交互」这一行为的自定义脚本若直接复用本平台 argv，会看到行为差异 | 本平台内已改为口令模式 `BatchMode=no` + `NumberOfPasswordPrompts=1`、私钥模式仍 `yes`；这是现场 12 台统一 `Permission denied` 的根因修复 |
| 3 | 远端采集命令统一加 `export LC_ALL=C; ` 前缀 | 采集证据里每条命令的文本会变长 | 证据与实际执行逐字一致是本平台的硬要求，故命令表本身即带前缀；远端输出不再受其 locale 影响 |

**没有**破坏性 API 变更：4 个新端点均为新增，既有端点签名、字段、语义不变。

## 修复

- **12 台现场设备脏字段**：`bmc_address` 里曾塞进账号口令、`device_type` 写过中文、多条记录带换行拼接 —— 32 处降到 0，并补上两个凭据外键。
- **`connect_options` 为 NULL 导致整批采集 500**：`JdbcSupport.first` 用 `Optional.of(null)` 抛 NPE，13 台统一报 `FAILED NullPointerException`；改 `Optional.ofNullable`。
- **中文报错被判成「连不上」**：远端 zh_CN 下 `bash: 行 1: nvidia-smi: 未找到命令` 匹配不到英文 keyword，落到兜底的 `unreachable`；已补中文判定并在采集侧固定 locale。
- **项目模式「用户管理」一进去就被踢到 /403**：`meta.capability = 'public'` 被角色能力表 `includes('public')` 判掉，而菜单过滤那边另有特判，于是「菜单看得见、点进去 403」。判据收敛到 `capabilitySatisfied` 一处，`public` 与空能力一律放行。
- **未绑定凭据的设备「采集」按钮空转**：不再渲染按钮，直接写出「未绑定凭据」。

## 升级步骤

1. **先备份 12 行资产**（本版改的是现场在用的 `device` 表）：
   `mysqldump -h… -u… -p… robot_dev device credential > /tmp/device-cred-before-v4120.sql`
2. 跑迁移：`bash scripts/db-migrate.sh`（`V11_45` 加 5 列，幂等：列存在即跳过）。
3. `bash scripts/build.sh`（编辑器 → 前端 → 后端，顺序不能反；合并过代码的话先 `python3 scripts/gen_build_info.py` 再建 jar，否则「关于」页会报旧 commit）。
4. 重启 `robot-autotest-backend` / `robot-autotest-entry`。
5. 现场设备若从未被本平台连过，先走**指纹准入**（`ssh-keyscan` 或一次 `StrictHostKeyChecking=accept-new` 登记），否则会被 `StrictHostKeyChecking=yes` 拦下并提示「主机指纹校验失败」——这是既有安全设计，不是缺陷。
6. 建 5 条平台凭据（口令经 API 加密写入，脚本不落明文），再给 12 台设备绑两个外键。
7. `python3 tools/check_version_consistency.py` 确认五处同源；`bash scripts/gates.sh` 看门禁读数。

## 回滚

- **代码**：本版改动集中在 `application/host/`（4 个采集器）+ `application/asset/`（服务与端点）+ 3 个前端视图；`git revert <merge-sha>` 即可，无隐藏状态。
- **数据**：`V11_45` 的 down 脚本会先删两个索引再删 4 列、最后删 `credential.connect_options`；delivery 前已跑过 down→up→down 两轮，`RESULT PASS`。若已产生采集记录（`device_host_spec` 行），回滚列不会删这些行，但记录里的 `spec_json` 不再能渲染到页面。
- **凭据**：`cipher_blob` 密文在回滚后仍留在库里（列不删），只是不再被读取；重新升级即恢复可用，无需重新录入口令。
