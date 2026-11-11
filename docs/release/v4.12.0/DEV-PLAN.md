# v4.12.0 开发计划（④ DEV-PLAN）

> 版本：v4.12.0（草案）
> 日期：2026-11-11
> 上游：`PRD.md`（25 条 FR）
> 纪律：**每个 WBS 必须带可执行的验收命令**；没有验收命令的条目不算计划。

---

## 1. 批次与依赖

| 批次 | 内容 | 依赖 | 可并行 |
|---|---|---|---|
| P1 | W-A 数据建模（迁移） | 无 | — |
| P2 | W-B 采集通道（两采集器 + 分派） | P1 | 与 P3 并行 |
| P3 | W-C 列举与手工录入（端点 + UI） | P1 | 与 P2 并行 |
| P4 | W-D 数据治理（12 台设备 + 5 凭证） | P1、P3 | 依赖 P3 的录入端点（或直接用 SQL） |
| P5 | W-E 平台设置 + W-F 我的账户 + W-G 顶栏 | P1（无强依赖） | 三条可并行，与 P2/P3 无关 |
| P6 | W-H 测试收口（版本、CHANGELOG、门禁、文档回写） | 全部 | — |

---

## 2. WBS

### W-A 数据建模

| ID | 任务 | 产出 | 验收命令 |
|---|---|---|---|
| W-A-01 | 新增迁移 `V11_45__asset_credential_binding.sql`：`device` + `management_ip_alt`、`bmc_address_alt`、`management_credential_id`、`bmc_credential_id`；`credential` + `connect_options` | 1 个 .sql | `bash scripts/db-migrate.sh && mysql -e "SHOW COLUMNS FROM device LIKE '%_alt'"` 返回 2 行 |
| W-A-02 | `Device` record / `DeviceRepository` 增字段（读 + 写） | Java | `mvn -o -B -Dtest=DeviceRepositoryTest test` 绿 |
| W-A-03 | `Credential` record / `CredentialRepository` 增 `connectOptions` | Java | `mvn -o -B -Dtest=CredentialServiceTest test` 绿 |
| W-A-04 | 迁移可重复执行守卫：列已存在时跳过（`information_schema` 判） | .sql | 连跑两次 `db-migrate.sh` 均成功 |

**约束**：只增可空列，不改既有列类型；`db-audit` 表数从 71 → 71（不新增表）。

### W-B 采集通道

| ID | 任务 | 产出 | 验收命令 |
|---|---|---|---|
| W-B-01 | `NetworkDeviceCollector`：`show version` + `show interface brief`，解析型号/版本/端口数 | Java + 单测 | `mvn -o -B -Dtest=NetworkDeviceCollectorTest test` 绿（含 centecOS / Ruijie 两段真实输出样本） |
| W-B-02 | `BmcRedfishCollector`：`GET /redfish/v1/Systems/1` Basic Auth，取 5 字段 | Java + 单测 | `mvn -o -B -Dtest=BmcRedfishCollectorTest test` 绿（含 v1_5_2 / v1_17_0 两份样本） |
| W-B-03 | `HostInventoryService` 按 `cred_type` 分派三采集器 | Java | `mvn -o -B -Dtest=HostInventoryServiceTest test` 绿 |
| W-B-04 | SSH 连接参数透传（`connect_options` → `-o` 列表），禁止拼进 shell | Java | 单测断言 `options` 含空格时被拆成独立 `-o` 参数且无 shell 拼接 |
| W-B-05 | 失败原因四分类（`unreachable` / `auth_failed` / `algorithm_mismatch` / `command_unsupported`） | Java + 单测 | 三种真实错误串各命中一类 |
| W-B-06 | 采集按钮与通道匹配（未绑定 / 不支持 → 不渲染） | Vue + spec | `npx vitest run src/views/global/GlobalAssetsView.spec.ts` 绿 |

**关键约束**：`HostInventoryCollector`（Linux）**一行不改** —— 它已被 v2.7 双内核演示与 8 台现场主机的形状验证过；分派逻辑放外层。

### W-C 列举与手工录入

| ID | 任务 | 产出 | 验收命令 |
|---|---|---|---|
| W-C-01 | `GET /api/global/assets/credentials/{id}/devices` | Controller + Service | `curl -s .../credentials/1/devices \| jq '.data \| length'` 返回绑定台数 |
| W-C-02 | `POST /api/global/assets/devices`（手工录入，同名 409） | 同上 | 重复提交同 name → HTTP 409 |
| W-C-03 | `POST /api/global/assets/credentials`（手工录入，响应不含 secret） | 同上 | 响应体 grep 不到明文，DB `cipher_blob IS NOT NULL` |
| W-C-04 | `POST /api/global/assets/credentials/{id}/collect`（批量，逐台独立） | 同上 | 返回 `{total,ok,failed,items}`；单台改错后 `failed=1` 其余仍落库 |
| W-C-05 | 资产页「关联设备」面板 + 「全部采集」按钮 | Vue + spec | `npx vitest run src/views/global/GlobalAssetsView.spec.ts` 绿（新用例 ≥6） |
| W-C-06 | 手工录入表单（设备 / 凭证两个） | Vue + spec | 同上 |

**路由坑**：`/credentials/{id}/devices` 与既有 `/{type}/{id}/references` 会抢匹配，`{id}` 必须写 `\\d+`。

### W-D 数据治理（现场）

| ID | 任务 | 产出 | 验收命令 |
|---|---|---|---|
| W-D-01 | 导出 12 行原始快照到 `docs/evidence/v4.12.0/raw/device-before.json` | 证据 | 文件存在且 12 条 |
| W-D-02 | 修正 `bmc_address` / `account` / `login_mode` / `device_type` 四列 | SQL 脚本（不入仓口令） | `SELECT COUNT(*) … LIKE '%\n%'` = 0 |
| W-D-03 | 建 5 条平台凭证（口令经 API 加密写入，脚本不落明文） | 运行期数据 | `SELECT COUNT(*) FROM credential WHERE owner_scope='PLATFORM'` = 5 |
| W-D-04 | 绑定 12 台设备的两个 FK | SQL | `management_credential_id IS NULL` = **0**（12 台全绑：交换机也走 SSH 采集，mgmt 凭据必须有）；`bmc_credential_id IS NULL` = **4**（4 台交换机不采带外）。原写「= 5（纯带外交换机）」与实测不符，v0.3 更正 |
| W-D-05 | 真采集取证：8 台 Linux + 4 台交换机 + 5 台 BMC | 证据 JSON | `device_host_spec` ≥17 条（实测 17）；8 台 Linux 各 ≥9 类，其中 ≥6 条 `status=OK`（实测 6 OK + 2 PARTIAL，缺 GPU 属不适用） |

### W-E 平台设置

| ID | 任务 | 产出 | 验收命令 |
|---|---|---|---|
| W-E-01 | `/g/settings` 路由组件换 `SettingsView` | `router/index.ts` | `curl :8001/g/settings` 页面含「最大并发」文本 |
| W-E-02 | `SettingsView` 全局分支：隐藏项目级 3 项、标题换 `global.settingsTitle` | Vue | 全局下源码无 `kernel.default` 控件；项目下仍有 |
| W-E-03 | 删除 `GlobalSettingsView.vue` 与其 spec（若存在），同步 `check_navigation.py` 断言 | 清理 | `python3 tools/check_navigation.py` 绿 + `grep -r GlobalSettingsView frontend/src` = 0 |

### W-F 我的账户

| ID | 任务 | 产出 | 验收命令 |
|---|---|---|---|
| W-F-01 | `capabilitySatisfied()` 单一真源，守卫与过滤同用 | `navigation.ts` + `router/index.ts` | 单测四角色 × 三能力；`grep -c "capabilitiesForRole(roleFromCodes" router` = 0 |
| W-F-02 | `POST /api/me/password`（旧密码校验 + 同策略 + 审计不落口令） | Controller + Service + IT | 错旧密码 400；成功后新密码可登录；审计 detail 无口令 |
| W-F-03 | `SettingsUsersView` 加密码区块 + 嵌 `AppearanceControls` | Vue + spec | `npx vitest run src/views/SettingsUsersView.spec.ts` 绿（新增 ≥6 例） |
| W-F-04 | 「用户管理」菜单与 `/settings/users` 四角色可达 | — | 工程师账号进入不跳 `/403`（人工 + spec） |

### W-G 顶栏

| ID | 任务 | 产出 | 验收命令 |
|---|---|---|---|
| W-G-01 | 段 0 两个按钮对调（全局左、项目右） | `AppHeader.vue` | `npx vitest run src/components/AppHeader.spec.ts` 绿（含「首个按钮=全局」断言） |

### W-H 收口

| ID | 任务 | 产出 | 验收命令 |
|---|---|---|---|
| W-H-01 | 五处版本同源 4.12.0 | VERSION / build-info / openapi / 两份 CHANGELOG | `python3 tools/check_version_consistency.py --static` PASS |
| W-H-02 | CHANGELOG v4.12.0 条目（新增/变更/修复/破坏性变更 四类齐全 + `<!-- current -->`） | CHANGELOG | `grep -A20 "v4.12.0" CHANGELOG.md` 四类标题齐全 |
| W-H-03 | 六份文档齐（00/01/PRD/DEV-PLAN/TEST-PLAN/05-TEST-REPORT）上移到 `docs/release/v4.12.0/` | 文档 | `python3 tools/check_release_docs.py v4.12.0` PASS |
| W-H-04 | 全量单测 + 前端全量 + 门禁 | 证据 | 后端 0 失败；前端 0 失败；`scripts/gates.sh` 除 CDP 外全绿 |

---

## 3. 关键实现约束（写给实现期，不是建议）

| # | 约束 | 违反后果 |
|---|---|---|
| 1 | `HostInventoryCollector` 不为交换机/BMC 改造 | 8 台已验证的 Linux 采集回归，属自伤 |
| 2 | `connect_options` 只作为 `-o` 参数数组元素传入，不拼字符串进 shell | 命令注入 + 含空格的选项直接失效 |
| 3 | 批量采集逐台独立事务 | 一台失败拖垮整批，8 台结果全丢 |
| 4 | 手工录入凭证的响应体必须脱敏 | 明文泄到前端与浏览器历史 |
| 5 | `{id}` 路由必须 `\\d+` | `GET /credentials/devices` 被当 id 解析，返回莫名 400 |
| 6 | 迁移只增列 | 已应用迁移 checksum 变更 → 迁移失败 |
| 7 | 删 `GlobalSettingsView` 必须同步 `check_navigation.py` 断言 | 机检红，进不了门禁 |

---

## 4. 回滚方案

| 场景 | 回滚动作 |
|---|---|
| 迁移引入问题 | 迁移只增可空列 → 直接 `ALTER TABLE ... DROP COLUMN` 回退，不影响既有查询 |
| 采集通道改动导致 Linux 采集异常 | 分派逻辑集中在 `HostInventoryService` 一处，回退该文件的 `switch` 即恢复原路径 |
| 数据治理改错 | `device-before.json` 反向生成 UPDATE；**不用 `git checkout`**（数据不在 git 里） |
| 前端守卫改动导致越权或误拦 | 单函数回退；单测已锁四角色 × 三能力 |

---

## 5. 与 PRD 的对应

| PRD 组 | WBS |
|---|---|
| A 建模（FR-01~04） | W-A |
| B 采集（FR-05~10） | W-B |
| C 列举/录入（FR-11~15） | W-C |
| D 治理（FR-16~17） | W-D |
| E 平台设置（FR-18~20） | W-E |
| F 我的账户（FR-21~24） | W-F |
| G 顶栏（FR-25） | W-G |

---

## 6. 修订记录

| 版本 | 日期 | 修改人 | 说明 |
|---|---|---|---|
| v0.1 | 2026-11-11 | 学徒 | 首版：8 组 27 个 WBS，每条带验收命令 |
| v0.2 | 2026-11-11 | 学徒 | W-D-05 判据按实测修订（详见 PRD §8 v0.2）；实际完成情况：W-D-01~05 全部完成，17 条采集记录 = 8 台 Linux 管理面 8 条 + 4 台交换机 4 条 + 5 台 BMC 5 条；偏差：原判据 `host_inventory` 表名有误（实际落 `device_host_spec`），考核指标与 PRD G1 同步改为「≥17 条 + 6 条 OK」 |
