# ADR-39-06 · pytest 日志摊平为 case-log.jsonl（JSONL 而非分隔符文本）

| 项 | 内容 |
|---|---|
| 状态 | 已采纳 |
| 日期 | 2026-11-09 |
| 约束范围 | v3.9.0 起 pytest 报告日志落盘与读取 |
| 依据 | RA-PRD-39-001 §3 ADR-39-06；`PytestLogPersister` / `JsonlLogReader` |

## 背景

RF 的日志正文在 `output.xml` 里，按 `log_offset/log_span` 切片即可；pytest 的 `json-report` 没有全局日志文件，各用例的 stdout/stderr/longrepr 分散在 `setup/call/teardown` 三个 stage 里，因此报告页的日志抽屉对 pytest run **一直是空白**，命令行「定位日志」也就无处可去。

## 决策

入库时把各用例各 stage 的输出按全局顺序摊平成 `case-log.jsonl`，一行一条记录（`seq/level/keyword/message`），同时算出每个用例的 `[start, end)` 区间入库；读取时按内核分派数据源（`JsonlLogReader` / `LogXmlReader`）。

## 后果与护栏

- 正面：pytest 与 RF 一样有「可定位的原始日志」；区间切片不依赖日志正文内容。
- 负面：同类信息存了两份（json-report 与 jsonl），存储翻倍；空输出用例不登记区间，读取时回退整段。
- 护栏：`PytestLogPersisterTest` 5 条（序号连续、空用例无区间、区间切片、中文与制表符逐字保留、报告缺失不抛）；坏 JSONL 行降级为 `WARN` 原文，不让整页 500。
- 复核条件：pytest 侧出现原生全局日志文件（如 pytest 的 `--log-file` 全量固化）时，可退回直接读原文件。

## 验证方式

`TC-v3.9-12`（10 条逐字比对 `mismatches=0`）、`TC-v3.9-11`（pytest 报告截图含命令与退出码）。

## 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-11-09 | 首版，随 v3.9.0 收口入库 |
