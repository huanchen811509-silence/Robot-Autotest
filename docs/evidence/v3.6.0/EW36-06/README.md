# EW36-06 · 前端交互性能精修

| 项 | 内容 |
|---|---|
| 版本 / 批次 | v3.6.0 / B3–B7 |
| 状态 | **PARTIAL** |
| 环境 | WSL `~/robot-autotest`（ext4）；服务 `:8001` / `:8081`；2026-11-09 |
| 复现命令 | `python3 tools/check_bundle_budget.py && python3 tools/check_perf_budget.py --report docs/evidence/v3.2.0/EW32-01/perf/perf-report.json --require` |
| 证据位置 | `docs/evidence/v3.6.0/EW36-06/` |

## 已做（有据）

门禁 21/22 步包体与性能预算 PASS；真实浏览器 CWV 实验室采样已落 `v3.5.0/EW35-06/cwv-probe.json`

## 未做 / 阻塞（如实）

p75 达标须多轮真实交互采样；1 万行虚拟滚动 fps 本轮未复测（既有 58.5 fps 证据）

## 判定说明

- `PASS` = 机检或真实链路实测通过且证据齐；`PARTIAL` = 工具与机检部分落地但缺全量复测；
  `BLOCKED` = 缺真人 / 真机 / 授权，**不得读作通过**。
- 本包不降低既有门禁阈值、不改断言迁就实现、不伪造现场结果。
- 发版门禁 G4/G5/G7/G8 与 G6 观察钟由大师判定，本包不代判、不申请。

## 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-11-09 | 首版：按 B3–B7 真实可达性落状态与证据路径 |
