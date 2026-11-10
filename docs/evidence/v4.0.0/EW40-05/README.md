# EW40-05 · 可回滚升级机制（FR-v4.0-05）

| 项 | 内容 |
|---|---|
| 工作包 | `W40-05` 可回滚升级机制 |
| 需求 | `FR-v4.0-05`：dry-run 默认 / 备份 / 健康 / 自动回滚 / 历史 / RTO |
| 日期 | 2026-11-09 |
| 环境 | Ubuntu-24.04 · 基线包 `releases/robot-autotest-3.11.0-d4ad9d5-linux-x64.tar.gz`（284 文件 / 57,291,037 字节） |
| 结论 | 演练 14 项 PASS、`UPDATE_DRILL_RESULT FAILS=0`；语法 `bash -n` 通过 |

---

## 1. 演练真实读数（`bash scripts/ops/update-drill.sh`）

| 场景 | 断言 | 读数 |
|---|---|---|
| A | dry-run 只读列出、不写盘 | `PASS A dry-run 只读列出，不写盘` |
| B | 包改动一字节即红 | `PASS B 改一字节即红（MANIFEST sha256 闸门有效）` |
| C | 同版本升级需 `--force` 后到 3.11.1 | `PASS C 版本已到 3.11.1` |
| C | 升级结论 | `PASS C 输出 UPDATE PASS`、`state=last_result=PASS` |
| C | 原包归档可回滚 | `PASS C 原包已归档（可回滚）` |
| C | 历史追加 | `PASS C UPDATE-HISTORY 已追加` |
| C | KEEP 区不动 | `PASS C workspace 原样保留（KEEP 区未动）` |
| D | 负向注入后自动回滚 | `PASS D 回滚后版本回到 3.11.0`、`state=ROLLED_BACK`、`历史记录回滚` |
| 汇总 | 全部断言 | `UPDATE_DRILL_RESULT FAILS=0 baseline=3.11.0 target=3.11.1` |

证据文件：

| 文件 | 内容 |
|---|---|
| `update-drill.log` | 演练全量输出（14 项 PASS） |
| `syntax-check.log` | `bash -n` 通过；shellcheck 状态 |

## 2. 实现口径（`scripts/ops/update.sh`）

| 护栏 | 落地 |
|---|---|
| 安全默认态 | `APPLY=0`；`--apply` 且交互输入 `UPDATE` 才写盘 |
| 版本 | 同版本必须 `--force`；降级必须 `--force --restore-db`（RPO=0） |
| 顺序 | 先 DB 全量备份 → 再停服覆盖；备份失败立即中止 |
| 迁移 | 仅 expand 迁移允许 `--schema=keep`，否则回滚恢复 DB 备份 |
| 健康 | 起服后同时探 `:8081/actuator/health`（就绪）与入口 `:8001/healthz`（liveness） |
| 失败 | `trap on_err ERR` → `rollback()`；回滚失败退出码 2，转人工 |
| 留痕 | `<prefix>/UPDATE-HISTORY.md`（`时间 / 原版本 / 目标版本 / 结论 / 备注`）+ `var/update/state` + `bin/status.sh` 显示上一可用版本 |
| 留存 | 默认保留最近 3 版（`KEEP=3` / `--keep N`） |
| 演练版本化 | 脚本固定为 `scripts/ops/update-drill.sh`，机器可复跑 |

## 3. 判不了的部分

| # | 项 | 原因 | 补齐 |
|---|---|---|---|
| 1 | `shellcheck` | WSL 未安装该工具（`NOT-INSTALLED`），已用 `bash -n` 语法检查替代 | 安装后 `shellcheck -S warning scripts/ops/update.sh` 复跑 |
| 2 | 真实生产升级 | 演练使用临时 prefix 与真实基线包，不触碰生产安装 | 生产升级按 `docs/DELIVERY-GUIDE.md §5.1` 执行 |
| 3 | 回滚失败后的人工恢复 | 演练只覆盖「回滚成功」路径；回滚失败路径按 `docs/ROLLBACK.md §2` 人工 | 需在隔离机做一次强制回滚失败演练 |
