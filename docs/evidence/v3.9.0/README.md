# v3.9.0 证据索引（RA-EVD-39）

> 一个工作包一个子目录，文件名即判据。所有数字来自**命令输出**，不来自估算；
> 括号里写清复跑命令，任何人按命令重跑应得到同一结论。

| 工作包 | 判据 | 证据文件 | 复跑命令 |
|---|---|---|---|
| EW39-01 暗色下拉 | 12 路由 27 个下拉 `flagged=0`、对比度 ≥ 4.5、亮色不回归；注入白底浅字必须判红 | `EW39-01/dark-select-probe.json`（改后）、`dark-select-probe-before.json`（改前 10/10 flagged）、`light-select-probe.json`（亮色） | `node scripts/dark-select-probe.mjs`、`PROBE_THEME=light node scripts/dark-select-probe.mjs`、`node scripts/dark-select-probe.mjs --selfcheck` |
| EW39-02 命令契约 | 双内核真实 run 覆盖率 100%、来源枚举合法、门禁第 28 步 PASS 且自证能判红、`V11_24` 可回滚 | `EW39-02/case-command-coverage.json`、`EW39-02/gate-28-case-command.log`、`EW39-02/down-up-rehearsal.log` | `python3 tools/case_command_report.py --run RF=<RUN> --run PYTEST=<RUN>`、`python3 tools/check_case_command.py`、`bash scripts/migration-down-up-drill.sh V11_24__v3_9_case_command` |
| EW39-03 报告页命令列 | 双内核各一张真实截图；10 条命令与源日志逐字一致；导出件含命令列 | `EW39-03/rf-report-command.png`、`EW39-03/pytest-report-command.png`、`EW39-03/verbatim-samples.json`、`EW39-03/export-command-column.log` | 见 `EW39-03` 小节 |
| EW39-04 列表密度 | 分页条下方空白 ≤ 120px、计划页补分页、模板页 ≤ 1 屏 | `EW39-04/density-probe-before.json`（改前）、`EW39-04/density-probe-after.json`（改后） | `node scripts/density-probe.mjs`（`DENSITY_OUT_NAME=` 区分前后） |
| EW39-05 模板分类卡片 | 21 字段 / 4 卡 / ≥4 预置 / 必填锁定 / ≤1 屏 | 同 `EW39-04/density-probe-after.json` 的 `templates` 段（同一台探针顺带采集） | 同上 |
| EW39-06 侧栏收起 | 折叠后首列 56px、`scrollWidth ≤ clientWidth+1`、按钮命中自身、可还原 | `EW39-06/side-toggle-probe.json` | `node scripts/side-toggle-probe.mjs` |
| EW39-07 关于/版本迭代 | 关于页「最近更新」≥1 条、版本号一致、两份 CHANGELOG 逐字一致 | `EW39-07/about-changelog-probe.json` | `node scripts/about-changelog-probe.mjs` |

## EW39-02 细节：双内核命令断言的三个坑

1. **RF 命令写在子关键字里**。现场套件的写法是
   ```
   执行命令并记录原始输出
       Log    $ ${cmd}        # ← RF 记成一条**子关键字** Log
       ${result}=    Run Process    bash    -c    ${cmd}
   ```
   命令原文落子关键字的消息上，只读命中关键字的自身消息 → 每条用例都是 `NONE`
   （2026-11-09 首次真实跑实测 3/3 `NONE`）。`CaseCommandExtractor.fromRobotKeywords`
   因此改为「命中白名单后前序遍历整棵子树取首条命令行」，祖先仍须是白名单关键字，
   不放开误识别面。
2. **pytest 侧取 stage 文本**，不是用例顶层：json-report 把 stdout 放在
   `setup/call/teardown` 里，只读顶层一条都取不到。
3. **NONE ≠ 未采集**。`NONE` 是「本来就没下发」，`NULL` 是「v3.9.0 之前入库、没采集」。
   两者在报告页显示不同文案，覆盖率也只把前者算作覆盖——用历史 NULL 行凑 100% 是错的，
   门禁第 28 步对此单独判红。

真实 run（2026-11-09，本机 workstation-local 直跑，不依赖 DUT）：

| 内核 | RUN_ID | 用例 | 带命令 | 显式 NONE | 覆盖率 | 来源 |
|---|---|---|---|---|---|---|
| RF | `RUN-20261109133212-b70000` | 7 | 7 | 0 | 100% | `RF_KEYWORD`×7 |
| pytest | `RUN-20261109133212-b70001` | 7 | 7 | 0 | 100% | `STDOUT_EXTRACT`×7 |

两个内核的 7 条命令**逐字相同**（`uname -r` … `ip -o link show | wc -l`），
可作为「同一动作在两个内核下产出同一份命令事实」的对照证据。

## EW39-03 细节：pytest 日志抽屉从「空」到「可定位」

RF 的日志正文在 `output.xml`，按 `log_offset/log_span` 切片即可；pytest 的 json-report
没有全局日志文件，因此抽屉一直是空白（PRD Q2-7）。本版在入库时把各用例的 stage 文本
按全局顺序摊平成 `case-log.jsonl`（一行一条），同时算出每个用例的 `[start, end)` 区间，
抽屉改为按内核分派数据源（`JsonlLogReader` / `LogXmlReader`）。于是「点命令定位日志」
在两个内核下都真的能落到那一行。

复跑方式（三个脚本是收口期一次性写的，**未入库**，这里只记判定逻辑，避免把不存在的路径写成命令）：

1. **截图**：CDP 连 `:9222` → 打开 RF / pytest 两份报告 → 先点开所有 `.suite.nested`（RF 每条用例自成一层套件）
   与 pytest 的两层套件，直到无可展开项 → 再把 `.app-main` 滚到「套件树」→ 视口截图。
   坑：`Page.captureScreenshot(captureBeyondViewport)` 对内滚容器无效，不滚 `.app-main` 只能拍到首屏。
2. **逐字比对**：按 `RUN_ID` 从库取 `test_result.command_text`，与报告日志接口返回的 `$ <命令>` 行
   逐条 `==` 比较（不做 trim、不做大小写折叠），任一不等即 `mismatches++`。
3. **导出校验**：`GET /api/reports/{id}/export?format=docx` → 解包 `word/document.xml` →
   断言含 `下发命令` / 具体命令子串 / `[exit=N]`。
   坑：`format=word` 会 400（只认 `pdf|docx|xlsx`）。

抽样 10 条（RF 7 条 + pytest 3 条）结果：`mismatches=0`，明细见
`verbatim-samples.json` 的 `rows[].dbCommandText / logCommandText`。

## 已知遗留

| 编号 | 内容 | 处置 |
|---|---|---|
| L-39-01 | pytest run 的 `run_artifact` 里 `kind=output_xml` 实际指向 `pytest-report.json` | 不阻塞（`locateCaseLog` 已按文件名判定）；修 kind 属夹具清理，排 v3.10 |
| L-39-02 | RF 侧 `caseKey` 缺 `suitePath` 前缀时，每条用例自成一层套件，树上出现「套件名 = 用例名」的重复层级 | 观感问题，不影响命令/日志；排 v3.10 目录规则统一 |
| L-39-03 | `EvidenceLogWriter.SECRET` 只覆盖 `password=/token:` 这类键值，`-u user:pass` 位置式写法不脱敏 | 命令列扩大了暴露面，v3.12「命令复跑」前必须补规则；先记为安全遗留 |
| L-39-04 | v3.5.0–v3.8.0 有完整版本文档，但 `CHANGELOG.md` / `VERSION` 一直停在 3.4.0 | 本版收口补齐四条版本记录并把 `VERSION` 提到 3.9.0（口径来自各版本 `05-TEST-REPORT.md` 的 TC 清单） |
