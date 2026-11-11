# 软件迭代与优化 · 测试计划（TEST-PLAN）

| 项 | 内容 |
|---|---|
| 编号 | `RA-TST-2026-049` |
| 版本 | v1.1 ｜ 2026-11-11（深圳 GMT+8；v1.0 为 ⑤ 计划基线，v1.1 增补附录 C 实测回写） |
| 上游 | `RA-PRD-2026-049` v1.1（38 条 FR）、`RA-DEV-2026-049` v1.0（8 个 WBS 包）、`RA-EVAL-49-001` v1.0、大师 2026-11-11 两次向导拍板 |
| 基线 | ~~`VERSION=4.9.2` / `c9be67f`；门禁 32 步 `FAILS=1`~~ → 执行时 `VERSION=4.10.0`；门禁 32 步 **30 PASS / 2 FAIL**（13-browser_regression=CDP 取消、06-lint=`check_tables` 存量债） |
| 范围 | 38 条 FR → 52 条 TC，L1~L7 七层；5 个专项；9 项故障注入；6 个回归集 |
| 状态 | **已执行并回写**：PASS 29 / PARTIAL 14 / NOT-RUN 9 / FAIL 0，逐条见 `05-TEST-REPORT.md`；本文件仍不构成放行依据 |
| 落位 | ~~`docs/plan/` 专题三件套（大师 D1 拍板）~~ → **2026-11-11 拍板 D4 改为 release 单源**（`docs/release/v4.10.0/`）；证据目录 `docs/evidence/opt-20261111/<包号>/` |
| 纪律 | **不接受实现方自证**：判定「对不对」的不能是被测的那个东西本身（`docs/process/版本开发流程.md` §2.5）；`BLOCKED` 不等于 PASS，环境不通一律如实记 BLOCKED 并追因 |

---

## 0. 测试治理

### 0.1 准入（不满足不开测）

| # | 条件 | 核实方式 |
|---|---|---|
| 1 | 38 条 FR 冻结、三份文档评审通过 | 评审纪要 + PRD 修订记录 |
| 2 | 对应 WBS 包完成且自测通过 | 包内验收命令输出留档 |
| 3 | 新迁移 `V11_4x` 有配对 down，并完成 up→down→up 演练 | `scripts/db-audit.sh` 三次输出 |
| 4 | 构建产物可启动，前后端同版本号 | 就绪探针打 `:8081`（`:8001/_ping` 自回 200 不算数） |
| 5 | 样本工作簿可读、账号矩阵可用 | `openpyxl` 只读打开 + 四角色登录冒烟 |
| 6 | K1 预演闸门通过（补导前） | `sync/preview` 非预期 UPDATE = 0 的原始输出 |
| 7 | 证据目录可写、备份可用 | `mysqldump` 备份文件非空 |

### 0.2 准出（不满足不放行）

| # | 判据 |
|---|---|
| 1 | 52 条 TC 100% 有结果（PASS / FAIL / BLOCKED 三态之一），无「未执行」空项 |
| 2 | 17 条 P0 FR 对应的 TC 零 FAIL、零 BLOCKED |
| 3 | 9 项故障注入全部执行，定向判据「必须红」的红态有证据 |
| 4 | 独立裁判的 7 类对拍全部完成，差异 0 或差异有书面解释 |
| 5 | `bash scripts/gates.sh` 的 `FAILS ≤ 1` 且唯一红项仍为 13-browser_regression；`check_release_docs.py v4.9.2` 保持 `[PASS] FR=8 TC=10 FAIL 0` |
| 6 | 所有 `NOT-RUN` 项写明原因与解除条件，且未被计入 PASS |
| 7 | 三份文档完成 ⑦ 回写（PRD 的 FR 状态、DEV-PLAN 的偏差、TEST-PLAN 的实测结果、评估报告的假设重标） |

### 0.3 缺陷分级

| 级别 | 定义 | 处置 |
|---|---|---|
| P1 | 数据丢失 / 越权 / 事务残留 / 明文口令 / 迁移不可回退 / 全局设置不可达 | 当日修，阻断放行 |
| P2 | 主流程受阻但有绕行、字段缺失、布局错位影响操作 | 本批内修 |
| P3 | 文案、间距、非关键提示 | 可进补丁，须登记 |
| P4 | 建议类 | 入待办，不阻断 |

### 0.4 轮次

| 轮次 | 内容 | 出口 |
|---|---|---|
| R1 冒烟 | 每包 1 条端到端链路 | 可继续 |
| R2 功能 | 52 条主用例全量 | 无「未执行」项 |
| R3 专项 | 幂等 / 权限 / 密钥 / 事务 / 视觉 / 迁移 / 负向 7 类 | 指标达标 |
| R4 回归 | 6 个 `REG-*` 集 | 全绿或红项与基线一致 |
| R5 取证 | 命令原文、原始输出、结构化结果、报告 | 证据索引可点开 |

### 0.5 环境矩阵

| 维度 | 取值 | 备注 |
|---|---|---|
| 运行环境 | WSL Ubuntu-24.04 `~/robot-autotest`（权威，ext4） | `E:\` 只编辑；9P（`/mnt/*`）禁止构建测试（慢 35 倍） |
| 数据库 | MySQL 8，`DB_NAME` 取自 `~/.config/robot-autotest/env` | `set -a; . ~/.config/robot-autotest/env; set +a`；`db_exec` 必带 `-D "$DB_NAME"` |
| 环境变量 | `EXECUTION_PYTEST_BIN`、`CRED_MASTER_KEY` 等沿用既有 systemd drop-in | 不新增凭据，metadata / 证据内不写口令 |
| 浏览器 | Chromium（CDP `:9222`） | **当前被大师取消、不许重启**；所有 L5 用例标 NOT-RUN |
| 分辨率 | 1440×900（主）、1024×768、720p 高 | 720p 与既有 `L-V25-02` 同源，NOT-RUN |
| 主题 / 语言 | system / light / dark × zh-CN / en-US | FR-v4.10-10 / 11 遍历 |
| 角色 | ADMIN / PM / ENGINEER / VIEWER | 24 格菜单矩阵 + 越权直达 |
| 样本工作簿 | `基本功能导入测试v0.5.0.0.xlsx`（5 明细表 / 730 条 / 14 列 / 表头第 3 行） | 本地只读解析；表格编辑器通道曾报 `No workbook open`，回退 openpyxl，不改文件 |

### 0.6 L1~L7 分层与覆盖

| 层 | 手段 | 覆盖 TC（按 §4 明细机器实算） | 独立裁判 |
|---|---|---|---|
| L1 单元 | JUnit 5 / Vitest | 7 条：TC-v4.10-01 / 06 / 13 / 14 / 29 / 35 / 42 | 断言由测试自身给出，但输入来自外部 fixture |
| L2 组件 | Vue Test Utils + DOM 断言 | 8 条：TC-v4.10-02 / 08 / 15 / 17 / 22 / 24 / 39 / 43 | 序列化比对独立于组件实现 |
| L3 接口 | HTTP 集成（curl / 独立 Python 客户端） | 10 条：TC-v4.10-03 / 07 / 12 / 18 / 19 / 23 / 30 / 33 / 38 / 44 | **不看业务日志自述**，直接验响应体与状态码 |
| L4 数据 | SQL 对拍 / 幂等计数 / 迁移演练 | 5 条：TC-v4.10-09 / 27 / 37 / 40 / 50 | openpyxl 源表 + `db_exec` 独立查库 |
| L5 端到端 | Playwright / CDP 真实点击 | 2 条：TC-v4.10-04 / 45 | **CDP 未恢复，2 条均 NOT-RUN** |
| L6 专项 | 权限矩阵 / 密钥 / 事务 / 视觉 / 负向 | 11 条：TC-v4.10-05 / 10 / 16 / 20 / 25 / 26 / 31 / 32 / 36 / 46 / 49 | 负向验证必须红；独立脚本扫描 |
| L7 回归门禁 | `scripts/gates.sh` + 机检脚本 + 6 个回归集 | 9 条：TC-v4.10-11 / 21 / 28 / 34 / 41 / 47 / 48 / 51 / 52 | 机检输出即判据 |

---

## 1. 测试范围与策略

### 1.1 范围

- **测**：PRD 38 条 FR 全部；开发计划 8 个 WBS 包的验收命令；横向基线不倒退（FR-v4.10-35 ~ 38）。
- **不测**：R9 乱码目录删除动作（运维，不在本批执行）、R10 整包移植（维持收口令）、发版 / 落号 / 对外口径（大师专属）、PRD §1.4 的 10 条非目标。
- **不计 PASS**：CDP 依赖的浏览器取证、真库 down→up（缺 `DB_PASSWORD` 时）、720p 窄屏，一律 `NOT-RUN` + 原因 + 解除条件。

### 1.2 策略

1. **判据先行**：每条 TC 的期望结果在写代码前就固定，⑥ 不得因实现方便而改判据；要改须走 PRD 修订记录并留授权人。
2. **对拍优于自述**：导入正确性用 openpyxl 读源表与 DB 结果对拍；模板明细用 API 与 SQL 对拍；权限用独立 HTTP 客户端四角色矩阵。
3. **反例优先**：关键需求（补导、事务、权限、空模板、分类约束）先跑反例，红态证据留档后才跑 happy path。
4. **负向验证真注入**：备份 → 注入 → 定向判据必须红 → **用备份拷贝还原（不用 `git checkout`）** → 复跑绿，五步齐全。
5. **密钥断言逐字段**：不对整段响应查 `sk-`（会命中描述文本），逐条校验 `keyMasked` 前缀与字段名黑名单。
6. **门禁机检进判据**：`check_md_tables.py`（显式传路径）、`check_release_docs.py v4.9.2`、`scripts/gates.sh` 三项作为 L7 硬判据。

---

## 2. FR → TC 覆盖矩阵

> 下表是机器可核对的总索引；52 条 TC 的明细见 §4。**38 条 FR 每条至少 1 条 TC**，关键 FR 另有反例（标 `NEG`）。

| FR | 覆盖 TC | 反例 TC | WBS | 优先级 |
|---|---|---|---|---|
| FR-v4.10-01 | TC-v4.10-01 | — | OPT-01 | P0 |
| FR-v4.10-02 | TC-v4.10-02 / TC-v4.10-04 | TC-v4.10-05 | OPT-01 | P0 |
| FR-v4.10-03 | TC-v4.10-03 | TC-v4.10-05 | OPT-01 | P0 |
| FR-v4.10-04 | TC-v4.10-01 / TC-v4.10-11 | — | OPT-01 | P1 |
| FR-v4.10-05 | TC-v4.10-06 / TC-v4.10-11 | TC-v4.10-10 | OPT-02 | P0 |
| FR-v4.10-06 | TC-v4.10-07 / TC-v4.10-09 | TC-v4.10-10 | OPT-02 | P0 |
| FR-v4.10-07 | TC-v4.10-08 | — | OPT-02 | P0 |
| FR-v4.10-08 | TC-v4.10-08 / TC-v4.10-12 | — | OPT-02 | P0 |
| FR-v4.10-09 | TC-v4.10-08 / TC-v4.10-12 | — | OPT-02 | P1 |
| FR-v4.10-10 | TC-v4.10-13 | — | OPT-03 | P1 |
| FR-v4.10-11 | TC-v4.10-14 | — | OPT-03 | P1 |
| FR-v4.10-12 | TC-v4.10-15 / TC-v4.10-16 | — | OPT-03 | P2 |
| FR-v4.10-13 | TC-v4.10-17 | — | OPT-04 | P1 |
| FR-v4.10-14 | TC-v4.10-18 / TC-v4.10-19 | TC-v4.10-20 | OPT-04 | P1 |
| FR-v4.10-15 | TC-v4.10-19 / TC-v4.10-20 | TC-v4.10-20 | OPT-04 | P1 |
| FR-v4.10-16 | TC-v4.10-18 / TC-v4.10-20 | TC-v4.10-20 | OPT-04 | P1 |
| FR-v4.10-17 | TC-v4.10-22 | — | OPT-05 | P1 |
| FR-v4.10-18 | TC-v4.10-23 | — | OPT-05 | P1 |
| FR-v4.10-19 | TC-v4.10-24 | — | OPT-05 | P0 |
| FR-v4.10-20 | TC-v4.10-24 / TC-v4.10-25 | TC-v4.10-25 | OPT-05 | P0 |
| FR-v4.10-21 | TC-v4.10-25 / TC-v4.10-26 | TC-v4.10-26 | OPT-05 | P1 |
| FR-v4.10-22 | TC-v4.10-29 | — | OPT-06 | P0 |
| FR-v4.10-23 | TC-v4.10-30 | — | OPT-06 | P0 |
| FR-v4.10-24 | TC-v4.10-31 / TC-v4.10-32 | TC-v4.10-31 | OPT-06 | P0 |
| FR-v4.10-25 | TC-v4.10-33 | — | OPT-06 | P1 |
| FR-v4.10-26 | TC-v4.10-35 / TC-v4.10-36 | TC-v4.10-36 | OPT-07 | P1 |
| FR-v4.10-27 | TC-v4.10-37 | TC-v4.10-37 | OPT-07 | P1 |
| FR-v4.10-28 | TC-v4.10-38 | — | OPT-07 | P1 |
| FR-v4.10-29 | TC-v4.10-39 | — | OPT-07 | P2 |
| FR-v4.10-30 | TC-v4.10-40 | — | OPT-07 | P1 |
| FR-v4.10-31 | TC-v4.10-42 | TC-v4.10-46 | OPT-08 | P0 |
| FR-v4.10-32 | TC-v4.10-43 | TC-v4.10-46 | OPT-08 | P0 |
| FR-v4.10-33 | TC-v4.10-44 / TC-v4.10-45 | TC-v4.10-46 | OPT-08 | P0 |
| FR-v4.10-34 | TC-v4.10-45 / TC-v4.10-46 | TC-v4.10-46 | OPT-08 | P0 |
| FR-v4.10-35 | TC-v4.10-48 | — | 全部 | P1 |
| FR-v4.10-36 | TC-v4.10-49 | — | 全部 | P2 |
| FR-v4.10-37 | TC-v4.10-50 | — | 全部 | P1 |
| FR-v4.10-38 | TC-v4.10-51 / TC-v4.10-52 | — | 全部 | P0 |

**统计**：FR 38 条 / TC 52 条 / 反例 TC 10 条（`NEG` 明细见 §4）/ P0 FR 17 条对应 TC 并集 25 条。四个数字均由 §2 矩阵与 §4 明细机器实算得出，不是估算。

---

## 3. 独立裁判方案（不接受实现方自证）

| # | 判定对象 | 被测方自述（不可采信） | 独立裁判 | 判据 |
|---|---|---|---|---|
| J1 | 四列导入正确性 | 导入器返回的 `successCount` | 独立 Python 脚本用 openpyxl 读源 xlsx 逐列计数，与 `db_exec` 查库计数对拍 | 两处条数逐列相等，diff = 0 |
| J2 | 补导幂等 | 导入批次接口返回 `replayed=true` | 独立 SQL 在补导前后各查 `test_case` 总数、`case_version` 总数 | 差值均为 0 |
| J3 | 设置菜单一致性 | 前端页面渲染结果 | 独立脚本序列化 `SETTINGS_NAV` 配置与两模式路由表，逐项比对 | 6 项名称与顺序 diff = 0 |
| J4 | 模板明细一致性 | `TemplateCard` 显示的数字 | 独立脚本对拍 `GET /api/global/templates/{id}` 响应与 `project_template_item` SQL 分组计数 | 四类计数 diff = 0 |
| J5 | 事务完整性 | `from-template` 返回 201 | 独立脚本在建项目失败注入后查 `project` 及 4 张关联表 | 残留记录 = 0 |
| J6 | 权限有效性 | 前端菜单显隐 | 独立 HTTP 客户端以四角色 token 直发 6 个设置页请求，记录状态码 | 24 格与能力矩阵逐格一致，越权 2 例落 403 |
| J7 | 密钥不泄漏 | 后端声称「已脱敏」 | 独立 Python 逐字段扫描响应 JSON 的键名黑名单与 `keyMasked` 前缀 | 明文口令命中 0；不对整段响应查 `sk-` |

> J1~J7 的脚本入 `tools/` 或 `scripts/`，**不留 `/tmp`**；输入固定 fixture，输出结构化 JSON 落 `docs/evidence/opt-20261111/independent/`。

---

## 4. 用例设计（52 条）

> 列说明：`层级` 见 §0.6；`形态` 为证据文件类型；`NEG` 表示反例（期望判据红）。所有 TC 当前状态 `未执行`。

### 4.1 OPT-01 设置树统一（TC-v4.10-01 / TC-v4.10-02 / TC-v4.10-03 / TC-v4.10-04 / TC-v4.10-05）

| 编号 | 覆盖 FR | 场景与判据 | 层级 | 形态 | 反例 |
|---|---|---|---|---|---|
| TC-v4.10-01 | FR-v4.10-01 / FR-v4.10-04 | 序列化 `SETTINGS_NAV`，两模式 6 项名称与顺序 diff = 0；`zh-CN` / `en-US` 各 6 个文案键齐全 | L1 | `settings-nav.json` | — |
| TC-v4.10-02 | FR-v4.10-02 | 6 路由 × 2 准入角色共 12 次导航，`ui_mode=global` 保持率 100%、URL 不回 `/workbench` | L2 | `router-guard.spec.ts` 输出 | — |
| TC-v4.10-03 | FR-v4.10-03 | 全局模式改平台配置 1 处，项目模式读回相等；DOM 中 `.global-settings-card` 元素数 = 0 | L3 | `settings-consistency.json` | — |
| TC-v4.10-04 | FR-v4.10-02 | 真实点击 6 个设置项并录 URL 与模式；**CDP 未恢复 → NOT-RUN，不计 PASS** | L5 | `shots/settings-*.png` | — |
| TC-v4.10-05 | FR-v4.10-02 / FR-v4.10-03 | `NEG`：engineer 与 viewer 请求全局模式应被拒；把守卫两判据之一临时去掉后定向断言必须红 | L6 | `negative/guard-injection.log` | NEG |

### 4.2 OPT-02 四列补录与用例详情（TC-v4.10-06 ~ 12）

| 编号 | 覆盖 FR | 场景与判据 | 层级 | 形态 | 反例 |
|---|---|---|---|---|---|
| TC-v4.10-06 | FR-v4.10-05 | `check_import_header.py` 对账参考 xlsx 14 列，命中 14；4 个新别名在源码中可检索 | L1 | `import-header.txt` | — |
| TC-v4.10-07 | FR-v4.10-06 | 补导后 `test_case` 总数仍 730、新增 `case_version` 0；重复提交返回同一批次 | L3 | `reimport.json` | — |
| TC-v4.10-08 | FR-v4.10-07 / 08 / 09 | 公共 / 项目 / 同步副本三条入口详情字段集 diff = 0；14 列可见 ≥ 12 项；空白单元格 0；副本显示 `external_id` + `sync_job.id` + `local_override` | L2 | `case-detail-contract.json` | — |
| TC-v4.10-09 | FR-v4.10-06 | J1 + J2 对拍：源 xlsx 四列非空行数与 DB 非空条数逐列相等；补导前后总数差 0 | L4 | `independent/import-diff.json` | — |
| TC-v4.10-10 | FR-v4.10-05 / 06 | `NEG`：K1 预演闸门——在预演库制造 `content_sha` 漂移，`sync/preview` 出现非预期 UPDATE 时判据必须红；备份还原后复跑绿 | L6 | `negative/k1-preview.log` | NEG |
| TC-v4.10-11 | FR-v4.10-04 / 05 / 35 | 新增中文同步补 `en-US`；`check_i18n.py` 缺失 0；`check_import_header.py` 在别名缺失时返回非 0 | L7 | `i18n.txt` | — |
| TC-v4.10-12 | FR-v4.10-08 / 09 | 公共库详情 URL 复制后直接打开可达；详情响应逐字段无 `secret` / 明文口令（J7） | L3 | `case-detail-secret.json` | — |

### 4.3 OPT-03 顶栏循环切换（TC-v4.10-13 ~ 16）

| 编号 | 覆盖 FR | 场景与判据 | 层级 | 形态 | 反例 |
|---|---|---|---|---|---|
| TC-v4.10-13 | FR-v4.10-10 | 连点 4 次状态序列严格 `system → light → dark → system`；刷新后保持 | L1 | `theme-cycle.spec.ts` 输出 | — |
| TC-v4.10-14 | FR-v4.10-11 | 连点 2 次回初态；全站文案切换无需刷新；新增存储键 0 | L1 | `locale-cycle.spec.ts` 输出 | — |
| TC-v4.10-15 | FR-v4.10-12 | 两按钮 `aria-label` 存在且 Tab 可达；账户菜单控件与快捷钮状态双向同步 | L2 | `a11y.json` | — |
| TC-v4.10-16 | FR-v4.10-12 | 1440 / 1024 两档顶栏重叠元素 0、横向溢出 0；720p 档 **NOT-RUN（CDP）**，注明解除条件 | L6 | `shots/header-*.png` + 纯 CSS 断言 | — |

### 4.4 OPT-04 资产详情（TC-v4.10-17 ~ 21）

| 编号 | 覆盖 FR | 场景与判据 | 层级 | 形态 | 反例 |
|---|---|---|---|---|---|
| TC-v4.10-17 | FR-v4.10-13 | `/g/assets/:id` 与 `/assets/:id` 可达且 URL 可分享；三分区与 `当前测试环境` 12 字段逐项对照，缺失标「未采集」、静默空白 0 | L2 | `asset-detail-map.json` | — |
| TC-v4.10-18 | FR-v4.10-14 / 16 | 选凭据 + 二次确认后采集，四层证据（`collectedAt` / 命令原文 / 原始输出 / 结构化值）齐全率 100%；J7 逐字段密钥扫描命中 0 | L3 | `collect-evidence.json` | — |
| TC-v4.10-19 | FR-v4.10-14 / 15 | 采集四态枚举覆盖；`scripts/host-inventory-contract.sh` 与 `tools/check_host_inventory.py` 通过 | L3 | `host-inventory-contract.log` | — |
| TC-v4.10-20 | FR-v4.10-14 / 15 / 16 | `NEG`：采集失败或中途取消后，上次成功快照仍在 `device_host_spec`（清空数 0）；注入明文口令后 J7 判据必须红 | L6 | `negative/collect-failure.log` | NEG |
| TC-v4.10-21 | FR-v4.10-38 | `gates.sh` 回归：红项不增加，唯一红仍为 13-browser_regression | L7 | `gates-opt04.log` | — |

### 4.5 OPT-05 模板卡片与工作台（TC-v4.10-22 ~ 28）

| 编号 | 覆盖 FR | 场景与判据 | 层级 | 形态 | 反例 |
|---|---|---|---|---|---|
| TC-v4.10-22 | FR-v4.10-17 | `TemplateCard.vue` 与 `ProjectCard.vue` 样式 token diff = 0；卡片 6 项信息齐全 | L2 | `template-card-tokens.json` | — |
| TC-v4.10-23 | FR-v4.10-18 | J4 对拍：卡片四类计数与 `GET /api/global/templates/{id}` 明细及 SQL 分组计数三方相等 | L3 | `independent/template-diff.json` | — |
| TC-v4.10-24 | FR-v4.10-19 / 20 | `/g/templates/:id` 增 / 删 / 改后刷新不丢；`itemCount=0` 提交被拒且落库 0 条、错误文案可见 | L2 | `template-workbench.spec.ts` | — |
| TC-v4.10-25 | FR-v4.10-20 / 21 | `NEG`：空模板提交返回 4xx；`builtin` 模板改写返回 4xx 且 items 不变 | L6 | `negative/template-guard.log` | NEG |
| TC-v4.10-26 | FR-v4.10-21 | `NEG`：尝试从模板工作台写回公共用例本体，调用数 = 0；注入一条写回后定向断言必须红 | L6 | `negative/k10-writeback.log` | NEG |
| TC-v4.10-27 | FR-v4.10-17 / 18 | `count===4` 硬校验残留核实（K6）；若残留，先改校验器再动内容，有实测输出 | L4 | `builtin-check.txt` | — |
| TC-v4.10-28 | FR-v4.10-38 | 模板域回归：`TemplateControllerTest` + `check_release_docs.py v4.9.2` 保持 PASS | L7 | `gates-opt05.log` | — |

### 4.6 OPT-06 双向模板链（TC-v4.10-29 ~ 34）

| 编号 | 覆盖 FR | 场景与判据 | 层级 | 形态 | 反例 |
|---|---|---|---|---|---|
| TC-v4.10-29 | FR-v4.10-22 | `POST /api/projects/from-template` 单事务完成 4 类动作；DTO 默认 `mode=copy` 有单测锁死；`ProjectService` 编码逻辑复用（新写编码规则 0 处） | L1 | `from-template.spec.java` 输出 | — |
| TC-v4.10-30 | FR-v4.10-23 | 新项目用例 / 用例集 / 场景 / 资产引用数与模板明细逐项相等；`mode=copy` 下改公共库后项目 `content_sha` 不变 | L3 | `from-template-detail.json` | — |
| TC-v4.10-31 | FR-v4.10-24 | `NEG` + J5：事务中途注入失败，独立脚本查 `project` 及 4 张关联表残留 = 0 | L6 | `negative/tx-rollback.json` | NEG |
| TC-v4.10-32 | FR-v4.10-24 | `NEG`：同一 `templateId` + `code` 重复提交返回 409，项目总数只 +1 | L6 | `duplicate-409.log` | NEG |
| TC-v4.10-33 | FR-v4.10-25 | 「存为模板」确认弹层列出三类计数；生成模板明细与原项目 `test_set_item` / `project_asset` 逐项相等；两条链 `audit_log` 含操作者与来源 id | L3 | `save-as-template.json` | — |
| TC-v4.10-34 | FR-v4.10-38 | 建项目既有链路回归（不带 `templateId` 的 `POST /api/projects` 行为不变） | L7 | `reg-project-create.log` | — |

### 4.7 OPT-07 分类与组集（TC-v4.10-35 ~ 41）

| 编号 | 覆盖 FR | 场景与判据 | 层级 | 形态 | 反例 |
|---|---|---|---|---|---|
| TC-v4.10-35 | FR-v4.10-26 | 项目用例设一级 / 二级分类，二级必属所选一级；三种筛选（全选一级 / 全选二级 / 跨表交集）结果可复现 | L1 | `category-tree.spec.ts` | — |
| TC-v4.10-36 | FR-v4.10-26 | `NEG`：非法父子组合提交被拒；删有引用的一级分类返回 4xx 且显示引用数 | L6 | `negative/category-constraint.log` | NEG |
| TC-v4.10-37 | FR-v4.10-27 | `NEG`：三步迁移 + down→up 演练；制造非唯一匹配后该行不写入并标人工复核，脏数据 0 | L4 | `migration/up-down-up.log` | NEG |
| TC-v4.10-38 | FR-v4.10-28 | 按分类筛选批量加入，`test_set_item` 新增条数 = 筛选条数；每条 `item_source='category_filter'` 可审计 | L3 | `category-filter-join.json` | — |
| TC-v4.10-39 | FR-v4.10-29 | `/scenarios` 路由可达、创建 / 编组 / 删除可用；场景仍限一层（FR-v4.10-29 降级，授权人：大师）有显式说明 | L2 | `scenario-crud.spec.ts` | — |
| TC-v4.10-40 | FR-v4.10-30 | 730 条按 32 个一级分类分组计数之和 = 730；CSV 导出每行含 `external_id` 且行数 = 筛选条数 | L4 | `category-sum-730.csv` | — |
| TC-v4.10-41 | FR-v4.10-38 | 导入 / 导出 / 同步三链回归；`check_release_docs.py v4.9.2` 不退化 | L7 | `gates-opt07.log` | — |

### 4.8 OPT-08 菜单权限（TC-v4.10-42 ~ 47）

| 编号 | 覆盖 FR | 场景与判据 | 层级 | 形态 | 反例 |
|---|---|---|---|---|---|
| TC-v4.10-42 | FR-v4.10-31 | 6 个二级项全部声明 `capability`，`about` / `changelog` 显式 `public`；四角色 × 6 项 = 24 格与接口返回逐格一致 | L1 | `capability-matrix-24.json` | — |
| TC-v4.10-43 | FR-v4.10-32 | `grep -rc 'ROLE_CAPABILITIES' frontend/src` 合计 = 0；改一处 `PermissionMatrix` 后前端代码 diff 无硬编码能力字符串 | L2 | `no-hardcoded.txt` | — |
| TC-v4.10-44 | FR-v4.10-33 | J6 独立 HTTP 客户端：6 设置页 × 4 角色 = 24 次请求状态码矩阵；engineer `/admin` 与 viewer `/settings/ai` 落 `/403` | L3 | `independent/url-guard-matrix.json` | — |
| TC-v4.10-45 | FR-v4.10-33 / 34 | 首屏能力加载早于导航渲染，「先全显再收起」闪烁 0 次；真实浏览器点击取证（**CDP 未恢复 → NOT-RUN**） | L5 | `shots/menu-*.png` | — |
| TC-v4.10-46 | FR-v4.10-31 / 32 / 33 / 34 | `NEG` 五步：备份 `PermissionMatrix.java` → 临时移除 admin 的 `ai:config` → 定向判据必须红 → **备份拷贝还原（不用 `git checkout`）** → 复跑绿 | L6 | `negative/capability-injection/` | NEG |
| TC-v4.10-47 | FR-v4.10-38 | 全量权限回归 + `gates.sh`，`FAILS` 不升到 2 | L7 | `gates-opt08.log` | — |

### 4.9 横向 / 基线（TC-v4.10-48 ~ 52）

| 编号 | 覆盖 FR | 场景与判据 | 层级 | 形态 | 反例 |
|---|---|---|---|---|---|
| TC-v4.10-48 | FR-v4.10-35 | 三份文档跑 `check_md_tables.py`（显式传路径）全 `[PASS]`；`check_i18n.py` 缺失 0；`check_api_prefix.py` 命中 0；全仓无 U+FFFD | L7 | `doc-check.log` | — |
| TC-v4.10-49 | FR-v4.10-36 | 730 条库量下详情接口 P95 < 500 ms、模板明细聚合 P95 < 800 ms；顶栏快捷钮除持久化 PUT 外新增请求 0；`check_perf_budget.py` 不新增红项 | L6 | `perf-p95.json` | — |
| TC-v4.10-50 | FR-v4.10-37 | 4 类审计事件（模板新建 / 编辑、从模板建项目、存为模板、触发采集）各抽 1 条，操作者 / 来源 id / 时间戳字段齐全率 100% | L4 | `audit-sample.json` | — |
| TC-v4.10-51 | FR-v4.10-38 | 后端 `mvn -q -o -B test -Dtest='!*IT'` 0 失败；前端 `vue-tsc` 0 错误 + `vitest run` 0 失败；`gates.sh` 的 `FAILS ≤ 1` | L7 | `baseline-gates.log` | — |
| TC-v4.10-52 | FR-v4.10-38 | `check_release_docs.py v4.9.2` 保持 `[PASS] FR=8 TC=10 FAIL 0 WARN 0`；6 个 `REG-*` 回归集读数与基线一致 | L7 | `release-docs-v492.log` | — |

---

## 5. 故障注入清单（9 项，红态必须留证）

| # | 注入对象 | 注入方式 | 定向判据（必须红） | 还原方式 | 关联 TC |
|---|---|---|---|---|---|
| F1 | 导入别名表 | 临时移除「A端命令」别名 | `check_import_header.py` 命中 < 14 且退出非 0 | 备份拷贝还原 | TC-v4.10-10 |
| F2 | 补导内容指纹 | 预演库制造 `content_sha` 漂移 | `sync/preview` 出现非预期 UPDATE | `mysqldump` 备份还原 | TC-v4.10-10 |
| F3 | 全局守卫 | 去掉「角色准入」判据 | engineer 可进全局模式，定向断言变红 | 备份拷贝还原 | TC-v4.10-05 |
| F4 | 空模板提交 | 提交 `items=[]` | 若返回 2xx 或落库 1 条则判据红 | 删除探针记录 | TC-v4.10-25 |
| F5 | 模板写回 | 注入一条写回公共用例本体的调用 | 调用数 ≠ 0 则判据红 | 备份拷贝还原 | TC-v4.10-26 |
| F6 | from-template 事务 | 在写 `project_asset` 后抛异常 | 独立查库发现残留项目则判据红 | 事务回滚 + 清理探针 | TC-v4.10-31 |
| F7 | 重复建项目 | 同 `templateId` + `code` 连发两次 | 若第二次非 409 或总数 +2 则判据红 | 删除探针项目 | TC-v4.10-32 |
| F8 | 分类约束 | 构造孤儿二级 / 删有引用的一级 | 若提交成功或删除成功则判据红 | 备份拷贝还原 | TC-v4.10-36 / 37 |
| F9 | 能力矩阵 | 临时移除 admin 的 `ai:config` | 若 ADMIN 仍可见 AI 助手入口则判据红 | **备份拷贝还原，不用 `git checkout`** | TC-v4.10-46 |

> 每项注入都必须按「备份 → 注入 → 判据红 → 还原 → 复跑绿」五步留痕；缺任何一步视为该 TC 未完成。

---

## 6. 专项细则

### 6.1 幂等与一致性专项

| 项 | 判据 | 独立裁判 |
|---|---|---|
| 补导重复提交 | `test_case` 总数差 0、`case_version` 总数差 0、返回同一批次 | J2 |
| from-template 重复提交 | 409、项目总数只 +1 | J5 |
| 两模式设置数据 | 改一处另一处读回相等 | J3 |
| 三类用例详情 | 字段集 diff = 0 | 独立序列化比对 |
| 模板卡片与明细 | 四类计数三方相等 | J4 |

### 6.2 权限与安全专项

| 项 | 判据 |
|---|---|
| 24 格矩阵 | 四角色 × 6 页状态码与能力矩阵逐格一致 |
| 越权直达 | engineer `/admin`、viewer `/settings/ai` 均落 `/403` |
| 服务端逐页盘点 | 6 个设置页 × 4 角色 = 24 次，未拦截项 0 |
| 密钥泄漏 | 逐字段校验 `keyMasked` 前缀；字段名黑名单命中 0；**不对整段响应查 `sk-`** |
| 负向验证 | F9 五步齐全 |

### 6.3 视觉与可访问性专项

| 项 | 判据 | 状态 |
|---|---|---|
| 顶栏 1440 / 1024 | 重叠元素 0、横向溢出 0 | 可测（纯 CSS 断言） |
| 顶栏 720p | 同上 | **NOT-RUN（CDP，与 `L-V25-02` 同源）** |
| `aria-label` + Tab | 两按钮齐全 | 可测 |
| 双语 | 新增键缺失 0 | 可测 |
| 真实浏览器点击 | 6 个设置项 + 菜单权限显隐 | **NOT-RUN（CDP）** |

### 6.4 迁移与回滚专项

| 项 | 判据 |
|---|---|
| 迁移成对 | 每个 `V11_4x` 有配对 down |
| up→down→up | 三次 `db-audit.sh` 均 PASS、表数符合 `schema-contract.json` |
| 存量回填 | 非唯一匹配写入 0、脏数据 0 |
| 备份可用性 | 备份文件可 `head` 出 CREATE 语句 |

---

## 7. 回归集与 CI 编排

| 集 | 内容 | 触发 | 期望 |
|---|---|---|---|
| REG-01 | 用例导入 / 导出 / 同步三链 | OPT-02 / 07 后 | 730 条口径不变 |
| REG-02 | 项目创建 / 卡片 / 向导 | OPT-05 / 06 后 | 既有建项目链路行为不变 |
| REG-03 | 权限与路由守卫 | OPT-01 / 08 后 | 越权 0、`FAILS` 不升 |
| REG-04 | 资产采集与凭据 | OPT-04 后 | `host-inventory-contract.sh` PASS |
| REG-05 | i18n / 主题 / 顶栏 | OPT-03 后 | `check_i18n.py` 缺失 0 |
| REG-06 | 文档机检与版本四处一致 | 每包 | `check_release_docs.py v4.9.2` PASS |

**门禁命令（每包必跑，与 DEV-PLAN §13.1 一致）**

```bash
cd ~/robot-autotest
set -a; . ~/.config/robot-autotest/env; set +a
cd backend && mvn -q -o -B test -Dtest='!*IT' && cd ..
cd frontend && npx vue-tsc --noEmit && npx vitest run && cd ..
python3 tools/check_i18n.py
python3 tools/check_api_prefix.py
python3 tools/check_release_docs.py v4.9.2
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/release/v4.10.0/TEST-PLAN.md`
bash scripts/gates.sh
```

---

## 8. 证据形态与目录

| 类型 | 路径 | 要求 |
|---|---|---|
| 命令原文 + 原始输出 | `docs/evidence/opt-20261111/<包号>/raw/` | 逐条可复现，禁止只贴结论 |
| 结构化结果 | `.../independent/*.json`、`*.csv` | J1~J7 独立裁判输出 |
| 截图 | `.../shots/` | 含时间与版本；CDP 未恢复时目录为空并出 `NOT-RUN.md` |
| 负向验证 | `.../negative/` | F1~F9 五步留痕 |
| 迁移演练 | `.../migration/` | up / down / up 三次 `db-audit` 输出 |
| 门禁日志 | `.../gates-*.log` | `gates.sh` 完整输出，不只 tail |
| 测试报告 | ⑥ `05-TEST-REPORT.md` | 每条 TC 的 PASS / FAIL / BLOCKED + 证据路径 + 失败归属 |

**证据纪律**：`output.xml` metadata 六必填（`CASE_ID` / `RUN_ID` / `QA_PROJECT` / `DUT` / `LINK` / `SW_BUILD`）键全大写、值 ≤ 40 字符、`RUN_ID` 键名不可改、**不得写凭据**；RF `--dryrun` 语法错退出码仍 0，须双判输出有无 `[ ERROR ]`。

---

## 9. 失败判据与归属

| 情形 | 判定 | 归属 |
|---|---|---|
| 判据未达但环境可用 | FAIL | 代码缺陷 / 用例缺陷，当场区分 |
| CDP `:9222` 不可用导致浏览器用例跑不了 | **BLOCKED（写明原因与解除条件）** | 环境问题，不计 PASS |
| 真库 `DB_PASSWORD` 缺失导致 down→up 跑不了 | **BLOCKED** | 环境问题 |
| 样本 xlsx 不可读 | **BLOCKED** | 环境问题；改用本地 openpyxl 只读解析须在报告注明差异 |
| 判定脚本自身报错 | FAIL（不得静默跳过） | 工具缺陷，先修工具 |
| 负向验证注入后判据未变红 | **FAIL（严重）** | 说明判据无效或权限/幂等未生效 |
| 机检 `check_release_docs.py v4.9.2` 从 PASS 变 FAIL | FAIL | 基线退化 |

---

## 10. 已知限制与遗留

> **编号口径**：本节用 `R-49-xx`（计划期已知限制 / 风险）；`05-TEST-REPORT.md` §7 的遗留**缺陷**用
> `L-49-xx`。两套编号不混用（首版两处都写 `L-49-xx` 且含义不同，2026-11-11 回写时拆分）。

| # | 项 | 状态 | 解除条件 |
|---|---|---|---|
| R-49-01 | Chrome CDP `:9222` 被大师取消，门禁第 13 步 `FAILS=1` 未消（整轮 30/32 绿，另一红为 `check_tables` 存量债） | NOT-RUN | 大师书面恢复通道；学徒不擅自重启 |
| R-49-02 | 窄屏 720p 顶栏取证 | NOT-RUN | 同 R-49-01，与既有 `L-V25-02` 同源 |
| R-49-03 | 真库 down→up 演练 | 条件执行 | `DB_PASSWORD` 与预演库可用（`V11_43` 曾在真库手工回退，`V11_44` 未演练 down） |
| R-49-04 | 8001 平台已吸收能力清单（上游 V6） | 未验 | 技术债议题，不阻断本批 |
| R-49-05 | 上游 V1（四列丢弃）/ V2（卡片被切回）仍标 `[推断]` | 待实测 | OPT-02 / OPT-01 的 TC 结果回写评估报告 |
| R-49-06 | G4 / G5 / G7 / G8 与 G9~G14 未判过，整仓「不得据以称发版」 | 沿袭 | 大师按 `docs/release/README.md` 时点判定 |

**回写补充（2026-11-11）**：计划期假设「内置模板内容为空、从模板建项目只能建空项目」已被推翻——
`V11_44` 补齐模板 2/3/4 内容后实测建出 20 用例 / 1 用例集 / 1 场景 / 2 资产的真实项目，
同时暴露并修复了 `copyCase` 的 `Unknown column 'category'` 缺陷（见 `05-TEST-REPORT.md` L-49-08）。

---

## 11. 准出判据（汇总）

1. 52 条 TC 100% 有结果，17 条 P0 FR 对应 TC 零 FAIL / 零 BLOCKED；
2. J1~J7 独立裁判全部完成，9 项故障注入红态证据齐全；
3. 6 个 `REG-*` 回归集读数与基线一致，`gates.sh` 的 `FAILS ≤ 1` 且唯一红仍为 13-browser_regression；
4. 所有 `NOT-RUN` / `BLOCKED` 项写明原因、解除条件与责任人，未被计入 PASS；
5. 证据目录可点开、命令可复现、结构化结果与报告数字对得上；
6. ⑦ 文档回写完成：三份文档修订记录齐全，PRD 的 FR 状态落定，评估报告的 `[推断]` / `[假设]` 按实测重标（被推翻的保留原文并划掉）；
7. **本批仍不触发发版、不落版本号**；是否并入正式版本由大师另行拍板。

---

## 12. 修订记录

| 版本 | 日期 | 变更 | 依据 |
|---|---|---|---|
| v1.0 | 2026-11-11 | 首版。按 `RA-PRD-2026-049` 的 38 条 FR 建 52 条 TC 全覆盖矩阵；定 L1~L7 分层、7 类独立裁判、9 项故障注入、失败判据、证据形态与准出汇总 | 大师 2026-11-11 指令 + D2「三波全覆盖」拍板 |
| v1.1 | 2026-11-11 | ⑥⑦ 回写：① 已知限制段编号 `L-49-xx` → `R-49-xx`，与报告里同前缀的缺陷编号拆开；② 新增附录 C（实测读数 PASS 29 / PARTIAL 14 / NOT-RUN 9 + 三处偏差）；③ 附录 B 的产物路径随「`docs/plan` 并入 release 单源」更新；④ 附录 A 标注为计划期快照 | 大师 2026-11-11 拍板 D3/D4 + `05-TEST-REPORT.md` v1.2 |

---

## 附录 A 测试用例状态表（计划期快照）

> 下表是 ⑤ 计划冻结时的状态快照，**保留不改**；实际结果见 `05-TEST-REPORT.md` §2 与本文附录 C。

| 区间 | 条数 | 当前状态 | 结果回填列 |
|---|---|---|---|
| TC-v4.10-01 / TC-v4.10-02 / TC-v4.10-03 / TC-v4.10-04 / TC-v4.10-05 | 5 | 全部「未执行」 | PASS / FAIL / BLOCKED + 证据路径 |
| TC-v4.10-06 ~ 12 | 7 | 全部「未执行」 | 同上 |
| TC-v4.10-13 ~ 16 | 4 | 全部「未执行」 | 同上 |
| TC-v4.10-17 ~ 21 | 5 | 全部「未执行」 | 同上 |
| TC-v4.10-22 ~ 28 | 7 | 全部「未执行」 | 同上 |
| TC-v4.10-29 ~ 34 | 6 | 全部「未执行」 | 同上 |
| TC-v4.10-35 ~ 41 | 7 | 全部「未执行」 | 同上 |
| TC-v4.10-42 ~ 47 | 6 | 全部「未执行」 | 同上 |
| TC-v4.10-48 ~ 52 | 5 | 全部「未执行」 | 同上 |
| **合计** | **52** | **未执行 52 / PASS 0 / FAIL 0 / BLOCKED 0** | — |

## 附录 B 三份文档与流程对照

| 流程环节 | 产物 | 状态 |
|---|---|---|
| ① 调研 | `docs/release/v4.10.0/00-RESEARCH.md` | 已完成（`docs/plan` 已按大师 2026-11-11 拍板 D4 并入 release 单源） |
| ② 评估 | `docs/release/v4.10.0/01-ASSESSMENT.md` | 已完成（修订记录见该文 §11） |
| ③ PRD | `docs/release/v4.10.0/PRD.md` | 已完成（`§12` 逐条状态已落定） |
| ④ 开发计划 | `docs/release/v4.10.0/DEV-PLAN.md` | 已完成（`§12` 偏差记录已补） |
| ⑤ 测试计划 | 本文 | 已完成（`附录 C` 已回写实测与偏差） |
| ⑥ 测试执行 | `05-TEST-REPORT.md` + `docs/evidence/opt-20261111/` | **已完成**（PASS 29 / PARTIAL 14 / NOT-RUN 9 / FAIL 0） |
| ⑦ 文档回写 | 四份各自修订记录 + 本附录 | **已完成** |

## 附录 C 实测结论回写（⑦ 环节产物）

| 项 | 计划 | 实测 |
|---|---|---|
| TC 总数 | 52 | 52（无增删） |
| PASS | — | 29 |
| PARTIAL | — | 14 |
| NOT-RUN | — | 9 |
| FAIL | 0（期望） | 0 |

**与计划的偏差（3 处）**：

| # | 计划怎么写 | 实测怎么变 | 处置 |
|---|---|---|---|
| 1 | 整轮 32 步门禁作为准出项（§11 第 3 条） | 已整轮执行：**30 PASS / 2 FAIL**（13 浏览器回归=CDP 取消；06 lint=`check_tables` 存量债） | 5 条 L7 用例记 PARTIAL，不宣称「门禁全绿」；准出第 3 条「`FAILS ≤ 1`」实测为 2，未满足 |
| 2 | 假设内置模板内容为空，从模板建项目只能建空项目 | 拍板补齐内容后实测建出 20 用例 / 1 用例集 / 1 场景 / 2 资产，并暴露修复 `copyCase` 缺陷 | §10 回写补充 + 缺陷 L-49-08 |
| 3 | 计划期 9 项故障注入 | 执行 3 项（401 / 400 / 409）通过，6 项需真实设备或浏览器未执行 | 记 NOT-RUN 并写解除条件 |

逐条结果与证据见 `05-TEST-REPORT.md`。

