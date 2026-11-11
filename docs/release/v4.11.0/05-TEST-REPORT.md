# v4.11.0 测试报告（⑥ 测试执行）

| 项 | 内容 |
|---|---|
| 编号 | `RA-TR-v4.11-001` |
| 版本 | `4.11.0`（`VERSION=4.11.0`；代码提交 `695626f` + 样式令牌修复 `b214a9b`，产物与交付包按 `b214a9b` 重建） |
| 执行时间 | 2026-11-11 20:40 ~ 21:35（GMT+8；含两轮门禁复跑与 1 轮样式令牌修复） |
| 执行环境 | WSL `Ubuntu-24.04` ext4 副本 `/home/evergreen/robot-autotest`；后端 `:8081`（systemd user）、入口 `:8001`；MySQL / 库 `robot`（业务表 71）、集成测试库 `robot_it` |
| 上游 | `TEST-PLAN.md`（49 条 TC）、`PRD.md` v1.2（30 条 FR）、`DEV-PLAN.md` v1.0（33 项 WBS） |
| 证据根目录 | `docs/evidence/v4.11.0/`（`raw/` 原始输出、`api/` 接口响应、`ui/` 说明） |
| 结论 | **可测项全绿**：49 条 TC 中 PASS 41 / PARTIAL 6 / NOT-RUN 2 / **FAIL 0**。2 条 NOT-RUN 均为浏览器通道（CDP `:9222`）被令关闭所致；6 条 PARTIAL 的缺口与排期见 §6 |

## 1. 结果总览

| 结果 | 条数 | 含义 |
|---|---|---|
| PASS | 41 | 有可复现命令输出或落盘证据，判据全部满足 |
| PARTIAL | 6 | 判据部分满足，未满足部分写明缺口与排期，不计 PASS |
| NOT-RUN | 2 | 未执行，写明原因与解除条件（均为 CDP 关闭） |
| FAIL | 0 | — |

**分层实测读数**

| 层 | 手段 | 实测结果 |
|---|---|---|
| L1 单元 | JUnit 5 / Vitest | 后端 **1385 例 / 0 失败 / 4 按环境跳过**（`raw/mvn-unit.log` 读数 `UNIT_RC=0`）；前端 **100 文件 / 737 例全过** |
| L2 组件 | Vitest + DOM 断言 | `vue-tsc --noEmit` **0 错误**；`eslint` **0 错误**；本版新增 9 个 spec 共 **64 例** |
| L3 接口 | 真实 HTTP（登录后携 token） | **12 项**断言落盘到 `api/*.json`（版本、菜单能力、越权三态、卡牌归属、批次、预览两态、build-info） |
| L4 数据 | 真库 `robot_it` | `ProjectTemplateCopyIT` **2 例**、`GlobalCasePreviewForceIT` **1 例**；`robot` 库 force 前后快照逐行一致 |
| L5 机检 | `tools/*.py` | 菜单 / 表格 / i18n / 版本 / 发布文档 / 样式令牌 / CSS 变量 / 对比度 **8 类全绿** |
| L6 门禁 | `scripts/gates.sh` | **32 步 31 PASS / FAIL 1**（唯一失败为第 13 步浏览器回归，CDP `:9222` 按令关闭；读数见 §5） |

## 2. 用例结果（A 组：菜单 / 顶栏 / 执行计划，TC-v4.11-01 ~ 33）

| TC | FR | 层级 | 结果 | 证据与说明 |
|---|---|---|---|---|
| TC-v4.11-01 | FR-v4.11-01 | UT | PASS | `navigation.spec.ts`「5 个一级组、14 个叶子，每组至少 2 项」；机检同口径 `[PASS] 全局一级 5 组 / 14 叶` |
| TC-v4.11-02 | FR-v4.11-01 | UT | PASS | `git diff -- AppNav.vue` 仅 2 处 `activeGroupKey` 调用行 + 注释，模板与展开逻辑零改动（`raw/git-diff-appnav.txt`） |
| TC-v4.11-03 | FR-v4.11-02 | UT | PASS | `navigation.spec.ts`「activeGroupKey 按传入的树判定：全局树认 global-settings，项目树认 execution」 |
| TC-v4.11-04 | FR-v4.11-02 | 人工 | NOT-RUN | CDP `:9222` 已按令关闭，无截图。同判据（组头自动展开）由 TC-03 的 UT 覆盖；解除条件：CDP 恢复后补 `ui/g-settings-expanded.png` |
| TC-v4.11-05 | FR-v4.11-03 | UT | PASS | `navigation.spec.ts`「低权限账号过滤后：全局「系统设置」只剩 public 两项，组仍保留」+「四角色菜单快照」 |
| TC-v4.11-06 | FR-v4.11-03 | API | PARTIAL | 实测 `getCapabilities`（engineer）= **空集**；全局模式准入为 admin/pm（P-08=A），engineer 不可达 `/g/**`，「与侧栏逐条比对」无业务意义。已改为核对 admin 能力集覆盖侧栏所需能力（`menu-capabilities-admin.json`：`settings:read` / `ai:config` / `user:write` / `audit:read`）。缺口排期 v4.12 与准入策略一并复评 |
| TC-v4.11-07 | FR-v4.11-04 | UT | PASS | `navigation.spec.ts`「全局「系统设置」组 6 项顺序与 SETTINGS_NAV 逐项一致」 |
| TC-v4.11-08 | FR-v4.11-05 | UT | PARTIAL | 「详情页点亮父项」机制在**项目侧**已锁（`navigation.spec.ts`「exclude 只作用于模板前缀，详情页仍归列表」）；**全局侧**（`/g/templates/2` → 内置模板）无专项断言。缺口排期 v4.12 补 1 例 |
| TC-v4.11-09 | FR-v4.11-06 | 机检 | PASS | `check-navigation.log`：层级 ≤2 / 全局 5 组 14 叶 / 项目 7 组 29 叶 / 同 path query 两两不同，四条全 PASS |
| TC-v4.11-10 | FR-v4.11-25 | 机检 | PASS | `check-navigation-selftest.log`：注入「模板组删到 1 叶」后内部判据输出 `[FAIL] 全局：分组 global-templates 只有 1 个二级项`，自检壳打印 `[PASS] 自检按预期判红`。**退出码语义见 §7 第 3 条** |
| TC-v4.11-11 | FR-v4.11-26 | UT | PASS | `GlobalCasesView.spec.ts`「裸路径只渲染用例网格，不请求批次」/「?view=imports 只渲染批次网格，不请求用例列表」 |
| TC-v4.11-12 | FR-v4.11-26 | API | PASS | `api/global-import-batches.json`：HTTP 200，`total=2 ≥ 1` |
| TC-v4.11-13 | FR-v4.11-27 | UT | PASS | `GlobalAssetsView.spec.ts`「裸路径 replace 到 ?tab=devices」/「非法 tab（?tab=links）规范化为 devices」 |
| TC-v4.11-14 | FR-v4.11-28 | UT | PASS | `GlobalTemplatesView.spec.ts`「内置行只有启停/复制」/「自定义行 4 个按钮」+ 两 kind 计数之和等于全量 |
| TC-v4.11-15 | FR-v4.11-29 | IT/UT | PASS | `ProjectCardServiceTest` 9 例全过。**本版曾红**：`ownAndJoinedIgnoreAdminFallback` 在第二段断言前没切 `TenantContext`，当前用户仍是 ENGINEER(15) 而 gamma 的 `owner_id` 是 ADMIN(17)，`own` 恒 false——属用例假断言，已修 |
| TC-v4.11-16 | FR-v4.11-29 | API | PARTIAL | 后端 `GET /api/projects/cards` **没有 `scope` 参数**（本版按「前端过滤」实现，未加参数）。替代取证：`api/project-cards-all.json` 实测 8 张卡牌，`own\|\|joined` 命中 **7** 张，严格小于全量；`myRole=admin` 有 7 张 → 证明不能拿 `myRole` 当归属判据。缺口排期：若后续要在服务端分页，再补 `scope` 参数与断言 |
| TC-v4.11-17 | FR-v4.11-07 | UT | PASS | `AppHeader.spec.ts`「工程师没有全局模式，因此不渲染模式段（三段）」 |
| TC-v4.11-18 | FR-v4.11-07 | API | PASS | 计划里的 `/api/admin/users` **不存在**（真实路径见 §7 第 2 条）：实测 `GET /api/users` → engineer **403**、viewer **403**、admin **200** |
| TC-v4.11-19 | FR-v4.11-08 | UT | PASS | `navigation.spec.ts`「v4.11.0：项目「系统设置」只留用户管理 + 关于」+「项目侧「系统设置」恰 2 项，且都是 public」 |
| TC-v4.11-20 | FR-v4.11-08 | 人工 | PASS（降级） | `raw/legacy-routes.txt`：`/settings`、`/settings/users`、`/settings/ai`、`/admin`、`/admin/audit`、`/changelog`、`/execution-plans` 七条 **全部 http=200 且含 `id="app"` 挂载点**，无 404；渲染正确性由 `router/index.ts` 的路由定义与各视图 spec 保证，**无截图**（CDP 关闭） |
| TC-v4.11-21 | FR-v4.11-30 | UT | PASS | `SettingsUsersView.spec.ts` 8 例：只列 `own\|\|joined`、页面无表单/输入框/增删改按钮、失败不残列表 |
| TC-v4.11-22 | FR-v4.11-30 | API/机检 | PASS | `git diff HEAD -- backend/src/main` 中新增的 `@Post/@Put/@Patch/@DeleteMapping` **0 处**；后端写端点零新增 |
| TC-v4.11-23 | FR-v4.11-09 | UT | PASS | `AppHeader.spec.ts`「AI 开启时 9 枚控件，关闭时 8 枚」+「Tab 顺序与视觉顺序一致」 |
| TC-v4.11-24 | FR-v4.11-10 | UT | PASS | `AppHeader.spec.ts`「账户段是 header-actions 内最后一个 data-seg，且 header-quick 容器已移除」 |
| TC-v4.11-25 | FR-v4.11-09 | UT | PASS | 同文件「管理员按「模式 → 上下文 → 工具 → 账户」排布，且恰好 2 条分隔线」 |
| TC-v4.11-26 | FR-v4.11-11 | 人工 | PARTIAL | 无真机截图（CDP 关闭）。判据降级为 UT 双证据：`AppHeader.spec.ts`「760px 断点存在，且工具段在窄屏收紧间距」（源码定长窗口）+「顶栏与右侧容器在空间不足时换行」（真实读取 `main.css` 匹配 `flex-wrap: wrap`）。**jsdom 的 `scrollWidth` 恒 0，探针本身不作判据**。缺口排期：CDP 恢复后补 `ui/header-760.png` |
| TC-v4.11-27 | FR-v4.11-12 | UT | PASS | `navigation.spec.ts`「执行与报告合并：组内 7 项顺序固定」+「合并组内恰好 1 条分区线」 |
| TC-v4.11-28 | FR-v4.11-12 | API | PASS | `api/execution-runs-viewer.json` 与 `execution-runs-nobody.json` 均 **403**（无 `execution:read` 无权限）；engineer 同路径 200 |
| TC-v4.11-29 | FR-v4.11-13 | UT | PASS | `ExecutionPlansView.spec.ts`「勾用例集展开成 caseIds，而不是把 set id 当用例 id 提交」/「再勾第二个集合时并入」。判据微调见 §7 第 4 条 |
| TC-v4.11-30 | FR-v4.11-14 | UT | PASS | 同文件「未填参数时请求体不带这些键（不是传 0 / 空串）」/「填了参数才带该键」 |
| TC-v4.11-31 | FR-v4.11-15 | UT | PASS | 同文件「安全预检 blocked 时：列出用例码，且按钮禁用」/「预检接口失败不阻塞发起」 |
| TC-v4.11-32 | FR-v4.11-16 | API | PARTIAL | `api/execution-runs-ep.json` 查询 `projectId=93` 返回 **0 行**，且 `build-info.selfCheck.executor=NOT_INSTALLED` → 无法构造真实 `EP-` 运行。UT 已锁「`runRequestId` 以 `EP-` 开头且 ≤64 字符」（39 字符 < `VARCHAR(64)`）。缺口排期：执行器就绪后补 1 次真实发起 |
| TC-v4.11-33 | FR-v4.11-16 | UT | PASS | `ExecutionPlansView.spec.ts`「只保留 EP- 前缀的运行，其他来源不混进来」 |

## 3. 用例结果（B 组：遗留结转 / 治理，TC-v4.11-34 ~ 49）

| TC | FR | 层级 | 结果 | 证据与说明 |
|---|---|---|---|---|
| TC-v4.11-34 | FR-v4.11-17 | IT | PASS | `ProjectTemplateCopyIT` **2 例通过**（真库 `robot_it`）：场景 `case_set_ids` 非空且指向本项目 `test_set.id` |
| TC-v4.11-35 | FR-v4.11-18 | API/IT | PASS | API：同一文件重复预览返回**同一 batchId=4**，`new=226 / upd=0 / unch=0 / dep=730`，**计数和 956 = total 956**；`force=1` 前后 `import_batch` 状态与 `import_item` 行数 **逐行完全一致**（`raw/batch-force-diff.txt` 为空 diff）。`replayed=true` 分支由 `GlobalCasePreviewForceIT` 在真库覆盖（该分支要求库内存在同 sha 的 COMMITTED 批次，生产库当前无此文件，不为此导入 226 条用例） |
| TC-v4.11-36 | FR-v4.11-18 | API | PARTIAL | 「篡改一行后带 `force=1` 该行应为 UPDATE」**无法在真实链路构造**：篡改行内容必然改变文件 sha256，批次匹配随之失效，走不到「同 sha 重算」分支（详见 §7 第 5 条）。已用两条替代判据锁死该风险：① IT 逐行比对 force 前后 `import_item` 不变；② API 实测 force 前后库快照全等。缺口排期：在 `robot_it` 里造数据补测 |
| TC-v4.11-37 | FR-v4.11-19 | 机检 | PASS | `check-tables.log`：**55 处**数据网格全部走壳，原生 `<table>` 仅 3 个白名单文件 |
| TC-v4.11-38 | FR-v4.11-19 | 机检 | PASS | `raw/lint.log` 末行 `[lint] PASS`（`LINT_RC=0`），含新增的菜单机检项；**首轮曾红 4 项**，见 §7 第 6 条 |
| TC-v4.11-39 | FR-v4.11-20 | UT | PASS | `CategoryTree.spec.ts` **9 例**、`TemplateWorkbenchView.spec.ts` **7 例**（均超计划下限 5 / 4）。判据「保存后计数刷新」调整为「保存后清单内容变化」——工作台无计数列 |
| TC-v4.11-40 | FR-v4.11-21 | 人工 | NOT-RUN | CDP `:9222` 关闭，3 条浏览器级用例（R6 / R8 / 45）未执行。解除条件：CDP 恢复并留 `ui/cdp-probe.txt` |
| TC-v4.11-41 | FR-v4.11-22 | 机检 | PASS | `check-i18n.log`：`[RESULT] 通过：键集相等 / 无硬编码中文 / 无同值漏译 / 键引用完整 / 枚举全覆盖`（zh-CN 与 en-US 各 2334 键） |
| TC-v4.11-42 | FR-v4.11-22 | UT/机检 | PASS | 键集相等（2334 = 2334）+ 受控词表 27 个值全部有键；`menu.*` / `settingsUsers.*` 新键中英成对。**未做逐串 EN 渲染断言**（机检已覆盖漏译，渲染风险低） |
| TC-v4.11-43 | FR-v4.11-23 | 机检 | PASS | `check-version.log`：`INFO VERSION=4.11.0 build-info=4.11.0` → `VERSION_CONSISTENCY PASS (0 项)`（含运行期 build-info 与入口托管 CHANGELOG 两项在线校验） |
| TC-v4.11-44 | FR-v4.11-23 | 机检 | PASS | `cmp CHANGELOG.md frontend/public/CHANGELOG.md` 退出码 0（两份逐字节一致） |
| TC-v4.11-45 | FR-v4.11-23 | API | PASS | `api/build-info.json`：`version=4.11.0`、`gitCommit=` 代码提交短 sha（最终 `b214a9b`）、`versionSource=file`。注：本报告的回写提交晚于代码提交，不改代码，故关于页 commit 与代码提交同源 |
| TC-v4.11-46 | FR-v4.11-24 | 机检 | PASS | `RELEASE-NOTES.md` 首表含「**本版 0 迁移、0 处 DDL**」；门禁第 12 步 `db_audit` PASS |
| TC-v4.11-47 | — | 门禁 | PASS | `scripts/gates.sh` **32 步：31 PASS / FAIL 1**，唯一失败为第 13 步 `browser_regression`（CDP `:9222` 按令取消，按既有口径不计 FAIL）；两轮复跑读数见 §5 |
| TC-v4.11-48 | — | 门禁 | PASS | `check_release_docs.py v4.11.0`：`[PASS] v4.11.0 FR=30 TC=49`，FAIL 0 / WARN 0 |
| TC-v4.11-49 | — | 机检 | PASS | 门禁第 30 步 `delivery_bundle` PASS；交付包 `robot-autotest-4.11.0-b214a9b-linux-x64.tar.gz`（357 文件），过期包已移入 `releases/archive/` |

## 4. 证据索引

| 文件 | 内容 |
|---|---|
| `raw/mvn-unit.log` | 后端全量单测读数（1385 例 / 0 失败） |
| `raw/lint.log` | `scripts/lint.sh` 全量输出（含菜单机检、i18n、对比度、令牌） |
| `raw/check-navigation.log` / `check-navigation-selftest.log` | 菜单四条不变量 PASS / 破坏性自检按预期判红 |
| `raw/check-tables.log` / `check-i18n.log` / `check-version.log` / `check-release-docs.log` | 其余机检原始输出 |
| `raw/batch-before-force.txt` / `batch-after-force.txt` / `batch-force-diff.txt` | force 预览前后库快照与 diff（空 = 未落库） |
| `raw/legacy-routes.txt` | 七条路由 HTTP 码与 SPA 挂载点 |
| `raw/git-diff-appnav.txt` | AppNav 改动范围（仅 2 处调用行） |
| `api/*.json` | 12 份接口响应原件（含 403/400 反例） |
| `raw/gates-final.log` | 最终轮 32 步门禁逐步读数（每步原始日志在 `docs/evidence/gates/logs/`） |
| `ui/` | 说明文件；截图因 CDP 关闭为空 |

## 5. 门禁读数（32 步）

| 轮次 | 结果 | 说明 |
|---|---|---|
| 第 1 轮 | 28 PASS / **FAIL 4** | 06-lint（样式令牌 4 项违规）、07-release_docs（缺本报告）、13-browser_regression（CDP 关闭）、15-css_vars（`--surface` 悬空） |
| 修复 | — | `--surface` → `--panel`；`280px/160px/980px` → 白名单档位 `240/180/900`；输出本报告；按最终提交 `b214a9b` 重建 jar / dist / 交付包并重启后端 |
| 第 2 轮 | **31 PASS / FAIL 1** | 第 06 / 07 / 15 三步全部转绿；**唯一失败是第 13 步浏览器回归**（CDP `:9222` 已按令取消，按既有口径不计入本版 FAIL） |

> 第 1 轮的 4 项红不是环境噪声，第 06 / 15 两步抓的是本版新写的真实缺陷（引用了不存在的 CSS 变量、写了未登记的 px）——`--surface` 写错不会报错，属性会静默回落，SVG `fill` 直接变黑。这正是把机检放在门禁里的意义。

## 6. 遗留与排期

| # | 缺口 | 来源 TC | 影响 | 排期 |
|---|---|---|---|---|
| L-51-01 | 全局详情页（`/g/templates/2`、`/g/cases/:id`）点亮父项无专项断言 | TC-08 | 回归时可能漏测 | v4.12（补 2 例 UT） |
| L-51-02 | 卡片接口无 `scope` 参数，「我参与的」由前端过滤 | TC-16 | 项目多时前端需全量取数后过滤 | v4.12 评估是否服务端分页 |
| L-51-03 | 无真实 `EP-` 发起的运行记录 | TC-32 | 来源标记只在单测层验证 | 执行器就绪后补 1 次真实发起 |
| L-51-04 | 篡改行后 `force` 重算的分支未在真实库构造 | TC-36 | 已有两条替代判据 | v4.12 在 `robot_it` 造数据补测 |
| L-51-05 | 窄屏与 3 条浏览器级用例无截图 | TC-26 / TC-40 | 视觉与交互靠单测兜底 | CDP 恢复后补 |
| L-51-06 | engineer 能力集为空，全局模式不可达 | TC-06 | 与 P-08=A 一致，非缺陷 | 与准入策略一并复评 |

## 7. 判据与计划不一致的修订说明（⑦ 回写）

本版实施中发现 6 处「计划写的判据与真实实现/真实可测性不符」。**判据一改就必须在这里留痕**，不能悄悄改计划文档当没发生：

| # | 计划原文 | 实际情况 | 处置 |
|---|---|---|---|
| 1 | TC-16 断言 `GET /api/projects/cards?scope=mine` 条数严格小于全量 | 该端点**没有** `scope` 参数（`ProjectCardController` 只接 `window` / `current`），本版按前端 `own\|\|joined` 过滤实现 | TC 记为 PARTIAL，改用卡牌字段计数替代取证；是否加参数进 v4.12 评估 |
| 2 | TC-18 断言 `GET /api/admin/users` 返回 4xx | 该路径**不存在**（用户管理在 `UserController`，类级 `@RequestMapping("/api")`，真实路径 `GET /api/users`） | 改用真实路径取证：engineer 403 / viewer 403 / admin 200 |
| 3 | TC-10 断言「`--self-test` 必须非 0 退出」 | 自检**壳**退出 0 表示「按预期拦住」；被判红的是内部判据（`[FAIL] 分组只有 1 个二级项`） | 判据改为「日志出现 `[PASS] 自检按预期判红` 且内部输出 `[FAIL]`」，两个退出码语义不同不可混 |
| 4 | TC-29 断言「选 20 条 → 取消 2 条 → 18」 | 单测按「勾用例集展开成 caseIds / 再勾并入 / 计数 +1」断言，未写死 20→18（样本用例集条数随环境变） | 判据改为「计数与已选 id 集合一致」，避免把环境数据写进断言 |
| 5 | TC-36 断言「篡改一行 `content_sha` 后 `force=1` 该行 UPDATE」 | 篡改内容必然改变文件 sha256，批次匹配失效，走不到「同 sha 重算」分支 | 记 PARTIAL；改用 IT 逐行比对 + API 库快照两条替代判据 |
| 6 | TC-38 期望 lint 24 项全绿 | 首轮实测 4 项红：`ExecutionPlansView.vue` 引用不存在的 `--surface`、三处 px（280/160/980）不在白名单 | 已修（`--panel` / 240 / 180 / 900），第二轮 `[lint] PASS` |
| 7 | TC-39 期望「保存后计数刷新」 | 模板工作台无计数列，保存后判据是「清单内容变化」 | 判据调整，断言仍锁住保存结果 |

## 8. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-11 | 首版：49 条 TC 结果落定（PASS 41 / PARTIAL 6 / NOT-RUN 2 / FAIL 0）；含两轮门禁读数、6 条遗留排期、7 条判据修订留痕 |
