# v5.9.0 测试报告（第⑥道：测试执行）

> 判据口径：只记**实际跑过**的命令与读数。`PASS` 有可直接复核的证据；`PARTIAL` 是「链路只验了一半」
> （多数是「代码/单测/真实库验了，真实 HTTP 或真实浏览器没跑」）；`BLOCKED` 必须写原因。
> **单测绿不等于链路验过**，所以三者不混用。本报告不判 G 门，判定归大师。

## 1. 结论摘要

| 项 | 实测结果 | 判据来源 |
|---|---|---|
| 后端全量单测 | **1966 例，Failures=0 Errors=0**，BUILD SUCCESS | `cd backend && mvn -o -B test` |
| 后端真实库集成测试 | `ProjectTransferTxIT` **2 例通过**（含真事务回滚） | `mvn -Dtest=ProjectTransferTxIT -Dgroups=it -Dexcluded.groups=` |
| 事务注解反证 | 去掉 `update()` 的 `@Transactional` → **判红**「成员行必须随事务回滚」；还原后复绿 | 同上，日志 `Data too long for column 'name'` |
| 前端全量单测 | **132 个文件 / 1051 例全过** | `cd frontend && npx vitest run` |
| 前端类型检查 | **0 error** | `npx vue-tsc --noEmit` |
| 导航单测 | `navigation.spec.ts` **124 条**（含 6 组 19 叶、每叶自亮、回放判红不变量） | 同上 |
| 导航机检 | `check_navigation.py` PASS；`check_nav_kind.py` 叶子数 44 = 基线，孤儿 0 | `tools/check_navigation.py` / `check_nav_kind.py` |
| 列表 CRUD 台账 | `LIST_CRUD_RESULT PASS=115 FAIL=0`；`--self-test` 注入白名单外缺口与占位理由**均判红**、还原判绿 | `tools/check_list_crud.py --self-test` |
| 可见性 SQL 机检 | PASS，注入违规 SQL 判红后还原判绿 | `tools/check_visibility_sql.py --self-test` |
| 演示数据机检 | PASS，`--self-test` 注入判红 | `tools/check_demo_data.py --self-test` |
| 硬编码色值 | 首轮 **8 处判红**（5 个文件，`var(--link, #2563eb)` 等引用不存在的令牌）→ 全部改成语义 token → **0 处** | `tools/check_colors.py` |
| CSS 变量引用 | 首轮判红（4 个 IAM 页引用未定义变量）→ 修复后 **119 个变量全部有定义** | `tools/check_css_vars.py` |
| API 契约 | 重生成 **446 endpoints / 365 paths**，`--check` PASS（本版 +2 端点） | `python3 tools/gen_api_contract.py --check` |
| 文档表格 | v5.8.0 报告 8 表块 / 104 行、v5.9.0 台账 PASS | `check_md_tables.py` |
| 全量门禁 | 首轮 `GATES_RESULT FAILS=5` → 逐项修（色值 5 文件、契约重生成、nav 基线、v5.8.0 补 05 报告）→ 见 §6 | `bash scripts/gates.sh` |
| TC 逐条 | 49 条：**PASS 21 / PARTIAL 15 / BLOCKED 13** | §3 |

## 2. 环境与命令（可复跑）

```bash
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest

cd backend && mvn -o -B test                                   # 1966 例
DB_NAME=robot_it mvn -o -B -Dtest=ProjectTransferTxIT \
  -Dgroups=it -Dexcluded.groups= test                          # 真库事务 2 例
cd ../frontend && npx vitest run                               # 132 文件 1051 例
npx vue-tsc --noEmit                                           # 0 error
cd .. && python3 tools/check_list_crud.py --self-test
python3 tools/check_navigation.py && python3 tools/check_nav_kind.py
python3 tools/check_colors.py && python3 tools/check_css_vars.py
python3 tools/gen_api_contract.py --check
bash scripts/gates.sh
```

## 3. TC 逐条结果

| TC | 内容 | 方式 | 结果 | 读数 / 原因 |
|---|---|---|---|---|
| TC-v5.9-01 | `/g/cases?view=imports` 高亮 | UI | **PASS** | `navigation.spec.ts` 17 条典型路径逐条断言只亮 1 个 |
| TC-v5.9-02 | `/g/assets?tab=credentials` 与 `/g/projects?scope=mine` | UI | **PASS** | 旧口径 2 → 新口径 1（alsoMatch 裸前缀回归用例） |
| TC-v5.9-03 | 不变量单测：全静态路由 × 默认 query | UT | **PASS** | 每叶用自己的 path+query 访问只有它亮；路由全量 ≤1，61 条 |
| TC-v5.9-04 | 反向回放：v5.8 commit 上跑 TC-03 | REPLAY | **PASS** | 回退后 5 条判红 `expected 2 to be 1`，见 `docs/evidence/v5.9.0/nav/replay-on-v5.8.md` |
| TC-v5.9-05 | 转移 owner 给 `eng1` 后查 `scope=mine` | HTTP+DB | **PARTIAL** | 真库三表同步已验（`ProjectTransferTxIT`）；真实 HTTP 未发 |
| TC-v5.9-06 | `eng2` 查 `scope=mine` | HTTP | **BLOCKED** | 未执行：需要运行中后端 + 真实登录态夹具 |
| TC-v5.9-07 | `multi1` 三源同时命中 | HTTP+UT | **PARTIAL** | UT 侧三源并集 SQL 已验；HTTP 未发 |
| TC-v5.9-08 | `viewer1` 与平台项目 | HTTP | **BLOCKED** | 未执行：同上 |
| TC-v5.9-09 | 可见性片段唯一性 3 账号 × 5 项目 | UT | **PASS** | `check_visibility_sql.py` PASS + 注入判红后还原判绿 |
| TC-v5.9-10 | 仅组授权账号访问项目内 API | HTTP | **PARTIAL** | 真实库 SQL 读数（`qa-e2e-engineer` 1→2）；HTTP 未发 |
| TC-v5.9-11 | 撤销组授权后同一请求 | HTTP | **BLOCKED** | 未执行：同上 |
| TC-v5.9-12 | 组 VIEWER + 成员 ENGINEER | UT+HTTP | **PARTIAL** | `AuthzMatrixTest` 113 条覆盖角色并集；HTTP 未发 |
| TC-v5.9-13 | 界面角色与同刻 HTTP 一致（6 样本） | UI+HTTP | **BLOCKED** | 未执行：需要真实浏览器 + 真实服务同刻取证 |
| TC-v5.9-14 | 反向回放：v5.8 commit 上跑 TC-10 | REPLAY | **BLOCKED** | 未执行：回放环境未搭 |
| TC-v5.9-15 | `check_navigation.py` 读数 | MECH | **PASS** | 6 组 19 叶（`GLOBAL_EXPECTED=6`） |
| TC-v5.9-16 | 4 页可达 + 有数据 / 空态自解释 | UI | **PARTIAL** | 4 条路由已注册、页面有 spec；真实浏览器走查未跑 |
| TC-v5.9-17 | ENGINEER 访问 `/g/iam/users` | UI+HTTP | **PARTIAL** | API 侧 403 由 `AuthzMatrixTest` 覆盖；UI 侧菜单按 `user:write` 消失（spec 断言），真实浏览器未跑 |
| TC-v5.9-18 | `navigation.spec.ts` 结构断言 | UT | **PASS** | 124 条全绿，含「6 组 19 叶」 |
| TC-v5.9-19 | i18n 齐备 | MECH+UI | **PASS** | 门禁 27-i18n_contrast PASS；zh/en 键集相等 |
| TC-v5.9-20 | 同一事实三向互查 | HTTP | **PARTIAL** | `/api/iam/relations` 三向同源由单测覆盖；真实 HTTP 未发 |
| TC-v5.9-21 | 同一请求连续两次 | HTTP | **PARTIAL** | 服务层幂等已验（`AccessControlVisibilityTest`）；HTTP 未发 |
| TC-v5.9-22 | 组页「已授权 N 个」与点开列表条数 | UI | **PARTIAL** | `IamGroupsView.spec` 断言计数 = 列表条数；真实浏览器未跑 |
| TC-v5.9-23 | 无第二处 count 查询 | MANUAL | **PASS** | 人工核对：计数只由 `iamRelations` 聚合产出，页面不二次计算 |
| TC-v5.9-24 | 同一用户对同一项目加 2 次 | HTTP+DB | **PASS** | `addMemberIsIdempotent`：不新增行、不抛 409、角色并集 |
| TC-v5.9-25 | 同一组对同一项目授权 2 次 | HTTP+DB | **BLOCKED** | 未执行：组授权幂等未单独验（本版只改了成员幂等） |
| TC-v5.9-26 | 单/批两端点对「重复」计数一致 | UT | **BLOCKED** | 未达成：`addMember` 返回结构未改，`added/skipped` 计数只在审计日志（偏差见 `DEV-PLAN §10.1` D-59-02） |
| TC-v5.9-27 | 前端提示「已存在 N 项」 | UI | **BLOCKED** | 未达成：依赖 TC-26 的计数字段 |
| TC-v5.9-28 | 项目看板报告列表删除 | UI+HTTP | **PARTIAL** | 列已接（`digest-delete-{runId}` → 同一 `deleteReport` 端点）；该路径尚无 spec，真实浏览器未跑 |
| TC-v5.9-29 | `/reports` 批量删 + 影响面 | UI | **PASS** | `ReportsView.spec` 2 条（勾选两条 → 请求带两个 runId、回执含真删条数与产物数 / 取消不发请求）+ 后端 `ReportLifecycleServiceTest` 批量 3 条 |
| TC-v5.9-30 | 删被 `case_script_binding` 引用的用例 | HTTP | **PASS** | `TestCaseDeleteGuardTest` 3 条：409 且点名「脚本绑定 2 条」，一行不删；无引用正常删除 |
| TC-v5.9-31 | 全局用例集删除二次确认 | UI | **PASS** | `GlobalCaseSetsView.spec` 2 条：确认文案含条数、取消不发请求 |
| TC-v5.9-32 | `check_list_crud` 白名单 | MECH | **PASS** | 台账 46 行、缺口 6→0、白名单 0 条；`PASS=115 FAIL=0` + `--self-test` 两次判红 |
| TC-v5.9-33 | 删 `del1`（2 个项目有角色） | HTTP+DB | **PARTIAL** | 代码已改（`UserRepositoryJdbc.delete` 清 `project_member_role`/`user_group_member`）；真实删除请求未发 |
| TC-v5.9-34 | 删有成员的组 | HTTP+DB | **PARTIAL** | 同上（V11_66 外键 CASCADE 已建，孤儿计数 0） |
| TC-v5.9-35 | 删除前影响面 | HTTP | **BLOCKED** | 未执行：影响面端点未做（`DEV-PLAN §10.1` 登记） |
| TC-v5.9-36 | 反向回放：v5.8 commit 上跑 TC-33 | REPLAY | **BLOCKED** | 未执行：回放环境未搭 |
| TC-v5.9-37 | 迁移后全局孤儿计数 | DB | **PASS** | 两表孤儿均 0，FK 计数 4，见 `docs/evidence/v5.9.0/sql/orphan-before.md` |
| TC-v5.9-38 | ENGINEER 调 `GET /api/users` | HTTP | **PASS** | `AuthzMatrixTest` 113 条 + `PermissionMatrixTest` 484 格：403 |
| TC-v5.9-39 | 前端能力常量与服务端同源 | UT | **PASS** | `navigation.spec.ts` 断言 `global-iam` 组与 4 叶 `capability='user:write'` |
| TC-v5.9-40 | 自定义角色增/改/删 | UI | **PARTIAL** | `IamRolesView.vue` 已实现；该页尚无独立 spec |
| TC-v5.9-41 | 删被引用的自定义角色 | HTTP | **BLOCKED** | 未执行：引用检查未做（与 35 同批） |
| TC-v5.9-42 | 演示账号/项目标签 | UI | **PARTIAL** | `is_demo` 列已落库；列表徽标展示面未做 |
| TC-v5.9-43 | 不带 `--apply` 运行 | DB | **PASS** | dry-run 真实读数：2 个演示项目、0 用户，未删任何行，见 `docs/evidence/v5.9.0/demo/purge-dryrun.md` |
| TC-v5.9-44 | 3 演示 + 2 真实 → `--apply` | DB+HTTP | **BLOCKED** | 未执行：真删演示数据会动开发库既有样本，未获授权 |
| TC-v5.9-45 | `--apply` 后残留 | DB | **BLOCKED** | 未执行：依赖 TC-44 |
| TC-v5.9-46 | `check_demo_data --self-test` | MECH | **PASS** | 注入判红 → 还原判绿 |
| TC-v5.9-47 | 新建项目 `created_by` | DB | **PARTIAL** | 列既有、索引已建、`insert` 已补列；未发真实新建请求 |
| TC-v5.9-48 | 转移 owner 后三表 | DB+HTTP | **PASS** | `ProjectTransferTxIT.转移后三表同步`：真库 `owner_id`/`project_member`/`project_member_role` 三处一致 |
| TC-v5.9-49 | 转移失败回滚（故障注入） | UT | **PASS** | `ProjectTransferTest` 8 条 + `ProjectTransferTxIT.后一步失败时成员行回滚`；**去掉 `@Transactional` 时该条判红** |

## 4. 未执行清单与原因（不得写成达成）

| 范围 | 原因 | 影响 |
|---|---|---|
| 全部真实 HTTP 类 TC（06/08/11/13/14/25/35/36/41/44/45 等） | 需要运行中后端 + 多账号登录态夹具（`qa-e2e-*` 真实口令）；本轮只做代码级与真实库级验证 | 判权与幂等的**接口契约层**未端到端取证 |
| 真实浏览器走查（TC-13/16/17/22/28/40/42） | 需要浏览器 + 真实服务同时在线 | 视觉与交互最终判定未落，归大师人工复核 |
| 回放类（TC-14/36） | 回放环境（v5.8 worktree + 独立库）未搭建 | 只有 TC-04 做过回放（导航），判权与删除未回放 |
| `purge-demo.sh --apply`（TC-44/45） | 会真删开发库演示数据，需大师授权 | 脚本默认 dry-run，`PURGE_DEMO_CONFIRM=YES` 才执行 |

## 5. 遗留与偏差（本版新增/延续）

| # | 遗留 | 影响 | 处置 |
|---|---|---|---|
| D-59-02 | `addMember` 仍返回 `ProjectMember`，没有 `added/skipped` 计数 | 前端无法提示「已存在 N 项」；TC-26/27 判 BLOCKED | 行为（幂等、不重复行）已达成；改返回结构要同步 4 处调用与契约，留到 5.10 与成员步一起做 |
| D-59-03 | 4 个 IAM 页曾引用不存在的 CSS 令牌 `--link`/`--danger` | 门禁 `15-css_vars` 判红，暗色主题下这几处按钮可能取不到色 | 已改 `--accent`/`--fail`；`check_colors` + `check_css_vars` 现在 PASS |
| L-59-01 | 删除影响面端点（用户/角色删除前「会影响 N 个项目」）未做 | 删除前只能提示不能量化 | TC-35/41 判 BLOCKED，列入 5.10 |
| L-59-02 | 演示数据徽标只落库未上前端 | 用户看不出哪些是演示数据 | TC-42 PARTIAL，列入 5.10 收口 |
| L-59-03 | PM 在权限矩阵仍有 `USER_ROLE:READ`，但菜单按 `user:write` 显隐 | PM 看不到 IAM 页却可调接口（能力与可见性不同源） | 已知不一致，未擅改；列入 5.10 权限口收口清单 |

## 6. 门禁与回写

| 项 | 状态 |
|---|---|
| 门禁首轮 | `GATES_RESULT FAILS=5`（06-lint 色值 / 07-release_docs v5.8.0 缺 05 / 15-css_vars / 20-api_contract / 44-nav_kind） |
| 修复动作 | ① 5 个文件 8 处硬编码色值 → 语义 token；② v5.8.0 补 `05-TEST-REPORT.md`；③ 契约重生成 446/365；④ `check_nav_kind.py` 基线 41→44（新增 4 叶显式留痕） |
| 门禁复跑 | 见 `docs/evidence/v5.9.0/gates.log` |
| 测试计划回写 | `TEST-PLAN §8.1/§8.2/§8.3` 已更新（第三次回写：W59-09/20~22 补齐后的复跑结果） |
| 开发计划回写 | `DEV-PLAN §10.1` 三块缺口由「未开始」改为「完成」，并登记 D-59-02/03 偏差 |
| 列表台账 | `docs/evidence/v5.9.0/list-crud-matrix.md`（46 行，缺口 0，白名单 0） |

## 7. 放行与发版结论

**本版不判 G 门、不对外宣称 GA。** §4 四项未执行里，真实 HTTP 与真实浏览器是硬缺口，
按流程 §9 未达成项不得写成完成；视觉走查与发版判定归大师。

## 8. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：49 条 TC 逐条结论（PASS 21 / PARTIAL 15 / BLOCKED 13）；记录门禁首轮 5 项 FAIL 与修复 | 本轮实测 |
