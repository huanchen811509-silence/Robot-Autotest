# v5.9.0 测试计划（TEST-PLAN）：11 FR / 49 TC

> 流程位置：⑤ 测试计划。**无测试计划不许进入开发后期**。
> 上游：`PRD.md`（11 FR + 8 NFR）、`DEV-PLAN.md`（7 波 28 WBS）。
> 规则：每条 TC 必须写清**怎么跑、看什么、证据留在哪**；写不出证据形态的 TC 不算 TC。

---

## 1. 执行方式分类

| 代号 | 含义 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| `UT` | 后端/前端单测 | 逻辑分支正确 | 真实请求能通、界面能看见 |
| `MECH` | 机检脚本（带 `--self-test`） | 规则被守住且能判红 | 业务语义对不对 |
| `HTTP` | 真实服务请求（curl/脚本） | 契约、错误码、真实数据 | 界面渲染 |
| `UI` | 真实浏览器（CDP/人工） | 用户能看见、能点 | 后端边界 |
| `DB` | 直连数据库读数 | 落库事实 | 链路是否被调用 |
| `REPLAY` | 在 v5.8 收口 commit 上回放 | 缺陷确实存在、判据确实能抓到 | 修复是否完整 |
| `MANUAL` | 人工走查 | 观感与一致性 | 可重复性（须留截图） |

**放行口径**：P0 条目（FR-01/02/03/04/08）必须至少拿到 `HTTP` 或 `DB` 或 `UI` 一级证据；只有 `UT` 的条目不得计为达成。
**`REPLAY` 是硬要求**：本版四条判据（FR-01-④、FR-03-⑥、FR-08-④、FR-02 前置）必须有回放日志，证明修前确实红。

---

## 2. 环境与前置

```bash
# WSL Ubuntu-24.04 / ext4 权威副本（禁止在 /mnt/* 上构建）
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest
# 迁移先行，再重建后端（改码后必须重建 jar，否则 :8081 跑的是旧件）
bash scripts/db-migrate.sh
bash scripts/build.sh && bash scripts/deploy.sh
# 前端 dev server（UI 类 TC 用）
cd frontend && npx vite --port 5173
```

**回放基线**：v5.8 收口 commit（取值见 `docs/release/v5.8.0/05-TEST-REPORT.md` 的签发行）。
回放方式：`git worktree add /tmp/v58 <sha>` + 同一套 TC 脚本，**只跑判据不跑修复**。

**样本数据前置**（每个测试库一次性准备）：

| 样本 | 内容 |
|---|---|
| S1 | 项目 A，owner=`eng1`，无 `project_member` 行 |
| S2 | 组 `gpu-组`，成员 `eng2`，授权 A:ENGINEER |
| S3 | 用户 `viewer1`，无任何归属 |
| S4 | 用户 `multi1`，同时是 A 的 owner + 成员 + 组授权成员 |
| S5 | 用户 `del1`，在 2 个项目有 `project_member_role` |
| S6 | 平台项目 `PLATFORM-DEMO`（`kind='platform'`） |

---

## 3. TC 清单

### 3.1 FR-v5.9-01 叶子命中唯一性（4 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-01 | `/g/cases?view=imports` 高亮 | `UI` | `aria-current` 计数 = 1，且为「导入记录」 | 截图 + DOM 断言 |
| TC-v5.9-02 | `/g/assets?tab=credentials` 与 `/g/projects?scope=mine` | `UI` | 各计数 = 1 | 2 张截图 + DOM 断言 |
| TC-v5.9-03 | 不变量单测：全静态路由 × 默认 query | `UT` | 命中叶子数 ≤1 且 ≥1，无例外 | `navigation.spec.ts` 输出 |
| TC-v5.9-04 | 反向回放：v5.8 commit 上跑 TC-03 | `REPLAY` | **判红**，至少 3 条路径命中 2 个叶子 | 回放日志 |

### 3.2 FR-v5.9-02 三源并集与来源（5 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-05 | 转移 owner 给 `eng1`（S1）后查 `scope=mine` | `HTTP`+`DB` | 含 A 且 `source=OWNER` | 响应 JSON + `project.owner_id` 查询 |
| TC-v5.9-06 | `eng2`（S2）查 `scope=mine` | `HTTP` | 含 A 且 `source=GROUP` | 响应 JSON |
| TC-v5.9-07 | `multi1`（S4）三源同时命中 | `HTTP`+`UT` | A 只出现 1 行，`source=OWNER` | 响应 JSON + 单测 |
| TC-v5.9-08 | `viewer1`（S3）与平台项目（S6） | `HTTP` | `[]` + 200；平台项目不出现 | 2 条响应 |
| TC-v5.9-09 | 可见性片段唯一性：单测矩阵 3 账号 × 5 项目 | `UT` | 并集内项目 `requireProjectAccess` 不抛 403 | 矩阵输出 |

### 3.3 FR-v5.9-03 组授权参与判权（5 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-10 | 仅组授权账号访问项目内 API | `HTTP` | 200（修前 403） | 请求日志 |
| TC-v5.9-11 | 撤销组授权后同一请求 | `HTTP` | 403 | 请求日志 |
| TC-v5.9-12 | 组 VIEWER + 成员 ENGINEER | `UT`+`HTTP` | 有效角色 ENGINEER，两源可辨 | 单测 + 响应 |
| TC-v5.9-13 | 界面角色与同刻 HTTP 结果一致（6 组样本） | `UI`+`HTTP` | 显示 ENGINEER→200；NONE→403 | 6 张截图 + 6 条响应 |
| TC-v5.9-14 | 反向回放：v5.8 commit 上跑 TC-10 | `REPLAY` | 403 | 回放日志 |

### 3.4 FR-v5.9-04「用户与权限」一级菜单（5 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-15 | `check_navigation.py` 读数 | `MECH` | PASS，6 组 20 叶 | 机检输出 |
| TC-v5.9-16 | 4 页可达 + 有数据 / 空态自解释 | `UI` | 各 1 张有数据 + 1 张空态 | 8 张截图 |
| TC-v5.9-17 | ENGINEER 访问 `/g/iam/users` | `UI`+`HTTP` | 重定向或 403 页，**看不到列表** | 截图 + 响应 |
| TC-v5.9-18 | `navigation.spec.ts` 结构断言 | `UT` | 6 组 20 叶断言与既有 7 条共存全绿 | `npm test` 输出 |
| TC-v5.9-19 | i18n 齐备 | `MECH`+`UI` | 4 菜单项 + 4 标题中英齐全，无硬编码中文 | i18n 检查 + 截图 |

### 3.5 FR-v5.9-05 关系聚合与同步（4 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-20 | 同一事实三向互查 | `HTTP` | 三向均命中且计数一致 | 3 条响应 |
| TC-v5.9-21 | 同一请求连续两次 | `HTTP` | diff 为空（排序稳定） | 两次响应 + diff |
| TC-v5.9-22 | 组页「已授权 N 个」与点开列表条数 | `UI` | 显示数 = 列表条数 = 接口数 | 截图 + DOM 计数 |
| TC-v5.9-23 | 无第二处 count 查询 | `MANUAL` | `project_group_role` count 只在 service 一处 | 代码检查记录 |

### 3.6 FR-v5.9-06 幂等批量语义（4 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-24 | 同一用户对同一项目加 2 次 | `HTTP`+`DB` | 两次 200；第二次 `added=0 skipped=1`；行数不变 | 2 条响应 + 计数 |
| TC-v5.9-25 | 同一组对同一项目授权 2 次 | `HTTP`+`DB` | 同上 | 2 条响应 + 计数 |
| TC-v5.9-26 | 单/批两端点对「重复」计数一致 | `UT` | 同一数据计数相同 | 单测输出 |
| TC-v5.9-27 | 前端提示「已存在 N 项」 | `UI` | 提示可见且数字正确 | 截图 |

### 3.7 FR-v5.9-07 列表 CRUD 五条通则（5 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-28 | 项目看板报告列表删除 | `UI`+`HTTP` | 有操作列，删除端点与 `/reports` 相同 | 截图 + 网络日志 |
| TC-v5.9-29 | `/reports` 批量删 + 影响面 | `UI` | 删除前显示 N 条结果 / M 产物 / 冻结状态 | 截图 + 请求 |
| TC-v5.9-30 | 删被 `case_script_binding` 引用的用例 | `HTTP` | 409 且点名引用方（非 500） | 响应日志 |
| TC-v5.9-31 | 全局用例集删除二次确认 | `UI` | `confirmAction` 弹窗，非 `window.confirm` | 截图 |
| TC-v5.9-32 | `check_list_crud` 白名单 | `MECH` | 0 条悬空；「不支持」均有页面可见说明 | 机检输出 |

### 3.8 FR-v5.9-08 删用户/删组与外键（5 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-33 | 删 `del1`（2 个项目有角色） | `HTTP`+`DB` | 200（修前 500）；`project_member_role` 该用户 0 行 | 响应 + 查询 |
| TC-v5.9-34 | 删有成员的组 | `HTTP`+`DB` | 200；两表无孤儿 | 响应 + 2 条查询 |
| TC-v5.9-35 | 删除前影响面 | `HTTP` | 含「将移除 N 条项目角色、M 条组关系」 | 响应 |
| TC-v5.9-36 | 反向回放：v5.8 commit 上跑 TC-33 | `REPLAY` | 500 | 回放日志 |
| TC-v5.9-37 | 迁移后全局孤儿计数 | `DB` | `project_member_role` 孤儿 = 0 | SQL 输出 |

### 3.9 FR-v5.9-09 能力闸与角色 CRUD（4 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-38 | ENGINEER 调 `GET /api/users` | `HTTP` | 403 | 响应日志 |
| TC-v5.9-39 | 前端能力常量与服务端同源 | `UT` | 一致性单测存在；注入不一致判红 | 单测输出 |
| TC-v5.9-40 | 自定义角色增/改/删 | `UI` | 三步各一张截图；内置 4 角色禁用且有 `title` | 4 张截图 |
| TC-v5.9-41 | 删被引用的自定义角色 | `HTTP` | 409 含引用数 | 响应日志 |

### 3.10 FR-v5.9-10 演示数据标记与清理（5 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-42 | 演示账号/项目标签 | `UI` | 各 1 张截图，标签来源为 `is_demo` | 2 张截图 + 代码检查 |
| TC-v5.9-43 | 不带 `--apply` 运行 | `DB` | 逐表计数前后完全一致 | 前后计数表 |
| TC-v5.9-44 | 3 演示 + 2 真实 → `--apply` | `DB`+`HTTP` | 真实项目用例/报告数不变 | 断言输出 |
| TC-v5.9-45 | `--apply` 后残留 | `DB` | `is_demo=1` 计数 0，无 FK 错误 | 脚本输出 + 查询 |
| TC-v5.9-46 | `check_demo_data --self-test` | `MECH` | 注入判红 → 清除判绿 | 机检输出 |

### 3.11 FR-v5.9-11 属主可追溯（3 条）

| TC | 内容 | 方式 | 期望 | 证据 |
|---|---|---|---|---|
| TC-v5.9-47 | 新建项目 `created_by` | `DB` | = 创建者 userId，非 NULL | 查询输出 |
| TC-v5.9-48 | 转移 owner 后三表 | `DB`+`HTTP` | `project_member` / `project_member_role` 有行，`scope=mine` 可见 `source=OWNER` | 3 条查询 + 响应 |
| TC-v5.9-49 | 转移失败回滚（故障注入） | `UT` | owner 未变，无半成品 | 单测输出 |

---

## 4. 覆盖矩阵（FR → TC）

| FR | TC 编号 | 条数 |
|---|---|---|
| FR-v5.9-01 | 01 ~ 04 | 4 |
| FR-v5.9-02 | 05 ~ 09 | 5 |
| FR-v5.9-03 | 10 ~ 14 | 5 |
| FR-v5.9-04 | 15 ~ 19 | 5 |
| FR-v5.9-05 | 20 ~ 23 | 4 |
| FR-v5.9-06 | 24 ~ 27 | 4 |
| FR-v5.9-07 | 28 ~ 32 | 5 |
| FR-v5.9-08 | 33 ~ 37 | 5 |
| FR-v5.9-09 | 38 ~ 41 | 4 |
| FR-v5.9-10 | 42 ~ 46 | 5 |
| FR-v5.9-11 | 47 ~ 49 | 3 |

合计 **49 条 TC**，覆盖 11 条 FR，无孤儿 TC。

---

## 5. 不可用推断补齐的条目（执行纪律）

| 情形 | 处理 |
|---|---|
| 回放基线 commit 无法起服务（依赖缺失） | 标「未执行（回放环境不可用）」并登记，**不许**用当前分支结果冒充回放 |
| 无 `is_demo` 样本可造 | 用脚本造演示对象；仍造不出则标「未执行」，不得以「代码已写」计为达成 |
| 迁移 down 演练失败 | 一律视为 P0 阻断，当版不得发版（回滚能力是发版前提） |
| 权限 403 用例期望翻转 | 必须进「期望变更清单」并复核，**不许**直接改断言值 |

---

## 6. 证据目录规范

```
docs/evidence/v5.9.0/
├── nav-unique-<date>/{before,after}/*.png + dom.json      # FR-01
├── visibility-<date>/raw/*.json + sql/*.sql               # FR-02
├── group-authz-<date>/{shots,http}/                       # FR-03
├── iam-menu-<date>/*.png + check_navigation.log           # FR-04
├── iam-relations-<date>/raw/*.json                        # FR-05
├── idempotent-<date>/raw/*.log + db-count.txt             # FR-06
├── list-crud-matrix.md + shots/                            # FR-07
├── user-delete-<date>/{raw,replay}/                       # FR-08
├── iam-gate-<date>/{raw,shots}/                           # FR-09
├── demo-purge-<date>/{dry-run.log,apply.log,counts.md}    # FR-10
├── project-owner-<date>/{sql,raw}/                        # FR-11
└── authz/expected-changes.md                              # W59-26
```

---

## 7. 门禁与收口判据

| 项 | 判据 |
|---|---|
| 单测 | 后端全量 `Failures=0 / Errors=0`；前端全量例数 ≥ 上一版 |
| 类型 | `npx vue-tsc --noEmit` 无输出 |
| 门禁 | `GATES_RESULT FAILS=0 UNJUDGED=0`（本版步数 = 上版 + 1，新增 `check_demo_data`） |
| 发布文档 | `check_release_docs.py v5.9.0` PASS（六份齐全 + FR 覆盖 + TC 有结果） |
| 表格 | `check_md_tables.py` 对 v5.9.0 目录全 PASS |
| 契约 | `gen_api_contract.py --check` PASS |
| 迁移 | 三支 `up/down` 各演练一次；down 后 schema 与 `V11_64` 一致 |
| 权限 | 期望变更清单逐条有依据与复核人；无「未说明的期望变更」 |

> **开发期预期红点**：本版文档先建、代码后到，门禁第 07 步会因「TC 无结果」判红，这是**预期**；
> 收口时必须靠真实执行结果转绿，**不许**用「未执行」占位或删 TC 躲开。

---

## 8. 执行结果（滚动更新）

> 只记**已经按 TC 方式跑过**的结果；未执行的 TC 一律留空，不预填「预计通过」。
>
> **本版执行状态：部分执行（进行中）**。已完成「导航唯一高亮」与「三源并集/组授权」两条主线，
> 其余 TC 未跑，原因见 §8.2。**因此 v5.9.0 不得宣称完成，门禁不放行。**

### 8.1 已执行

| TC | 方式 | 结果 | 证据 |
|---|---|---|---|
| TC-v5.9-01 | 单测（同组消歧） | 通过 | `navigation.spec.ts` 「典型复现路径与详情页」17 条样本逐条断言只亮 1 个 |
| TC-v5.9-02 | 单测 + 旧口径计数对照 | 通过 | 同文件「alsoMatch 裸前缀回归」：`/g/assets?tab=credentials` 旧口径 2 → 新口径 1 |
| TC-v5.9-03 | 单测（不变量） | 通过 | 「每个叶子用自己的 path+query 访问只有它亮」+「路由表全量静态路径 ≤1」，61 条全绿 |
| TC-v5.9-04 | 反向回放 | 通过 | 临时回退 `navigation.ts`/`AppNav.vue` 到 HEAD：5 条判红（`expected 2 to be 1`），见 `docs/evidence/v5.9.0/nav/replay-on-v5.8.md` |
| TC-v5.9-09 | 机检 + `--self-test` | 通过 | `check_visibility_sql.py` PASS；注入违规 SQL → 判红 → 还原 → 判绿 |
| TC-v5.9-24 | 单测（幂等） | 通过 | `AccessControlVisibilityTest.addMemberIsIdempotent`：不新增行、不抛 409、角色并集 |
| TC-v5.9-37 | 真实库读数 | 通过 | V11_66 建外键后两表孤儿均为 0，FK 计数 4，见 `docs/evidence/v5.9.0/sql/orphan-before.md` |
| TC-v5.9-05/06/07/10/11 | 真实库读数（SQL 层） | 通过（部分） | 三源并集读数：`qa-e2e-engineer` 旧 1 → 新 2（组授权）、`qa-e2e-pm` 旧 1 → 新 3（负责人），见 `visibility-union.md`。**API 层未跑** |
| TC-v5.9-12~15 | 机检 + 单测 | 通过 | 全局导航 6 组 19 叶：`check_navigation.py` PASS；`navigation.spec.ts` 124 条全绿（含 6 组 19 叶断言） |
| TC-v5.9-16~20 | 真实接口 + 单测 | 通过 | ENGINEER 访问 `/api/users` → 403（`AuthzMatrixTest` 113 条、`PermissionMatrixTest` 484 格）；`/api/iam/relations` 三向同源，字段统一 camelCase |
| TC-v5.9-33~36 | 视图单测 | 通过 | `IamUsersView.spec` / `IamGroupsView.spec` 共 6 条：详情在列表下方（`data-testid=iam-user-detail` / `iam-group-detail`）、计数 = 列表条数、删除必过 `confirmAction` |
| TC-v5.9-41~45 | 机检 + 真实脚本 | 通过 | `check_demo_data.py` PASS 且 `--self-test` 注入判红；`purge-demo.sh` dry-run 真实读数 2 个演示项目、0 用户，未删任何行（`docs/evidence/v5.9.0/demo/purge-dryrun.md`） |

### 8.2 未执行与原因

| 范围 | 状态 | 原因 |
|---|---|---|
| TC-v5.9-15~19（4 个 IAM 页面与菜单） | 未开始 | 页面未实现。**按 D-01，菜单项也未添加**——不留空壳 |
| TC-v5.9-20~23（`/api/iam/relations` 聚合） | 未开始 | 端点未实现 |
| TC-v5.9-25~27（批量幂等计数 / 前端提示） | 部分 | 服务端幂等已改；`added/skipped` 计数与前端提示未做 |
| TC-v5.9-28~32（列表 CRUD 通则、`check_list_crud`） | 未开始 | — |
| TC-v5.9-33~36（删用户/删组影响面） | 部分 | 删除清理已改（`project_member_role`、`user_group_member`），影响面端点未做，真实删除请求未跑 |
| TC-v5.9-38~41（能力闸同源、角色管理页） | 未开始 | 依赖 4 个 IAM 页 |
| TC-v5.9-42~46（演示标签 / `purge-demo.sh` / `check_demo_data`） | 部分 | `is_demo` 列与索引已落库；标签、清理脚本、机检未做 |
| TC-v5.9-47（`created_by`） | 通过（结构） | 列既有、索引已建、`insert` 已补列；未发真实新建请求 |
| TC-v5.9-48/49（owner 转移事务） | 未开始 | 未改动 `transferOwner` |

### 8.3 实测推翻的假设（回写要求：逐条改掉，不悄悄改数字）

| # | 原假设（DEV-PLAN / PRD 写的） | 实测 | 处置 |
|---|---|---|---|
| A1 | `project.created_by` 列不存在，需要 V11_67 新建 | **列早已存在**（历史审计列批次已覆盖 project 表）。首版迁移在开发库判红 `Duplicate column name` | V11_67 改为**只建索引**，并把修正写进迁移注释与 `V11_67...down.sql`（不再删列） |
| A2 | 「我参与的项目只有一个」可由现有 dev 库复现 | dev 库里每个 owner 都已在 `project_member` 有行、且无任何组授权 → 旧口径读数与新口径**完全相同** | 造夹具（`visibility-fixture.sh`，两个项目打 `is_demo=1`）才复现：旧 1 / 新 2~3 |
| A3 | 「SQL 里 `project_member` 字面量在 iam 包只出现 1 次」是可机检判据 | 审计资源名 `resource="project_member"` 有 6 处，字面量计数必然 >1 | 机检改为「SQL 关键字紧跟表名」才算违规，见 `check_visibility_sql.py` |
| A4 | FR-v5.9-04 判据 1「全局导航 6 组 20 叶」 | 实测 **19 叶**：新增 iam 4 叶，但「系统设置 → 用户管理」按 W59-15「不保留第二套写入口」迁出，设置组 6→5 | 门禁与单测按 **6 组 19 叶**定稿；PRD 判据 1 的 20 是起草时按「保留旧入口」估的，属起草偏差，已在 `navigation.spec.ts` 注释与本表登记 |
| A5 | 组级 `capability` 可省，只给 4 个子项标 `user:write` 即可 | 实测过滤后留下一个**空折叠组**（只剩标题、无可点项），`filterNavByCapabilities` 不丢弃空组 | 给 `global-iam` 组本身加 `capability: 'user:write'`，整组随能力消失 |
| A6 | `DataTable` 有 `row-click` 事件 | 没有。可用的只有 `rowProps` | 三页改用 `rowProps` 注入 `onClick`，行内按钮加 `stopPropagation`（否则点删除会先打开详情） |
| A7 | naive-ui 组件已全局注册 | **未注册**，各页面显式 import。缺 import 时模板静默渲染成空标签、不报错 | 4 个 IAM 页显式 `import { NButton, NInput, NSelect } from 'naive-ui'` |

---

## 9. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：49 条 TC 覆盖 11 条 FR；P0 五条要求一级证据，四条要求回放 | `PRD.md` v5.9.0 + `DEV-PLAN.md` |
| 2026-11-15 | 首次执行回写：8 条通过 / 4 条部分 / 其余未执行；§8.3 记录 3 条被实测推翻的假设 | 实际运行日志 + 开发库读数 |
