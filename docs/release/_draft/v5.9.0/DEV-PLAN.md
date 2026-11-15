# v5.9.0 开发计划（DEV-PLAN）：导航与权限基座 —— 先正口径，再开菜单

> 流程位置：④ 开发计划。**无开发计划不许开工编码**。
> 上游：`PRD.md`（11 FR + 8 NFR）、`docs/plan/重构评估-20261115/01-信息架构与导航评估.md`、`06-数据治理与开发隔离评估.md`。
> 本文件给 WBS 编号、改动面与**可复制的验收命令**；不给「大约几天」，只给依赖关系与可独立验收的边界。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | 迁移与数据完整性 | W59-01 ~ W59-04 | 是 | 必须最先（W2 的 SQL 依赖 `created_by` 列与组三表外键） |
| W2 | 归属与判权 | W59-05 ~ W59-10 | 是 | 依赖 W1；W3 的 IAM 页读本波口径 |
| W3 | 导航与 IA | W59-11 ~ W59-15 | 是 | 11/12/13 与 W1/W2 并行；14/15 依赖 W4 的页面就绪 |
| W4 | 关系聚合与能力闸 | W59-16 ~ W59-19 | 是 | 依赖 W2（判权口径） |
| W5 | 列表 CRUD 通则 | W59-20 ~ W59-22 | 是 | 依赖 W3（4 个新页存在） |
| W6 | 演示数据治理 | W59-23 ~ W59-25 | 是 | 依赖 W1（`is_demo` 列） |
| W7 | 收口 | W59-26 ~ W59-28 | 否（最终波） | 依赖 W1 ~ W6 全部出口判据 |

**并行纪律**：W1 先行，W2/W3 的 11~13 可并行；W3 的 14/15 必须等 W4 的 4 个页面写完（导航先加会点开空壳，违反 D-01）；
W5 必须在 W3-15 之后（通则要落在真实页面上）；W7 最后，且 `AuthzMatrixTest` 期望变更清单必须先冻结。

**本版迁移 3 支**（`V11_65` / `V11_66` / `V11_67`），每支配 `.down.sql`。**迁移先行**：`is_demo` 与 `created_by` 是 W2/W6 的编译前提。

---

## 2. W1 迁移与数据完整性（W59-01 ~ W59-04）

### W59-01 `V11_65` 演示标记与 kind 扩展

| 项 | 内容 |
|---|---|
| 目标 | `app_user.is_demo`、`project.is_demo`（`TINYINT NOT NULL DEFAULT 0`），`project.kind` 允许 `dev` |
| 改动面 | `db/migration/V11_65__demo_flag.sql` + `.down.sql`；`check_schema.py` 若维护列清单需同步 |
| 出口判据 | ① 迁移在空库与既有库各跑一次成功；② `down` 可完整回滚（列删除后表结构回到 `V11_64`）；③ `is_demo` 默认值对存量行全为 0 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=MigrationSmokeTest test`；`psql "$DB_URL" -c "select count(*) from app_user where is_demo<>0"` |
| 依赖 | — |

### W59-02 `V11_66` 组三表外键与级联

| 项 | 内容 |
|---|---|
| 目标 | `user_group_member` / `project_group_role` / `user_group` 的删除路径无孤儿；对 `app_user`/`user_group` 的 FK 补 `ON DELETE CASCADE` 或由服务层显式清理（二选一，须在迁移注释写明选择理由） |
| 改动面 | `db/migration/V11_66__group_fk.sql` + `.down.sql`；`UserRepositoryJdbc` / `GroupRepositoryJdbc` 的 delete |
| 出口判据 | ① 先做孤儿存量盘点（`LEFT JOIN ... IS NULL` 计数）并写进证据；② 有孤儿则迁移前先清理或迁移失败中止；③ 迁移后三表孤儿计数全 0 |
| 验证命令 | `psql "$DB_URL" -f docs/evidence/_draft/v5.9.0/sql/orphan-before.sql`（前后各一次） |
| 依赖 | W59-01（同批交付、同批回滚演练） |

### W59-03 `V11_67` `project.created_by` 补齐

| 项 | 内容 |
|---|---|
| 目标 | `project.created_by BIGINT NULL` + 索引；**不回填**存量（PRD FR-11 判据 4：不盲目回填） |
| 改动面 | 迁移两支 + `ProjectRepositoryJdbc.insert`（列清单补 `created_by`） |
| 出口判据 | ① 新建项目 `created_by` 非 NULL；② 存量 NULL 数量记录在测试报告；③ 列已存在时迁移幂等（`IF NOT EXISTS`） |
| 验证命令 | `psql "$DB_URL" -c "select count(*) filter (where created_by is null), count(*) from project"` |
| 依赖 | — |

### W59-04 删用户/删组的清理与影响面

| 项 | 内容 |
|---|---|
| 目标 | 删有项目角色的用户不再 500；删组不留孤儿；删除前返回影响面 |
| 改动面 | `UserRepositoryJdbc.delete`（补 `project_member_role` 清理）、`GroupRepositoryJdbc.delete`、`UserService`/`GroupService` 的 `impact` 端点、单测 |
| 出口判据 | ① 复现用例（2 个项目有角色的用户）由 500 转 200；② 影响面响应含 `projectRoleCount` / `groupRelationCount`；③ 在 v5.8 收口 commit 上回放判红（500） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=UserServiceTest+GroupServiceTest test` |
| 依赖 | W59-02 |

---

## 3. W2 归属与判权（W59-05 ~ W59-10）

### W59-05 可见性 SQL 抽为单一片段

| 项 | 内容 |
|---|---|
| 目标 | 三源并集写成一个 `ProjectVisibilitySql`（常量或类），`visibleProjects` 与 `requireProjectAccess` 共用 |
| 改动面 | 新增 `application/iam/ProjectVisibilitySql.java`；`AccessControlService:145-173` 改为调用它 |
| 出口判据 | ① 两处调用同一常量（代码检查：`project_member` 字面量在 iam 包内只出现 1 次）；② 单测矩阵 3 账号 × 5 项目：并集内项目 `requireProjectAccess` 必不抛 403 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=AccessControlServiceTest+VisibilityMatrixTest test` |
| 依赖 | W59-03 |

### W59-06 三源并集与来源字段

| 项 | 内容 |
|---|---|
| 目标 | `scope=mine` 含 OWNER/MEMBER/GROUP 三源；同项目多源只出现一次，`source` 取 `OWNER > MEMBER > GROUP`；新增 `roleCodes` |
| 改动面 | `AccessControlService.visibleProjects`、`ProjectController.cards` 的响应 record、`ProjectCardView`、单测 |
| 出口判据 | ① 转移 owner 后新 owner `source=OWNER`；② 组授权账号 `source=GROUP`；③ 三源同时命中只 1 行；④ 无归属账号返回 `[]` 且 HTTP 200；⑤ `kind='platform'` 不出现 |
| 验证命令 | `curl -s "$API/api/projects/cards?scope=mine" -H "Authorization: Bearer $TOKEN" \| jq '.[] \| {code,source,roleCodes}'` |
| 依赖 | W59-05 |

### W59-07 组授权参与真实判权

| 项 | 内容 |
|---|---|
| 目标 | 有效角色 = `project_member_role` ∪ `project_group_role`（按 `user_group_member` 展开），同源计算；修 `GroupsView.vue:58` 的 `userId=0` |
| 改动面 | `AccessControlService` 角色计算、`EffectiveRoleResolver` 收敛为唯一入口、`GroupsView.vue`、`PermissionMatrix` 不新增分支 |
| 出口判据 | ① 仅组授权的账号访问项目 API → 200；② 撤销组授权 → 403；③ 组 VIEWER + 成员 ENGINEER → 取 ENGINEER；④ 界面显示角色与同刻 HTTP 结果一致（6 组样本） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=EffectiveRoleTest+AuthzMatrixTest test` |
| 依赖 | W59-05 |

### W59-08 幂等批量写入

| 项 | 内容 |
|---|---|
| 目标 | 单个与批量关联统一为「重复视为成功跳过」，响应含 `added`/`skipped` |
| 改动面 | `ProjectService.addMember`（`:338-346` 去 409 分支）、`GroupService.grant`、两个响应 record、前端提示文案 |
| 出口判据 | ① 同一用户连续添加 2 次：两次 200，第二次 `added=0 skipped=1`；② `project_member` 行数不变；③ 单/批两端点计数口径一致 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ProjectServiceTest+GroupServiceTest test` |
| 依赖 | W59-06 |

### W59-09 owner 转移事务

| 项 | 内容 |
|---|---|
| 目标 | 转移 owner 同事务补 `project_member` + `project_member_role`；失败整体回滚 |
| 改动面 | `ProjectService.transferOwner`、`ProjectRepositoryJdbc.updateOwner` |
| 出口判据 | ① 转移后新 owner 三表都有行且 `scope=mine` 可见；② 故障注入（成员写入抛错）→ owner 未变；③ 单测覆盖回滚 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ProjectTransferTest test` |
| 依赖 | W59-06 |

### W59-10 「我参与的」来源列与空态

| 项 | 内容 |
|---|---|
| 目标 | 项目列表新增来源列（成员/负责人/用户组）；空列表显示自解释空态 |
| 改动面 | `frontend/src/views/ProjectsView.vue`（或项目看板入口页）、`api/projects.ts` 类型、spec |
| 出口判据 | ① 三种来源各一条真实数据截图；② 多源命中只 1 行；③ 空态文案可见（非空白页） |
| 验证命令 | `cd frontend && npx vitest run src/views/ProjectsView.spec.ts` |
| 依赖 | W59-06 |

---

## 4. W3 导航与 IA（W59-11 ~ W59-15）

### W59-11 `defaultOf` 与 `alsoMatch` 规则化

| 项 | 内容 |
|---|---|
| 目标 | `NavLeaf` 增 `defaultOf?: true`；同 path 组内若有带 query 且命中的兄弟，默认叶子不亮；`alsoMatch` 限定为详情段或显式 `exclude` |
| 改动面 | `frontend/src/config/navigation.ts`（`queryMatches` / `isLeafActive` / 类型）、所有 `alsoMatch` 用法 |
| 出口判据 | ① 三条复现路径 `aria-current` 计数 = 1；② `alsoMatch` 全量改造后无裸前缀；③ 项目模式三页签不出现「全灭」 |
| 验证命令 | `cd frontend && npx vitest run src/config/navigation.spec.ts` |
| 依赖 | — |

### W59-12 `check_navigation.py` 增 `alsoMatch` 校验

| 项 | 内容 |
|---|---|
| 目标 | 静态扫描 `alsoMatch` 是否合法（须含详情段特征或出现在白名单）；带 `--self-test` |
| 改动面 | `tools/check_navigation.py` + `GLOBAL_EXPECTED` 常量位置预留 |
| 出口判据 | ① `--self-test` 真注入一条非法 `alsoMatch` → 判红 → 还原判绿；② 正常仓库 PASS |
| 验证命令 | `python3 tools/check_navigation.py --self-test` |
| 依赖 | W59-11 |

### W59-13 命中唯一性不变量单测

| 项 | 内容 |
|---|---|
| 目标 | 遍历 `router.getRoutes()` 静态路由 × 各组默认 query，断言命中叶子数 **≤1 且 ≥1** |
| 改动面 | `navigation.spec.ts` 新增用例；若路由表不可静态枚举则补 `routes.manifest.json` |
| 出口判据 | ① 新用例全绿；② 在 v5.8 收口 commit 上跑必须红（≥3 条路径命中 2 个叶子）；③ 断言含「≥1」以防全灭回退 |
| 验证命令 | `cd frontend && npx vitest run src/config/navigation.spec.ts -t 命中唯一性` |
| 依赖 | W59-11 |

### W59-14 新一级菜单「用户与权限」与门禁常量

| 项 | 内容 |
|---|---|
| 目标 | 全局导航 5 组 → 6 组、16 叶 → 20 叶；同 commit 改门禁与单测常量 |
| 改动面 | `navigation.ts`、`tools/check_navigation.py:50-51`、`navigation.spec.ts:439/444/445` |
| 出口判据 | ① `check_navigation.py` PASS 且读数 6 组 20 叶；② `npm test` 全绿；③ 常量与代码同 commit（`git show --stat` 可证） |
| 验证命令 | `python3 tools/check_navigation.py`；`cd frontend && npx vitest run src/config/navigation.spec.ts` |
| 依赖 | W59-12 |

### W59-15 四个 IAM 页面

| 项 | 内容 |
|---|---|
| 目标 | `/g/iam/{roles,groups,users,projects}` 四页可 CRUD；系统设置下的旧入口改为跳转（不保留第二套写入口） |
| 改动面 | 4 个新 view + 路由 + i18n + 系统设置菜单项收敛 + 4 份 spec |
| 出口判据 | ① 4 页各一张有数据截图、各一张空态截图；② 旧入口只剩跳转（代码检查：无重复的表单提交）；③ i18n 中英齐备 |
| 验证命令 | `cd frontend && npx vitest run src/views/iam && npx vue-tsc --noEmit` |
| 依赖 | W59-16（读聚合接口取计数） |

---

## 5. W4 关系聚合与能力闸（W59-16 ~ W59-19）

### W59-16 `GET /api/iam/relations` 聚合

| 项 | 内容 |
|---|---|
| 目标 | `dimension` 取 `user` / `group` / `project` 三向聚合，作为三处读数唯一来源；排序稳定可幂等 |
| 改动面 | 新增 `IamRelationController` + `IamRelationService` + DTO；契约快照重生成 |
| 出口判据 | ① 同一事实三向互查命中一致；② 同请求两次响应 diff 为空；③ 只返回掩码/必要字段（不返回 secret） |
| 验证命令 | `for d in user group project; do curl -s "$API/api/iam/relations?dimension=$d&id=1" -H "Authorization: Bearer $TOKEN" \| jq -S .; done` |
| 依赖 | W59-06 |

### W59-17 三处显示接线

| 项 | 内容 |
|---|---|
| 目标 | 用户组页「已授权项目 N 个」、用户页「参与项目 N / 所属组 M」、项目关联页计数全部读聚合接口 |
| 改动面 | 3 个 view 的计数调用；删除各自的本地 count 查询 |
| 出口判据 | ① 界面数字 = 接口数字（DOM 断言 + 响应比对）；② 点开列表条数 = 显示数字；③ 代码检查：`project_group_role` 的 count 调用只出现在 service 一处 |
| 验证命令 | `cd frontend && npx vitest run src/views/iam` |
| 依赖 | W59-16 |

### W59-18 能力闸同源

| 项 | 内容 |
|---|---|
| 目标 | 前端路由守卫能力来源与服务端 `MENU_CAPABILITIES` 同源；ENGINEER 访问 `/g/iam/*` 被拦；后端 `USER_ROLE:READ` 不对 ENGINEER 放行 |
| 改动面 | `PermissionMatrix.java:145-146,178-179`、前端 `router/guards.ts` + 能力常量、一致性单测 |
| 出口判据 | ① ENGINEER 登录访问 4 页全部被拦；② `GET /api/users` → 403；③ 一致性单测存在且注入不一致能判红 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=PermissionMatrixTest test` |
| 依赖 | W59-07 |

### W59-19 角色管理页 CRUD

| 项 | 内容 |
|---|---|
| 目标 | 内置 4 角色只读（禁用 + title）；自定义角色增删改；删除被引用 → 409 点名 |
| 改动面 | `views/iam/RolesView.vue` + `UserController` 既有 CRUD 的界面接线 + spec |
| 出口判据 | ① 三态截图（只读/新建/删除确认）；② 删除被引用角色返回 409 且信息含引用数；③ 内置角色编辑按钮 `disabled` 且有 `title` |
| 验证命令 | `cd frontend && npx vitest run src/views/iam/RolesView.spec.ts` |
| 依赖 | W59-15 |

---

## 6. W5 列表 CRUD 通则（W59-20 ~ W59-22）

### W59-20 五条通则落地

| 项 | 内容 |
|---|---|
| 目标 | 项目看板报告列表可删；`/reports` 批量删 + 影响面；全局资产页编辑入口；用例删除被引用 → 409；全局用例集删除二次确认 |
| 改动面 | `OverviewView.vue:104-136`、`ReportsView.vue`、`GlobalAssetsView.vue`、`GlobalCaseSetsView.vue:281`、`TestCaseService.delete` |
| 出口判据 | ① 五处各一条真实操作证据；② 用例删除被 `case_script_binding` 引用时 409 且点名引用方；③ 影响面数字与实际级联一致 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=TestCaseServiceTest test`；`cd frontend && npx vitest run src/views/ReportsView.spec.ts` |
| 依赖 | W59-15 |

### W59-21 `check_list_crud` 白名单归零

| 项 | 内容 |
|---|---|
| 目标 | 台账每条 C 类缺项要么补齐、要么写「本页不支持，理由：…」；白名单 0 条 |
| 改动面 | `docs/evidence/v5.9.0/list-crud-matrix.md`、`tools/check_list_crud.py`（如需加白名单字段） |
| 出口判据 | ① 机检无「待补」字样；② 每条「不支持」都有页面可见说明（截图）；③ `--self-test` 注入一条缺项判红 |
| 验证命令 | `python3 tools/check_list_crud.py --self-test` |
| 依赖 | W59-20 |

### W59-22 `window.confirm` 清零

| 项 | 内容 |
|---|---|
| 目标 | 全站删除确认统一 `confirmAction` |
| 改动面 | 全站 `grep -rn "window.confirm"` 命中点 |
| 出口判据 | ① 命中数 0；② 删除确认弹窗含影响面文本；③ 至少 3 个页面的确认截图 |
| 验证命令 | `cd frontend && grep -rn "window.confirm" src \| wc -l`（须为 0） |
| 依赖 | W59-20 |

---

## 7. W6 演示数据治理（W59-23 ~ W59-25）

### W59-23 演示标签

| 项 | 内容 |
|---|---|
| 目标 | 演示账号与演示项目有可见「演示」标签 |
| 改动面 | 用户列表/项目列表/项目头部的 badge + i18n |
| 出口判据 | ① 2 张截图；② 标签来源为 `is_demo` 字段（代码检查，非按名称猜） |
| 验证命令 | `cd frontend && npx vitest run -t 演示` |
| 依赖 | W59-01 |

### W59-24 `purge-demo.sh`

| 项 | 内容 |
|---|---|
| 目标 | 默认 dry-run 打印清单；`--apply` 才删；只删 `is_demo=1`；删除顺序与评估 06 §2.2 一致 |
| 改动面 | `scripts/ops/purge-demo.sh`；`docs/evidence/v5.9.0/demo-purge/` |
| 出口判据 | ① 无 `--apply` 时逐表计数前后不变；② 3 演示 + 2 真实项目 → `--apply` 后真实项目数据零变化；③ 删后 `is_demo=1` 计数 0 且无 FK 错误 |
| 验证命令 | `bash scripts/ops/purge-demo.sh --dry-run`；`bash scripts/ops/purge-demo.sh --apply` |
| 依赖 | W59-01、W59-02 |

### W59-25 `check_demo_data` 机检

| 项 | 内容 |
|---|---|
| 目标 | 发版号 + 库内 `is_demo=1` > 0 时判红；带 `--self-test` |
| 改动面 | `tools/check_demo_data.py` + 门禁步注册 + `VERSION` 读取 |
| 出口判据 | ① `--self-test` 注入一条 `is_demo=1` → 判红 → 清掉 → 判绿；② 门禁步数 +1 且总 FAILS 不增 |
| 验证命令 | `python3 tools/check_demo_data.py --self-test`；`python3 tools/check_gates.py 2>&1 \| tail -3` |
| 依赖 | W59-24 |

---

## 8. W7 收口（W59-26 ~ W59-28）

### W59-26 权限矩阵期望变更清单

| 项 | 内容 |
|---|---|
| 目标 | `AuthzMatrixTest` / `AuthorizationBoundaryTest` 的期望值变化逐条列入测试报告，禁「改绿了事」 |
| 改动面 | 测试报告新增「期望变更清单」表；`docs/evidence/v5.9.0/authz/expected-changes.md` |
| 出口判据 | ① 每条变更含「原期望 / 新期望 / 依据判据 / 复核人」；② 无「未说明的期望变更」（机检或人工清单比对） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=AuthzMatrixTest+AuthorizationBoundaryTest test` |
| 依赖 | W59-07、W59-18 |

### W59-27 契约与迁移收口

| 项 | 内容 |
|---|---|
| 目标 | `openapi.json` 重生成并 PASS；三支迁移 `up/down` 在干净库各演练一次 |
| 改动面 | `docs/contracts/openapi.json`（WSL 内重生成，勿拷 E 盘旧件） |
| 出口判据 | ① `--check` PASS；② `endpoints/paths` 读数写进测试报告；③ down 演练后 schema 与 `V11_64` 一致 |
| 验证命令 | `cd backend && python3 tools/gen_api_contract.py --check` |
| 依赖 | W59-16、W59-17 |

### W59-28 门禁全量与文档回写

| 项 | 内容 |
|---|---|
| 目标 | `GATES_RESULT FAILS=0`；六份产物齐全；表格机检 PASS；总纲进度回写 |
| 改动面 | `docs/release/v5.9.0/*`（从 `_draft/` 整体迁入）、`docs/plan/重构评估-20261115/00-总纲` 交付表 |
| 出口判据 | ① 门禁 `FAILS=0`；② `check_release_docs.py v5.9.0` PASS；③ 表格机检全 PASS；④ 总纲状态列更新 |
| 验证命令 | `python3 tools/check_gates.py 2>&1 \| tail -3`；`python3 tools/check_release_docs.py v5.9.0` |
| 依赖 | W1 ~ W6 全部 |

---

## 9. 接口与数据变更清单

| 变更 | 类型 | 影响 | 处理 |
|---|---|---|---|
| `GET /api/projects/cards` 加 `source` / `roleCodes` | 响应新字段 | 老前端忽略不受影响；新前端按 `source` 渲染来源列 | 契约重生成 |
| `POST /projects/{id}/members` 去 409、加 `added`/`skipped` | 语义变更 | 依赖 409 的前端提示需改按 `added` 判断 | 契约重生成 + 前端同步 |
| `GET /api/iam/relations` | 新端点 | 三处计数改读它 | 契约重生成 |
| 删用户/删组影响面端点 | 新端点 | 删除确认弹窗消费 | 契约重生成 |
| `V11_65` / `V11_66` / `V11_67` | 3 支迁移 | 加列 + 外键 | 每支配 `.down.sql` |

---

## 10. 风险与应对

| # | 风险 | 应对 |
|---|---|---|
| R1 | 三源并集改判权导致大面积 403 期望翻转 | 先冻结期望变更清单（W59-26）再改代码；逐条复核，禁批量改绿 |
| R2 | 组三表补 CASCADE 误删真实数据 | 迁移前做孤儿盘点 + 在测试库演练 down；`ON DELETE` 选择写进迁移注释 |
| R3 | 新一级菜单与门禁常量不同 commit，第一道门就红 | W59-14 明确要求常量与代码同 commit，`git show --stat` 取证 |
| R4 | 4 个 IAM 页写不完却先上菜单 | PRD 降级预案：整页降级并移除菜单项，不留空壳（D-01） |
| R5 | `alsoMatch` 规则化后仍有页面无法用 `defaultOf` 表达 | 允许「显式默认 query + `router.replace`」，但须注释理由且不变量单测仍绿 |

---

## 11. 与相邻版本的边界

1. 本版**不改**用例/资产页面布局（5.11/5.12）、**不做**向导（5.10）、**不执行**演示数据清理（只建机制）。
2. 本版把「可见性 SQL」抽成单一片段，5.10 向导的成员步与 5.11 起的权限校验都直接复用，**不再各写一份**。
3. 本版**不新增第二套写入口**：三种关联动作共用既有端点，旧入口只保留跳转。

---

## 12. 实际完成情况（首次执行回写）

> 只记**真正落地并跑过**的；未做的写「未开始」，不改口径。

| WBS | 状态 | 落地内容 / 偏差 |
|---|---|---|
| W59-01 `V11_65` | 完成 | `app_user.is_demo` / `project.is_demo` + 两索引，迁移在开发库 success=1 |
| W59-02 `V11_66` | 完成 | 孤儿先删后建 4 个 FK（ON DELETE CASCADE）；迁移后孤儿 0 |
| W59-03 `V11_67` | 完成（范围缩小） | **列已存在**，只建 `idx_project_created_by`；`insert` 补写 `created_by`。见 TEST-PLAN §8.3 A1 |
| W59-04 删用户/删组清理 | 部分 | `UserRepositoryJdbc.delete` 补清 `project_member_role`/`user_group_member`；**影响面端点未做** |
| W59-05 可见性 SQL 单点 | 完成 | 新增 `ProjectVisibilitySql`；`check_visibility_sql.py` + `--self-test` |
| W59-06 三源并集 | 完成（API 层未验） | `findByMember` 改并集、新增 `visibleSources`；真实库读数见证据。**`source` 字段未进卡片响应** |
| W59-07 组授权参与判权 | 完成（API 层未验） | `effectiveRoles` 改读 `effectiveRoleCodes`（含组授权与 owner 隐含 pm） |
| W59-08 幂等批量写入 | 部分 | `addMember` 重复视为成功并合并角色；`added/skipped` 计数与前端提示未做 |
| W59-09 owner 转移事务 | 未开始 | — |
| W59-10 「我参与的」来源列 | 未开始 | 前端未改 |
| W59-11 `defaultOf` 与 `alsoMatch` 规则化 | 完成 | 新增 `defaultOf`、`activeLeaves`（精确 > 最长前缀 > 详情段），`AppNav` 改走同组消歧 |
| W59-12 `check_navigation.py` 增校验 | 未开始 | — |
| W59-13 命中唯一性单测 | 完成 | 4 条不变量用例；回放判红 5 条 |
| W59-14 新一级菜单 | 未开始 | 依赖 15，按 D-01 不先加空壳 |
| W59-15 四个 IAM 页面 | 未开始 | — |
| W59-16 ~ W59-28 | 未开始 | — |

**结论**：本版**未完成**，不得进 ④ 收口、不得发版。已完成部分是「导航唯一高亮 + 判权三源并集」两条主线，
可作为后续 WBS 的地基。

---

## 13. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：7 波 28 个 WBS；W1 迁移先行，W2 归属判权与 W3 导航可并行，W7 收口 | `PRD.md` v5.9.0 + 评估 01/06 + 总纲 §4 D-3/D-4/D-5 |
| 2026-11-15 | §12 首次执行回写：W59-01/02/05/06/07/11/13 完成；03 范围缩小；04/08 部分；其余未开始 | 实际提交与运行日志 |
