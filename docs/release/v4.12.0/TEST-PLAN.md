# v4.12.0 测试计划（⑤ TEST-PLAN）

> 版本：v4.12.0（草案）
> 日期：2026-11-11
> 上游：`PRD.md`（25 条 FR）、`DEV-PLAN.md`（27 个 WBS）
> 覆盖要求：**每条 FR 至少 1 条 TC**；负向与回归单列。
> 证据形态口径：`单测` / `真库IT` / `真机`（现场设备实采）/ `接口`（HTTP 响应原件）/ `机检` / `人工`。

---

## 1. 测试范围

| 在范围 | 不在范围 |
|---|---|
| 凭证—设备建模与迁移 | 网段扫描自动发现 |
| 三条采集通道（Linux / 网络设备 / BMC） | BMC 硬件全量清单（CPU/内存/风扇/温度） |
| 列举关联设备、手工录入、按凭证批量采集 | 网络设备配置备份与基线比对 |
| 12 台设备数据治理与真机采集 | 多租户隔离（沿用既有） |
| 全局「平台设置」页 | 平台设置项本身的新增/删除 |
| 我的账户（改密 / 主题 / 强调色） | 账号 CRUD 与角色管理 |
| 顶栏顺序 | 顶栏其余控件顺序 |

---

## 2. 环境与前置

| 项 | 值 |
|---|---|
| 目标环境 | WSL `Ubuntu-24.04` 权威副本 + `robot_dev` 库 + `:8001` 入口 / `:8081` 后端 |
| 现场设备 | 12 台（8 Linux / 4 网络设备），映射地址 `10.202.54.x` 可达 |
| 已知缺口 | R750 ×3 的 BMC 账号未知（PRD C1）→ 相关 TC 记 PARTIAL |
| 不阻塞项 | Chrome CDP `:9222` 关闭（沿用既有口径，浏览器级用例记 NOT-RUN） |

---

## 3. TC 清单

### A 建模

| TC | FR | 类型 | 步骤 / 判据 | 证据形态 |
|---|---|---|---|---|
| TC-v4.12-01 | FR-v4.12-01 | 正向 | `SHOW COLUMNS FROM device` 含 `management_ip_alt`、`bmc_address_alt` | 机检（SQL 输出） |
| TC-v4.12-02 | FR-v4.12-01 | 正向 | 手工录入只填 `managementIp`、两个 alt 留空 → 201/200 且回读 alt 为 null | 单测 + 接口 |
| TC-v4.12-03 | FR-v4.12-02 | 正向 | 绑定 `management_credential_id` 后反查该凭证返回 `plane=mgmt` | 真库IT |
| TC-v4.12-04 | FR-v4.12-03 | 正向 | 绑定 `bmc_credential_id` 后反查返回 `plane=bmc` | 真库IT |
| TC-v4.12-05 | FR-v4.12-04 | 正向 | 给 `10.202.54.93` 配 `KexAlgorithms=+diffie-hellman-group14-sha1` → 采集成功 | 真机 |
| TC-v4.12-06 | FR-v4.12-04 | 负向 | 同一台清空 `connect_options` → 失败且原因 `algorithm_mismatch` | 真机 + 单测 |

### B 采集通道

| TC | FR | 类型 | 步骤 / 判据 | 证据形态 |
|---|---|---|---|---|
| TC-v4.12-07 | FR-v4.12-05 | 正向 | 三类设备各采一次，`spec_json` 分类分别落在 Linux / network / bmc 三套 | 真机 + 单测 |
| TC-v4.12-08 | FR-v4.12-06 | 正+负 | `credType=network` → 200；`credType=foo` → 400 | 接口 |
| TC-v4.12-09 | FR-v4.12-07 | 正向 | `10.202.54.91`（centecOS E780）与 `10.202.54.93`（Ruijie）采出型号 + 版本 | 真机 |
| TC-v4.12-10 | FR-v4.12-08 | 正向 | 5 台 inspur BMC 采出 `NF5468M7`×4 与 `NF5280-M7-A0-R0-00`×1 | 真机 |
| TC-v4.12-11 | FR-v4.12-09 | 负向 | `172.31.20.101`（无路由）→ `unreachable` | 真机 |
| TC-v4.12-12 | FR-v4.12-09 | 负向 | R750 BMC 错账号 → `auth_failed` | 真机 |
| TC-v4.12-13 | FR-v4.12-09 | 负向 | 10000 端口无服务 → `unreachable`；未知命令 → `command_unsupported` | 单测 |
| TC-v4.12-14 | FR-v4.12-10 | 正向 | 未绑定 mgmt 凭证的设备不渲染「采集」按钮 | 单测（View spec） |

### C 列举与录入

| TC | FR | 类型 | 步骤 / 判据 | 证据形态 |
|---|---|---|---|---|
| TC-v4.12-15 | FR-v4.12-11 | 正向 | `inspur` 凭证反查返回 5 台，`plane` 全 `mgmt` | 真库IT + 接口 |
| TC-v4.12-16 | FR-v4.12-12 | 正向 | 5 台全通过 → `{total:5, ok:5, failed:0}` | 真机 + 接口 |
| TC-v4.12-17 | FR-v4.12-12 | 负向 | 把其中一台凭证指错 → `ok=4, failed=1`，4 条记录仍落库 | 真库IT |
| TC-v4.12-18 | FR-v4.12-13 | 正+负 | 录入成功 200；同名 409；只填 name 无地址 400 | 单测 + 接口 |
| TC-v4.12-19 | FR-v4.12-13 | 边界 | `name` 超 128 字符 → 400（不截断静默入库） | 单测 |
| TC-v4.12-20 | FR-v4.12-14 | 正+负 | 响应体不含明文/`cipher`；`cipher_blob IS NOT NULL`=1 | 单测 + 接口 + SQL |
| TC-v4.12-21 | FR-v4.12-15 | 正向 | 面板列出 5 行 + 「全部采集」返回 5/5 | 单测（View spec） |

### D 数据治理

| TC | FR | 类型 | 步骤 / 判据 | 证据形态 |
|---|---|---|---|---|
| TC-v4.12-22 | FR-v4.12-16 | 正向 | `LIKE '%\n%'` 计数 = 0；`device_type` 全在 `gpu/server/storage/switch` 四值内 | 机检（SQL） |
| TC-v4.12-23 | FR-v4.12-17 | 正向 | 平台凭证 = 5；12 台在役设备 `management_credential_id` 全绑（`IS NULL` = 0），`bmc_credential_id IS NULL` = 4（交换机不采带外）；`asset_status='deprecated'` 的 `leaf-switch-01` 不计入 | 机检（SQL） |

### E 平台设置

| TC | FR | 类型 | 步骤 / 判据 | 证据形态 |
|---|---|---|---|---|
| TC-v4.12-24 | FR-v4.12-18 | 正向 | `/g/settings` 有 4 个可编辑控件；改值保存后接口回读新值 | 人工 + 接口 |
| TC-v4.12-25 | FR-v4.12-19 | 正向 | 全局下无 `kernel.default` 控件；`/settings` 仍有 3 项 | 单测（View spec） |
| TC-v4.12-26 | FR-v4.12-20 | 正向 | `grep -r GlobalSettingsView frontend/src` = 0；`check_navigation.py` 绿 | 机检 |

### F 我的账户

| TC | FR | 类型 | 步骤 / 判据 | 证据形态 |
|---|---|---|---|---|
| TC-v4.12-27 | FR-v4.12-21 | 正向 | 四角色 × 三能力矩阵；`grep` 确认只有一处 `includes(capability)` | 单测 + 机检 |
| TC-v4.12-28 | FR-v4.12-22 | 正向 | ENGINEER 进 `/settings/users` 不跳 `/403` | 单测 + 人工 |
| TC-v4.12-29 | FR-v4.12-23 | 正向 | 改密成功后新密码可登录 | 真库IT |
| TC-v4.12-30 | FR-v4.12-23 | 负向 | 旧密码错 → 400；审计 detail grep 不到新旧口令 | 真库IT + SQL |
| TC-v4.12-31 | FR-v4.12-24 | 正向 | 切 `violet` → `<html data-accent="violet">`；刷新保持 | 单测 + 人工 |
| TC-v4.12-32 | FR-v4.12-24 | 正向 | 主题三态切换写入 `user_preference` | 真库IT |

### G 顶栏

| TC | FR | 类型 | 步骤 / 判据 | 证据形态 |
|---|---|---|---|---|
| TC-v4.12-33 | FR-v4.12-25 | 正向 | `[data-seg="mode"] button` 首个 = `mode.global`；点击切换正确 | 单测 |

### H 回归与收口

| TC | 范围 | 判据 | 证据形态 |
|---|---|---|---|
| TC-v4.12-34 | 后端全量单测 | 0 失败（基线 1385 例起） | 命令输出 |
| TC-v4.12-35 | 前端全量单测 | 0 失败（基线 100 文件 / 737 例起） | 命令输出 |
| TC-v4.12-36 | 32 步门禁 | 除 CDP 外全绿 | `scripts/gates.sh` 日志 |
| TC-v4.12-37 | 版本五处同源 | `check_version_consistency.py --static` PASS | 机检 |
| TC-v4.12-38 | 发布文档 | `check_release_docs.py v4.12.0` PASS | 机检 |
| TC-v4.12-39 | 口令不入仓 | `scripts/secret-scan.sh` PASS；`git grep` 不到现场口令 | 机检 |
| TC-v4.12-40 | 既有 Linux 采集不回退 | `HostInventoryCollector` 相关单测全绿；8 台真机仍 OK | 单测 + 真机 |

---

## 4. 负向与边界用例汇总

| # | 场景 | 期望 |
|---|---|---|
| N1 | 无地址录入设备 | 400，不落库 |
| N2 | 同名设备 / 凭证 | 409 |
| N3 | 凭证响应泄漏明文 | 测试失败（断言 must-fail） |
| N4 | 批量采集中单台失败 | 其余仍落库，摘要含 `failed` |
| N5 | 未绑定凭证点采集 | 按钮不渲染 / 禁用，不发请求 |
| N6 | 老 KEX 未配 | `algorithm_mismatch`，不是 `auth_failed` |
| N7 | 旧密码错 | 400，审计无口令 |
| N8 | `public` 能力访问 | 四角色均放行（回归 v4.11.0 缺陷） |
| N9 | 迁移重复执行 | 两次均成功（列存在即跳过） |

---

## 5. 证据归档

| 目录 | 内容 |
|---|---|
| `docs/evidence/v4.12.0/raw/device-before.json` | 治理前 12 行原值快照 |
| `docs/evidence/v4.12.0/raw/device-after.json` | 治理后快照 |
| `docs/evidence/v4.12.0/raw/collect-matrix.txt` | 17 条采集记录逐台分类矩阵（状态 / 采到几类 / 缺失原因） |
| `docs/evidence/v4.12.0/raw/collect-*.json` | 每次真机采集的接口响应原件（4 次批量调用，逐台含 `spec_json`） |
| `docs/evidence/v4.12.0/api/` | 手工录入 / 反查 / 批量采集 / 改密的请求响应原件（含 4xx 反例） |
| `docs/evidence/v4.12.0/raw/gates-*.log` | 门禁逐步读数 |

**证据脱敏**：任何 `secret` 字段在归档前必须被替换为 `***`；采集响应里不含口令（本就不返回）。

---

## 6. 进入开发后期的门槛

| 门槛 | 条件 |
|---|---|
| 可进入 W-D（数据治理） | W-A 迁移通过、W-C 录入端点可用 |
| 可进入 W-H（收口） | TC-01~33 全部 PASS 或 PARTIAL 有记录；TC-34/35 全绿 |
| 可交付 | TC-36~40 全绿；PARTIAL 项在测试报告 §6 写缺口与排期 |

---

## 7. 修订记录

| 版本 | 日期 | 修改人 | 说明 |
|---|---|---|---|
| v0.1 | 2026-11-11 | 学徒 | 首版：40 条 TC 覆盖 25 条 FR + 9 条负向 + 5 条收口 |
| v0.2 | 2026-11-11 | 学徒 | 按 W-D-05 实测回写证据路径（实际落 `raw/`，不是 `collect/`）；补 `collect-matrix.txt`。实测结果与遗留缺陷记入 `05-TEST-REPORT.md` |
