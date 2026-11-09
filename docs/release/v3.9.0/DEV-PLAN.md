# v3.9.0 开发计划（RA-DVP-39）

> 上游：`docs/plan/v3.9+版本演进-开发与测试计划-20261109.md`。本文件是**发布口径**的 WBS 与验收命令。
> 执行顺序：B0 基线 → B1（EW39-01 → 04 → 02 → 03 → 05）→ B2 收口。

## 1. WBS 与验收命令

| WBS | 内容 | 改动面 | 验收命令 | 状态 |
|---|---|---|---|---|
| EW39-01 | 暗色下拉可读 | `main.css`、`SettingsView.vue`、`dark-select-probe.mjs`、`select-theme.spec.ts` | `node scripts/dark-select-probe.mjs`（暗/亮各一次，`flagged=0`） | 完成 |
| EW39-04 | 列表密度与工作区利用 | `useListPageSize.ts`、`DataTable.vue`、6 个列表视图、`main.css`、`density-probe.mjs` | `node scripts/density-probe.mjs`（空白 ≤120px） | 完成 |
| EW39-02 | 双内核命令契约 | `V11_24`、`CaseCommandExtractor`、`CaseNode`、`NormalizedCaseResult`、`OutputXmlParser`、`PytestKernel`、`ReportRepositoryJdbc`、`RunCaseRow`、`ReportTreeService`、`case_command_report.py`、`check_case_command.py`、`gates.sh` | `python3 tools/check_case_command.py` + `bash scripts/gates.sh --selfcheck` | 完成 |
| EW39-03 | 报告页命令列 | `ReportReadRepositoryJdbc`、`DocxExporter`、`SuiteTree.vue`、`ReportPrintView.vue`、`useClipboard.ts`、`PytestLogPersister`、`JsonlLogReader`、`ReportLogService`、`ReportFileLocator` | 截图 + `verbatim.py`（10 条 `mismatches=0`）+ 导出校验 | 完成 |
| EW39-05 | 模板分类卡片 + 预置 | `ReportTemplatesView.vue`、locales、`ReportTemplatesView.spec.ts` | 探针 `templates` 段（21/4/4/≤1 屏） | 完成 |
| EW39-06 | 侧栏收起可逆 | `main.css`、`AppSide` 相关、`side-toggle-probe.mjs` | `node scripts/side-toggle-probe.mjs`（`FAILS=0`） | 完成 |
| EW39-07 | 关于/版本迭代同步 | `AboutView.vue`、`CHANGELOG.md` 双副本、`about-changelog-probe.mjs` | `node scripts/about-changelog-probe.mjs` + 门禁第 23 步 | 完成 |

## 2. 迁移与部署

| 项 | 内容 |
|---|---|
| 迁移 | `db/migration/V11_24__v3_9_case_command.sql`：`test_result` 增 `command_text TEXT`、`command_source VARCHAR(24)`、`command_exit_code INT`（均可空，历史行留 NULL） |
| 部署 | 重建 jar → `systemctl --user restart robot-autotest-backend` → 前端 `vite build`（`:8001` 读 dist） |
| 兼容 | 老报告重解析后才有命令列；未重解析的历史 run 显示「未采集（v3.9.0 之前的报告）」 |

## 3. 门禁变更

| 步骤 | 变更 |
|---|---|
| 第 28 步 | 新增 `case_command`：静态契约（迁移/提取器/读取链/前端/导出五层）+ 证据覆盖率（双内核 100%） |
| `--selfcheck` | 新增「删步骤 28 必须变红」与「五类脏数据必须判红」两条负向实证 |

## 4. 交付清单

- 代码：后端 9 个文件、前端 12 个文件、工具 2 个、脚本 4 个（探针/门禁）、迁移 1 份 + 回滚 1 份；
- 文档：本目录六份产物 + `docs/evidence/v3.9.0/README.md` 索引；
- 数据：两个演示套件 `v39/command_demo.robot`、`v39/test_command_demo.py`（各 7 条命令用例）。

## 5. 偏差记录

| 项 | 计划 | 实际 | 原因 |
|---|---|---|---|
| EW39-02 提取策略 | 读命中关键字自身消息 | 改为前序遍历子树 | 真实 run 实测 3/3 `NONE`（命令在子关键字 `Log` 消息上） |
| EW39-03 范围 | 只加命令列 | 追加 pytest 日志落盘（`case-log.jsonl`） | pytest 日志抽屉恒空，不修则「命令与日志联动」对该内核不成立 |
| EW39-03 导出 | 只改 Word | Word 与打印页同时改 | 打印页是 PDF 的唯一来源，只改 Word 会造成两种导出不一致 |
| EW39-02 回滚 | 未列回滚脚本 | 补 `db/down/V11_24__v3_9_case_command.down.sql` + 真实 down→up 演练 | 全量单测抓到 `DownScriptGuardTest` 报「up 58 / down 57」；只补静态配对等于没验过 SQL |
| EW39-07 版本号 | 只要求四副本一致 | 同时把 `CHANGELOG` 补齐 v3.5.0–v3.8.0 四条并落号 3.9.0 | `VERSION` 自 3.4.0 起未再 bump，而 3.5–3.8 已有完整版本文档；不补齐则 3.9.0 直接跳号，版本迭代页断档 |
