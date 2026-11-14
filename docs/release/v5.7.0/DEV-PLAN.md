# v5.7.0 开发计划（DEV-PLAN）· 全局用例资产化、执行可信度与全站操作体验

> 流程位置：④ 开发计划。**无开发计划不许开工编码**。
> 上游：`PRD.md`（30 FR / 218 AC + 12 NFR）、`01-ASSESSMENT.md`。第二轮新增 15 FR / 108 AC / 20 WBS / 4 波。
> 本文件给 WBS 编号与**可复制的验收命令**；不给「大约几天」，只给**依赖关系与可独立验收的边界**。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | 基础模型 | W57-01 ~ W57-05 | 是 | 后续全部依赖 |
| W2 | 全局用例资产化 | W57-06 ~ W57-11 | 是 | 依赖 W1；W3 依赖本波 |
| W3 | 全局向导建项目 | W57-12 ~ W57-14 | 是 | 依赖 W1、W2 |
| W4 | 列表三件套 | W57-15 ~ W57-17 | 是 | 只依赖 W1（无强耦合） |
| W5 | 执行可信度 | W57-18 ~ W57-23 | 是（RF 侧） | 依赖 W1；与 W4 可并行 |
| W6 | 证据包与阶段回归 | W57-24 ~ W57-26 | 是 | 依赖 W2 ~ W5；**全量收口移交给 W10** |
| W7 | 执行选择四模式 | W57-27 ~ W57-30 | 是 | 依赖 W4（selection）、W5（冻结计划） |
| W8 | 列表 CRUD 全覆盖 | W57-31 ~ W57-37 | 是 | 依赖 W1（迁移）、W4（列表骨架）；与 W7 可并行 |
| W9 | 设备凭证 | W57-38 ~ W57-42 | 是 | 依赖 W1（迁移）；与 W7/W8 可并行 |
| W10 | 体验契约与全量收口 | W57-43 ~ W57-46 | 否（最终收口波） | 依赖 W7 ~ W9；**W57-26 的全量回归职责在此执行** |

**并行纪律**：W4 与 W5 可并行；W2 → W3 串行；W7 / W8 / W9 三者可并行（改动面不重叠：执行台 / 列表页 / 资产页）；
W10 必须最后，且 W10 开始前 W7 ~ W9 的出口判据全部满足。

**第二轮范围说明**：新增 20 个 WBS（W57-27 ~ W57-46）、4 个波次、3 个迁移（拟定 `V11_62` ~ `V11_64`）。
既有 W57-01 ~ W57-25 编号与内容**不变**，仅 W57-26 把「全量回归与文档回写」职责移交 W57-45，
本波只做证据包接入后的阶段性回归——避免两个波次都声称自己是收口。

---

## 2. 开发纪律（先读，违反打回）

> **第二轮新增三条**（对应 PRD 硬约束 7 ~ 11）：
> ① 一次性凭证不得落任何持久化介质，新增请求体字段必须过 `SensitiveMask` 与通用日志白名单复核；
> ② 所有删除端点只允许软删 + 审计，**禁止硬删旁路**；
> ③ `ListScaffold` 只组合 `DataTable`，不复制第二份表格实现；接入页不得再自建工具条。

| 编号 | 纪律 | 出处 |
|---|---|---|
| G-01 | 依赖一律**构造器注入**；`ArchitectureTest` R6 禁 `@Autowired` 字段注入；一个类**只能有一个公开构造器** | 既有硬规矩 10 |
| G-02 | 异步线程必须 `TenantContext.runAs(...)` 包裹，否则需鉴权的服务在后台线程抛「未认证」 | 既有硬规矩 11 |
| G-03 | 已应用迁移**不可改**，只能追加新号；开工前 `ls db/migration/*.sql \| sort -V \| tail -1` 复核 | PRD 硬约束 1 |
| G-04 | 合并后必须重跑 `python scripts/gen_build_info.py` 再建 jar，否则「关于」页与运行期 build-info 报旧 commit | 既有坑 |
| G-05 | 构建会覆盖运行中的 jar，构建后必须 `systemctl --user restart robot-autotest-backend`；**不许**拿 `/api/_ping` 200 当健康 | 既有坑 |
| G-06 | 每跑一次全量门禁会重写四份已跟踪副产物，收口前 `git restore --` 那四个文件 | 既有坑 |
| G-07 | 前端 i18n 字面量含 `@` 必须写 `{'@'}` 且整串双引号，否则整页空白（组件单测与门禁都发现不了） | 既有坑 |
| G-08 | Markdown 写完跑 `docs/evidence/v1.0/policy-20261106/check_md_tables.py <路径>` | 既有硬规矩 7 |
| G-09 | 文档补丁按**内容匹配**，绝不用行号 | 既有硬规矩 7 |
| G-10 | 双远程推送：`git push origin main` + `git push github main`，判据＝三处 sha 一致 | 既有硬规矩 6 |
| G-11 | 构建/测试必须在 WSL `~/robot-autotest`（ext4），**禁止**在 `/mnt/*` 上跑 | 既有硬规矩 1 |
| G-12 | 门禁读数只看尾部 `GATES_RESULT FAILS=N`，`grep FAIL` 会把历史日志的旧失败当本轮 | 既有坑 |

---

## 3. W1 基础模型

### W57-01 迁移：用例集样例标记

| 项 | 内容 |
|---|---|
| 目标 | `test_set` 加 `sample_flag` / `sample_l1` / `user_modified` 与索引 |
| 文件 | `db/migration/V11_58__case_set_sample.sql`（新建） |
| 内容要求 | 三列均带 `DEFAULT`，`sample_l1` 可空；索引 `idx_test_set_sample(sample_flag, sample_l1)`；文件头写「v5.7.0 / FR-v5.7-04、05」 |
| 依赖 | 无 |
| 验收 | `DB_NAME=robot_it ./scripts/db-migrate.sh` 成功；`mysql -e "SHOW COLUMNS FROM robot_it.test_set LIKE 'sample%'"` 返回 2 行；`SHOW COLUMNS ... LIKE 'user_modified'` 返回 1 行 |
| 风险 | 迁移号撞号 → 开工前复核 `V11_57` 之后是否已有新文件 |

### W57-02 迁移：判据列

| 项 | 内容 |
|---|---|
| 目标 | `test_result` 加 `criteria_json` / `criteria_verdict` / `criteria_missing` |
| 文件 | `db/migration/V11_59__test_result_criteria.sql`（新建） |
| 内容要求 | `criteria_json MEDIUMTEXT NULL`；`criteria_verdict VARCHAR(16) NULL`；`criteria_missing TEXT NULL`；索引 `(run_id, criteria_verdict)` |
| 依赖 | W57-01（同批次执行顺序） |
| 验收 | 迁移成功；`SHOW COLUMNS FROM robot_it.test_result LIKE 'criteria%'` 返回 3 行 |
| 风险 | 已有大表加列锁表 → 在 `robot_it` 验证耗时，必要时用 `ALGORITHM=INSTANT` 并记录 |

### W57-03 迁移：计划确认摘要

| 项 | 内容 |
|---|---|
| 目标 | `execution_plan` 加 `confirmed_digest` / `confirmed_at` / `confirmed_by` |
| 文件 | `db/migration/V11_60__execution_plan_confirm.sql`（新建） |
| 依赖 | W57-01 |
| 验收 | 迁移成功；`SHOW COLUMNS FROM robot_it.execution_plan LIKE 'confirm%'` 返回 3 行 |

### W57-04 迁移：证据包指针表

| 项 | 内容 |
|---|---|
| 目标 | 新建 `evidence_bundle` 表 |
| 文件 | `db/migration/V11_61__evidence_bundle.sql`（新建） |
| 字段 | `id` / `project_id` / `bundle_key VARCHAR(128)` / `pointer_sha CHAR(64)` / `file_count INT` / `total_bytes BIGINT` / `status VARCHAR(16)`（`building`/`ready`/`failed`）/ 四个审计列 |
| 唯一键 | `uk_evidence_bundle_key (bundle_key)` |
| 依赖 | W57-01 |
| 验收 | 迁移成功；`SHOW CREATE TABLE robot_it.evidence_bundle` 含唯一键；`scripts/db-audit.sh` PASS |
| 注意 | 若 `db-audit` 有「新表须登记租户守卫」的检查，需同步登记 |

### W57-05 领域模型与仓储

| 项 | 内容 |
|---|---|
| 目标 | 把新列接进领域 record 与 JDBC 仓储，不改变既有方法签名 |
| 改动 | `domain/caseasset/TestSet.java` 加 `sampleFlag`/`sampleL1`/`userModified`（**只增字段不删**）；`infrastructure/TestSetRepositoryJdbc.java` 的 `SELECT`/`MAPPER` 同步；新增 `domain/caseasset/TestCaseCriteria.java` 与 `infrastructure/TestResultCriteriaRepository.java`（读写三列）；`domain/execution/` 下计划 record 加确认字段 |
| 依赖 | W57-01 ~ W57-04 |
| 验收 | `cd backend && mvn -o -B -q compile` 通过；`mvn -o -B test -Dtest=TestSetRepositoryJdbcTest` 通过；既有 `TestSet` 构造调用点全部编译通过（`grep -rn "new TestSet(" backend/src` 逐一核对） |
| 风险 | record 加字段会破坏所有 `new TestSet(...)` 位置 → 优先加在**末尾**并同步全量调用点；或改静态工厂 |

**W1 出口判据**：W1 批次的四个迁移（`V11_58` ~ `V11_61`）在 `robot_it` 全部通过；`db-audit.sh` PASS；后端编译 + 既有单测全绿；`git commit` 一笔（提交信息写 W57-01~05）。

---

## 4. W2 全局用例资产化

### W57-06 平台用例集写端点与权限

| 项 | 内容 |
|---|---|
| 目标 | 平台集从「只读 + admin」改为「读写 + `case:read`/`case:write`」 |
| 改动 | `api/caseasset/PlatformCaseSetController.java` 扩 6 个写端点；`application/caseasset/TestSetService.java` 加 `platformCreate` / `platformRename` / `platformDelete` / `platformAddItems` / `platformRemoveItem` / `platformReorder`；把 `requirePlatformAdmin()` 拆成 `requirePlatformRead()`（`case:read`）与 `requirePlatformWrite()`（`case:write`）；`infrastructure/TestSetRepositoryJdbc.java` 加对应写方法（`project_id IS NULL` + `owner_scope='PLATFORM'`） |
| 关键点 | 幂等：`platformAddItems` 用 `INSERT ... ON DUPLICATE KEY UPDATE seq_no=seq_no` 或先查后插，返回 `added`/`skipped`；`set_code` 冲突转 409 |
| 依赖 | W57-05 |
| 验收 | `mvn -o -B test -Dtest=PlatformCaseSetControllerTest` 通过（需新增 ≥ 12 例：创建/重名/改名/删除/加成员/重复加/移除/排序/无权限 403/只读用户 403/审计写入）；`curl` 冒烟见 §13 |
| 风险 | D-57-04 未拍板前**不得**放宽 `isAdmin` → 该任务标记 `blocked` |

### W57-07 样例集播种与重建

| 项 | 内容 |
|---|---|
| 目标 | 首次访问自动播种、手动重建补齐 |
| 改动 | 新建 `application/caseasset/SampleSetSeeder.java`；端点 `POST /api/platform/case-sets/seed-samples`（`api/caseasset/PlatformCaseSetController.java`）；在 `platformPage` 入口调用 `seeder.ensureSamples()`（异步，失败不影响响应） |
| 关键点 | 幂等键 `(sample_flag=1, sample_l1=?)`；命名 `<一级分类> · 样例`；`set_code = SAMPLE-<序号>`；`user_modified=1` 的集跳过；返回 `created`/`skipped`/`addedItems` 三计数；`TenantContext.runAs` 包裹异步线程（G-02） |
| 依赖 | W57-06 |
| 验收 | `mvn -o -B test -Dtest=SampleSetSeederTest` 通过（≥ 10 例：0→3 播种、二次幂等、0 用例分类、禁用分类不播种、并发两次不重复、user_modified 跳过、重建计数正确、失败不阻塞、runAs 生效、审计写入） |
| 风险 | 一级分类 > 30 个时同步播种慢 → 异步 + 页面轮询刷新 |

### W57-08 分类归组聚合接口

| 项 | 内容 |
|---|---|
| 目标 | `GET /api/global/cases/grouped` 返回「一级 → 二级 → 计数」树 |
| 改动 | 新建 `application/caseasset/CaseGroupingService.java`；`api/caseasset/` 下加端点；`infrastructure/` 加两条聚合 SQL（按 `category_l1_id`/`category_l2_id` 分组 count，含未分类兜底） |
| 关键点 | 只用 `category_l1_id`/`category_l2_id` 不好兜底历史脏数据（存在只有文本没有 id 的行）→ 按 `COALESCE(id)` 与文本名双轨分组，或先按 id 分组再补文本兜底；**必须**与列表接口的筛选口径一致 |
| 依赖 | W57-05 |
| 验收 | `mvn -o -B test -Dtest=CaseGroupingServiceTest` 通过（≥ 8 例：空库、只有一级、二级挂错父、未分类兜底、禁用分类不出现、计数与列表一致、筛选叠加、大分类分页） |
| 风险 | 计数与列表口径漂移 → 单测里用**同一份数据**分别调两个接口比对数字 |

### W57-09 前端：全局用例集页与菜单叶

| 项 | 内容 |
|---|---|
| 目标 | `/g/case-sets` 页面 + 导航第 3 叶 |
| 改动 | `frontend/src/config/navigation.ts` 的 `global-cases` 加 `global-cases-sets` 叶（`path: '/g/case-sets'`, `alsoMatch: ['/g/case-sets/']`）；`frontend/src/router/index.ts` 加路由与详情路由；新建 `frontend/src/views/global/GlobalCaseSetsView.vue` + `.spec.ts`；新建 `frontend/src/api/platformCaseSets.ts` |
| 关键点 | 复用项目侧 `CaseSetsView` 的交互骨架但**不复制**——抽出 `components/CaseSetWorkbench.vue` 由两侧共用（若时间不允许，允许先各写一份并登记技术债，但必须在 DEV-PLAN 标注） |
| 依赖 | W57-06 |
| 验收 | `cd frontend && npx vue-tsc --noEmit` 无输出；`npm test -- src/views/global/GlobalCaseSetsView.spec.ts` 通过（≥ 10 例：渲染、空态、搜索写 URL、选中集合、写按钮权限、只读用户无按钮、删除确认、分页、错误提示、i18n 键齐全）；`npm test -- src/config/navigation.spec.ts` 通过（全局叶数 16 断言） |
| 风险 | 既有 `navigation.spec.ts` 有叶数断言 → 必须同步更新而不是改断言绕过 |

### W57-10 前端：分类树选择组件

| 项 | 内容 |
|---|---|
| 目标 | 可复用的「一级/二级/用例」三态勾选树，W2 与 W3 共用 |
| 改动 | 新建 `frontend/src/components/CaseCategoryTree.vue` + `.spec.ts`；配套 `composables/useCategorySelection.ts`（全选 / 半选 / 已选集合 / 清空 / 搜索过滤） |
| 关键点 | 半选态用 `indeterminate`；懒加载展开时才请求子节点；搜索过滤只影响可见项不影响已选；已在本集的用例置灰；1000 条上限可配 |
| 依赖 | W57-08（树数据源） |
| 验收 | `npm test -- src/components/CaseCategoryTree.spec.ts` 通过（≥ 12 例：三层结构、一级全选、半选态、单条勾选、搜索过滤、清空、已在本集置灰、懒加载、上限阻止、空分类字典退化、键盘可用、i18n） |
| 风险 | 1000 条勾选后渲染卡顿 → 用 Set 存已选 + 虚拟列表只渲染可见节点 |

### W57-11 前端：全局用例归组视图

| 项 | 内容 |
|---|---|
| 目标 | `/g/cases?group=1` 渲染分类树视图，与列表视图共存 |
| 改动 | `frontend/src/views/global/GlobalCasesView.vue` 加视图切换与树渲染；`api/globalCases.ts` 加 `grouped()` |
| 关键点 | 切换只增删 `group` 参数，保留其他筛选；树默认折叠到二级；单分类 > 200 条显示「前 200 / 共 N」 |
| 依赖 | W57-08、W57-10 |
| 验收 | `npm test -- src/views/global/GlobalCasesView.spec.ts` 通过（既有用例全绿 + 新增 ≥ 8 例：切视图保留筛选、计数一致、未分类节点、禁用分类不出现、0 用例分类、分页提示、URL 还原、折叠默认态） |
| 风险 | 既有 spec 有筛选断言 → 只增不改 |

**W2 出口判据**：全局侧栏 3 叶；能播种出与一级分类同数的样例集；能按树勾选建集且成员数与勾选数一致；`vue-tsc` 无输出；前后端新老单测全绿。

---

## 5. W3 全局向导建项目

### W57-12 全局新建项目入口

| 项 | 内容 |
|---|---|
| 目标 | 全局「项目」组 3 叶 + `/g/projects` 页头主按钮 |
| 改动 | `navigation.ts` 的 `global-projects` 加 `global-projects-new` 叶（`path: '/projects/new'`, `capability: 'project:create'`）；**注意**：该 path 已在项目模式使用，需确认 `isLeafActive` 不会让两个叶同时点亮（用 `query: { from: 'global' }` 或独立 path `/g/projects/new` 二者择一，**建议后者更干净**）；`views/global/GlobalProjectsView.vue` 页头加按钮；`ProjectWizardView.vue` 支持 `from=global` 的返回目标 |
| 关键点 | 若选 `/g/projects/new`，需在 router 加 alias 到同一组件，并在创建成功后按来源跳转 |
| 依赖 | 无（可与 W2 并行） |
| 验收 | `npm test -- src/config/navigation.spec.ts src/views/global/GlobalProjectsView.spec.ts` 通过；手动走查：全局进向导 → 创建 → 回 `/g/projects` |
| 风险 | 同 path 双叶导致同时点亮 → 用独立 path 规避（推荐） |

### W57-13 向导内容步四类来源

| 项 | 内容 |
|---|---|
| 目标 | content 步扩为模板 / 用例集 / 用例 / 资产，可组合可跳过 |
| 改动 | `views/ProjectWizardView.vue` 重构 content 步；新增 `components/WizardContentPicker.vue`；`api/iam.ts` 或 `projectCards.ts` 扩 `applyContent` 入参 |
| 关键点 | 已选摘要常显；选择顺序决定集合内顺序；提交时去重；集合已删的并发错误要指名；不丢已选 |
| 依赖 | W57-10（树）、W57-06（平台集） |
| 验收 | `npm test -- src/views/ProjectWizardView.spec.ts` 通过（≥ 12 例：四分区渲染、选集合展开用例数、摘要实时更新、全部跳过可前进、返回上一步不丢选择、提交去重、资产数量、并发删除错误、1000 上限、i18n、a11y、按钮态） |
| 风险 | content 步过重导致单屏做不完 → 分区折叠，默认只展开「用例」 |

### W57-14 后端内容应用扩 setIds

| 项 | 内容 |
|---|---|
| 目标 | `POST /api/projects/{projectId}/content` 接受 `caseSetIds`，把集合成员并入「初始化用例集」 |
| 改动 | `api/iam/ProjectContentController.java` 请求体加 `caseSetIds`；服务层展开集合成员、去重、保持顺序；集合不可用返回明确错误 |
| 依赖 | W57-06 |
| 验收 | `mvn -o -B test -Dtest=ProjectContentServiceTest` 通过（≥ 8 例：只传 caseIds、只传 setIds、两者混合去重、集合不存在报错指名、集合为空、顺序保持、资产绑定数、事务回滚） |
| 风险 | 事务边界：集合展开在中途失败会留下半个项目 → 整个 content 应用放在一个事务内 |

**W3 出口判据**：全局能新建项目；向导能同时带入集合与用例并落到「初始化用例集」；去重后数量与摘要一致。

---

## 6. W4 列表三件套

### W57-15 `useTableFilters` 与列头筛选

| 项 | 内容 |
|---|---|
| 目标 | 筛选状态 URL 化，`DataTable` 支持列级筛选 |
| 改动 | 新建 `frontend/src/composables/useTableFilters.ts` + `.spec.ts`；`components/DataTable.vue` 加 `filterable` 列配置与筛选弹层；新建 `components/ColumnFilter.vue` |
| 关键点 | 参数命名 `f_<列>`（多值逗号）、`t_<列>`（模糊）、`q`（全局）；`prefix` 支持同页多表；刷新/分享/前进后退可还原 |
| 依赖 | 无 |
| 验收 | `npm test -- src/composables/useTableFilters.spec.ts src/components/DataTable.spec.ts` 通过（≥ 14 例：单值、多值、模糊、组合、prefix 隔离、URL 还原、空值剔除、非法值降级、与分页共存、与排序共存） |
| 风险 | 既有页面用自建筛选 → 不强制迁移，只在新页面与 3 个指定页面接入 |

### W57-16 服务端全选

| 项 | 内容 |
|---|---|
| 目标 | 「全选」以筛选条件表达，跨页生效；批量执行接口双入参 |
| 改动 | 新建 `frontend/src/composables/useServerSelection.ts`；后端 `CreateRunCommand` 或批量执行请求体加 `selection`（条件对象）；服务端按条件解析命中集合；`composables/useSelection.ts` 保持兼容 |
| 关键点 | `selection` 与 `ids` 二选一，同时传以 `selection` 为准并记告警；空对象 = 未选择；命中 > 10000 条要求二次确认；筛选变化重置全选 |
| 依赖 | W57-15 |
| 验收 | `mvn -o -B test -Dtest=ServerSelectionTest` 通过（≥ 8 例：条件解析、ids 与 selection 等价、空对象、超限、筛选变化重置、权限过滤不被绕过、SQL 注入防护、审计）；`npm test -- src/composables/useServerSelection.spec.ts` 通过 |
| 风险 | **R-57-01** 高：selection 可能绕过租户/权限过滤 → 必须在服务层复用项目作用域查询，禁止字符串拼 SQL |

### W57-17 三页面接入

| 项 | 内容 |
|---|---|
| 目标 | 全局用例、用例集成员、执行记录三页接入筛选与全选 |
| 改动 | `GlobalCasesView.vue`、`CaseSetsView.vue`（成员表）、`RunsView.vue`（或对应执行记录页） |
| 依赖 | W57-15、W57-16 |
| 验收 | 三页各 `npm test` 全绿；手动走查：筛 3 个条件 → 全选 → 翻页仍全选 → 批量执行条数与「全选全部 N 条」一致 |
| 风险 | 执行记录页若为只读列表，全选无意义 → 该页只接筛选不接全选，需在 TEST-PLAN 注明 |

**W4 出口判据**：三页筛选可 URL 还原；全选跨页且条数与后端 count 一致；`ids` 老路径不回归。

---

## 7. W5 执行可信度

### W57-18 命令来源抽象 + RF 实现

| 项 | 内容 |
|---|---|
| 目标 | `CommandPlanProvider` 接口 + RF 实现（argv 来自用例解析/dryrun） |
| 改动 | 新建 `application/execution/CommandPlanProvider.java`（接口）、`RobotCommandPlanProvider.java`、`CommandPlan.java`（record：caseCode / argv / cwd / timeout / dut）；注册为 Spring bean 列表，按 `testKernel` 选择 |
| 关键点 | RF 侧 argv 必须**不依赖 dryrun 的退出码**（历史坑：语法错退出码仍 0）；cwd 必须是项目工作区绝对路径；环境变量规范化 |
| 依赖 | W57-05 |
| 验收 | `mvn -o -B test -Dtest=RobotCommandPlanProviderTest` 通过（≥ 8 例：正常 argv、含空格路径、环境变量排序、无脚本绑定跳过、cwd 绝对化、超时透传、dut 透传、解析失败标红） |
| 风险 | **R-57-07**：argv 顺序不稳定 → 规范化后再算 digest，单测锁死样例 |

### W57-19 预览与确认端点

| 项 | 内容 |
|---|---|
| 目标 | `POST /runs/preview-commands`（只读）与 `POST /runs/confirm`（落 digest） |
| 改动 | `api/execution/` 下新增端点；`application/execution/CommandPreviewService.java`；`application/execution/RunConfirmationService.java`（算 digest、写 `execution_plan.confirmed_*`） |
| 关键点 | 预览不写 `run`；confirm 需 `execution:read` + 项目成员；digest 前缀 `v5.7-run-confirm:`；同 planId 只留最新确认并记审计 |
| 依赖 | W57-18、W57-03 |
| 验收 | `mvn -o -B test -Dtest=CommandPreviewServiceTest,RunConfirmationServiceTest` 通过（≥ 14 例：预览条数=可执行数、跳过原因、空清单拒绝、预览不写库、digest 恒定、重复确认覆盖、权限、审计、digest 前 12 位返回、规范化等价） |

### W57-20 派发前比对与 409

| 项 | 内容 |
|---|---|
| 目标 | 派发前重算，不一致 409，不启动进程 |
| 改动 | `application/execution/` 中派发入口（`FrozenPlanService.submit` 前置或 run 创建处）加校验；新增错误码 `DIGEST_MISMATCH`（409）；审计 `run.digest-mismatch` |
| 关键点 | 校验必须在**创建 run 之前**；409 响应体带 `added`/`removed`/`reordered` 三个编号数组；cron 触发未确认走 `run.blocked-unconfirmed` |
| 依赖 | W57-19 |
| 验收 | `mvn -o -B test -Dtest=DigestGuardTest` 通过（≥ 8 例：一致放行、新增用例 409、删除用例 409、改序 409、未确认 cron 跳过、响应体差异项、审计、不产生 run 记录） |
| 风险 | 若校验放在启动后，等于没做 → 单测里断言「409 时 run 表行数不变」 |

### W57-21 pytest 命令来源

| 项 | 内容 |
|---|---|
| 目标 | pytest 侧 `CommandPlanProvider` 实现 |
| 改动 | 新建 `PytestCommandPlanProvider.java`；从用例台账的 `nodeId`/参数生成 argv |
| 关键点 | 复用 v5.6.0 的 pytest 执行入口参数（`POST /projects/{id}/pytest/runs`），保证「预览的命令」与「实际执行的命令」同源 |
| 依赖 | W57-18 |
| 验收 | `mvn -o -B test -Dtest=PytestCommandPlanProviderTest` 通过（≥ 6 例：nodeId 生成、多 nodeId、仓库根 cwd、超时、无 nodeId 跳过、与实跑 argv 一致性对照） |
| 风险 | 若与实跑不同源，摘要承诺就是假的 → 必须有「预览 vs 实跑」一致性测试 |

### W57-22 判据采集（pytest 侧）

| 项 | 内容 |
|---|---|
| 目标 | 从 pytest 标记/fixture 收集判据，落 `criteria_json` |
| 改动 | pytest 插件侧（用例库仓库）加采集；后端解析 junit/附加 JSON 写 `test_result.criteria_*` |
| 关键点 | 未声明 → `NULL`；采集异常 → `MISSING` 并写原因；> 64KB 截断标记；**禁止**默认 PASS |
| 依赖 | W57-02 |
| 验收 | `mvn -o -B test -Dtest=CriteriaIngestTest` 通过（≥ 8 例：无声明 NULL、单判据 PASS、单判据 FAIL、缺指标 MISSING、多指标部分失败、截断、重复指标取末次、报告读取） |
| 风险 | **R-57-02**：RF 侧指标来源不稳 → 本版仅 pytest，RF 登记 L-57-02 |

### W57-23 判据机检与报告呈现

| 项 | 内容 |
|---|---|
| 目标 | PRD §7 的 5 行判定矩阵落地；报告可展开判据 |
| 改动 | `application/report/` 加结论计算（`CaseConclusionResolver`）；报告模板/页面加判据区块与覆盖率；`GET /runs/{runId}/criteria` |
| 关键点 | 结论计算是**唯一入口**，报告页与导出都调它；覆盖率只是展示不参与评分 |
| 依赖 | W57-22 |
| 验收 | `mvn -o -B test -Dtest=CaseConclusionResolverTest` 通过（≥ 10 例：5 种组合 + 旧数据 + 覆盖率计算 + 导出与页面一致）；`npm test -- src/views/ReportDetailView.spec.ts` 通过 |
| 风险 | 报告页既有断言可能假定「PASS 即通过」→ 需同步更新断言并写明原因 |

**W5 出口判据**：预览条数=实跑条数；digest 不一致必 409 且无 run；判定矩阵 5 行可复现；RF 侧若未覆盖，测试报告必须写明。

---

## 8. W6 证据包与阶段回归

### W57-24 `EvidenceBundleService`

| 项 | 内容 |
|---|---|
| 目标 | 内容寻址 + 两级分片 + 原子发布 + 读时校验 |
| 改动 | 新建 `application/evidence/EvidenceBundleService.java`；`EvidencePaths.java`（路径规则）；`infrastructure/EvidenceBundleRepositoryJdbc.java` |
| 关键点 | 文件名=sha256；`ab/cd/<sha>`；临时目录 → `ATOMIC_MOVE` → 指针替换；读时重算；单文件 > 512MB 拒绝；磁盘不足显式报错；旧扁平目录读兼容 |
| 依赖 | W57-04 |
| 验收 | `mvn -o -B test -Dtest=EvidenceBundleServiceTest` 通过（≥ 12 例：写入命名、去重、分片路径、原子发布、中断无半套、篡改拒读、审计、旧目录兼容、大文件拒绝、磁盘不足、并发写同内容、指针替换原子性） |
| 风险 | `ATOMIC_MOVE` 跨文件系统会失败 → 临时目录必须与目标同挂载点，单测覆盖 |

### W57-25 三处收口接入

| 项 | 内容 |
|---|---|
| 目标 | 报告导出 / 编辑器保存 / 用例导入三处改走 bundle |
| 改动 | `api/report/ReportExportController.java`、`api/editor/EditorSaveController.java`、`application/caseasset/CaseImportService.java` |
| 依赖 | W57-24 |
| 验收 | 三处既有单测全绿；新增「导出后读回校验通过」用例；篡改文件后读回抛异常 |
| 风险 | 三处行为变化可能影响既有证据路径 → 保留旧路径读兼容 |

### W57-26 阶段性回归（证据包波）

| 项 | 内容 |
|---|---|
| 目标 | 证据包波收口后的**阶段性**回归；全量回归与文档回写由 W57-45 承担 |
| 步骤 | ① `bash scripts/lint.sh`；② 后端 / 前端全量单测；③ `npx vue-tsc --noEmit`；④ `bash scripts/gates.sh` 读尾部 `GATES_RESULT`；⑤ 记录阶段性结果供 W10 对照（**本波不打交付包、不回写最终文档**） |
| 依赖 | W1 ~ W5 全部 |
| 验收 | `GATES_RESULT FAILS=0 UNJUDGED=0`；前后端全量绿；证据包三处接入单测全绿 |
| 注意 | G-04/G-05/G-06/G-12 全部适用 |

**W6 出口判据**：门禁全绿；证据包三处接入完成；阶段性回归结果留档（最终交付包与文档回写在 W10）。

---

## 9. W7 执行选择四模式（FR-v5.7-16 ~ 19）

### W57-27 迁移与选择快照服务

| 项 | 内容 |
|---|---|
| 目标 | `V11_62__execution_selection_snapshot.sql` + `SelectionSnapshotService` |
| 改动 | 新迁移；新建 `domain/execution/SelectionSnapshot.java`、`application/execution/SelectionSnapshotService.java`（`freeze()` / `recomputeSha()` / `consume()`）；仓储用 JdbcTemplate |
| 关键点 | `sha256(mode : sortedSetIds : sortedCaseIds : canonicalFilter : projectId : caseCount)`；`ALL` 模式 `caseIds` 为空、只存 filter + count；`UNIQUE(selection_sha)`；`consumed_at` 非空即拒绝复用 |
| 依赖 | W57-03（计划确认摘要同链路）、W57-05 |
| 验收 | `mvn -o -B test -Dtest=SelectionSnapshotServiceTest` 通过（≥ 12 例：四模式、排序规范化、同输入同 sha、条件变化、命中数变化、一次有效、并发重复提交、越权项目、空选择、JSON 往返、审计、409 文案） |
| 风险 | argv / filter 顺序不稳导致误报 409 → 规范化逻辑复用 W57-20 的实现，不另写一份 |

### W57-28 后端四模式解析与预览 / 确认端点

| 项 | 内容 |
|---|---|
| 目标 | `POST /runs/preview`、`POST /runs/confirm-and-start`、`GET /cases/count` |
| 改动 | `api/execution/RunLaunchController.java`（或既有控制器加方法）；`application/execution/SelectionResolver.java` 按模式解析命中集合 |
| 关键点 | `ALL` 必须复用项目作用域查询（**不得拼 SQL、不得绕过 TenantGuard**）；`MIXED` = 集合成员 ∪ 单选用例去重；预览只读不启动进程；确认只吃 `snapshotId`；派发前重算不一致 409 |
| 依赖 | W57-27、W57-16（`selection` 分支）、W57-19（命令预览） |
| 验收 | `mvn -o -B test -Dtest=SelectionResolverTest,RunLaunchControllerTest` 通过（≥ 14 例：四模式、并集、越权、空选择、超 10000、重复确认、老 `caseIds` 兼容、409 不启动进程、审计、count 与清单一致） |
| 风险 | **R-57-08** 高：`ALL` 若绕过租户过滤会跨项目执行 → 必须复用既有 `ProjectScope` 查询，单测用两项目数据对照 |

### W57-29 前端选择状态重构与四模式面板

| 项 | 内容 |
|---|---|
| 目标 | `ExecutionPlansView.vue` 从扁平 `selectedCaseIds` 改为来源映射，新增四模式切换 UI |
| 改动 | 新建 `composables/useRunSelection.ts` + `.spec.ts`；`views/ExecutionPlansView.vue` 选区重构；新建 `components/RunSelectionPanel.vue`、`components/SelectedSourcesDrawer.vue` |
| 关键点 | 唯一真源 `Map<caseId, Set<source>>`；取消集合只摘该来源；三态父节点；模式草稿各自保留；**修复现行 `toggleSet` 误删手工勾选的缺陷** |
| 依赖 | W57-28 |
| 验收 | `npm test -- src/composables/useRunSelection.spec.ts src/views/ExecutionPlansView.spec.ts` 全绿（≥ 18 例，含对照回归：集 A 3 条 + 手工 1 条 → 取消 A → 剩 1 条；现行实现剩 0 条） |
| 风险 | 既有 `ExecutionPlansView.spec.ts` 断言扁平数组 → 保留兼容计算属性 `selectedCaseIds`，老断言不删只补 |

### W57-30 大范围选择性能与上限

| 项 | 内容 |
|---|---|
| 目标 | 集合分页搜索、清单前 200 条、> 10000 二次确认 |
| 改动 | `caseSetApi.page` 接入搜索；`RunSelectionPanel` 虚拟滚动或分页清单；`cases/count` 接前端 |
| 关键点 | 去掉 `loadCaseSets` 的 `size:100`；选择态不写 `localStorage`；10 万用例首屏 ≤ 500ms |
| 依赖 | W57-28、W57-29 |
| 验收 | 造 10 万用例压测首屏 ≤ 500ms（20 次采样取 P95）；`npm test` 覆盖分页 / 超限 / 清空 / URL 还原 |
| 风险 | 全量 count 慢 → 用覆盖索引 + 缓存 5s，缓存 key 含项目 id 与筛选 |

**W7 出口判据**：四种模式各自能真实发起一次执行；组合模式取消集合不误删；摘要变化被 409 拦下且进程未启动；老 `caseIds` 调用方回归通过。

---

## 10. W8 列表 CRUD 全覆盖（FR-v5.7-23 ~ 27）

### W57-31 迁移与软删基础设施

| 项 | 内容 |
|---|---|
| 目标 | `V11_63__report_crud.sql`；统一软删 + 审计 + 影响面 count |
| 改动 | 新迁移；`application/report/ReportLifecycleService.java`；`common/SoftDeleteSupport.java`（查询默认过滤 + 恢复） |
| 关键点 | **开工先 `SHOW TABLES` 复核报告展示层真实表名**（PRD 已标为待复核）；软删只加列不改语义；`test_result` 不在删除范围；影响面 count 走后端 |
| 依赖 | W57-01（同批次迁移顺序） |
| 验收 | `mvn -o -B test -Dtest=ReportLifecycleServiceTest` 通过（≥ 10 例：软删、默认过滤、恢复、审计、影响面、已冻结拒绝、权限、幂等、旧数据 NULL、导出失效） |
| 风险 | **R-57-09** 中：表名判断错会全波返工 → W1 出口判据里加「表名复核记录」 |

### W57-32 覆盖度台账与机检

| 项 | 内容 |
|---|---|
| 目标 | `docs/evidence/v5.7.0/list-crud-matrix.md` + `tools/check_list_crud.py` |
| 改动 | 按 `navigation.ts` 叶子 + `router/index.ts` 枚举全局 / 项目两侧列表页（≥ 24 页），逐页登记 A/B/C 类与 C/R/U/D 现状、目标、缺口、FR；机检扫台账，白名单外缺口即 FAIL |
| 关键点 | 台账是**事实清单不是计划表**：现状列按端点与页面代码核实填写，不许按目标倒推；机检并入 `scripts/gates.sh` 并登记步数 |
| 依赖 | 无（可与其它 W8 任务并行） |
| 验收 | `python tools/check_list_crud.py` 初版允许 FAIL 并列出真实缺口清单；收口时全绿；门禁步数变化写进 W57-46 |
| 风险 | 台账漏页 → 用路由表自动生成候选清单，人工只补分类与理由 |

### W57-33 执行报告与运行记录 U/D

| 项 | 内容 |
|---|---|
| 目标 | `PUT/DELETE /api/reports/{runId}`、`PATCH/DELETE /api/runs/{runId}` + 两个页面行内动作 |
| 改动 | `api/report/ReportController.java`、`RunsController`（按实际类名）；`views/ReportsView.vue`、`views/RunsView.vue`、`api/reports.ts`、`api/execution.ts` |
| 关键点 | 删除确认含关联导出条数；冻结 / 归档态给「请先撤销」；就地刷新不跳转；旧报告打开正常 |
| 依赖 | W57-31、W57-43（ListScaffold，若未完成可先接自写工具条后回填） |
| 验收 | `mvn -o -B test -Dtest=ReportControllerTest` + `npm test -- src/views/ReportsView.spec.ts src/views/RunsView.spec.ts` 全绿；手工走一遍编辑 / 删除 / 恢复 |
| 风险 | 报告由 run 派生，删除 run 可能影响其它页 → 只软删展示层，不动原始事实表 |

### W57-34 用户报告 U/D

| 项 | 内容 |
|---|---|
| 目标 | `PUT/DELETE /api/test-reports/{id}` + 页面行内 / 批量动作 |
| 改动 | `api/report/TestReportController.java`、`views/TestReportsView.vue`、`api/testReports.ts` |
| 关键点 | freeze / revoke / archive 三个既有动作**语义零改动**；冻结后标题不可改、备注可改；批量删除复用 W4 服务端全选 |
| 依赖 | W57-31 |
| 验收 | 端点单测 ≥ 10 例；`TestReportsView.spec.ts` 覆盖编辑 / 删除 / 批量 / 冻结态；导出链接失效有可读说明 |
| 风险 | 既有「删除」文案实际是 revoke → 改造前先核清，避免把撤销误当删除 |

### W57-35 项目与模板列表两侧对称

| 项 | 内容 |
|---|---|
| 目标 | 全局项目页加列表视图；全局模板页补批量删除 / 行内重命名 / 状态切换 |
| 改动 | `views/global/GlobalProjectsView.vue`（当前仅包装 `WorkbenchView`）、`views/WorkbenchView.vue` 抽 `ProjectList.vue`；`views/global/GlobalTemplatesView.vue`；项目模式对应页复用同一组件 |
| 关键点 | 列表 / 卡片视图切换写 URL；删除沿用输入项目代码确认；权限不足**不渲染**动作；两侧筛选 / 分页 / URL 一致 |
| 依赖 | W57-15（useTableFilters）、W57-43 |
| 验收 | `npm test -- src/views/global/GlobalProjectsView.spec.ts src/views/global/GlobalTemplatesView.spec.ts` 全绿；两个模式各走一遍新建 → 编辑 → 删除 |
| 风险 | `WorkbenchView` 复用面广，抽组件会波及工作台 → 只抽列表部分，卡片部分原样保留 |

### W57-36 其余列表逐页补齐

| 项 | 内容 |
|---|---|
| 目标 | 按台账补齐全局资产、全局用例、导入记录、报告模板、场景、节点、计划组等 A/B 类页面 |
| 改动 | 逐页接 `ListScaffold` + 行内 CRUD；每页至少 1 条组件单测覆盖新增 / 编辑 / 删除 |
| 关键点 | 影响面数据统一来自后端 count；不做的动作直接不渲染；接入顺序按台账缺口优先级（B 类优先） |
| 依赖 | W57-32、W57-33 ~ 35 的公共组件 |
| 验收 | `check_list_crud.py` 显示白名单外缺口为 0；每页单测通过；抽查 5 页手工走查 |
| 风险 | **R-57-10** 中：页面数量多易漏 → 机检兜底，且收口波按台账逐页勾验 |

### W57-37 只读白名单与页面明示

| 项 | 内容 |
|---|---|
| 目标 | C 类列表逐条登记只读理由并在页面显示「系统生成，不支持编辑」 |
| 改动 | 台账白名单段；`components/ReadOnlyNotice.vue`；看板 / 趋势 / 失败聚类 / 拓扑 / 审计等页接入 |
| 关键点 | 只读页不渲染灰按钮；提示文案中英双份；白名单每条必须写理由，不接受「暂时不做」 |
| 依赖 | W57-32 |
| 验收 | `check_list_crud.py` 白名单结构校验通过；抽查 4 页有明示文案 |
| 风险 | 低 |

**W8 出口判据**：台账覆盖 ≥ 24 页且白名单外 0 缺口；B 类三类报告 / 记录均可编辑与软删；机检并入门禁。

---

## 11. W9 设备凭证（FR-v5.7-28 ~ 30）

### W57-38 迁移与验证服务

| 项 | 内容 |
|---|---|
| 目标 | `V11_64__credential_verification.sql` + `CredentialVerificationService` |
| 改动 | 新迁移；`application/asset/CredentialVerificationService.java`（`verify()` / `latest()` / `recordFromCollect()`）；仓储 JdbcTemplate |
| 关键点 | 通道复用 `HostAccess.channelOf`；成功 / 失败都落记录；失败原因四分类；**不记明文**；采集成功自动补记；单次探测超时 ≤ 10s |
| 依赖 | W57-01 |
| 验收 | `mvn -o -B test -Dtest=CredentialVerificationServiceTest` 通过（≥ 10 例：三类通道、成功、四类失败、只增不改、最近一次、采集补记、超时、脱敏、权限） |
| 风险 | 探测会真实连设备 → 必须 `confirm=true` + `asset:write`，且本机目标直接拒绝 |

### W57-39 验证端点与候选 options 端点

| 项 | 内容 |
|---|---|
| 目标 | `POST /api/global/assets/{id}/credentials/{credId}/verify`、`GET /api/global/assets/{id}/credentials/options` |
| 改动 | `api/asset/GlobalAssetController.java`；`application/asset/GlobalAssetService.java` 加 `credentialOptions()` |
| 关键点 | 排序四级（本设备成功 > 同型号 / 同域成功 > 已配置 > 其它）；返回验证时间、结果、绑定设备数；> 50 条走服务端搜索参数 |
| 依赖 | W57-38 |
| 验收 | 端点单测 ≥ 10 例（四级排序、稳定性、失败排末位、搜索、绑定数、无记录、权限、审计）；`curl` 冒烟见 §13 |
| 风险 | 同域推断口径不清 → 只用 `domain_tag` 精确匹配，不做模糊推断 |

### W57-40 一次性凭证安全通道

| 项 | 内容 |
|---|---|
| 目标 | `HostInventoryService.accessOf` 加一次性材料分支 + `POST /collect/manual` |
| 改动 | 新增 `domain/asset/OneShotCredential.java`（内存对象）；`HostInventoryService`、`GlobalAssetService`、`SshHostCommandRunner` 白名单复核；`SensitiveMask` 扩展 |
| 关键点 | **只走 POST body**；材料不写 DB / 日志 / 审计 / 响应 / 证据包 / 异常堆栈；`toString()` 打码；连接参数沿用既有白名单；`credentialId` 老分支行为零改动；「另存为凭据」默认关闭且走 `CredentialService.create` |
| 依赖 | W57-38、W57-39 |
| 验收 | 集成探针 `tools/probe_oneshot_secret.py`：抓 DB / 日志 / 审计 / 响应四处断言口令零出现；单测 ≥ 12 例含老分支回归、另存、公钥模式、失败不回显 |
| 风险 | **R-57-11 最高**：通用请求日志可能已打印 body → 先查日志切面，必要时给该端点单独加脱敏白名单，机检锁住 |

### W57-41 前端凭证选择器组件

| 项 | 内容 |
|---|---|
| 目标 | `components/CredentialPicker.vue`：自动关联已验证项 + 手工输入切换 |
| 改动 | 新组件 + `.spec.ts`；`views/global/GlobalAssetsView.vue`（设备表单管理 / BMC 两个下拉）、`components/AssetDetailPanel.vue`（采集弹层）接入 |
| 关键点 | 与设备表单、采集弹层**共用同一组件**；默认选中本设备已验证成功项并标时间；无记录不自动选；失败项排末位并标原因；手工输入表单含用户名 / 口令或私钥 / 类型 / 连接参数 / 端口 / 另存勾选 |
| 依赖 | W57-39、W57-40 |
| 验收 | `npm test -- src/components/CredentialPicker.spec.ts` 全绿（≥ 14 例：四级排序、默认选中、可改选、无记录、失败项、搜索、手工切换、另存默认关闭、字段校验、切换后清理、两处复用）；手工走查设备新建 / 编辑 / 采集弹层 |
| 风险 | 私钥大文本进组件状态 → 提交后立即清空，切走 / 关闭弹层也清空 |

### W57-42 安全探针与回归

| 项 | 内容 |
|---|---|
| 目标 | 六处零泄漏可复跑 + 既有采集链回归 |
| 改动 | `tools/probe_oneshot_secret.py`；`CredentialPicker` 状态清理单测；采集成功自动补记的端到端用例 |
| 关键点 | 探针覆盖 DB / 应用日志 / 审计表 / HTTP 响应 / 证据包 / 异常堆栈；既有 `credentialId` 采集路径全绿；本机采集隐藏手工输入区 |
| 依赖 | W57-40、W57-41 |
| 验收 | 探针 6/6 PASS；既有 `HostInventoryServiceTest` / `GlobalAssetServiceTest` 全绿；真机（或 mock SSH）采集一次成功并自动补记验证记录 |
| 风险 | 无真机时无法验真 → 用 mock SSH 通道，报告写明「未接真机」（不得写达成） |

**W9 出口判据**：options 排序可复核；一次性材料六处零泄漏；老凭证采集路径不回归；采集成功自动补记成立。

---

## 12. W10 体验契约与全量收口（FR-v5.7-20 ~ 22 + 收口）

### W57-43 `ListScaffold` 与全站接入

| 项 | 内容 |
|---|---|
| 目标 | 六槽位列表骨架，台账内 A/B 类页面全部接入 |
| 改动 | `components/ListScaffold.vue` + `.spec.ts`；`components/ConfirmAction.vue`（危险操作统一确认）；逐页替换自建工具条 |
| 关键点 | 只组合 `DataTable`，不复制表格实现；不适用的动作不渲染；空态三态；批量条与筛选区互斥；加载保留表头骨架；写操作就地刷新 |
| 依赖 | W57-15（筛选）、W57-16（全选）、W57-36（台账页清单） |
| 验收 | `npm test -- src/components/ListScaffold.spec.ts src/components/ConfirmAction.spec.ts` 全绿（≥ 16 例）；台账内页面接入率 100%；抽查 6 页手工走「新建 → 编辑 → 保存 → 返回」 |
| 风险 | **R-57-12** 中：全站替换影响面大 → 分批接入，每批跑一次 `npm test`，保留旧工具条组件直到全绿 |

### W57-44 布局与可用性机检

| 项 | 内容 |
|---|---|
| 目标 | 两档宽度 / 两主题 / 溢出 / 对比度 / 可访问名断言进 `browser-regression.mjs` |
| 改动 | `scripts/browser-regression.mjs` 扩展；`tools/check_ui_contrast.py`（或前端单测内实现）；视觉基线截图入 `docs/evidence/v5.7.0/ui/` |
| 关键点 | 1440×900 与 1280×800；`scrollWidth <= clientWidth`；标题容器 ≤ 2 行；语义色只用 token；状态三重编码；基线更新须人工确认 |
| 依赖 | W57-43 |
| 验收 | `node scripts/browser-regression.mjs` 全绿；`check_styleguide_tokens.py` PASS；浅 / 深两主题各一轮；基线截图归档 |
| 风险 | 基线截图在不同机器字体渲染有差异 → 只做 DOM 断言做阻断，像素差异走人工确认 |

### W57-45 全量回归与文档回写（承接 W57-26）

| 项 | 内容 |
|---|---|
| 目标 | 门禁、全量单测、构建部署、交付包、四份文档回写 + 测试报告 |
| 步骤 | ① `bash scripts/lint.sh`；② 后端 `mvn -o -B test` 全量；③ `cd frontend && npm test` 全量 + `npx vue-tsc --noEmit`；④ `python scripts/gen_build_info.py`；⑤ `bash scripts/build.sh`；⑥ `systemctl --user restart robot-autotest-backend`；⑦ `bash scripts/gates.sh` 读尾部 `GATES_RESULT`；⑧ `bash scripts/package-release.sh`；⑨ 回写 `00-RESEARCH` / `01-ASSESSMENT` / `PRD` / `DEV-PLAN` / `TEST-PLAN` + 新建 `05-TEST-REPORT.md` |
| 依赖 | W7 ~ W9 全部出口判据满足 |
| 验收 | `GATES_RESULT FAILS=0 UNJUDGED=0`；前后端全量绿；`deploy.sh --verify` 4/4；三处 sha 一致；30 条 FR 在测试报告有逐条结论 |
| 注意 | G-04/G-05/G-06/G-12 全部适用；**构建后必须重启后端**（否则懒加载类报 `NoClassDefFoundError`，登录超时而 `_ping` 仍 200） |

### W57-46 门禁新增项登记

| 项 | 内容 |
|---|---|
| 目标 | 新增机检项与步数变化写进 `GATES_RESULT` 口径 |
| 改动 | `check_list_crud.py`、`probe_oneshot_secret.py`、UI 回归断言三项并入 `scripts/gates.sh`；更新门禁步数台账 |
| 关键点 | 机检必须**真注入 → 判红 → 还原 → 判绿**（`--self-test` 口径），不许只跑绿灯；分别登记原始段与新增段，不得相加成「全绿」 |
| 依赖 | W57-32、W57-42、W57-44 |
| 验收 | 三项各跑一次注入判红还原判绿；`GATES_RESULT FAILS=0 UNJUDGED=0`；步数变化在测试报告写明 |
| 风险 | 门禁副产物被重写 → 收口前 `git restore --` 四份已跟踪副产物（AI 评估 / 容量 / 安全扫描 / DORA 趋势） |

**W10 出口判据**：全部门禁绿；文档回写完成；交付包版本号与 `VERSION` 一致；三处 sha 一致。

---

## 13. 冒烟命令（每波结束跑一遍）

```bash
# 环境（WSL，先加载凭据）
wsl.exe -d Ubuntu-24.04 bash -lc 'set -a; . ~/.config/robot-autotest/env; set +a; cd ~/robot-autotest && ...'

# 平台集读写（W2 后）
curl --noproxy '*' -s -H "Cookie: $COOKIE" "$BASE/api/platform/case-sets?source=all&page=1&size=5"
curl --noproxy '*' -s -X POST -H "Cookie: $COOKIE" -H "Content-Type: application/json" \
  -d '{"setCode":"T57-001","name":"冒烟集"}' "$BASE/api/platform/case-sets"

# 播种（W2 后）
curl --noproxy '*' -s -X POST -H "Cookie: $COOKIE" "$BASE/api/platform/case-sets/seed-samples"

# 归组（W2 后）
curl --noproxy '*' -s -H "Cookie: $COOKIE" "$BASE/api/global/cases/grouped"

# 命令预览（W5 后）
curl --noproxy '*' -s -X POST -H "Cookie: $COOKIE" -H "Content-Type: application/json" \
  -d '{"caseIds":[1,2,3]}' "$BASE/api/projects/95/runs/preview-commands"

# 判据（W5 后）
curl --noproxy '*' -s -H "Cookie: $COOKIE" "$BASE/api/projects/95/runs/<runId>/criteria"

# 执行选择（W7 后）
curl --noproxy '*' -s -X POST -H "Cookie: $COOKIE" -H "Content-Type: application/json" \
  -d '{"mode":"ALL","scope":"PROJECT","filter":{}}' "$BASE/api/projects/95/runs/preview"
curl --noproxy '*' -s -X POST -H "Cookie: $COOKIE" -H "Content-Type: application/json" \
  -d '{"snapshotId":"<id>"}' "$BASE/api/projects/95/runs/confirm-and-start"

# 列表 CRUD（W8 后）
python tools/check_list_crud.py
curl --noproxy '*' -s -X DELETE -H "Cookie: $COOKIE" "$BASE/api/test-reports/1"

# 设备凭证（W9 后）
curl --noproxy '*' -s -H "Cookie: $COOKIE" "$BASE/api/global/assets/1/credentials/options"
curl --noproxy '*' -s -X POST -H "Cookie: $COOKIE" -H "Content-Type: application/json" \
  -d '{"confirm":true}' "$BASE/api/global/assets/1/credentials/2/verify"
python tools/probe_oneshot_secret.py

# 视觉回归（W10 后）
node scripts/browser-regression.mjs
```

> 说明：`$COOKIE` 从登录接口获取，`$BASE` 为后端地址（就绪探针打 `:8081`）。

---

## 14. 提交与验收节奏

| 时点 | 动作 |
|---|---|
| 每波开始 | `git pull --ff-only`；`ls db/migration/*.sql \| sort -V \| tail -1` 复核迁移号 |
| 每波结束 | 一笔提交（信息含 W57 编号）+ 双远程推送 + 冒烟 |
| 每周 | 跑一次全量门禁，记录 `GATES_RESULT`（只记尾部一行） |
| 收口（W10） | 五份文档回写 + `05-TEST-REPORT.md` + `list-crud-matrix.md` + UI 基线 + 交付包 |
| 每波结束（第二轮） | W7 ~ W9 各跑本波出口判据，结果留档供 W10 对照 |

**提交信息格式**：`v5.7.0 W57-xx <动词> <对象>`（例：`v5.7.0 W57-06 平台用例集开放写端点`）

---

## 15. 风险与降级对照

| 风险 | 触发条件 | 降级动作 | 登记 |
|---|---|---|---|
| R-57-01 服务端全选绕过权限 | W57-16 单测出现越权命中 | 回退到「仅 ids 全选（分页全选）」，`selection` 标记为实验特性 | L-57-04 |
| R-57-02 RF 判据来源不稳 | W57-22 结束时 RF 侧采集率 < 60% | 只交 pytest 侧，RF 侧遗留 | L-57-02 |
| R-57-07 digest 误报 | W57-20 出现同输入不同 digest | 规范化逻辑加固；仍不稳则 digest 只对「用例集合」算，不含 argv | L-57-05 |
| D-57-04 未授权 | 大师未批平台集写权限 | W57-06/07/09 标记 blocked，先做只读浏览 | A-57-02 |
| 迁移撞号 | 复核发现 `V11_58` ~ `V11_64` 已被占用 | 顺延到下一个空号，**不改已应用文件** | — |
| R-57-08 `ALL` 绕过租户 | W57-28 单测出现跨项目命中 | 关闭 `ALL` 模式，只留 `SETS/CASES/MIXED` 并显式列举 id | L-57-06 |
| R-57-09 报告表名判断错 | W57-31 迁移在 `robot_it` 失败 | 先 `SHOW TABLES` 复核，顺延新迁移号追加修正，不改已应用文件 | — |
| R-57-10 列表台账漏页 | W57-36 收口仍有机检缺口 | 按路由表自动补全候选，缺口页分批接入并登记 | L-57-09 |
| R-57-11 一次性凭证泄漏 | W57-42 探针任一处命中 | **阻断本波**，先修日志切面白名单再重跑探针 | — |
| R-57-12 全站骨架替换回归 | W57-43 出现页面单测批量红 | 分批接入，保留旧工具条直到全绿 | L-57-10 |

---

## 16. 文档回写清单（流程第 7 道）

| 文档 | 回写内容 |
|---|---|
| `01-ASSESSMENT.md` | 被实测推翻的假设逐条改掉 + 修订记录 |
| `PRD.md` | 每条 FR 状态（达成/未达成/降级），降级写明理由与授权人 |
| `DEV-PLAN.md` | 实际完成情况与偏差原因 |
| `TEST-PLAN.md` | 实测结果与遗留缺陷 |
| `05-TEST-REPORT.md` | 执行记录、TC 逐条结论、遗留 L-57-xx |
| 根 `CHANGELOG.md` + `frontend/public/CHANGELOG.md` | 首条带 `<!-- current -->`，四小节齐全（新增/变更/修复/破坏性变更） |
| `README.md` | 版本与能力变化 |
| `docs/evidence/v5.7.0/list-crud-matrix.md` | 逐页 C/R/U/D 实测结论（不是计划表） |
| `docs/evidence/v5.7.0/ui/` | 两档宽度 × 两主题视觉基线截图 |

---


---

## 17. 实际完成情况与偏差（回写）

### 17.1 交付规模（`git status` 实测，相对 v5.6.0 基线 `0060216`）

| 项 | 实测 |
|---|---|
| 改动文件 | **133** 个（新增 91 / 修改 42） |
| 新增 Java | 38 个主源 + 13 个测试类 |
| 新增 Vue / TS | 32 个（含 `.spec.ts`） |
| 新增迁移 | 7 个（`V11_58` ~ `V11_64`），配 7 个 `.down.sql` |
| 单测 | 后端 **1899** 例、前端 **1008** 例，全绿；`vue-tsc` 0 错 |
| 业务表 | 105 → **108**（`db-audit` PASS） |

### 17.2 波次完成情况

| 波次 | 计划 | 实际 | 偏差 |
|---|---|---|---|
| W1 | 7 个迁移 + 领域接线 | 完成；`robot_it` 重放通过；表数契约 105→108 | 无 |
| W2/W3 | 平台集可写、样例播种、分类归组、向导内容 | 完成；真实服务写闭环全通 | `requirePlatformAdmin` 拆成 read/write，旧方法保留 |
| W4 | 分类树与全局用例归组视图 | 组件完成 | 浏览器验收未做（见 TEST-REPORT §四） |
| W5 | 命令摘要、判据层 | 完成 | 真派发未接线（L-57-01） |
| W6 | 证据包 + 阶段回归 | 完成 | 三处接入只接 1 处（L-57-03） |
| W7 | 选择四模式 + 快照 | 预览链路完成 | `confirm-and-start` 未接既有批量派发 |
| W8 | 列表 CRUD 与台账 | 端点 + 台账 + 机检完成 | 3 个页面未接骨架（L-57-05） |
| W9 | 凭证验证与一次性凭证 | 完成 | 真实远程采集未触发（L-57-04） |
| W10 | 收口、门禁、落号、交付包 | 完成；55 步 `FAILS=0`；jar/交付包已产 | 无 |

### 17.3 计划外工作（必须记账）

| 项 | 原因 | 代价 |
|---|---|---|
| 7 个 `.down.sql` | `DownScriptGuardTest` 要求每支正向迁移配对回滚 | 小 |
| `EvidenceBundleStore` 端口 + 仓储改走 `application` 层 | 证据包服务直接依赖 `infrastructure` 形成分层环，`LayerRulesTest` 判红 | 中（换实现不改语义） |
| i18n 迁移 252 处 | 新页面按早期约定写了中文字面量，`check_i18n` 判红；统一改 `t()` 并补 2824 键 | 大（含 en 译文） |
| 10 处 px 收敛 | `check_tokens` 白名单不允许新档位；全部改已有档位，未加白名单 | 小 |
| `ColumnFilter` 去中间层 | 中间 `export type` 让 `check_component_imports` 只扫到第一个 `<script>` 块 | 小 |
| 平台集列宽校验 | 真实服务实测 129 字符名返回 **500**（应为 400），补入口校验 + 2 条单测 | 小 |
| `check_nav_kind` 基线 39→41 | 全局真实新增 2 叶，属基线同步而非放宽 | 极小 |

---

## 18. 修订记录

| 日期 | 修订 | 原因 |
|---|---|---|
| 2026-11-14 | 第三轮：§17 实际完成情况与偏差（133 文件 / 7 迁移 / 1899+1008 单测；7 项计划外工作逐条记账） | 编码与测试完成，按流程第 7 道回写 |
| 2026-11-14 | 初稿 | 依 PRD 15 FR 拆 26 个 WBS（**AC 数更正为 110**：初稿写 88 系统计笔误，实测表格 110 行） |
| 2026-11-14 | 第二轮补充（+20 WBS / +4 波） | 大师四条补充：W7 执行选择、W8 列表 CRUD、W9 设备凭证、W10 体验契约与全量收口；W57-26 改为阶段性回归，全量收口移交 W57-45 |
