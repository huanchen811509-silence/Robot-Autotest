# EW42-05 · 横向回归 / 全量门禁 / ⑦ 回写（4.2 收口）

> 包：`W42-05`；覆盖 4.2 全部 FR（`FR-v4.2-01` 至 `FR-v4.2-04`）。落号原不在本包职责内（`DEV-PLAN §5`），
> **2026-11-10 大师书面确认后已 bump 至 `4.2.0`**，四副本一致、六份发版产物齐（见 §5）。

## 1. 结论

| 项 | 结果 | 证据 |
|---|---|---|
| 全量门禁 32 步 | `GATES_RESULT FAILS=0`（32/32 PASS） | `gates.log` |
| 门禁自检 | `SELFCHECK_EXIT=0`，`SELFCHECK_RESULT FAILS=0` | `gates-selfcheck.log` |
| 表格规范（第 6 步） | `[lint] PASS`；`WorkspaceView.vue` 三张原生表改 `DataTable` 壳后无违规 | `lint.log` |
| 契约快照（第 20 步） | 重生成后 `--check` 不漂移（291 endpoints / 232 paths） | `gates.log` 第 20 步 PASS |

## 2. 本轮门禁阻断与修复

| 步 | 失败原文 | 修复 | 复验 |
|---|---|---|---|
| `06-lint` | `[FAIL] views/WorkspaceView.vue 出现原生 <table> 3 处` | 导出包表 / 导入预检表 / 任务表改为项目 `DataTable` 壳，列定义抽成 `computed<DataTableColumns<T>>` | `check_tables.py` PASS、`vue-tsc` 0、`eslint --max-warnings 0` 0、门禁第 6 步 PASS |

未采用「把 `WorkspaceView.vue` 加进白名单」的替代路径：白名单只保留 `ReportPrintView.vue`（打印页零库依赖）、`ReportDetailView.vue`（metadata 定义表）、`StyleguideView.vue`（设计系统演示）三个既有例外，不降门禁。

## 3. 环境坑位（复现时注意）

| 现象 | 原因 | 正确姿势 |
|---|---|---|
| 非登录 shell 跑 `--selfcheck` 出现 `第 13 步 FAIL（检测器未判红）` | selfcheck 第 13 步直调 `node`，非登录 shell 的 PATH 落到 Windows `node.exe` | `bash -lc "bash scripts/gates.sh --selfcheck"` |
| `mvn -o -B test -Dgroups=it` 报 `BUILD SUCCESS` 但 0 用例 | 默认 `excludedGroups=it` 与 `-Dgroups=it` 抵消 | `mvn -o -B -Pit test -Dtest=XxxIT` |
| IT 打到 `robot_dev` 被守卫拦下 | env 文件里 `DB_NAME=robot_dev` | 显式 `export DB_NAME=robot_it` |

## 4. `NOT-RUN`（如实保留）

| 项 | 原因 |
|---|---|
| 真实远端 SSH 采集（`TC-v4.2-09`） | 无受控目标主机，`W42-00` 未取得准入记录 |
| `gh run view` / `gh run download`（`TC-v4.2-03/04/12`） | 本机无 `gh`；`actionlint` 亦未安装 |

## 5. 回写落点

| 文档 | 改动 |
|---|---|
| `docs/plan/v4.x-总体演进-产品PRD-20261109.md` | 新增 `§9 验收状态`，14 条 FR 全部「待验」 |
| `docs/plan/v4.x-总体演进-测试计划-20261109.md` | 新增「4.2.0 实测结果」段，逐条 TC 结果 + `NOT-RUN` 原因 |
| `docs/plan/v4.x-总体演进-开发计划-20261109.md` | `§5` 偏差表补 `W42-00` 至 `W42-05` 行，修订记录加 v1.2 |
| `docs/release/v4.2.0/` 六份产物 | **已生产**（2026-11-10 落号后）：00-RESEARCH / 01-ASSESSMENT / PRD / DEV-PLAN / TEST-PLAN / 05-TEST-REPORT；`check_release_docs.py v4.2.0` → `[PASS] FR=5 TC=27`，`check_md_tables.py` PASS，`check_version_consistency.py PASS (0 项)` |
