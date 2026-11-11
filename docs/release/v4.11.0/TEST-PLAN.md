# v4.11.0 测试计划（TEST-PLAN）

| 项 | 内容 |
|---|---|
| 编号 | `RA-TESTPLAN-v4.11-001` |
| 版本 | `v4.11.0` / 文档 **v1.0** |
| 基线 | `v4.10.0` / commit `fd2311e` |
| 上游 | `PRD.md` v1.2（30 条 FR）、`DEV-PLAN.md` v1.0（33 项 WBS） |
| TC 总数 | **36 条**（UT 16 / IT 3 / API 6 / 机检 7 / 门禁 2 / 人工 2） |
| 证据根 | `docs/evidence/v4.11.0/`（raw 原始输出 / api 接口响应 / ui 截图） |
| 状态 | 计划期 |

## 1. 策略

| 层级 | 覆盖对象 | 手段 | 判据来源 |
|---|---|---|---|
| UT | 导航树结构、选中判定、顶栏顺序、视图分支 | Vitest（`frontend/src/**/*.spec.ts`） | PRD 判据直接写成断言 |
| IT | 后端字段与复制语义 | `mvn -Pit`（真库 `robot_it`） | 库内实际行 |
| API | 端点行为、权限、幂等 | `curl` + token（`qa-e2e-admin`） | HTTP 码 + 响应体字段 |
| 机检 | 菜单不变量、表格规范、i18n、版本一致性 | `python3 tools/*.py` | 退出码 0 + 打印读数 |
| 门禁 | 全量 32 步 | `scripts/gates.sh` | 每步 PASS/FAIL 日志 |
| 人工 | 视觉与手感（窄屏、展开动画、文案） | 截图 + 走查 | 人工核对清单 |

**不接受「实现方自证」**：UT 断言必须来自 PRD 判据原文，不得为了实现方便改判据；接口证据必须落 `api/*.json`，不能只在对话里贴一行。

## 2. 环境与数据

| 项 | 值 |
|---|---|
| WSL 副本 | `~/robot-autotest`（ext4，唯一真源） |
| 后端 | `:8081`，`robot-autotest-backend.service`（systemd user） |
| 入口 | `:8001`（node 静态托管 + 反代） |
| 库 | `robot`（生产口径）、`robot_it`（IT 专用，名称必须 `_it` 结尾） |
| 账号 | `qa-e2e-admin`（环境变量 `QA_PASSWORD`）、ENGINEER 账号用于准入负向 |
| 样本项目 | `projectId=93`（v4.10.0 由模板 3 建出，20 用例 / 1 用例集 / 1 场景） |
| 样本用例集 | 项目 93 的模板用例集（含 20 条用例，执行计划选择区用） |
| 阻断项 | Chrome CDP `:9222` 当前取消 → 浏览器级 TC 一律 NOT-RUN 并注明 |

## 3. 测试用例（A~D 组）

| TC | FR | 层级 | 步骤与判据 | 证据形态 |
|---|---|---|---|---|
| TC-v4.11-01 | FR-v4.11-01 | UT | 渲染 `GLOBAL_NAVIGATION`：断言 5 个组头、每组 `children.length >= 2`、合计 14 叶 | `raw/vitest-navigation.log` |
| TC-v4.11-02 | FR-v4.11-01 | UT | 断言 `AppNav.vue` 未被本版修改（`git diff fd2311e -- AppNav.vue` 仅含 `activeGroupKey` 调用行） | `raw/git-diff-appnav.txt` |
| TC-v4.11-03 | FR-v4.11-02 | UT | `activeGroupKey(route, GLOBAL_NAVIGATION)` 对 `/g/settings/ai` 返回 `global-settings`；对 `/plans/graph` 传 `NAVIGATION` 返回 `execution` | `raw/vitest-navigation.log` |
| TC-v4.11-04 | FR-v4.11-02 | 人工 | `/g/settings/ai` 刷新后「系统设置」组自动展开且组头带 `has-active` | `ui/g-settings-expanded.png` |
| TC-v4.11-05 | FR-v4.11-03 | UT | 能力集仅 `public` 时过滤全局树：`系统设置`组恰 2 项（关于 / 版本迭代）；含 `user:write` 时为 6 项 | `raw/vitest-navigation.log` |
| TC-v4.11-06 | FR-v4.11-03 | API | 用 ENGINEER token 取 `GET /api/me/menu-capabilities`，与全局侧栏可见项逐条比对（脚本断言集合相等） | `api/menu-capabilities-engineer.json` |
| TC-v4.11-07 | FR-v4.11-04 | UT | 全局「系统设置」组 children 的 `key` 序列 === `SETTINGS_NAV.map(i => i.key)` | `raw/vitest-navigation.log` |
| TC-v4.11-08 | FR-v4.11-05 | UT | `isLeafActive`：`/g/templates/2` 对「内置模板」为 true；手动折叠后切详情，展开态保持 false | `raw/vitest-appnav.log` |
| TC-v4.11-09 | FR-v4.11-06 | 机检 | `python3 tools/check_navigation.py` 退出码 0，打印「层级 ≤2 / 全局一级 5 / 每组 ≥2 / query 互异」四条 PASS | `raw/check-navigation.log` |
| TC-v4.11-10 | FR-v4.11-25 | 机检 | 破坏性自检：临时把某组删到 1 叶，脚本必须非 0 退出（`--self-test`） | `raw/check-navigation-selftest.log` |
| TC-v4.11-11 | FR-v4.11-26 | UT | `?view=imports` 时用例列表不渲染（`table[data-testid="cases-grid"]` 不存在）、批次表存在；无 query 时相反 | `raw/vitest-globalcases.log` |
| TC-v4.11-12 | FR-v4.11-26 | API | `GET /api/global/cases/import/batches?page=1&size=5` 200，`total >= 1` | `api/global-import-batches.json` |
| TC-v4.11-13 | FR-v4.11-27 | UT | 挂载于 `/g/assets?tab=credentials` 时激活页签为凭据；`?tab=links`（非法）触发 `router.replace` 到 `devices` | `raw/vitest-globalassets.log` |
| TC-v4.11-14 | FR-v4.11-28 | UT | `?kind=builtin` 下每行 `builtin === true` 且无编辑/删除按钮；`custom` 相反；两 kind 条数之和 === 不带 kind 的总数 | `raw/vitest-globaltemplates.log` |
| TC-v4.11-15 | FR-v4.11-29 | IT | `ProjectCardServiceTest`：admin 非成员 → `own=false, joined=false`；owner → `own=true`；普通成员 → `joined=true` | `raw/mvn-projectcard.log` |
| TC-v4.11-16 | FR-v4.11-29 | API | admin 取 `/api/projects/cards`：`scope=mine` 条数 **严格小于** 全量条数（至少少 1） | `api/project-cards-all.json`、`api/project-cards-mine.json` |
| TC-v4.11-17 | FR-v4.11-07 | UT | ENGINEER 角色 `canGlobal === false`（不渲染 `[data-seg="mode"]`） | `raw/vitest-appheader.log` |
| TC-v4.11-18 | FR-v4.11-07 | API | ENGINEER token 请求 `GET /api/admin/users` 返回 4xx（越权后端兜底） | `api/admin-users-engineer.json` |
| TC-v4.11-19 | FR-v4.11-08 | UT | 项目树一级 7 组；「系统设置」组 children 恰 2 项且顺序 `settings-me → settings-about` | `raw/vitest-navigation.log` |
| TC-v4.11-20 | FR-v4.11-08 | 人工 | 直访 `/settings`、`/settings/ai`、`/admin`、`/admin/audit`、`/changelog` 五条路由均非 404 非空白 | `ui/legacy-settings-routes.md`（逐条状态码与截图） |
| TC-v4.11-21 | FR-v4.11-30 | UT | `SettingsUsersView`：只渲染 1 条用户记录；无任何写操作控件（`button` 文本不含新增/停用/改角色）；关联项目条数 === 过滤后条数 | `raw/vitest-settingsusers.log` |
| TC-v4.11-22 | FR-v4.11-30 | API | 该页未新增后端端点：`git diff fd2311e -- backend/src/main` 中无 `@PostMapping/@PutMapping/@DeleteMapping` 新增 | `raw/git-diff-backend.txt` |
| TC-v4.11-23 | FR-v4.11-09 | UT | `[data-seg="tools"]` 内可聚焦控件 `aria-label` 序列 === `[检索, 通知, AI, 主题, 语言]` | `raw/vitest-appheader.log` |
| TC-v4.11-24 | FR-v4.11-10 | UT | `[data-seg="account"]` 是 `header-actions` 内最后一个 `data-seg`；`header-quick` 不存在（`queryByTestId` 为 null） | 同上 |
| TC-v4.11-25 | FR-v4.11-09 | UT | `.app-sep` 数量 === 2 | 同上 |
| TC-v4.11-26 | FR-v4.11-11 | 人工 | 760px 视口下顶栏 `scrollWidth <= clientWidth`；Tab 遍历顺序为 模式 → 上下文 → 工具 → 账户 | `ui/header-760.png` |
| TC-v4.11-27 | FR-v4.11-12 | UT | 侧栏「执行与报告」7 叶，顺序 `执行计划 → 运行记录 → 测试计划 → 执行节点 → 报告 → 报告模板 → 外部报告`，分隔线 1 条 | `raw/vitest-navigation.log` |
| TC-v4.11-28 | FR-v4.11-12 | API | 无 `execution:read` 的 token 请求 `/api/execution/runs?projectId=93` 返回 4xx；页面路由 `meta.capability` 使其落 403 | `api/execution-runs-viewer.json` |
| TC-v4.11-29 | FR-v4.11-13 | UT | 选含 20 条用例的用例集 → 计数 20；取消 2 条 → 18；提交请求体 `caseIds.length === 18` | `raw/vitest-execplans.log` |
| TC-v4.11-30 | FR-v4.11-14 | UT | 缺省提交：请求体仅含 `caseIds` 与 `runRequestId`（无 `concurrency` 等键）；并发改 4 后仅多 `concurrency` | 同上 |
| TC-v4.11-31 | FR-v4.11-15 | UT | 勾选 `safetyLevel=LX` 用例 → 发起按钮 `disabled` 且提示含用例编号；取消后恢复；发起成功跳 `/runs/:id` | 同上 |
| TC-v4.11-32 | FR-v4.11-16 | API | 由执行计划页发起 1 次后，`GET /api/execution/runs?projectId=93` 中该 run 的 `runRequestId` 以 `EP-` 开头 | `api/execution-runs-ep.json` |
| TC-v4.11-33 | FR-v4.11-16 | UT | 用例页发起的记录（`runRequestId` 不以 `EP-` 开头）不出现在本页列表 | `raw/vitest-execplans.log` |

## 4. 测试用例（E~F 组）

| TC | FR | 层级 | 步骤与判据 | 证据形态 |
|---|---|---|---|---|
| TC-v4.11-34 | FR-v4.11-17 | IT | 模板 3 建项目后，场景 `case_set_ids` 非空且与该项目 `test_set.id` 一致 | `raw/mvn-templatecopy.log` |
| TC-v4.11-35 | FR-v4.11-18 | API | 同一 xlsx 二次预览不带 `force` → `replayed=true`；带 `force=1` → `replayed=false` 且 `newCount+updateCount+unchangedCount+deprecateCount === totalRows` | `api/import-preview-replay.json`、`api/import-preview-force.json` |
| TC-v4.11-36 | FR-v4.11-18 | API | 负向：篡改一行的 `content_sha` 后带 `force=1` 预览，该行 `action=UPDATE`（不得为 `UNCHANGED`） | `api/import-preview-force-tampered.json` |
| TC-v4.11-37 | FR-v4.11-19 | 机检 | `python3 tools/check_tables.py` 退出码 0，原生 `<table>` 违规 0 处 | `raw/check-tables.log` |
| TC-v4.11-38 | FR-v4.11-19 | 机检 | `bash scripts/lint.sh` 读数 24 项全绿（第 06 项转绿） | `raw/lint.log` |
| TC-v4.11-39 | FR-v4.11-20 | UT | `CategoryTree.spec.ts` 用例数 ≥5 且「一级选中连带二级」断言存在；`TemplateWorkbenchView.spec.ts` ≥4 且含「保存后计数刷新」 | `raw/vitest-new-specs.log` |
| TC-v4.11-40 | FR-v4.11-21 | 人工 | CDP `:9222` 探活：可用则跑 R6/R8/45 三条并留截图；不可用记 NOT-RUN + 原因 | `ui/cdp-probe.txt` |
| TC-v4.11-41 | FR-v4.11-22 | 机检 | `python3 tools/check_i18n.py` 退出码 0 | `raw/check-i18n.log` |
| TC-v4.11-42 | FR-v4.11-22 | UT | 切 EN 后侧栏新增项显示英文：`t('menu.executionPlans', 'en')` 非中文、`t('settingsUsers.title', 'en')` 非中文 | `raw/vitest-i18n.log` |
| TC-v4.11-43 | FR-v4.11-23 | 机检 | `python3 tools/check_version_consistency.py` PASS（0 项） | `raw/check-version.log` |
| TC-v4.11-44 | FR-v4.11-23 | 机检 | `cmp CHANGELOG.md frontend/public/CHANGELOG.md` 退出码 0 | `raw/cmp-changelog.txt` |
| TC-v4.11-45 | FR-v4.11-23 | API | 运行期 `GET /api/system/build-info` 的 `version === "4.11.0"`、`commit` === 当前 HEAD 短 sha | `api/build-info.json` |
| TC-v4.11-46 | FR-v4.11-24 | 机检 | `RELEASE-NOTES.md` 含「本版 0 迁移」字样；`db-audit` PASS | `raw/db-audit.log` |
| TC-v4.11-47 | — | 门禁 | `bash scripts/gates.sh` 32 步：除第 13 步（CDP 取消）外全部 PASS；第 06 步本轮应转绿 | `raw/gates-*.log` |
| TC-v4.11-48 | — | 门禁 | `python3 tools/check_release_docs.py v4.11.0` FAIL 0，`FR=30 TC>=36` | `raw/check-release-docs.log` |
| TC-v4.11-49 | — | 机检 | `python3 tools/check_delivery_bundle.py` PASS（版本 4.11.0 与 `VERSION` 一致） | `raw/check-delivery.log` |

## 5. 进入与退出判据

**进入**：DEV-PLAN 完成且 M1 里程碑达成（全局两级树可渲染）。

**退出（发版前必须全满足）**：

1. 36 条计划 TC + 13 条追加 TC（共 49）全部有结果，无「未记录」；
2. FAIL = 0；PARTIAL 必须写明缺口与排期；NOT-RUN 仅允许 CDP 依赖项，且注明原因；
3. 门禁 32 步仅允许第 13 步红；第 06 步若仍红则本版不宣称「清债完成」；
4. `check_version_consistency` / `check_delivery_bundle` / `check_release_docs` 三连绿；
5. ⑤→⑥→⑦ 文档回写完成（PRD FR 状态落定）。

## 6. 计划期风险

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| R-50-01 | 命令面板仍只索引项目树 | 全局项搜不到 | 本版范围外，记入 v4.12 待办 |
| R-50-02 | ~~设置去重致 engineer 丢入口~~ | 已消解 | 项目侧保留「关于」 |
| R-50-03 | 顶栏断言漏改致 vitest 红 | 阻塞门禁 | 断言按 `aria-label` 序列而非纯计数 |
| R-50-04 | 执行计划与测试计划混淆 | 用户误用 | 页面副标题明示「即时发起」 |
| R-50-05 | `force` 预览影响存量补录链 | 数据风险 | 重算分支不落库、不改批次状态 |
| R-50-06 | 两模式设置项不再 1:1 | 既有断言必红 | 先改断言为「2 项均有同源视图」 |
| R-50-07 | 卡片接口加字段影响工作台 | 回归 | 只加字段不改语义 + 三情形单测 |
| R-50-08 | 同路径多 query 互亮 | 选中态错 | 机检锁 query 互异 + 每组唯一默认值 |
| R-50-09 | `DataTable` 化改动 6 处表格 | 交互回归 | 三视图既有 spec 全跑；`MIN_TABLES` 下限同步上调 |
| R-50-10 | `RunRecord` 加字段波及 5 处构造 | 编译/序列化 | 编译即保护；接口层断 `runRequestId` 字段存在 |

## 7. 证据目录规划

```
docs/evidence/v4.11.0/
├── raw/     # 单测、机检、门禁、编译原始输出
├── api/     # 每个端点的请求与响应 JSON
└── ui/      # 截图与走查记录（CDP 不可用时为文字说明）
```

## 8. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-11 | 首版：49 条 TC（UT 16 / IT 3 / API 6 / 机检 7 / 门禁 3 / 人工 4 / 追加 10），覆盖 PRD 30 条 FR；含 10 条计划期风险与证据目录规划 |
