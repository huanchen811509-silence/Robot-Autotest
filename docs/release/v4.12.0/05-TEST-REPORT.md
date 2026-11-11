# v4.12.0 测试报告（⑥ 测试执行）

| 项 | 内容 |
|---|---|
| 编号 | `RA-TR-v4.12-001` |
| 版本 | `4.12.0`（`VERSION=4.12.0`；`build-info` 由 `scripts/gen_build_info.py` 生成） |
| 执行时间 | 2026-11-11 21:00 ~ 23:51（GMT+8；含 3 轮现场采集复跑与 2 处真注入） |
| 执行环境 | WSL `Ubuntu-24.04` ext4 副本 `/home/evergreen/robot-autotest`；后端 `:8081`（systemd user，jar 52 863 518 B @ 23:48，内含 `git.commit=f11c704`）；入口 `:8001`；MySQL 库 `robot_dev`（71 表）、集成库 `robot_it` |
| 现场 | 8 台 Linux（`10.202.54.101~108`）+ 4 台交换机（`.91/.92/.93/.100`）+ 5 台 BMC（`.11~.15`），全部经真实 HTTP 接口与真实网络 |
| 上游 | `TEST-PLAN.md` v0.2（40 条 TC）、`PRD.md` v0.2（25 条 FR）、`DEV-PLAN.md` v0.2（8 组 WBS） |
| 证据根目录 | `docs/evidence/v4.12.0/raw/` |
| 结论 | **40 条 TC：PASS 32 / PARTIAL 6 / NOT-RUN 2 / FAIL 0**。两层单测全绿（后端 1428 / 前端 761）；17 条真机采集记录中 16 条 `OK`、2 条 `PARTIAL`（同一台机器两面计数）；2 条 NOT-RUN 均为**决策性不采**，非缺陷 |

## 1. 结果总览

| 结果 | 条数 | 含义 |
|---|---|---|
| PASS | 32 | 有可复现命令输出或落盘证据，判据全部满足 |
| PARTIAL | 6 | 判据主体满足，缺口写明（多为「为不给资产表留测试行而跳过成功创建」与「浏览器人工未做，CDP 关闭」） |
| NOT-RUN | 2 | 未执行，且是**决策**而非缺陷（详见 §6） |
| FAIL | 0 | — |

**分层实测读数**

| 层 | 手段 | 实测结果 |
|---|---|---|
| L1 单元 | JUnit 5 / Vitest | 后端 **1428 例 / 0 失败 / 0 错误**（`mvn -o -B test`，BUILD SUCCESS，2 分 11 秒）；前端 **102 文件 / 761 例全过**（25.4 秒） |
| L2 组件 | Vitest + DOM 断言 | 本版新增/改动 8 个前端 spec；`GlobalAssetsView.spec` 14 例、`SettingsView.spec` 5 例、`SettingsUsersView.spec` 12 例 |
| L3 接口 | 真实 HTTP（登录后携 token） | `raw/tc-run.log` **28 项 PASS**、`raw/tc-run2.log` **6 项 PASS**，`RESULT FAILS=0` |
| L4 数据 | 真库 `robot_dev` | 17 条采集记录（`device_host_spec`）；治理前后快照逐行比对；`cipher_blob` 7/7 为密文 |
| L5 真机 | 现场 17 台设备 | 采集矩阵见 `raw/collect-matrix.txt`；失败原因全部消失 |
| L6 机检 | `scripts/gates.sh` | 32 步 / **PASS 31 / FAIL 1**（唯一失败＝第 13 步 CDP 关闭）；逐条读数见 §5 |

## 2. 用例结果（A 建模 / B 采集通道）

| TC | 结果 | 证据 |
|---|---|---|
| TC-v4.12-01 | PASS | `SHOW COLUMNS FROM device` 含 `management_ip_alt` / `bmc_address_alt` / `management_credential_id` / `bmc_credential_id`；`credential` 含 `connect_options`。**计划里写的 `collect_channel` 不是列**——通道由凭据类型经 `HostAccess.channelOf` 推导，换凭据即换通道，判据已按此更正 |
| TC-v4.12-02 | PARTIAL | 两侧校验分支由 spec 覆盖；「只填 `managementIp` 成功创建」这一步**未走接口**——这台库是现场在用的资产表，为一条正向用例留一行测试数据不划算，缺口记入 §6 |
| TC-v4.12-03 | PASS | `GET /credentials/3/devices` → 5 台、`plane` 全 `mgmt`（`raw/tc15-devices.json`） |
| TC-v4.12-04 | PASS | `POST /credentials/5/collect` → 5 台 BMC 全 `OK`、`plane=bmc`（`raw/collect-lab-inspur-bmc.json`） |
| TC-v4.12-05 | PASS | 25G Leaf（`10.202.54.93`，只支持 `diffie-hellman-group14-sha1`）带 `connect_options` 采集 `OK`，6/6 类 |
| TC-v4.12-06 | PASS | **真注入**：备份 `credential 7.connect_options`（逐字入 `raw/tc06-connect-options.bak`）→ 置 NULL → 重采 → 25G Leaf 报 `algorithm_mismatch: Unable to negotiate … no matching key exchange method found`，另 3 台仍 `OK` → 逐字还原 → 重采 4/4 全 `OK` |
| TC-v4.12-07 | PASS | 三类采集各自落三套分类名：Linux 10 类（`OS…ROUTE`）、网络设备 6 类（`NET_*`）、BMC 6 类（`BMC_*`） |
| TC-v4.12-08 | PARTIAL | `credType=foo` → **400**（`raw/tc08-bad-type.json`）；`credType=network` → 200 需新建一条凭据，同样为避免留测试行跳过 |
| TC-v4.12-09 | PASS | 4 台交换机 6/6：`800G RoCE` / `200G RoCE` / `25G Leaf` / `OOB 带外` 均采出 `NET_VENDOR` + `NET_MODEL` + `NET_VERSION` |
| TC-v4.12-10 | PASS | 5 台 BMC 6/6：`gpu01~04` = `inspur / NF5468M7`（SN `21D509382/389/419/429`），`storage01` = `IEIT SYSTEMS / NF5280-M7-A0-R0-00`（SN `29E303686`）；电源 `On`、健康 `OK`、BIOS 版本齐全 |
| TC-v4.12-11 | NOT-RUN | 判 `unreachable` 需造一台指向无路由网段（`172.31.20.x`）的设备；分类逻辑由 `CollectFailureTest` 单测锁定，现场不为此改资产表 |
| TC-v4.12-12 | NOT-RUN | 决策 **C1**：R750 BMC 账号待确认，**只登记不采集**，故「错账号 → `auth_failed`」在 R750 上无实测；该分类由 `CollectFailureTest`（`eht@10.202.54.16: Permission denied`）与第一轮真实报错锁定 |
| TC-v4.12-13 | PASS | 单测：`Permission denied`→`auth_failed`、`no matching key exchange`→`algorithm_mismatch`、`command not found`/`未找到命令`→`command_unsupported`、空原因→`unreachable` |
| TC-v4.12-14 | PASS | `GlobalAssetsView.spec`：未绑定凭据的设备不渲染「采集」按钮、改为输出「未绑定凭据」；纯 BMC 设备仍可采 |

## 3. 用例结果（C 列举与录入 / D 治理 / E 设置 / F 我的账户 / G 顶栏）

| TC | 结果 | 证据 |
|---|---|---|
| TC-v4.12-15 | PASS | `inspur` 凭据反查 5 台，`plane` 全 `mgmt` |
| TC-v4.12-16 | PASS | `credential 3` 批量采集 `{total:5, ok:5, failed:0}`（`raw/collect-lab-inspur-ssh.json`） |
| TC-v4.12-17 | PASS | 部分失败不拖死整批：TC-06 注入那一轮 `total=4 / ok=3 / failed=1`，3 台成功项全部落库、1 台失败项也留记录 |
| TC-v4.12-18 | PARTIAL | 同名设备 → **409**、只填 `name` 无地址 → **400**（`raw/tc18-dup.json`、`raw/tc18-noaddr.json`）；成功创建未走接口（同 TC-02 理由） |
| TC-v4.12-19 | PASS | `name` 129 字符 → **400**（`raw/tc19-longname.json`），不静默截断入库 |
| TC-v4.12-20 | PASS | 平台凭据 5/5 全部 `cipher_blob IS NOT NULL`、全库 7/7 为密文、库内搜不到现场口令原文；凭据列表响应字段里没有 `cipher`/`secret`/`password`（`raw/tc20-credentials.json`） |
| TC-v4.12-21 | PASS | `GlobalAssetsView.spec`：面板列 5 行 + 「全部采集」摘要 `2/1/1` + 失败原因逐条展示 |
| TC-v4.12-22 | PASS | 治理后脏行 = **0**（换行拼接、`device_type` 非法值、`bmc_address` 混账号口令三类全清）；治理前 32 处、13 行快照在 `raw/device-before.json` |
| TC-v4.12-23 | PASS | 12 台在役设备 `management_credential_id IS NULL` = **0**（交换机也走 SSH 采集，mgmt 凭据必须有）、`bmc_credential_id IS NULL` = **4**（交换机不采带外）；`asset_status='deprecated'` 的 `leaf-switch-01` 不计入 |
| TC-v4.12-24 | PASS | `PUT /api/system/settings` 把 `execution.maxParallel` 由 `4` 改 `5` → 读回一致 → 逐字还原（`raw/tc24-before/put/after/restored.json`）。注：`entry.port` 是只读展示项，混进 PUT 会被 400 —— 这既是校验也是回归保护 |
| TC-v4.12-25 | PASS | 全局语义下项目段整块不渲染（连 `pytest` 字样都不出现）、不拉项目设置；5 个字段中 4 个可编辑、端口只读 |
| TC-v4.12-26 | PASS | `grep -r GlobalSettingsView frontend/src` = 0；`tools/check_navigation.py` PASS（门禁第 21 步） |
| TC-v4.12-27 | PASS | 路由与导航两处调用点都只调 `capabilitySatisfied`，自拼 `includes(capability)` = **0** 处；`public` 特判代码行全仓仅 1 处；`capabilitySatisfied.spec` 7 例锁四角色矩阵 |
| TC-v4.12-28 | PARTIAL | 守卫单测与 `settingsGuard.spec`（9 例）通过；以真实 ENGINEER 账号登入 `/settings/users` 的人工复核未做——实验环境没有该角色的可用口令 |
| TC-v4.12-29 | PASS | `POST /api/me/password` → 200；新口令可登录；随后改回原口令并再次验证可登录（全程未留半改状态） |
| TC-v4.12-30 | PASS | 旧口令错 → **400**（错与空同一句「原口令不正确」，不泄露是哪种错）；`audit_log` 里新旧口令明文均搜不到（`user.password.self-change` 只记 `username=`） |
| TC-v4.12-31 | PARTIAL | 单测覆盖「切 `violet` → `data-accent` 变化 + 刷新保持」；浏览器实际取值未做（CDP `:9222` 按令关闭） |
| TC-v4.12-32 | PARTIAL | 同上，`user_preference` 真库落库未单独取证（行为由单测锁定） |
| TC-v4.12-33 | PASS | `AppHeader.spec`：`[data-seg="mode"]` 首个子按钮文本 = `mode.global`，点击切换正确 |

## 4. 用例结果（H 回归与收口）

| TC | 结果 | 证据 |
|---|---|---|
| TC-v4.12-34 | PASS | 后端全量 **1428 例 / 0 失败**（基线 1385 例起） |
| TC-v4.12-35 | PASS | 前端全量 **102 文件 / 761 例 / 0 失败**（基线 100 文件 / 737 例起） |
| TC-v4.12-36 | PASS | `scripts/gates.sh` 读数见 §5 |
| TC-v4.12-37 | PASS | `check_version_consistency.py --static`：`VERSION` / `build-info` / 两份 `CHANGELOG` / `openapi` 五处同源 `4.12.0` |
| TC-v4.12-38 | PASS | `check_release_docs.py v4.12.0` PASS |
| TC-v4.12-39 | PASS | `secret-scan` 无命中；`git grep` 不到现场口令 |
| TC-v4.12-40 | PASS | 8 台 Linux 真机全部重新采出清单（6 条 `OK` + 2 条仅 GPU 不适用），既有 10 类命令白名单除新增的 `export LC_ALL=C;` 前缀外逐字未改 |

## 5. 门禁读数

> 终轮读数：`docs/evidence/v4.12.0/raw/gates-v4120-final.log`（2026-11-11 23:47 ~ 23:51 GMT+8）。
> **32 步 / PASS 31 / FAIL 1**，唯一失败是第 13 步浏览器回归 —— CDP `:9222` 按令关闭，属决策性未跑（§6 `L-412-02`），不是本版回归。

| 步骤 | 结果 | 关键读数 |
|---|---|---|
| 01 subset_font | PASS | 字体子集在预算内 |
| 02 frontend_test | PASS | **102 文件 / 761 例全过**（23s） |
| 03 type_check | PASS | `vue-tsc --noEmit` 0 错 |
| 04 frontend_build | PASS | dist 重建 8.9s（新 `GlobalAssetsView` 已入包） |
| 05 backend_test | PASS | **1428 例 / 0 失败 / 0 错误**，BUILD SUCCESS（86s） |
| 06 lint | PASS | `eslint` 0 问题；表格规范 **56 处数据网格全部走壳**，原生 `<table>` 仅白名单 3 文件 |
| 07 release_docs | PASS | `v4.12.0`：`FR=25 TC=40`，FAIL 0 / WARN 0 |
| 08 wbs_consistency | PASS | 130 包 / 已完成 124 / 待现场 6 / 进行中 0 |
| 09 md_tables | PASS | 全部文档表格列数自洽 |
| 10 diff_check | PASS | 无空白错 |
| 11 editor_test | PASS | 编辑器包独立 vitest 全过 |
| 12 db_audit | PASS | `robot_dev` 71 表结构体检通过 |
| **13 browser_regression** | **FAIL** | `fetch failed` —— CDP `:9222` 按令关闭（`L-412-02`），本轮不做人工复核 |
| 14 report_sensitive | PASS | 报告与证据无敏感信息 |
| 15 css_vars | PASS | 无硬编码颜色/间距令牌违规 |
| 16 report_consistency | PASS | 报告前后口径一致 |
| 17 perf_baseline | PASS | 性能基线未退化 |
| 18 structure_selfcheck | PASS | 门禁结构 32 步齐全 |
| 19 evidence_integrity | PASS | 三处 `failures=0 / errors=0` 且 tests 不低于基线；5 组破坏性自校验全部按预期判红 |
| 20 api_contract | PASS | **293 paths / 358 endpoints**（含本版 4 个新端点） |
| 21 bundle_budget | PASS | 图标 / 组件库 / 字体 / 主入口 / 单路由块均达标，无 styleguide 标记泄漏 |
| 22 perf_budget | PASS | 性能预算内 |
| 23 version_consistency | PASS | 五处同源 `4.12.0`，运行期 `build-info=4.12.0` |
| 24 ai_eval | PASS | 黄金集事实一致性达标、严重幻觉 0 |
| 25 security_scan | PASS | 高危 0 |
| 26 capacity_backup | PASS | 容量与备份判据通过 |
| 27 i18n_contrast | PASS | 14 组（主题 × 强调色）共 **280 项对比度**全部达标 |
| 28 case_command | PASS | 用例命令契约一致 |
| 29 host_inventory | PASS | 10 类命令与源码字面量逐字一致 + 只读白名单（含新增 `export`） |
| 30 delivery_bundle | PASS | `artifact=robot-autotest-4.12.0-f11c704-linux-x64.tar.gz`（sha256 校验 + MANIFEST + 解包清单） |
| 31 update_mechanism | PASS | 更新机制安全默认态（dry-run / 二次确认 / 回滚护栏） |
| 32 workspace_bundle | PASS | 工作区包契约通过 |

**首轮 → 终轮的收敛过程**（首轮 `raw/gates-v4120.log` 为 `FAILS=5`）

| 首轮 FAIL | 根因（实测，不是猜测） | 处置 | 复验读数 |
|---|---|---|---|
| 06 lint | `GlobalAssetsView.vue` 弹窗内手写原生 `<table>`，被 `tools/check_tables.py` 判为「数据网格绕过规范」 | 改 `bindColumns` + `DataTable` 壳，加载/空态交给壳（提交 `f11c704`） | 表格规范 PASS：56 处全走壳 |
| 23 version_consistency | 运行期 `build-info` 仍 `4.11.0` —— 改了源码但后端没重启（`BUILD SUCCESS` 只代表编译过） | `gen_build_info.py` → 重建 jar → `systemctl --user restart` | 运行期 `build-info=4.12.0` |
| 29 host_inventory | 命令表加 `export LC_ALL=C;` 前缀后，证据与只读白名单未同步 | 按 v4.12.0 重新取证（`EW412-01`）+ `READONLY_BINARIES` 补 `export` | `HOST_INVENTORY PASS` |
| 30 delivery_bundle | MANIFEST 仍 `4.11.0` —— v4.12.0 的包还没打 | `scripts/package-release.sh` 产 `4.12.0-f11c704` | `DELIVERY_BUNDLE PASS fails=0` |
| 13 browser_regression | 保留 | CDP 按令关闭，决策性未开 | 仍 FAIL（预期） |

**证据链**：门禁日志 `raw/gates-v4120-final.log`；分步日志 `docs/evidence/gates/logs/*.log`；交付包 `releases/robot-autotest-4.12.0-f11c704-linux-x64.tar.gz` + `.sha256` + `.manifest.json`（357 文件 / 48 584 314 B / `sha256 9fc0480…221d`）。

## 6. 遗留、缺口与排期

| 编号 | 缺口 | 性质 | 处置 |
|---|---|---|---|
| L-412-01 | TC-02 / TC-18 的「成功创建」未走接口实测 | 取舍：不在现场资产表留测试行 | 手工录入链路已由 `GlobalAssetsView.spec` + 后端校验单测覆盖；如需端到端取证，改在集成库 `robot_it` 造行（下版） |
| L-412-02 | TC-28 / TC-31 / TC-32 的浏览器人工项未做 | 环境：CDP `:9222` 按令关闭 | 由单测锁定行为；恢复 CDP 后补 3 条人工复核 |
| L-412-03 | R750 BMC 未采（`lab-eht-bmc`） | 决策 **C1**：账号待确认 | 账号到位后补采，预期 3 台 `bmc_credential_id=6` |
| L-412-04 | `server03` / `storage01` 恒为 `PARTIAL` | 机器本身无 NVIDIA 驱动/无 GPU | **口径已定**：保持 `PARTIAL`、改判据（大师 2026-11-11 拍板，见 PRD §8 v0.2）。不引入「不适用」新状态 |
| L-412-05 | 交换机只采基础 6 类（型号/版本/序列号/运行时长/端口数/厂商） | 决策 **C2**：不深入厂商私有 CLI | 需要端口逐口状态时再单独立项 |

## 7. 判据与计划不一致的修订说明（⑦ 回写）

本轮实测推翻了 5 条写死的判据，全部已回写上游文档（不是悄悄改数）：

| # | 原判据 | 实测 | 处置 |
|---|---|---|---|
| 1 | PRD G1「≥8 条 `status=OK`」 | 8 台里 2 台机器本身无 GPU，永远采不到该类 | 改为「≥17 条采集记录 + 8 台各 ≥9 类 + 6 条 OK」（PRD §8 v0.2；大师拍板「保持 PARTIAL、改判据」） |
| 2 | DEV-PLAN W-D-05「`host_inventory` ≥17 条」 | 实际表名是 `device_host_spec`（`host_inventory` 表不存在） | 更正表名，并同步「8 条 OK」为「≥6 条 OK」（DEV-PLAN v0.2） |
| 3 | DEV-PLAN W-D-04「`management_credential_id IS NULL` = 5（纯带外交换机）」 | 交换机也走 SSH 采集，mgmt 凭据必须有；实测 = **0** | 更正为 `= 0`，并补 `bmc_credential_id IS NULL = 4`（DEV-PLAN v0.3） |
| 4 | TEST-PLAN TC-23「4 台纯带外交换机 mgmt 为空」 | 同上 | 改写判据并注明排除 `deprecated` 行（TEST-PLAN v0.2） |
| 5 | TEST-PLAN §5 证据路径 `docs/evidence/v4.12.0/collect/` | 实际落 `raw/collect-*.json` | 更正路径并补 `collect-matrix.txt`（TEST-PLAN v0.2） |

另外三条**被实测推翻的技术假设**已回写评估报告 `01-ASSESSMENT.md` v0.2：BMC 需同时放开信任链**与主机名**、`sshpass` 直连能通不代表平台 argv 能通（`BatchMode`）、远端 locale 会影响解析与分类。

## 8. 修订记录

| 版本 | 日期 | 修改人 | 说明 |
|---|---|---|---|
| v0.1 | 2026-11-11 | 学徒 | 首版：40 条 TC 逐条落结果，含 6 条 PARTIAL 与 2 条 NOT-RUN 的缺口说明 |
| v0.2 | 2026-11-11 | 学徒 | ⑦ 回写：补 §5 门禁终轮读数（32 步 / 31 PASS / 1 FAIL(CDP)）与首轮 5 项 FAIL 的收敛过程；执行环境的 jar 与 commit 更新到最终产物（`f11c704`） |
