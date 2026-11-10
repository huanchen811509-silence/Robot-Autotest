# ADR-4X-14 · 升级前必须成功做一次 DB 全量备份，恢复走人工确认

- **状态**：已采纳
- **日期**：2026-11-09
- **决策人**：大师（书面批准 2026-11-09）
- **约束范围**：v4.1.0 起 `scripts/ops/update.sh` 与全部 schema 变更版本

## 背景

`FR-v4.1-03` 要求升级脚本在变更 schema 前做**全量 DB 备份**，备份失败即终止升级；
本仓已有 `scripts/db-undo.sh`（按版本回滚）与 `scripts/migration-down-up-drill.sh`（up→down→up 演练），
但 `db/migration` 是「只增不改」，一旦某版本 up 脚本在目标环境把数据改坏，down 只删表、不保证数据回来。

已知事实：MySQL 8.0 部署在 `127.0.0.1:3306`，`mysqldump` 存在于 `/usr/bin/mysqldump`（本次实测）；
`update.sh` 已有 `--schema=keep` 分支，但**没有**「备份成功才继续」的硬闸。

## 选项

| 选项 | 优点 | 代价 | 为何不选 / 为何选 |
|---|---|---|---|
| ① 靠 down 脚本回滚 | 快 | 只删表，数据不可逆；跨版本 down 顺序易错 | 不选 |
| ② 升级前 `mysqldump` 全量备份 + 失败即停 | 恢复有据可依；实现成本低 | 大库耗时、占磁盘；备份文件本身含敏感数据 | **选** |
| ③ 自动恢复（失败时脚本自动 `mysql < dump`） | 少一步人工 | 自动覆盖运行中库的风险极高，误操作不可撤销 | 不选：恢复必须人工确认 |

## 决定

- `update.sh` 在 schema 变更分支前执行 `mysqldump --single-transaction --routines --triggers` 全量备份，
  落到 `backups/db/<version>-<timestamp>.sql.gz`；**退出码非 0 或文件小于 1KB → 终止升级**，不写版本号。
- 备份文件权限 0600，目录不复用、不覆盖；`backups/` 已在 `.gitignore` 中。
- 恢复**只提供命令与文档**（`scripts/ops/restore-db.sh --file <dump> --yes`），脚本必须显式 `--yes` 才执行，
  默认只打印将要做什么；不挂在 `update.sh` 的失败路径上自动执行。
- 备份只覆盖数据库；`workspace/` 由 `backup.sh` 单独归档（见 ADR-4X-15）。

## 后果

- 正面：每次升级都有可回退的数据快照；「schema 变更失败」不会变成不可恢复事故。
- 负面：升级耗时增加（与库大小线性相关）；备份文件含业务数据，必须纳入备份介质的管理与轮换。
- 触发复核的条件：库大到分钟级备份不可接受，或引入托管数据库的原生快照能力时，改用存储层快照。

## 关联

- WBS：`W41-05`、`W41-07`
- 相关文件：`scripts/ops/update.sh`、`scripts/ops/restore-db.sh`、`scripts/db-undo.sh`
- 上游依据：PRD `FR-v4.1-03`「升级前 DB 全量备份」
