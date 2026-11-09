# v3.1.0「顺手」证据包（experience-20261108）

> 对应 PRD 的 FR-3.1-01 ~ FR-3.1-10、开发与测试计划的 EW31-01 ~ EW31-11。
> 本目录只放真实产物：命令与原始日志一一对应，未执行项单列并说明原因，不补造数值。

## 1. 范围

本批次完成 v3.1.0「顺手」版本的编码与自动化验证，覆盖：

| FR | 内容 | 主要落点 |
|---|---|---|
| FR-3.1-01 | 设计令牌与 UI 原语统一 | `frontend/src/components/ui/*`、`columnSettings.ts` |
| FR-3.1-02 | 全局实体搜索 | `SearchController` / `SearchService` / `CommandPalette.vue` |
| FR-3.1-03 | 保存视图 | `saved_view` + `/api/projects/{id}/views` + `useSavedViews` |
| FR-3.1-04 | 批量操作与撤销 | `batch_undo` + `/api/projects/{id}/batch/undo` + `SelectionBar` |
| FR-3.1-05 | 表单草稿 | `useDraft` + `CasesView` |
| FR-3.1-06 | 运行预检分级与 ETA | `PrecheckController` / `RunEtaCalculator` / `RunsView` |
| FR-3.1-07 | 报告速览 | `/api/reports/{runId}/summary` + `ReportSummaryCard` |
| FR-3.1-08 | 通知中心 | `notification` + `notification_read` + `notification_preference` |
| FR-3.1-09 | 快捷键与新手引导 | `ShortcutPanel` / `OnboardingChecklist` / `keyboard.ts` |
| FR-3.1-10 | 无障碍与降级 | `useFocusTrap` / skip link / `prefers-reduced-motion` |

代码规模：`git status` 显示 36 个修改文件 + 71 个新增文件（不含 `target/`、`dist/`）。

## 2. 环境

| 项 | 值 |
|---|---|
| 系统 | Windows（win32），PowerShell |
| Node / npm | v22.16.0 / 10.9.2 |
| Java / Maven | 23.0.2 / 3.9.16 |
| Python | 3.13.3 |
| 仓库 HEAD | `4dea84a`（工作区含本批未提交改动） |
| 版本号 | `VERSION` = `3.1.0`；`CHANGELOG.md` 顶部 `v3.1.0-experience` |

## 3. 命令与原始日志

| 日志 | 命令（工作目录） | 结果 |
|---|---|---|
| `mvn-test.log` | `mvn -q test`（`backend/`） | 1142 用例：11 失败 / 2 错误 / 4 跳过 → 见 §4 |
| `frontend-test.log` | `npm run test`（`frontend/`） | 70 文件 / 479 用例全绿 |
| `frontend-build.log` | `npm run build`（`frontend/`） | 通过（仅既有 `EditorView` chunk >500 kB 提示） |
| `editor-test.log` | `npm run test`（`packages/editor/`） | 24 文件 / 219 用例全绿 |
| `tool-*.log` | `python tools/<check>.py`（仓库根） | 19 项门禁全绿，见 §5 |

## 4. 后端失败项归因（13 条，均为 Windows 环境限制，非本批回归）

| 测试类 | 数量 | 根因 |
|---|---|---|
| `AiRealCliChainTest` / `AiSuggestContentTest` / `AiContextScrubTest` / `AiProcessManagerStdinTest` | 9 | 用例依赖 `fake-cli.sh`；Windows 无 `sh` |
| `EvidenceLogFormatTest` | 2（错误） | `ProcessBuilder` 执行 `sh`：`CreateProcess error=2` |
| `PytestRunAdapterTest.buildUsesVenvPytestAndFourEnvKeys` | 1 | 断言 venv 可执行文件为 `bin/pytest`，Windows 为 `Scripts/pytest.exe` |
| `TestTimeoutInjectorTest` | 1 | 资源文件按 LF 断言，Windows 检出为 CRLF |

本批新增的后端单测全部通过：`SearchServiceTest`(4)、`SearchRepositoryJdbcTest`(1)、`SavedViewServiceTest`(5)、`BatchUndoServiceTest`(6)、`TestCaseBatchUndoTest`(3)、`RunPrecheckServiceTest`(6)、`RunEtaCalculatorTest`(4)、`NotificationServiceTest`(10)、`ReportSummaryServiceTest`(4)，合计 43 例。

复现方式：`mvn -q test -Dtest=AiRealCliChainTest` 等单类执行即可看到同一报错；在 Linux/CI 上这些用例按 `.sh` 与 venv 路径可正常通过。

## 5. 门禁工具（19 项全绿）

`check_i18n`、`check_colors`、`check_tokens`、`check_css_vars`、`check_states`、
`check_component_imports`、`check_api_prefix`、`check_styleguide_tokens`、`check_tables`、
`check_accent_presets`、`check_brand`、`check_icons`、`check_ai_contract`、`check_release_docs`、
`check_wbs_consistency`、`check_w7_tokens`、`check_output_metadata`、`check_bundle_budget`、
`check_contrast`。

本批修掉的问题（首轮为红，现已全绿）：

1. `frontend/src/utils/eta.ts` 内置中文单位 → 改为 `formatEta(seconds, labels)`，单位由调用方从 i18n 注入。
2. `NotificationCenter.vue` 使用未定义令牌 `--primary*` / `--success`、硬编码 `#fff` → 改用 `--accent` / `--accent-soft` / `--ok` / `--accent-contrast`。
3. `SelectionBar.vue` 同上的 `--primary-soft` → `--accent-soft`。
4. `main.css` 的 `var(--accent-contrast, #fff)` 兜底值 → 去掉硬编码兜底。
5. 新增组件的合法像素值 → 重建 `tools/px-allow.txt` 基线（+12 项）。
6. `check_bundle_budget.py` 在 Windows 上把 `assets\index-*.js` 漏匹配，导致入口 chunk 与 vue/lucide 被误算进「懒加载第三方分包」→ 统一改 `as_posix()` 比对；修正后懒加载分包 gzip 合计 147014 B ≤ 163840 B。

## 6. FR / TC 结果（三态）

| FR | TC | 结论 | 依据 |
|---|---|---|---|
| FR-3.1-01 | TC-3.1-01 | PASS | `ui/*` 组件 spec 与 `columnSettings.spec` 全绿；i18n/令牌/颜色门禁绿 |
| FR-3.1-01 | TC-3.1-02 | 未执行 | 明暗 × 6 强调色 × 2 密度截图需浏览器人工走查 |
| FR-3.1-02 | TC-3.1-03 | PASS | `SearchRepositoryJdbcTest`（转义 / LIMIT）、`SearchServiceTest`、`CommandPalette` 与实体检索 spec |
| FR-3.1-03 | TC-3.1-04 | PASS | `SavedViewServiceTest`、`CasesView.views.spec`（默认视图回填、切换回放） |
| FR-3.1-04 | TC-3.1-05/06 | PASS | `BatchUndoServiceTest`、`TestCaseService` 撤销 token 用例、`SelectionBar` 倒计时 spec |
| FR-3.1-05 | TC-3.1-07 | PASS | `useDraft` spec、`CasesView` 草稿恢复/丢弃用例 |
| FR-3.1-06 | TC-3.1-08/09 | PASS | `RunPrecheckServiceTest`、`RunEtaCalculatorTest`、`RunsView` 预检与 ETA spec |
| FR-3.1-07 | TC-3.1-10 | PASS | `ReportSummaryServiceTest`、`ReportSummaryCard.spec`、`ReportDetailView.spec` |
| FR-3.1-08 | TC-3.1-11 | PASS | `NotificationServiceTest`、`notification_read` 按用户隔离用例、`NotificationCenter.spec` |
| FR-3.1-09 | TC-3.1-12 | PARTIAL | `ShortcutPanel` / `OnboardingChecklist` / `keyboard` spec 全绿；8 人任务测试属人工环节 |
| FR-3.1-10 | TC-3.1-13 | PARTIAL | `useFocusTrap` / skip link / 减弱动效 spec 全绿；axe 0 严重项需浏览器跑 |
| FR-3.1-10 | TC-3.1-14 | 未执行 | 键盘焦点走查表需人工逐页确认 |

## 7. 未执行项与原因

| 项 | 原因 |
|---|---|
| `scripts/gates.sh` | 纯 bash 脚本，Windows 无 `sh` / 部分步骤依赖 Linux 工具链 |
| `scripts/db-audit.sh` | 需连 MySQL 实例；表结构口径已由 `SchemaIT`（75 张表）与迁移/回滚脚本覆盖 |
| `tools/check_import_header.py` | 需传入具体 xlsx 路径，本批无 Excel 产物 |
| `tools/check_report_consistency.py` | 需传入报告目录与 runId，本批未生成对外报告 |
| `tools/check_test_baseline.py` | 依赖 `docs/evidence/gates/logs/` 的标准门禁日志；本地后端 13 条环境性失败使基线判据必然为红，不改写日志以充数 |
| axe / 截图类证据 | 需真实浏览器渲染；属 TC-3.1-02 / 13 / 14 的人工环节 |

## 8. 复现步骤

```powershell
# 后端
cd backend; mvn -q test

# 前端
cd frontend; npm run test; npm run build

# 编辑器
cd packages/editor; npm run test

# 门禁
python tools/check_i18n.py   # 其余同名脚本同理
python tools/check_bundle_budget.py
```

## 9. 回滚

- 数据库：`db/down/V11_18__v3_1_experience.down.sql` 逆序删除
  `notification_read` → `notification_preference` → `batch_undo` → `saved_view`。
- 代码：本批未改变既有接口语义；`/api/search`、`/api/projects/{id}/views`、`/api/projects/{id}/batch/undo`、
  `/api/reports/{runId}/summary`、`/api/notifications*` 均为新增端点；`run` progress 仅新增字段。
- 前端：`VERSION` 与 `CHANGELOG.md` 回退到 `3.0.1` / `v2.7-dual-kernel-demo` 为当前版本即可。
