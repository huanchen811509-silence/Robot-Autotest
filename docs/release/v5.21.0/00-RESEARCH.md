# v5.21.0 需求调研：角色可用性与工程同构

> 流程位置：① 需求调研。上游：`../v5.19.0/PRD.md` §9 遗留 L-19-03 / L-19-04、`w518_ux_review/UX走查报告-20261116.md`。
> 本文件只记**事实**与**证据**；判断放 `01-ASSESSMENT.md`，需求放 `PRD.md`。
> 版本号 `v5.21.0` 为**拟用号**，本文件不修改 `VERSION`。

---

## 1. 调研目的

回答两个在 v5.19/v5.20 被明确排除、但阻碍"普通用户可用"的问题：

1. **非管理员能不能用**——v5.19 只做了 admin 视角与 viewer 的负向断言，pm / engineer / viewer 的完整体验从未走过（L-19-04）。
2. **换一个工程还是不是好的**——v5.19 只在 EHT pytest 工程（#95）验证；demo（#1）/ ED202（#77）/ demo-pytest（#80）三个工程未验（L-19-03）。

## 2. 方法与边界

| 项 | 内容 |
|---|---|
| 环境 | `http://localhost:8001`，v5.18.0（`/api/_ping` 实测） |
| 身份 | 界面 `admin`；接口层 `qa-e2e-{viewer,engineer,pm,admin,nobody}` 五账号 |
| 手段 | 真实 Chrome（CDP :9222）连续操作 + 直接接口取证 + 只读 SQL 核对 |
| 视口 | 1440×900（主）；关键项已在 v5.19 覆盖三档 |
| 主题 | 暗色（平台持久化设置） |
| 工程范围 | demo#1（RF）、ED202#77（RF）、demo-pytest#80、EHT#95（pytest） |
| 不在范围 | 移动端、并发压测、LDAP/SSO 对接、审计日志内容合规 |

**证据分级**：`[已核实]`= 有截图 / 接口响应 / SQL 读数；`[推断]`= 合理推测未取证。

**一处操作声明**：`qa-e2e-viewer` / `qa-e2e-engineer` / `qa-e2e-pm` 三个 E2E 账号原口令未知（`~/.config/robot-autotest/env` 无 `QA_E2E_PASSWORD`），
为使角色走查可执行，用 admin 的 `POST /api/users/{id}/reset-password` 将其口令重置为临时值。**未改动任何业务数据、未改动角色绑定**。

---

## 3. 事实清单

### 3.1 角色：库里只有"管理员"一个角色

| # | 事实 | 证据 | 级别 |
|---|---|---|---|
| F-21-01 | `GET /api/roles` 仅返回 **1** 条：`{id:1, code:"admin", name:"管理员", builtIn:true}` | 接口响应原文 | 已核实 |
| F-21-02 | 用户管理"新建用户"表单的「全局角色」下拉**只有"管理员"一项**（`role-options=["管理员"]`） | 图 `shots/iam-role-options.png` | 已核实 |
| F-21-03 | 用户列表 6 个账号，其中 `qa-e2e-nobody / qa-e2e-pm / qa-e2e-engineer / qa-e2e-viewer` 的「角色」列均为 `—` | 图 `shots/iam-users.png` | 已核实 |
| F-21-04 | `user_role` 表只有 1 行（user_id=17 → role_id=1 admin）；`role` 表只有 1 行 | SQL 读数 | 已核实 |
| F-21-05 | 后端 `RoleCode` 枚举定义了 **4** 个角色（admin/pm/engineer/viewer），`PermissionMatrix.buildMenuMatrix()` 也为 4 个角色分别写了能力集 | `RoleCode.java` / `PermissionMatrix.java:115-123` | 已核实 |
| F-21-06 | 即：**代码里有 4 个角色，库里只有 1 个**，界面上无法创建非管理员账号 | F-21-01 + F-21-05 | 已核实 |

> 影响面：`L-19-04` 的真实性质不是"非管理员体验没测"，而是**非管理员账号根本无法通过界面产生**。

### 3.2 能力集：三角色返回空，但菜单照旧全开

| # | 事实 | 证据 | 级别 |
|---|---|---|---|
| F-21-07 | 三个非管理员账号登录后 `GET /api/me/menu-capabilities` 均返回 **`data: []`** | 接口响应 | 已核实 |
| F-21-08 | 按 `PermissionMatrix` 口径，pm/engineer/viewer **都应**至少含 `settings:read`/`about`/`changelog` → 返回空说明"无角色"走了兜底分支，而不是按角色算 | F-21-07 + `PermissionMatrix:119-123` | 已核实 |
| F-21-09 | 尽管能力集为空，三个角色的侧栏仍渲染 **19 个菜单叶子**（含「环境采集」「用户管理」等写页面）；三角色菜单**完全一致** | 图 `shots/role-qa-e2e-viewer.png` 等 3 张 | 已核实 |
| F-21-10 | admin 能力集 7 项：`settings:read / ai:config / user:write / audit:read / about / changelog / case:write`；**无 `execution:read`**（与 v5.19 F-36 同源） | 接口响应 | 已核实 |
| F-21-11 | 三个角色的侧栏都没有「执行计划 / 运行记录」，与 admin 一致 → 菜单可见性主要由**本地静态配置**决定，`menu-capabilities` 只影响少数带 capability 的叶子 | F-21-09 vs F-21-10 | 已核实 |

### 3.3 权限边界：后端拦得住，前端不设防

| # | 事实 | 证据 | 级别 |
|---|---|---|---|
| F-21-12 | viewer 在用例库可见「新建」「Excel 导入」按钮；点「新建」**直接展开可填写的新建表单**（编号/名称/优先级/前置条件/预期结果/描述 + 保存/取消），保存前无任何拦截 | 图 `shots/viewer-new-click.png` | 已核实 |
| F-21-13 | viewer 直接访问 `/execution-plans`、`/runs` 页面**正常打开**（h1 分别为「执行计划」「执行」），无 403 页 | 图 `shots/viewer-route_execution-plans.png`、`viewer-route_runs.png` | 已核实 |
| F-21-14 | viewer 直接访问 `/g/iam/users` 页面能打开，`GET /api/users` 返回 **403**，页面上**没有任何错误提示**（内容区空白） | 图 `shots/viewer-route_g_iam_users.png` + console `403 /api/users` | 已核实 |
| F-21-15 | 后端判定正确：viewer `PUT /api/projects/29/cases/252` → **403 `无权限：CASE:UPDATE`**；engineer 同请求 → **400 参数错误**（说明已放行） | 接口响应 | 已核实 |
| F-21-16 | viewer `POST /api/projects/29/members` → 403 `无权限：PROJECT:UPDATE`；pm / admin 同请求 → 200 | 接口响应 | 已核实 |

> 结论方向：**不是权限模型错了，而是权限没有被前端表达出来**——用户要走到"保存"才知道自己没权限。

### 3.4 角色数据口径：大小写不一致

| # | 事实 | 证据 | 级别 |
|---|---|---|---|
| F-21-17 | `project_member_role.role_code` 存量数据大小写混杂：user 14 = `viewer`（小写）、user 15 = `ENGINEER`（大写） | 接口 `GET /api/projects/29/members` 原文 | 已核实 |
| F-21-18 | 同一接口里 `roleCode` 字段对 user 14 返回 `viewer`、对 user 15 返回 `ENGINEER` → 前端按字面量比较必然一半失效 | F-21-17 | 已核实 |

### 3.5 工程同构：四个工程同一处塌陷

| # | 事实 | 证据 | 级别 |
|---|---|---|---|
| F-21-19 | 四工程 `/cases` 全部 `.n-data-table` 高度 **44px**、首行 `elementFromPoint` 命中 `DIV.list-area`、`firstHit=false` | 探针 `probe-cross-project.mjs`（1440×900） | 已核实 |
| F-21-20 | 四工程 `tbody` 高度：demo 880 / ED202 880 / demo-pytest 528 / EHT 880 → 溢出量随条数变化，但表体都被裁 | 同上 | 已核实 |
| F-21-21 | 四工程侧栏叶子**完全一致**（22 项），无 RF 特有页面 → RF 与 pytest 共用同一套前端页面 | `probe-rf-admin.mjs` 输出 | 已核实 |
| F-21-22 | ED202 用例集页有 **7** 条用例集，表宽 964 / 容器 316（未选中右栏时）/ 横向溢出 648；4 列 × 7 行 = **28 个行内按钮全部不可命中**（`topCls=case-set-empty` 或 `case-set-workspace`） | `probe-cross-project.mjs` | 已核实 |
| F-21-23 | 同一 ED202 用例集页在另一次加载中 `tableH=364`、`firstHit=true` → 行内按钮可达性**随右栏状态变化**（偶发遮挡，不是恒定失效） | `probe-rf-admin.mjs` | 已核实 |
| F-21-24 | demo#1 用例集页为 0 条（无数据），无法用该页验证行内操作 | 同上 | 已核实 |

### 3.6 顺带核到的口径问题

| # | 事实 | 证据 | 级别 |
|---|---|---|---|
| F-21-25 | 非管理员账号只能看到 **1** 个项目（#29，其为成员）；EHT/demo/ED202 的项目成员只有 admin → 角色体验在真实工程里无法验证 | `GET /api/projects` 三角色响应 | 已核实 |
| F-21-26 | `/api/projects/29/runs` 对 admin 也返回 404（真实路由是 `/api/runs?projectId=`）→ 运行记录接口路径口径与文档不一致 | 接口响应 | 已核实 |

---

## 4. 该保留的既有设计（别在改造中改坏）

| # | 设计 | 为什么保留 |
|---|---|---|
| K-21-01 | 后端权限判定按资源+动作（`CASE:UPDATE` 等）返回 403，message 含具体权限名 | 定位问题快，便于机检 |
| K-21-02 | 项目级角色（`project_member_role`）与全局角色分离 | 一个工程师在不同项目可不同权限，这是对的 |
| K-21-03 | 无角色账号不获得任何数据权限（只能看自己是成员的项目） | fail-closed，方向正确 |
| K-21-04 | `RoleCode` 枚举与 `PermissionMatrix` 已按 4 角色写好 | 只差数据与前端表达，不需要重写模型 |

## 5. 未覆盖的缺口（本版不做，登记）

| # | 缺口 | 处置 |
|---|---|---|
| G-21-01 | 权限矩阵没有机检（4 角色 × N 接口的期望码表） | 本版 NFR 只要求新增"角色矩阵契约探针"，完整覆盖留 v5.22 候选 |
| G-21-02 | 审计日志里角色变更是否留痕未验 | 归入 NFR，不作为 FR |
| G-21-03 | LDAP / SSO 与角色映射 | 不在范围 |
| G-21-04 | 项目内"成员管理"页的角色下拉是否也只有 admin（未逐页核） | 本版 FR 判据覆盖"项目成员角色可选项 = 4"，页面位置不限定 |
