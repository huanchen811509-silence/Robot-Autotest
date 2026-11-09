# v3.9.0 测试计划（RA-TPL-39）

> 原则：一条 FR 至少一条 TC；每条 TC 有**可执行的判定命令**与**证据文件**。
> 探针类 TC 必须含负向自证，否则「一直绿」不可信。

## 1. 用例清单

| TC | 覆盖 FR | 场景 | 判定命令 | 证据 |
|---|---|---|---|---|
| TC-v3.9-01 | FR-v3.9-01 | 暗色主题全站下拉可读 | `node scripts/dark-select-probe.mjs` | `EW39-01/dark-select-probe.json` |
| TC-v3.9-02 | FR-v3.9-01 | 抬色主题不回归 | `PROBE_THEME=light node scripts/dark-select-probe.mjs` | 同上（同文件含双主题） |
| TC-v3.9-03 | FR-v3.9-01 | 注入白底浅字必须判红 | `node scripts/dark-select-probe.mjs --selfcheck` | 控制台 `SELFCHECK 01 PASS` |
| TC-v3.9-04 | FR-v3.9-02 | RF 命令提取（含子关键字 Log） | `mvn -o -B test -Dtest=CaseCommandExtractorTest*` | surefire 报告 |
| TC-v3.9-05 | FR-v3.9-02 | pytest 命令提取（stage stdout） | `mvn -o -B test -Dtest=PytestKernelTest` | surefire 报告 |
| TC-v3.9-06 | FR-v3.9-02 | 三列贯通到读取链 | `mvn -o -B test -Dtest=ReportTreeServiceTest` | surefire 报告 |
| TC-v3.9-07 | FR-v3.9-02 | 双内核真实 run 覆盖率 100% | `python3 tools/check_case_command.py` | `EW39-02/case-command-coverage.json` |
| TC-v3.9-08 | FR-v3.9-02 | 门禁第 28 步判据可判红 | `python3 tools/check_case_command.py --self-check` | `EW39-02/gate-28-case-command.log` |
| TC-v3.9-09 | FR-v3.9-03 | 报告页命令行三态（有命令 / NONE / 未采集） | `npx vitest run src/components/report/SuiteTree.spec.ts` | vitest 输出 |
| TC-v3.9-10 | FR-v3.9-03 | 字段从接口贯穿到组件 | `npx vitest run src/views/ReportDetailView.spec.ts` | vitest 输出 |
| TC-v3.9-11 | FR-v3.9-03 | 双内核截图可见命令 | CDP 截图（一次性脚本未入库，见 `docs/evidence/v3.9.0/README.md` 判定逻辑） | `EW39-03/*.png` |
| TC-v3.9-12 | FR-v3.9-03 | 命令与源日志逐字一致（10 条） | 库内 `command_text` 与日志 `$ <命令>` 行逐条 `==` 比对（同上） | `EW39-03/verbatim-samples.json` |
| TC-v3.9-13 | FR-v3.9-03 | 导出件含命令列 | `export?format=docx` → 解包 `word/document.xml` 断言关键词（同上） | `EW39-03/export-command-column.log` |
| TC-v3.9-14 | FR-v3.9-04 | 列表空白 ≤120px、页大小记忆 | `node scripts/density-probe.mjs` | `EW39-04/density-probe-after.json` |
| TC-v3.9-15 | FR-v3.9-04 | 页大小档位与估算纯逻辑 | `npx vitest run src/composables/useListPageSize.spec.ts` | vitest 输出 |
| TC-v3.9-16 | FR-v3.9-05 | 21 字段 4 卡 + 预置 + 必填锁定 | `npx vitest run src/views/ReportTemplatesView.spec.ts` | vitest 输出 |
| TC-v3.9-17 | FR-v3.9-05 | 模板页 ≤1 屏 | `node scripts/density-probe.mjs` | `EW39-04/density-probe-after.json` 的 `templates` |
| TC-v3.9-18 | FR-v3.9-06 | 侧栏折叠可逆、命中自身 | `node scripts/side-toggle-probe.mjs` | `EW39-06/side-toggle-probe.json` |
| TC-v3.9-19 | FR-v3.9-06 | 删/改折叠逻辑必须判红 | `node scripts/side-toggle-probe.mjs --selfcheck` | 控制台 |
| TC-v3.9-20 | FR-v3.9-07 | 关于页版本与 CHANGELOG 一致 | `node scripts/about-changelog-probe.mjs` | `EW39-07/about-changelog-probe.json` |
| TC-v3.9-21 | FR-v3.9-07 | 版本四副本一致 | `python3 tools/check_version_consistency.py` | 门禁第 23 步日志 |
| TC-v3.9-22 | FR-v3.9-02 | 迁移可回滚（down→up 真实演练） | `bash scripts/migration-down-up-drill.sh V11_24__v3_9_case_command` | `EW39-02/down-up-rehearsal.log` |

## 2. 放行条件

1. 上表 22 条 TC 全部 `PASS`；
2. 门禁 `bash scripts/gates.sh` 的失败项只允许为**已知且被授权**的项（第 13 步 Chrome CDP）；
3. 后端全量单测、前端全量单测全绿；
4. 证据目录 `docs/evidence/v3.9.0/` 完整，且能被 `docs/evidence/v3.9.0/README.md` 索引到。

## 3. 不测什么

- **不测**命令复跑（v3.12-03，尚未实现）；
- **不测**多命令场景（本版只保留首条，属已知限制）；
- **不测** DUT 真实设备上的远程命令（本版演示 run 在本机 shell 上跑，DUT 字段仅作标注）。

## 4. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-09 | 初版 21 条 TC |
| v1.1 | 2026-11-09 | 新增 TC-v3.9-12/13（逐字比对、导出校验）：FR-v3.9-03 判据③要求，原计划缺失 |
| v1.2 | 2026-11-09 | 新增 TC-v3.9-22：补 `V11_24` 回滚脚本时把 down→up 做成真实演练，只做静态配对等于没验过 SQL |
