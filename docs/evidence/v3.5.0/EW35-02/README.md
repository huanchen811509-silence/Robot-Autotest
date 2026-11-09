# EW35-02 · 27 步门禁全量汇总与负向自检

| 项 | 内容 |
|---|---|
| 环境 | WSL `~/robot-autotest`（ext4）；`set -a; . ~/.config/robot-autotest/env; set +a` |
| 命令 | `bash scripts/gates.sh --selfcheck`；`bash scripts/gates.sh 2>&1 \| tee docs/evidence/gates/logs/00-full-run.log` |
| 证据 | `docs/evidence/gates/logs/00-full-run.log`（同批次汇总）、`00-selfcheck.log`、分步 `01..27-*.log` |
| 结论 | **同批次 27 步 `FAILS=0`；`--selfcheck` `FAILS=0`** |

## 1. 两次全量汇总（时间戳与分步日志同批次）

| 轮次 | 时点 | 汇总结论 | 失败步 | 处置 |
|---|---|---|---|---|
| 第一次 | 2026-11-09 10:29 | `GATES_RESULT FAILS=2` | 02 `frontend_test`（`AdminView` 一条用例 5000ms 超时）、19 `evidence_integrity`（因 02 日志格式变化取不到 `Tests N passed (N)` 而连锁判红） | 按 flaky 台账 §4 连续 2 次单文件复跑（均 8/8，该用例 598ms）→ 判定为 73 文件并发下的 5s 边界偶发 |
| 修复 | 10:33–10:36 | `frontend/vite.config.ts` 设 `testTimeout=10000` / `hookTimeout=10000` | 只放宽超时余量，**未改断言、未删用例、未跳过步骤**；根因与复跑证据写入 `v3.2.0/flaky-ledger.md` §3.1 | — |
| 第二次 | 2026-11-09 10:40–10:43 | **`GATES_RESULT FAILS=0`** | 无 | 27 步全 PASS，02 与 19 同步转绿，闭环成立 |

## 2. 负向自检（`00-selfcheck.log`）

| 类型 | 项目 | 结果 |
|---|---|---|
| 注入实证 | 13 步注入横滚被判红、14 步黑样本命中 / 白样本 0 命中 | PASS |
| 删步骤实证 | 13 / 14 / 19 / 20 / 23 / 24 / 27 共 7 步，删任一步 `check_structure` 必变红 | 7/7 PASS |
| 汇总 | `SELFCHECK_RESULT FAILS=0` | PASS |

## 3. 旧口径处置

11-08 12:06 的 19 步 `FAILS=2` 汇总标为**历史读数**；CDP 状态由「不可用」改判「已恢复」
（11-09 00:05 `13-browser_regression.log` `checks=48 fails=0`，本次 13 步亦 PASS）。
业务表基线由 71 更新为 **87**（`12-db_audit.log`）。分步日志 PASS 不单独作为全绿依据，
裁决只认本目录的同批次汇总。

## 4. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-11-09 | 首版：两次同批次汇总 + 负向自检 + 连锁失败修复闭环 |
