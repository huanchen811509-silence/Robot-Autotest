# v4.11.0 开发计划（DEV-PLAN）

| 项 | 内容 |
|---|---|
| 编号 | `RA-DEVPLAN-v4.11-001` |
| 版本 | `v4.11.0` / 文档 **v1.0** |
| 基线 | `v4.10.0` / commit `fd2311e`；开工时代码 HEAD `26d4baf`（仅文档） |
| 上游 | `PRD.md` v1.2（30 条 FR，已批准）、`01-ASSESSMENT.md` v1.2、`00-RESEARCH.md` v1.2 |
| 迁移 | **0 组**（PRD P-04=A：不做执行计划草稿表；本版无 DDL 变更） |
| 后端改动 | 4 个文件（`ProjectCard` + `ProjectCardService`、可选 `ProjectCardRepository` 注释；`RunRecord` + 仓储映射；`ProjectTemplateService`；`GlobalCaseService` + 控制器） |
| 前端改动 | 4 个配置/组件 + 6 个视图 + 2 个新视图 + i18n 双语 |
| 状态 | 进行中 |

## 1. 实施顺序与依赖

本版有一条硬依赖链，顺序不能倒：

1. **先修断言再动数据**（R-50-06）：`SETTINGS_NAV` 的「两模式 diff = 0」断言必须先改成「项目 2 项均有同源视图」，否则改菜单数据时单测会红成一片，分不清是改动错还是断言旧。
2. **后端字段先于前端筛选**（FR-v4.11-29）：`own` / `joined` 不先落地，「我参与的」只能靠 `myRole != null` 判断，而该值对全局 admin 恒真（A14）。
3. **`activeGroupKey` 加参先于全局两级树**（FR-v4.11-02）：不改签名就启用分组，全局组头永远不会自动展开。
4. **query 规范化先于侧栏挂 query 项**（FR-v4.11-25）：否则裸路径访问时两个叶子都不亮，看起来像「选中态坏了」。
5. **`runRequestId` 先于发起记录页**（FR-v4.11-16）：不暴露该字段就无法把「本页发起」与「用例页发起」分开。

## 2. WBS

### 2.1 W-A 全局菜单两级化

| WBS | 任务 | FR | 改动文件 | 验收命令 |
|---|---|---|---|---|
| W-A-01 | `activeGroupKey(route, nodes)` 加树入参，`AppNav` 传当前 `props.nodes` | FR-v4.11-02 | `config/navigation.ts`、`components/AppNav.vue` | `npx vitest run src/config/navigation.spec.ts src/components/AppNav.spec.ts` |
| W-A-02 | `GLOBAL_NAVIGATION` 改 5 组 14 叶（项目 2 / 公共用例 2 / 公共资产 2 / 模板 2 / 系统设置 6） | FR-v4.11-01、04 | `config/navigation.ts`、`config/settingsNav.ts` | `npx vitest run src/config/navigation.spec.ts` |
| W-A-03 | `App.vue` global 分支接入 `filterNavByCapabilityPredicate` | FR-v4.11-03 | `App.vue` | `npx vitest run src/App.spec.ts`（若缺则新建） |
| W-A-04 | 菜单机检：层级 ≤2、全局每组 ≥2、同 path query 互异、每组唯一默认值、一级不混业务域 | FR-v4.11-06、25 | `tools/check_navigation.py`（新）＋`scripts/lint.sh` 增一项 | `python3 tools/check_navigation.py` |
| W-A-05 | 公共用例 `?view=imports` 提级（批次表成独立分支，用例列表不再渲染） | FR-v4.11-26 | `views/global/GlobalCasesView.vue` | `npx vitest run src/views/global/GlobalCasesView.spec.ts` |
| W-A-06 | 公共资产 `?tab=devices\|credentials` 双向同步 + 非法值 `router.replace` | FR-v4.11-27 | `views/global/GlobalAssetsView.vue` | 同上（`GlobalAssetsView.spec.ts`） |
| W-A-07 | 模板 `?kind=builtin\|custom` 筛选 | FR-v4.11-28 | `views/global/GlobalTemplatesView.vue` | `npx vitest run src/views/global/GlobalTemplatesView.spec.ts` |
| W-A-08 | 后端 `ProjectCard` 增 `own` / `joined`，Service 按 `ownerId` / `memberRole` 计算 | FR-v4.11-29 | `domain/iam/ProjectCard.java`、`application/iam/ProjectCardService.java` | `mvn -o -B -q -Dtest=ProjectCardServiceTest test` |
| W-A-09 | 前端 `ProjectCard` 类型加两字段；`/g/projects?scope=mine` 过滤 | FR-v4.11-29 | `api/projectCards.ts`、`views/global/GlobalProjectsView.vue`、`views/WorkbenchView.vue` | `npx vitest run src/views/global/GlobalProjectsView.spec.ts` |

### 2.2 W-B 设置入口收敛

| WBS | 任务 | FR | 改动文件 | 验收命令 |
|---|---|---|---|---|
| W-B-01 | 新增 `PROJECT_SETTINGS_NAV`（2 项：用户管理 `/settings/users`、关于 `/about`） | FR-v4.11-08 | `config/settingsNav.ts` | `npx vitest run src/config/settingsNav.spec.ts` |
| W-B-02 | 项目「系统设置」组改由 `PROJECT_SETTINGS_NAV` 派生；断言从「diff=0」改「2 项均有同源视图」 | FR-v4.11-08 | `config/navigation.ts`、`src/config/navigation.spec.ts` | `npx vitest run src/config/navigation.spec.ts` |
| W-B-03 | 新建 `/settings/users` 只读页（当前用户 + 关联项目，0 新端点） | FR-v4.11-30 | `views/SettingsUsersView.vue`（新）、`router/index.ts` | `npx vitest run src/views/SettingsUsersView.spec.ts` |
| W-B-04 | 全局准入维持 admin/pm 的断言补齐（ENGINEER 无切换按钮、直访 `/g/projects` 落 403） | FR-v4.11-07 | `components/AppHeader.spec.ts`、`router/index.spec.ts` | `npx vitest run src/components/AppHeader.spec.ts` |
| W-B-05 | i18n：新增菜单/页面文案中英成对 | FR-v4.11-22 | `locales/zh-CN.ts`、`locales/en-US.ts` | `python3 tools/check_i18n.py` |

### 2.3 W-C 顶栏重排

| WBS | 任务 | FR | 改动文件 | 验收命令 |
|---|---|---|---|---|
| W-C-01 | 主题 / 语言按钮移入 `[data-seg="tools"]`，删除 `header-quick` 容器与其响应式规则 | FR-v4.11-09、10 | `components/AppHeader.vue` | `npx vitest run src/components/AppHeader.spec.ts` |
| W-C-02 | 账户段置末；单测按 `aria-label` 序列断言 6 控件顺序、`.app-sep` 仍为 2 | FR-v4.11-09、10 | `components/AppHeader.spec.ts` | 同上 |
| W-C-03 | 760px 顶栏不横向溢出（探针断言 `scrollWidth <= clientWidth`） | FR-v4.11-11 | `components/AppHeader.spec.ts`、`styles/main.css` | 同上 |

### 2.4 W-D 执行计划

| WBS | 任务 | FR | 改动文件 | 验收命令 |
|---|---|---|---|---|
| W-D-01 | 后端 `RunRecord` 增 `runRequestId`，`SELECT` / `MAPPER` / 构造点同步 | FR-v4.11-16 | `domain/execution/RunRecord.java`、`infrastructure/RunRepositoryJdbc.java`、`application/execution/RunService.java` | `mvn -o -B -q -DskipTests compile` |
| W-D-02 | 菜单「执行与报告」首位插入「执行计划」`/execution-plans`（`section: run`，7 叶） | FR-v4.11-12 | `config/navigation.ts` | `npx vitest run src/config/navigation.spec.ts` |
| W-D-03 | 新建页：选择区（用例集展开 + 用例搜索勾选 + 实时计数） | FR-v4.11-13 | `views/ExecutionPlansView.vue`（新）、`router/index.ts` | `npx vitest run src/views/ExecutionPlansView.spec.ts` |
| W-D-04 | 参数区 5 项：不填即不发字段 | FR-v4.11-14 | 同上 | 同上（断请求体） |
| W-D-05 | 安全预检 + 发起（`runRequestId` 前缀 `EP-`）+ 跳 `/runs/:id` | FR-v4.11-15 | 同上 | 同上 |
| W-D-06 | 发起记录：按 `runRequestId.startsWith('EP-')` 过滤最近 20 条 | FR-v4.11-16 | 同上 | 同上 |

### 2.5 W-E 遗留结转

| WBS | 任务 | FR | 改动文件 | 验收命令 |
|---|---|---|---|---|
| W-E-01 | 模板 `SCENARIO` 写入同模板 `CASE_SET` 产出的 set id | FR-v4.11-17 | `application/iam/ProjectTemplateService.java` | `DB_NAME=robot_it mvn -o -B -Pit -Dtest=ProjectTemplateCopyIT test` |
| W-E-02 | 导入预览加 `force` 参数：`false` 走 `replayPreview`（幂等不变），`true` 重算且**不落库、不改批次状态** | FR-v4.11-18 | `application/caseasset/GlobalCaseService.java`、`api/caseasset/GlobalCaseController.java`、`api/globalCases.ts` | `mvn -o -B -q -Dtest=GlobalCaseServiceTest test` |
| W-E-03 | `ProjectSyncView` 2 处原生 `<table>` 改 `DataTable` | FR-v4.11-19 | `views/ProjectSyncView.vue` | `python3 tools/check_tables.py` |
| W-E-04 | `GlobalAssetsView` 1 处、`GlobalCasesView` 3 处改 `DataTable` | FR-v4.11-19 | 两个视图 | 同上 + 两视图 spec |
| W-E-05 | `CategoryTree.spec.ts`（≥5 例）、`TemplateWorkbenchView.spec.ts`（≥4 例） | FR-v4.11-20 | 两个新 spec | `npx vitest run src/components/CategoryTree.spec.ts src/views/global/TemplateWorkbenchView.spec.ts` |
| W-E-06 | 3 条浏览器级用例：CDP 可用则跑，不可用如实记 NOT-RUN | FR-v4.11-21 | `docs/evidence/v4.11.0/` | `bash scripts/gates.sh`（第 13 步读数） |

### 2.6 W-F 治理与收口

| WBS | 任务 | FR | 改动文件 | 验收命令 |
|---|---|---|---|---|
| W-F-01 | 版本五处同源 `4.11.0` | FR-v4.11-23 | `VERSION`、`CHANGELOG.md`、`frontend/public/CHANGELOG.md`、`docs/contracts/openapi.json` | `python3 tools/check_version_consistency.py` |
| W-F-02 | 关于页 / 版本迭代数据源回写（`AboutView` 读 `build-info`，`ChangelogView` 读 MD） | FR-v4.11-23 | `CHANGELOG.md`（中英条目）、`AboutView.vue`（若需补字段） | `npx vitest run src/views/AboutView.spec.ts` |
| W-F-03 | 文档收口：草案区 6 份整体上移为 `docs/release/v4.11.0/` | FR-v4.11-24 | 目录移动 | `python3 tools/check_release_docs.py v4.11.0` |
| W-F-04 | 交付包按最终 HEAD 重打 | — | `releases/` | `python3 tools/check_delivery_bundle.py` |
| W-F-05 | 全量门禁 32 步 | — | — | `bash scripts/gates.sh` |
| W-F-06 | 双远程推送 + E: 工作区同步 | — | — | `git rev-parse HEAD origin/main github/main` 三处一致 |

## 3. 实施要点（易错处先写死）

| # | 易错点 | 处理 |
|---|---|---|
| 1 | 全局组头不自动展开 | `activeGroupKey` 必须用**当前模式**的树；`AppNav` 里 `props.nodes` 就是当前树，直接传 |
| 2 | 同 path 多 query 双双不亮 | 每个「同 path 组」第一个叶子是该组默认值；进入页面时无 query/非法值一律 `router.replace` 补齐 |
| 3 | 「我参与的」对 admin 恒真 | 只认 `own \|\| joined`，**不用** `myRole` |
| 4 | 项目侧设置组变单叶违反「组+唯一子项」 | 保留 2 项（用户管理 + 关于），组本身仍成立 |
| 5 | 顶栏断言 7/6 改 9/8 | 主题/语言进 tools 段后，`header-actions` 内可聚焦控件 7→9、8→9 需按实际重算，单测按 `aria-label` 序列断言而非纯计数 |
| 6 | `force=1` 破坏历史批次 | 重算分支**不写库**：只返回内存计算结果，`import_batch.status` 保持 `COMMITTED` |
| 7 | `DataTable` 化丢交互 | 三个视图的既有 spec 全部保留，改完逐个跑；`check_tables.py` 的 `MIN_TABLES` 下限需从 19 上调到 23（新增 6 处中 4 处入壳，另 2 处为弹层明细） |
| 8 | 执行计划与 cron 计划混淆 | 页面副标题明写「即时发起，不设定时」；侧栏顺序把发起放首位 |
| 9 | `runRequestId` 前缀被幂等键长度截断 | `EP-` + UUID 共 39 字符 < `VARCHAR(64)`，安全 |
| 10 | i18n 缺键回退成 key | 新增键必须中英成对，`check_i18n.py` 兜底 |

## 4. 里程碑

| 阶段 | 内容 | 完成判据 |
|---|---|---|
| M1 | W-A 全部 + W-B-01/02 | 全局侧栏出 5 组 14 叶，单测绿 |
| M2 | W-B-03/04/05 + W-C | 设置收敛 + 顶栏重排，断言改完 |
| M3 | W-D 全部 | 执行计划页可发起并跳详情 |
| M4 | W-E 全部 | 遗留 5 项结清，`check_tables` 转绿 |
| M5 | W-F | 五处同源、门禁、交付包、双远程 |

## 5. 回滚

- 前端全部改动在 6 个视图 + 2 个配置 + 1 个组件内，回滚 = `git revert` 单个提交即可；
- 后端 4 个文件均为**加字段**，无 DDL、无数据迁移，回滚无残留；
- `check_navigation.py` 为新增机检，若规则过严可先降级为 WARN（不删脚本）。

## 6. 完成情况与偏差（⑦ 测试后回写，2026-11-11）

33 项 WBS **全部完成**，无未开工项。实测偏差 5 处，逐条写明原因与处理：

| WBS | 计划 | 实际 | 偏差原因与处理 |
|---|---|---|---|
| W-A-04 | 新建 `tools/check_navigation.py`（层级/每组叶数/query 互异/唯一默认值） | 完成并接入 `scripts/lint.sh` | 解析器第一版按「单行 = 一个对象」写，`key:` 与 `path:` 分行的条目全被判「叶子没有 path」，38 行假红。改为「按 `key:` 行开块、后续行归块」+ `PATH_RE` 去掉尾逗号要求，同时支持多行与单行对象 |
| W-A-08 | `ProjectCard` 增 `own`/`joined` | 完成 | 对应的 `ProjectCardServiceTest.ownAndJoinedIgnoreAdminFallback` 首跑红：第二段断言前没切 `TenantContext`，当前用户仍是 ENGINEER(15) 而 gamma 的 `owner_id` 是 ADMIN(17)，`own` 恒 false——**用例假断言**，非实现错误。已在测试里补 `TenantContext.set(ADMIN, …)` 并注释「只换角色不换登录用户是不够的」；实现侧把 `== userId` 改成显式 `longValue()` 比较，防以后被重写成两个 `Long` 相比 |
| W-E-03/04 | 6 处原生 `<table>` 改 `DataTable` | 完成（`ProjectSyncView` 2 / `GlobalAssetsView` 1 / `GlobalCasesView` 3） | 换壳后单元格样式会**静默失效**：列由 `render()` 的 `h()` 渲染在 `NDataTable` 内部，拿不到组件 scope id。全部改 `:deep()` 并删除残留 `.asset-table` 死样式；`MIN_TABLES` 由 19 上调到 55、`MIN_VUE_FILES` 20 → 100，自检基线跟随下限 |
| W-E-05 | 两个新 spec（≥5 / ≥4 例） | 完成（9 例 / 7 例） | 断言过程踩到两处坑：`list.at(-1)` 拿到的是 `SCENARIO`（保存顺序是 cases → caseSets → others），改用 `find(refId===99)`；未 mock `vue-router` 导致 `route.params.id` 为空、detail 收到 `''` |
| W-F-01~06 | 版本同源 / 文档收口 / 重打交付包 / 门禁 / 双远程 | 完成 | 首轮门禁第 06 / 15 步红：新视图引用了不存在的 `--surface`（应为 `--panel`）、三处 px（280/160/980）不在白名单。**这是本版新写的真实缺陷**，已修（→ `--panel` / 240 / 180 / 900）；第 2 轮 31 PASS / 1 FAIL（仅第 13 步 CDP） |

**里程碑达成**：M1~M5 全部达成，判据与 `05-TEST-REPORT.md` 一致（FR-16 与 FR-21 的验收因执行器未安装 / CDP 取消降级，见该报告 §6）。

## 7. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-11 | 首版：6 组 WBS 共 33 项，映射 PRD 30 条 FR；确定实施顺序 5 条硬依赖、10 条易错点、5 个里程碑 |
