# v5.21.0 开发计划（DEV-PLAN）：角色可用性与工程同构

> 流程位置：④ 开发计划（草案）。上游：`PRD.md`（6 FR / 33 条判据 / 8 NFR）、`01-ASSESSMENT.md`（B 方案）。
> WBS 前缀随版本：v5.21.0 → **W71-xx**（v5.19→W69，v5.20→W70，编号不跨版）。
> 前置：**v5.19.0 与 v5.20.0 已发版**（列表可见、操作可达、四态与反馈已统一）。
> 数据库为 MySQL / MariaDB：用 `scripts/db-audit.sh` / `db-migrate.sh` 与 `mysql` CLI，**不用 `psql`**。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 对应 FR | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|---|
| W1 | 角色数据与账号 | W71-01 ~ W71-02 | 01 | 是 | 后面所有波都依赖"角色可选" |
| W2 | 能力集与菜单 | W71-03 ~ W71-04 | 02 | 是 | 04 依赖 03 的最小集口径 |
| W3 | 权限表达 | W71-05 ~ W71-06 | 03 | 是 | 06 依赖 05 的 `v-can` 指令 |
| W4 | 同构与偶发 | W71-07 | 04 / 05 | 是 | 整波可后移一个版本（不与角色耦合） |
| W5 | 收口 | W71-08 | 全部 + NFR | 否（最终波） | 依赖 W1 ~ W4 |

**并行纪律**

1. **W1 必须最先合入**：没有 pm/engineer/viewer 三条角色，W2/W3 的一切验证都只能用 `qa-e2e-*` 这种"无角色账号"凑，验不出真实差异。
2. **W1-01 与 W1-02 同批做、分开验收**：迁移只负责"角色存在"，账号种子负责"角色可分配"，混做时分级定位不了是数据问题还是接口问题。
3. **W2-03 先于 W2-04**：后端必须先定"无角色=最小集"，前端白名单制才有确定的期望值。
4. **W3-05 先于 W3-06**：先有 `v-can` 再把路由守卫挂上，否则按钮与路由判据会互相打架。
5. **W4 整波可后移**：只涉及布局与探针，不参与角色链路；若要缩短本版周期，W4 可整体搬 v5.22。
6. **探针先写再修**：W71-01/03/05/07 的探针必须先能在当前代码上判红。

---

## 2. W1 角色数据与账号（W71-01 ~ W71-02）

### W71-01 四内置角色落库 + 启动自检

| 项 | 内容 |
|---|---|
| 改动面 | `db/migration/V11_80__v5_21_builtin_roles.sql`（新）；`RoleService` 增加 `ensureBuiltinRoles()` 启动自检 |
| 出口判据 | `GET /api/roles` = 4 条；连续迁移 2 次仍 4 条；已存在行的 `name`/`permissions` 不被覆盖 |
| 验证命令 | `set -a; . ~/.config/robot-autotest/env; set +a; bash scripts/db-migrate.sh`（连跑 2 次）→ `curl -s --noproxy '*' -H "Authorization: Bearer $T" http://127.0.0.1:8001/api/roles` |
| 依赖 | 无 |

**实现要点**

1. 幂等写法：`INSERT INTO role (code,name,description,built_in,created_at,updated_at) SELECT ... WHERE NOT EXISTS (SELECT 1 FROM role WHERE code='pm')`，三条各一段；**不使用** `ON DUPLICATE KEY UPDATE name=...`（会覆盖人工改名）。
2. 启动自检只做**校验+补缺**，不覆盖已有行；发现缺角色时 `WARN` 日志 + 补齐，不阻断启动。
3. 迁移号必须大于当前最大 `V11_79`；写完 `git ls-files db/migration | sort -V | tail -3` 自查。

### W71-02 账号夹具与界面角色分配

| 项 | 内容 |
|---|---|
| 改动面 | `scripts/seed-qa-accounts.sh`（新，幂等）；用户管理页角色下拉改为渲染 `GET /api/roles` 全量；`UserService` 增加 `roles: string[]` 入参与出参对齐 |
| 出口判据 | 界面新建 engineer 账号成功并登录；`GET /api/users/{id}` 的 `roles=["engineer"]`；种子脚本重跑不产生重复用户 |
| 验证命令 | `bash scripts/seed-qa-accounts.sh`（跑 2 次）→ `curl /api/users?page=1&size=50`；界面截图（新建对话框 4 个角色选项） |
| 依赖 | W71-01 |

**实现要点**

1. 种子脚本用 `POST /api/users` + `PUT /api/users/{id}` 绑定角色；用户名固定 `qa-e2e-{role}`，已存在则跳过创建只校正角色。
2. 口令从 `QA_E2E_PASSWORD` 读取；未设置时**跳过口令设置并显式告警**，不写死默认口令。
3. 前端"新建用户"表单的角色字段从单选改为多选（`multiple`），提交为数组；后端兼容单值。

---

## 3. W2 能力集与菜单（W71-03 ~ W71-04）

### W71-03 后端最小集兜底

| 项 | 内容 |
|---|---|
| 改动面 | `AccessControlService#currentMenuCapabilities`：无全局角色 → `{about, changelog}`；有角色 → 按 `MENU_MATRIX` 并集 |
| 出口判据 | 无角色账号返回 `["about","changelog"]`；engineer 返回含 `settings:read`；admin 仍 7 项不变 |
| 验证命令 | `curl /api/me/menu-capabilities` ×4 账号，对照 `PermissionMatrix.MENU_MATRIX` |
| 依赖 | W71-01（角色存在才能验 engineer） |

**实现要点**

1. **fail-closed 而不是放大**：空角色不得返回全量；也不得返回空集合（空会被前端当"未加载"）。
2. 单测覆盖三种输入：无角色 / 单角色 / 多角色并集；断言顺序稳定（`LinkedHashSet`）。
3. 不改 `MENU_MATRIX` 的既有 4 角色内容（`execution:read` 属 v5.19）。

### W71-04 前端白名单制 + 导航能力机检

| 项 | 内容 |
|---|---|
| 改动面 | `frontend/src/config/navigation.ts`：每个叶子补 `capability` 或 `public: true`；`stores/menuCapabilities.ts#has()` 改为"无声明即 false"；新增 `tools/check_nav_capability.py` |
| 出口判据 | admin 叶子数 ≥ 22（不回退）；无角色账号叶子 ≤ 3；机检 PASS 且 `--self-test` 能判红 |
| 验证命令 | `python3 tools/check_nav_capability.py --self-test`；界面 3 账号侧栏 DOM 计数 |
| 依赖 | W71-03（期望值来自后端最小集） |

**实现要点**

1. 叶子映射表一次建全（22 项），按"平台域/项目域"两种口径：项目域叶子用 `project:read` 之类，管理域用 `user:write` 等。
2. `has()` 的语义改为：`public===true` → true；否则 `capability` 必须在能力集内；**未声明 capability 且非 public → false**。
3. 机检脚本读 `navigation.ts` 的 AST 或正则块，逐个叶子判定；`--self-test` 通过临时删一条 `capability` 触发判红后还原。

---

## 4. W3 权限表达（W71-05 ~ W71-06）

### W71-05 `v-can` 能力指令与 8 类写操作

| 项 | 内容 |
|---|---|
| 改动面 | 新增 `frontend/src/directives/can.ts`；在 `main.ts` 注册；8 类写操作改为 `v-can`：用例（新建/编辑/删除/导入）、用例集（新建/编辑/删除）、执行（发起）、成员（管理） |
| 出口判据 | viewer 写按钮数 = 0；engineer 按矩阵可见；admin 全部可见 |
| 验证命令 | `node scripts/ux-probes/role-buttons.mjs`（遍历 3 账号 × 4 页面，输出每页写按钮清单） |
| 依赖 | W71-04（能力判定同源） |

**实现要点**

1. 指令取角色来源：全局角色（`user_role`）+ 当前项目角色（`project_member_role`），**任一满足即可见**（取并集，与后端判定一致）。
2. 不可见用 `v-if` 语义（移除 DOM），不用 `disabled`——禁用按钮仍会让用户以为"再点点就能用"。
3. 首个版本只覆盖 8 类，其余写操作登记到 W71-08 的遗留清单，不扩散。

### W71-06 路由守卫 + 403 页 + 接口 403 错误态

| 项 | 内容 |
|---|---|
| 改动面 | `router/index.ts`：每条受限路由 `meta.capability`；守卫跳 `/403`；`ForbiddenView.vue` 文案含角色名与返回入口；`ListState` 失败态接入 403 |
| 出口判据 | viewer 直达 3 条受限路由均落 403；403 页有角色名与返回；接口 403 页面不空白 |
| 验证命令 | `node scripts/ux-probes/role-routes.mjs`（3 账号 × 6 路由，断言落点） |
| 依赖 | W71-05 |

**实现要点**

1. 403 页文案：`当前角色（访客）没有访问「用户管理」的权限` + 申请路径说明；不得只写"403 Forbidden"。
2. 守卫判定复用 `has()`，避免"按钮藏了但路由能进"。
3. 接口 403 与网络错误分开展示：403 = 权限问题（不显示"重试"），500/超时 = 可重试。

---

## 5. W4 同构与偶发（W71-07）

### W71-07 工程矩阵探针 + 用例集操作列解耦

| 项 | 内容 |
|---|---|
| 改动面 | 新增 `scripts/ux-probes/project-matrix.mjs`（4 工程 × 6 页面）；`CaseSetsView.vue` 左侧列表操作列 sticky + 表格宽度不随右栏挤压 |
| 出口判据 | 24 格矩阵 PASS；4 右栏状态 × 3 视口 12 组行内按钮可命中 |
| 验证命令 | `node scripts/ux-probes/project-matrix.mjs`；`node scripts/ux-probes/set-row-actions.mjs`（12 组矩阵） |
| 依赖 | 无（可独立于 W1~W3 进行） |

**实现要点**

1. 操作列 `position: sticky; right: 0`，配 `background` 避免透字；列宽固定 4 按钮 + 内边距。
2. 左栏宽度用 `flex: 0 0 <固定>` 或 `minmax()`，右栏展开/收起不改变左栏表格可用宽度。
3. 矩阵探针输出 CSV + 摘要行，便于贴进测试报告；无数据工程标注"仅验空态"。

---

## 6. W5 收口（W71-08）

| 项 | 内容 |
|---|---|
| 改动面 | `scripts/ux-probes/role-matrix.mjs`（4 角色 × ≥8 接口期望码表）；门禁脚本接入 3 个新探针；文档回写四份；`CHANGELOG` 与前端副本同源 |
| 出口判据 | 门禁 `FAILS=0`；3 个新探针 `--self-test` 全绿；6 条 FR 状态全部落定 |
| 验证命令 | `bash scripts/gate.sh`（或现有门禁入口）+ 三个探针自检 |
| 依赖 | W71-01 ~ W71-07 |

**实现要点**

1. 角色矩阵期望码表由产品确认后写入（PRD §9 Q-1/Q-2 未定项先按现有矩阵，标注来源）。
2. 门禁新增断言只加不减；每条带 `--self-test`。
3. 收口时重写四份已跟踪副产物（`v3.3.0/ai-eval.json`、`v3.4.0` 两份、`v5.0.0/ops/dora-trend.json`）与 churn 截图基线后 `git restore`。

---

## 7. 工作量与顺序建议

| 顺序 | WBS | 前置 | 说明 |
|---|---|---|---|
| 1 | W71-01 | — | 数据先行，1 个迁移 + 1 个自检 |
| 2 | W71-02 | 01 | 脚本 + 一个下拉，改动小但要真建账号 |
| 3 | W71-03 | 01 | 一个方法改兜底 + 单测 |
| 4 | W71-04 | 03 | 22 项映射表是主要工作量 |
| 5 | W71-05 | 04 | 指令 + 8 处替换 |
| 6 | W71-06 | 05 | 路由守卫 + 403 页 |
| 7 | W71-07 | — | 可与其他波并行 |
| 8 | W71-08 | 全部 | 收口 |

## 8. 风险与回退

| 风险 | 触发信号 | 回退方式 |
|---|---|---|
| 迁移在已部署环境重复插角色 | `role` 表出现重复 code（唯一键冲突） | 迁移已是 `WHERE NOT EXISTS`；冲突时先查 `flyway_schema_history` 再补 |
| 白名单制把 admin 菜单也收掉 | admin 叶子数 < 22 | `has()` 保留 `public`/超管短路；先跑对照再合入 |
| `v-can` 漏改导致按钮消失但功能还在 | 用户报"找不到入口" | 先按 8 类范围交付，其余登记遗留；不要一次性改全站 |
| 403 页误伤正常流（如登录过期） | 401 与 403 混用 | 守卫只处理 403；401 走重新登录 |
| sticky 操作列在 Safari/旧内核错位 | 视觉截图异常 | 降级为"表格容器横向滚动 + 操作列固定宽度"，不用 `sticky` |

## 9. 提交与验收纪律

1. 每个 WBS 一个提交，提交信息含 WBS 号与 FR 号；W1 三件先合入。
2. 探针与实现分开提交（先提交判红的探针，再提交修复），便于回溯"修之前确实是红的"。
3. 不改 `VERSION`；落号由大师决定。
4. 四份文档（00/01/PRD/DEV/TEST）在收口时回写实测结果与偏差原因。

## 10. 修订记录

| 日期 | 变更 | 依据 |
|---|---|---|
| 2026-11-16 | 首版：5 波 8 个 WBS（W71-01 ~ W71-08）；W1 三件 P0 最先；W4 可后移；3 个新探针随开发同波落地 | `PRD.md` 6 FR / 8 NFR + `01-ASSESSMENT.md` B 方案 |
| 2026-11-16（⑦ 回写） | 8 个 WBS 全部完成，**偏差 3 处**见 §11 | `05-TEST-REPORT.md` |

---

## 11. 实际完成情况与偏差（⑦ 回写）

| WBS | 状态 | 实际做法 / 偏差 |
|---|---|---|
| W71-01 四角色落库 | 完成 | 迁移 `V11_80` 落库；`RoleService.ensureBuiltinRoles()` 用 `@EventListener(ApplicationReadyEvent)` 自检（**只补缺不覆盖**，失败不阻断启动）。原计划"启动自检"未指定钩子，实现选事件监听而非 `@PostConstruct`——后者在测试装配时会误触发。 |
| W71-02 账号夹具与角色分配 | 完成 | `scripts/seed-qa-accounts.sh`（幂等，含工程成员身份）；界面角色下拉本已渲染 `GET /api/roles`，本版只需角色存在即可用。**偏差**：`UserService.update` 原先对空 `roleIds` 回落 viewer，导致"清空角色"永远清不掉 → 编辑路径改为不兜底（新建仍兜底）。 |
| W71-03 最小集兜底 | 完成 | `PermissionMatrix.minimalMenuCapabilities()` = `{about, changelog}`；`currentMenuCapabilities()` 在无角色时返回它。 |
| W71-04 前端白名单 + 机检 | 完成 | 35 个导航叶子全部声明 `capability`/`public`；`navNodeVisible` 实现"未声明=不可见"（**分组仍是容器语义**：分组不声明时由子项决定存亡，否则整组会消失）。新增 `tools/check_nav_capability.py`。**偏差**：能力码从 9 扩到 16——白名单制要求"只读"有码可用，否则只读页只能继续复用 `settings:read`。 |
| W71-05 `v-can` 指令 | 完成（范围守约） | `frontend/src/directives/can.ts`（`display:none` 隐藏，可逆）+ `composables/useCan.ts`；覆盖用例新建/编辑/删除/导入、用例集新建/编辑/删除/立即执行、发起执行。**未扩面**：成员面板内部批量操作仍靠"入口不可达"兜住 → L-21-02。 |
| W71-06 路由守卫 + 403 页 | 完成 | 守卫改读**服务端能力集**并先 `await auth.loadMe()`（原写法 `if (capability && auth.user)` 会在刷新瞬间放行）；403 页含角色名 + 被拦路径 + 申请路径。新增 `tools/check_route_capability.py`（82 条路由全声明）。 |
| W71-07 工程矩阵与行内操作解耦 | 完成 | 新增 `project-matrix.mjs`（30 格）、`set-row-actions.mjs`（12 组）。**抓到两个真缺陷**：资产页高度链断裂（44px）、两个 `v-if` 掉的表格外壳仍占 240px×2。 |
| W71-08 收口 | 完成 | 门禁新增 63–67 五步（均带 `--self-test`）；`role-matrix.mjs` 36 格；本报告所列遗留 4 条已登记。 |

**未按原计划做的一件事**：W71-07 原写"操作列 `position: sticky`"，实际沿用 v5.19 已落地的 `fixed:'right'`（Naive UI 固定列），因为 sticky 在横向滚动容器里与虚拟滚动列宽计算冲突；12 组实测已证明可达性达标。

