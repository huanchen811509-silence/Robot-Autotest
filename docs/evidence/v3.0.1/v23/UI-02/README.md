# UI-02 项目卡牌聚合端点（`GET /api/projects/cards`）

> 日期 2026-11-08 ｜ 需求 PRD v2.3 FR-UI-09 / FR-UI-10（`docs/plan/v2.3-PRD-增补草案-待评审-20261108.md` §3）
> 结论：**端点已上线并跑通**。真实 HTTP 200，8 个项目一次返回；定向单测 **11/11 绿**；
> 全量回归 **`Tests run: 1061, Failures: 0, Errors: 0, Skipped: 0`**；非法窗口 **HTTP 400 / code 40001**。
> 原始件在同目录 `raw/`（含可复跑的取证脚本 `raw/collect-*.sh`）。

## 1. 代码落点

沿用现有分层，没有新包：

| 文件 | 职责 |
|---|---|
| `backend/src/main/java/com/qa/autotest/api/iam/ProjectCardController.java` | `GET /api/projects/cards`，单独一个控制器（避免 `/cards` 被 `ProjectController` 的 `/{projectId}` 当 long 解析） |
| `backend/src/main/java/com/qa/autotest/application/iam/ProjectCardService.java` | 窗口解析、可见范围复用、通过率与 `myRole` 口径、`isCurrent` 回填 |
| `backend/src/main/java/com/qa/autotest/application/repository/ProjectCardRepository.java` | 端口 + `ProjectCardAggregate`（只回传分子分母，不算通过率） |
| `backend/src/main/java/com/qa/autotest/infrastructure/ProjectCardRepositoryJdbc.java` | **一条 SQL**：五张表各自 `GROUP BY project_id` 后 LEFT JOIN 到 `project`，外层 `p.id IN (...)` |
| `backend/src/main/java/com/qa/autotest/domain/iam/ProjectCard.java` | 响应记录，无框架依赖（过 ArchUnit R1/R2） |
| `backend/src/test/java/com/qa/autotest/application/iam/ProjectCardServiceTest.java` | 窗口 / `isCurrent` / `passRateBp` / `myRole` / 聚合只查 1 次 |
| `backend/src/test/java/com/qa/autotest/application/iam/ProjectCardVisibilityTest.java` | 只输出 `visible()` 的 2 个项目，聚合 id 集合不带全表 |

可见范围**直接调 `ProjectService.visible()`**（内部 `access.visibleProjects()`），服务层没有第二处权限判断；
卡牌的 `code/name/description/enabled/visibility/ownerId` 全部取自 `visible()` 返回的 `Project`，
`ProjectCardVisibilityTest` 里的 fake `findById` 直接抛 `AssertionError`，等于反证「没有另查一遍」。

## 2. 聚合口径（写进代码注释的那一份）

| 字段 | 口径 | 来源表 | 真实读数样例（2026-11-08） |
|---|---|---|---|
| `caseCount` | 该项目 `test_case` 行数 | `test_case` | 项目 77 → 813；项目 1 → 226 |
| `runCount` | 窗口内 `run` 行数，按 `COALESCE(started_at, created_at)`，**不限状态** | `run` | 项目 29 → 54（10 canceled + 27 failed + 17 finished） |
| `passRateBp` | 窗口内 `run_status='finished'` 的 `sum(passed_tests)/sum(total_tests)` × 10000；分母 0 → `null` | `run` | 项目 29 → 7222（26/36）；项目 1 → 8000（4/5）；项目 77 → `null` |
| `lastRunAt` | 该项目最近一条 run 的时间，**不限窗口** | `run` | 项目 29 → `2026-11-07T19:00:38.348Z` |
| `deviceCount` | 设备行数 | `device` | 项目 1 → 2，其余 → 0 |
| `memberCount` | 成员行数 | `project_member` | 项目 29 → 4；项目 1 → 3 |
| `myRole` | 当前用户的 `member_role`；全局 admin 且无成员关系 → `admin`；否则 `null` | `project_member` | 项目 77（qa-e2e-admin，全局 admin）→ `admin` |
| `isCurrent` | `current` 由调用方回传（当前项目是前端 localStorage 状态），`id == current` | — | `current=77` 时只有 77 为 `true` |

## 3. 命令原文

部署（源码对齐 ≠ 部署对齐，故必须重建 + 重启）：

```bash
set -a; . /home/evergreen/.config/robot-autotest/env; set +a
cd /home/evergreen/robot-autotest/backend
mvn -o -B -DskipTests package
systemctl --user list-units --type=service --all | grep -i robot
systemctl --user restart robot-autotest-backend.service
```

真实链路（完整脚本见 `raw/collect-http.sh`，逐字执行的就是它）：

```bash
BASE=http://127.0.0.1:8001
TOKEN=$(curl -s --noproxy '*' -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"qa-e2e-admin\",\"password\":\"$QA_E2E_PASSWORD\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')
curl -s --noproxy '*' "$BASE/api/projects/cards?window=7d&current=77" -H "Authorization: Bearer $TOKEN"
curl -s --noproxy '*' "$BASE/api/projects" -H "Authorization: Bearer $TOKEN"
curl -s --noproxy '*' "$BASE/api/projects/cards?window=90d" -H "Authorization: Bearer $TOKEN"
```

单测：

```bash
cd /home/evergreen/robot-autotest/backend
mvn -o -B test -Dtest=ProjectCardServiceTest,ProjectCardVisibilityTest
mvn -o -B test
```

## 4. 真实输出

`GET /api/projects/cards?window=7d&current=77`（`qa-e2e-admin`，HTTP 200，body 2663 字节），前 600 字符：

```json
{"code":0,"codeName":"OK","message":"OK","data":[{"id":77,"code":"ED202-COMMON","name":"ED202 公共用例库","description":"ED202 公共用例库（v2.2 SCEN-04 由 tools/seed_ed202_organization.py 经 HTTP 落库）","enabled":true,"visibility":"private","ownerId":17,"isCurrent":true,"caseCount":813,"runCount":0,"passRateBp":null,"lastRunAt":null,"deviceCount":0,"memberCount":1,"myRole":"admin"},{"id":76,"code":"a8-report-20261107144236","name":"A8 报告链路夹具 20261107144236","description":"A8 取证用隔离项目（3-run 聚合 / 冻结 / 导出 / 保留策略）","enabled":true,"v
```

`data` 长度 **8**，id 依次为 `77, 76, 75, 61, 44, 29, 2, 1`（沿用 `visible()` 的顺序，管理员路径是 id 倒序）。
逐项读数：

| id | code | caseCount | runCount | passRateBp | lastRunAt | deviceCount | memberCount | myRole | isCurrent |
|---|---|---|---|---|---|---|---|---|---|
| 77 | ED202-COMMON | 813 | 0 | null | null | 0 | 1 | admin | true |
| 76 | a8-report-20261107144236 | 0 | 2 | 10000 | 2026-11-07T06:41:39Z | 0 | 1 | admin | false |
| 75 | a8-report-20261107142938 | 0 | 2 | 10000 | 2026-11-07T06:30:00Z | 0 | 1 | admin | false |
| 61 | qa-e2e-platform-fixture | 0 | 0 | null | null | 0 | 1 | ADMIN | false |
| 44 | qa-e2e-orch02 | 0 | 0 | null | null | 0 | 1 | ADMIN | false |
| 29 | qa-e2e-fixture | 5 | 54 | 7222 | 2026-11-07T19:00:38.348Z | 0 | 4 | ADMIN | false |
| 2 | AST05-B | 0 | 0 | null | null | 0 | 1 | admin | false |
| 1 | demo | 226 | 2 | 8000 | 2026-11-06T02:16:48Z | 2 | 3 | admin | false |

非法窗口 `window=90d`（HTTP 400）：

```json
{"code": 40001, "codeName": "INVALID_ARGUMENT", "message": "非法的统计窗口：90d（仅支持 7d / 30d）", "data": null, "success": false}
```

独立 SQL 复核（不看接口代码，直接用库算一遍，见 `raw/db-crosscheck.txt`）：

| id | case_count | run_count_7d | pass_rate_bp_7d | last_run_at | device_count | member_count |
|---|---|---|---|---|---|---|
| 1 | 226 | 2 | 8000 | 2026-11-06 10:16:48.000 | 2 | 3 |
| 29 | 5 | 54 | 7222 | 2026-11-08 03:00:38.348 | 0 | 4 |
| 75 | 0 | 2 | 10000 | 2026-11-07 14:30:00.000 | 0 | 1 |
| 76 | 0 | 2 | 10000 | 2026-11-07 14:41:39.000 | 0 | 1 |
| 77 | 813 | 0 | null | null | 0 | 1 |

接口与库逐项一致。`lastRunAt` 的 8 小时差是时区：接口回 UTC（`...T02:16:48Z`），库里的 `datetime` 存的是本地时间（`10:16:48`）。

## 5. 判据表

| 判据 | 实测 | 原始件 |
|---|---|---|
| FR-UI-09：端点 200，`data` 为数组，每项 15 个字段（`id/code/name/description/enabled/visibility/ownerId/isCurrent/caseCount/runCount/passRateBp/lastRunAt/deviceCount/memberCount/myRole`） | HTTP 200；`data` 长度 8；字段顺序与需求逐字一致（`isCurrent` 未被 Jackson 改名成 `current`） | `raw/cards-admin-7d-current77.json`（另有 `*.pretty.json`） |
| FR-UI-09：`window` 支持 `7d` / `30d`，默认 `7d`，非法值 400 `INVALID_ARGUMENT` | 不带参数 → 200（默认 7d）；`window=30d` → 200；`window=90d` → 400 + `code=40001` + 中文文案 | `raw/cards-admin-no-current.json`、`raw/cards-admin-30d.json`、`raw/cards-bad-window.json` / `.status` |
| 参数边界：空串与坏类型 | `window=` 空串按默认 7d（200）；`current=` 空串按「不传」（200）；`current=abc` → 400「参数类型不正确：current」；无令牌 → 401 `UNAUTHENTICATED` | `raw/edge-cases.txt` |
| `current` 回传后 `isCurrent = (id == current)`，不传则全 `false` | `current=77` → 只有 77 为 `true`；省略 `current` → 无一条 `true` | `raw/cards-admin-7d-current77.json`、`raw/cards-admin-no-current.json` |
| FR-UI-10：可见范围与 `GET /api/projects` 逐 id 相等（复用 `visible()`） | admin：卡牌 8 个 = 列表 8 个，集合相同；engineer：卡牌 `[29]` = 列表 `[29]` | `raw/projects-admin.json`、`raw/cards-engineer-7d.json`、`raw/projects-engineer.json` |
| FR-UI-10 负向：非成员看不到该项目 | `qa-e2e-engineer`（仅项目 29 成员）返回 1 条且不含 77；`qa-e2e-nobody`（无角色无项目）返回空数组 | `raw/cards-engineer-7d.json`、`raw/cards-nobody-7d.json` |
| FR-UI-14 / 禁止 N+1：一次请求只查 1 次聚合 | 单测断言 `aggregate` 调用次数 `== 1` 且入参是全部可见 id；仓储是单条 `IN (...)` SQL；fake `findById` 调用即抛错 | `raw/mvn-targeted-test.log`、`ProjectCardServiceTest#defaultWindowIsSevenDays` |
| `passRateBp` 无分母为 `null`，不拿 0 冒充 | 项目 77（0 次运行）→ `null`；项目 29 → 7222；项目 1 → 8000；单测另断言 0/0 → `null` | `raw/cards-admin-7d-current77.json`、`raw/mvn-targeted-test.log` |
| `myRole` 兜底：成员角色 → 全局 admin 无成员关系 → `null` | 77 → `admin`（全局 admin 兜底）；29 → `ADMIN`（成员表原值）；engineer 视角 29 → `ADMIN`；无角色用户 → 空列表无从触发 | `raw/cards-admin-7d-current77.json`、`raw/mvn-targeted-test.log` |
| 定向单测 | `Tests run: 11, Failures: 0, Errors: 0`（Service 8 + Visibility 3），`BUILD SUCCESS` | `raw/mvn-targeted-test.log` |
| 全量回归不弄红既有用例 | `Tests run: 1061, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`（基线 1050 + 本包 11） | `raw/mvn-full-test.log` |
| 无 DDL、无迁移、未 bump 版本、未 commit | 变更集只有 7 个 Java 文件 + 本证据目录；`db/migration/` 无改动 | `git status --porcelain`（见下方说明） |

## 6. 与 PRD 口径表的差异（如实记录）

需求方在派单里把口径写死，本包按派单实现，与 PRD §3「卡牌字段的来源口径」表有三处出入，**代码注释里也写了**：

| 字段 | PRD 表写法 | 本包实现 | 原因 |
|---|---|---|---|
| `caseCount` | 「启用中的用例数」 | `test_case` 全部行数 | 派单明确「= 该项目 test_case 行数」；当前库中 1044 条用例 `status` 全为 1，两种口径读数相同（813 / 226 / 5），但一旦引入停用用例，卡牌会与用例页总数分叉，届时需二选一 |
| `runCount` | 「近 N 天已结束运行数」 | 近 N 天全部运行（含 failed / canceled） | 派单明确「窗口内 run 行数」；卡牌语义是「跑过几次」，项目 29 若只算 finished 会从 54 掉到 17 |
| `passRateBp` | 「用例级通过率（来源 `test_result`）」 | `run.passed_tests / run.total_tests`（只取 finished） | 派单口径；两者在 `test_result` 完整时应当接近，但 `run` 表的分子分母是执行器落库时就写好的，不依赖结果行是否回写完整 |

另：`window=30d` 与 `window=7d` 当前读数完全相同——库里最早一条 run 是 `2026-11-04 15:58:15`，整库 60 条 run 全落在 7 天窗口内，不是窗口参数没生效（`raw/db-crosscheck.txt` 末段有时间范围）。

## 7. 本包没做的部分

- **前端未动**（硬约束）：`frontend/` 整目录未修改。工作区里已有的 `frontend/src/api/projectCards.ts`、`WorkbenchView.vue`、`ProjectCard.vue` 等改动不是本包产生的。
- **没做浏览器取证**：FR-UI-11/12/13/14 的组件级判据属前端包，本包只保证接口侧「一次请求给全量数据」。
- **`myRole` 大小写原样透传**：库里 `project_member.member_role` 同时存在 `admin` 与 `ADMIN`（历史种子数据），接口不统一大小写——统一需要一次数据清洗或映射策略，属另一个决定，未在本包擅自改。
