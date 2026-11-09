# 交付包运维命令（bin/）

交付包解压后，这一组脚本位于 `bin/`；同一份源码在开发机上位于 `scripts/ops/`。
两者是**同一套文件**（打包时复制），不存在「源码树一套、交付包另一套」。

## 命令矩阵

| 命令 | 作用 | 破坏性 | 默认行为 |
|---|---|---|---|
| `bin/doctor.sh --ci` | 体检工具链与产物（CI 用） | 无 | 全项检查，`FAIL` 退出 1 |
| `bin/doctor.sh` | 全项体检（含端口 / HTTP / 数据库连通） | 无 | `WARN` 不算失败 |
| `install.sh --prefix DIR` | 打印安装计划 | 无 | **dry-run** |
| `install.sh --prefix DIR --apply` | 装配到目标目录 | 写目标目录 | 需 `--apply`；非空目录要 `--upgrade` |
| `bin/db-migrate.sh` | 列出待执行迁移 | 无 | **dry-run** |
| `bin/db-migrate.sh --apply` | 空库首次初始化 | 写数据库 | 非空库必须 `--force` |
| `bin/ctl.sh start\|stop\|restart` | 启停服务 | 影响可用性 | 只认本安装根的 pid / unit |
| `bin/status.sh` | 运行态总览 | 无 | 只读 |
| `bin/logs.sh --service backend -f` | 看日志 | 无 | 最近 80 行 |
| `bin/backup.sh` | 打印备份计划 | 无 | **dry-run** |
| `bin/backup.sh --apply` | 逻辑备份 + 键名清单 | 写备份目录 | 口令不进命令行/日志 |
| `uninstall.sh --prefix DIR` | 打印拆卸计划 | 无 | **dry-run** |
| `uninstall.sh --prefix DIR --apply` | 停服务 + 备份 + 删程序文件 | **高** | 需输入 `UNINSTALL` 确认 |

## 硬护栏

1. 所有写操作默认 dry-run，必须显式 `--apply`；拆卸还需交互确认（非交互用 `--yes`）。
2. 凭据只从 `~/.config/robot-autotest/env`（或 `ROBOT_ENV_FILE`）读，脚本不打印、不写日志、不进 MANIFEST。
3. 拆卸**从不碰数据库**；`logs / var / backups` 先备份到 `~/robot-autotest-uninstall-backups/<时间戳>/`。
4. 停止服务只认本安装根的 pid 文件与 systemd unit，不扫全机进程。
5. `db-migrate.sh` 只服务全新空库；已有数据的库必须走 Flyway。
6. 只有安装根带 `var/systemd-managed` 标记（`install.sh --systemd` 写入）时，
   `ctl.sh` / `uninstall.sh` 才会操作用户级 systemd unit；否则只处理本安装根的 pid 文件，
   不会误停别处正在跑的实例。

## 布局判定

`common.sh` 依据脚本同层是否有 `MANIFEST.json` 判定布局：

- `bundle`：脚本在 `<prefix>/bin/`，产物在 `app/backend`、`app/frontend`、`entry/`；
- `source`：脚本在 `<repo>/scripts/ops/`，产物在 `backend/target`、`frontend/dist`、`scripts/`。

`APP_HOME`、`ROBOT_LOG_DIR`、`ROBOT_RUN_DIR`、`ROBOT_BACKUP_DIR`、`ROBOT_ENV_FILE` 可覆盖默认值。

## 不在本组脚本范围内

- 构建源码（用 `scripts/build.sh`）；
- 打包（用 `scripts/package-release.sh`）；
- 全量门禁（用 `bash scripts/gates.sh`，只在 WSL 权威副本跑）；
- 版本升级的数据迁移决策（属变更管理，见 `docs/ROLLBACK.md`）。
