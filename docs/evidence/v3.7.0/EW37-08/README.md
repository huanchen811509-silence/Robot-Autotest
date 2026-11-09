# EW37-08 · 备份恢复与 DR

| 项 | 内容 |
|---|---|
| 版本 / 批次 | v3.7.0 / B3–B7 |
| 状态 | **PASS** |
| 环境 | WSL `~/robot-autotest`（ext4）；服务 `:8001` / `:8081`；2026-11-09 |
| 复现命令 | `python3 tools/check_backup_status.py && cat docs/evidence/gates/logs/26-capacity_backup.log` |
| 证据位置 | `docs/evidence/v3.7.0/EW37-08/` |

## 已做（有据）

门禁 26 步隔离库演练 `RTO=20s RPO=0s tables=76`，`check_backup_status.py` PASS

## 未做 / 阻塞（如实）

季度正式恢复演练需维护窗口；正式 RPO 由备份计划决定，不由隔离库读数替代

## 判定说明

- `PASS` = 机检或真实链路实测通过且证据齐；`PARTIAL` = 工具与机检部分落地但缺全量复测；
  `BLOCKED` = 缺真人 / 真机 / 授权，**不得读作通过**。
- 本包不降低既有门禁阈值、不改断言迁就实现、不伪造现场结果。
- 发版门禁 G4/G5/G7/G8 与 G6 观察钟由大师判定，本包不代判、不申请。

## 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-11-09 | 首版：按 B3–B7 真实可达性落状态与证据路径 |
