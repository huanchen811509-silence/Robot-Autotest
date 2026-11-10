# AIDC AutoTest 交付包安装 / 拆卸 / 运维指南

| 项 | 内容 |
|---|---|
| 文档 | `docs/DELIVERY-GUIDE.md` ｜ v1.0 ｜ 2026-11-09 |
| 适用 | WSL2 Ubuntu 22.04+ / Ubuntu 24.04（linux-x64） |
| 交付物 | `robot-autotest-<VERSION>-<SHA>-linux-x64.tar.gz` + `.sha256` + `.manifest.json` |
| 数据来源 | `VERSION`（唯一版本真源）、`git rev-parse --short HEAD`、`scripts/package-release.sh` |
| 配套 | `docs/OPS-MANUAL.md`（历史运维口径）、`docs/ROLLBACK.md`（回滚）、`bin/README.md`（命令矩阵） |
| 边界 | 本指南是**部署与运维说明，不是发版判定**；G 门与对外口径仍由大师拍板 |

> **口径优先级**：涉及交付包的安装/拆卸/运维，**以本文档为准**。
> `docs/OPS-MANUAL.md` 记录的是 2026-11-03 的开发态（D 形态）与当时的部署约定（P 形态），
> 其中「本产品尚未在本地 WSL 部署」等表述是历史事实，不代表当前交付能力。

---

## 1. 交付物里有什么

| 路径 | 内容 |
|---|---|
| `MANIFEST.json` | 版本 / commit / 构建时间 / 逐文件 sha256 / 入口命令 / 前置条件 |
| `VERSION`、`build-info.properties` | 版本号与「关于」页所需的构建信息 |
| `CHANGELOG.md`、`README.md` | 版本迭代数据源与产品说明 |
| `app/backend/robot-autotest.jar` | 后端可执行 jar（统一命名，unit 不依赖版本号） |
| `app/frontend/` | 前端静态产物（含 `CHANGELOG.md`，供「版本迭代」页读取） |
| `app/editor/` | 编辑器子包产物（已打进前端，留档便于核对） |
| `entry/serve-entry.mjs` | 零依赖 Node 统一入口：静态托管 + SPA 回退 + `/api`·`/ws` 反代 |
| `db/migration/`、`db/down/` | Flyway 迁移与手写回滚脚本 |
| `deploy/` | `env.example`、两个 systemd --user unit 样例、nginx 反代样例 |
| `bin/`、`install.sh`、`uninstall.sh` | 安装 / 拆卸 / 启停 / 状态 / 日志 / 备份 / 体检 / 迁移 |
| `docs/` | 本指南 + 运维手册 + 回滚 + CI 接入指南 |

**明确不含**：任何凭据明文、`logs/`、`var/`、`backups/`、`node_modules/`、`target/`、`.git/`。
打包脚本会逐项断言这一点，门禁第 30 步再校验一遍。

---

## 2. 前置条件

| 依赖 | 版本 | 用途 | 缺失后果 |
|---|---|---|---|
| JDK | 21 | 运行后端 jar | `ctl.sh start` 直接失败 |
| Node.js | 22+ | 运行统一入口（零 npm 依赖） | 静态页与反代不可用 |
| MySQL | 8.0 | 业务库（部署方事先建库建号） | 后端起不来 |
| `curl` / `tar` / `gzip` | 系统自带 | 下载、解包、健康检查 | 安装与备份判据失效 |
| `mysqldump` | 与 MySQL 配套 | 逻辑备份 | `backup.sh --apply` 失败 |
| `systemd --user` | 可选 | 常驻托管、开机自起 | 降级为 `nohup`（会话结束可能停） |

**必须由部署方预先完成**（脚本不做，也不猜）：

1. `CREATE DATABASE <DB_NAME> CHARACTER SET utf8mb4;` 与对应账号授权；
2. 填好 `~/.config/robot-autotest/env`（模板在 `deploy/env.example`），`chmod 600`；
3. 确认 `:8001` / `:8081` 未被占用。

---

## 3. 下载与校验

```bash
# 方式 A：GitHub Actions 交付流水线产物（需登录 GitHub）
gh run list --workflow delivery.yml
gh run download <run-id> -n robot-autotest-linux-x64 -D ./downloads

# 方式 B：在 WSL 权威副本本地自建（无需联网）
cd ~/robot-autotest && bash scripts/build.sh && bash scripts/package-release.sh
ls -lh releases/

# 校验完整性（两处都要过）
sha256sum -c robot-autotest-*.tar.gz.sha256
tar -xzf robot-autotest-*.tar.gz
cd robot-autotest-*/
cat MANIFEST.json | python3 -m json.tool | head -20
```

> `.manifest.json` 里逐文件记录了 sha256；离线复核可用
> `python3 -c "import json,hashlib,pathlib; ..."` 对照，见 §8 排障表。

---

## 4. 安装

```bash
cd robot-autotest-*/

# 4.1 先体检（只读）
bin/doctor.sh --ci

# 4.2 打印安装计划（dry-run，不写盘）
./install.sh --prefix /opt/robot-autotest

# 4.3 确认后真装
sudo mkdir -p /opt && sudo chown "$USER" /opt
./install.sh --prefix /opt/robot-autotest --apply --systemd

# 4.4 填凭据（模板已复制，口令为空）
$EDITOR ~/.config/robot-autotest/env && chmod 600 ~/.config/robot-autotest/env

# 4.5 空库首次初始化（已有数据的库请走 Flyway，别用这条）
cd /opt/robot-autotest && bin/db-migrate.sh            # 先看清单
cd /opt/robot-autotest && bin/db-migrate.sh --apply

# 4.6 启动并验收
bin/ctl.sh start
bin/status.sh
curl -fsS --noproxy '*' http://127.0.0.1:8001/healthz
curl -fsS --noproxy '*' http://127.0.0.1:8001/api/health
```

手工启用 systemd（若第 4.3 步没加 `--systemd`）：

```bash
cp deploy/robot-autotest-*.service ~/.config/systemd/user/
# 把 unit 里的 %h/robot-autotest 改成实际 --prefix
systemctl --user daemon-reload
systemctl --user enable --now robot-autotest-backend robot-autotest-entry
loginctl enable-linger "$USER"     # 需要开机自起
```

> **systemd 托管标记**：`bin/ctl.sh` 与 `uninstall.sh` 只有在安装根存在
> `var/systemd-managed`（由 `--systemd` 写入）时才会操作用户级 systemd unit；
> 否则一律走 pid 文件 / `nohup`。这样在临时目录解包验证时，不会误停开发机上
> 指向 `~/robot-autotest` 的全局 unit（2026-11-09 真拆卸实测前后服务均 200）。

**安装脚本不会做**：建库、迁移、启动、写口令、自动 enable unit。
这五件事都留给人显式执行，避免「一条命令把生产库改了」。

---

## 5. 日常运维

| 场景 | 命令 |
|---|---|
| 看运行状态 | `bin/status.sh` |
| 体检（含 DB 连通） | `bin/doctor.sh` 或 `bin/doctor.sh --json` |
| 看后端日志 | `bin/logs.sh --service backend --lines 200 -f` |
| 重启 | `bin/ctl.sh restart` |
| 备份 | `bin/backup.sh`（dry-run）→ `bin/backup.sh --apply` |
| 升级 | `bin/update.sh --package <新包目录>`（默认 dry-run）→ 确认后 `--apply`，见 §5.1 |
| 回滚 | 升级失败由 `bin/update.sh` 自动回滚；人工处置见 `docs/ROLLBACK.md` |

备份产物落在 `<prefix>/backups/<库名>-<时间戳>.sql.gz`，另有一份 `.env-keys.txt`
**只记录键名，不记录值**；口令经 `MYSQL_PWD` 传递，不出现在命令行与日志。

### 5.1 可回滚升级（`bin/update.sh`）

```bash
bin/update.sh --package /tmp/robot-autotest-4.2.0            # 默认 dry-run：只列迁移清单
bin/update.sh --package /tmp/robot-autotest-4.2.0 --apply    # 交互输入 UPDATE 才写盘
```

| 护栏 | 口径 |
|---|---|
| 默认态 | dry-run；`--apply` 且输入 `UPDATE` 才写盘 |
| 版本 | 同版本必须 `--force`；降级必须 `--force --restore-db`（RPO = 0） |
| 备份 | `--apply` 之前强制 DB 全量备份，失败立即中止；原包归档到 `backups/artifacts/` |
| 迁移 | 仅 expand 迁移允许 `--schema=keep`；否则回滚恢复 DB 备份 |
| 健康 | 起服后打 `:8081/actuator/health`（无 DUT 时降级为登录 + 报告列表） |
| 失败 | 任一步失败自动回滚；回滚失败按 `docs/ROLLBACK.md` 人工恢复 |
| 留痕 | `<prefix>/UPDATE-HISTORY.md`（表头 `时间 / 原版本 / 目标版本 / 结论 / 备注`）与 `var/update/state` |
| 留存 | 默认保留最近 3 版归档（`--keep N`） |
| 数据 | `workspace/` 与库数据不动；凭据文件哈希一致 |

---

## 6. 拆卸

```bash
cd /opt/robot-autotest

# 6.1 先看计划：会逐条列出待删路径、备份位置、明确「数据库不动」
./uninstall.sh --prefix /opt/robot-autotest

# 6.2 真拆：脚本会先停服务 → 备份 env/logs/var/backups → 逐项删除程序文件
./uninstall.sh --prefix /opt/robot-autotest --apply
# 交互输入 UNINSTALL；自动化场景用 --yes
```

**⚠️ 此操作非常危险，可能导致不可逆的数据丢失！** 具体风险：

- 程序文件移除后服务立即不可用；
- 日志与运行数据只在 `~/robot-autotest-uninstall-backups/<时间戳>/` 留一份；
- 目录里会留 `REMOVED.txt` 说明备份位置与恢复步骤；
- **数据库完全不动**（不 DROP / 不 TRUNCATE / 不改表），清库须由 DBA 另行决策。

脚本护栏：目标必须含本产品 `MANIFEST.json`、拒绝系统目录与家目录、默认 dry-run、
逐项删除且每 10 项校验一次、备份失败立即中止。

---

## 7. CI 与本地的关系

| 环境 | 做什么 | 不做什么 |
|---|---|---|
| GitHub Actions `delivery.yml`（ubuntu-latest） | 拉源码 → 装 JDK/Node → 构建三件套 → 静态机检 → 打包 → 解包自检 → 上传 tarball artifact | 不连 MySQL、不跑全量门禁、不判发版 |
| WSL 权威副本 | `bash scripts/gates.sh` 29→30 步全量门禁（真库 / 真浏览器 / 真链路） | 不在 CI 里复刻 |
| 目标 Ubuntu / WSL 机器 | 下载 tarball → 按本指南安装运维 | 不拉源码、不装 Maven/npm 依赖 |

**CI 产物 ≠ 发版**：`delivery.yml` 只上传 Actions artifact，不创建 GitHub Release、
不 bump 版本号、不产生对外口径。是否需要公开 Release 由大师另行拍板。

---

## 8. 排障

| 现象 | 判据 / 定位 | 处置 |
|---|---|---|
| `doctor.sh` 报 `frontend-dist FAIL` | `app/frontend/index.html` 不存在 | 交付包不完整，重新下载并用 sha256 校验 |
| 入口起报 `frontend dist not built` | `ENTRY_DIST` 没指向 `app/frontend` | `ENTRY_DIST=<prefix>/app/frontend bin/ctl.sh restart`；unit 样例已写好该变量 |
| `:8081` 未监听但 `:8001` 是 200 | 旧入口还活着（历史踩过的假绿） | `bin/ctl.sh stop` 后确认两端口都释放，再 `start` |
| `db-migrate.sh` 拒绝执行 | 目标库非空 | 已有库走 Flyway，不要 `--force` |
| 后端起不来且不打印原因 | 日志在 `<prefix>/logs/backend.log` | `bin/logs.sh --service backend --lines 200` |
| 备份文件为空 | `mysqldump` 缺 PROCESS 权限或库名错 | 看 `backups/*.dump-warnings.log`，确认库名与账号 |
| MANIFEST 与实际文件对不上 | 逐文件 sha256 不符 | 停止使用，重新下载；不要手工改 MANIFEST |

---

## 9. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-09 | 首版：交付包结构、安装/拆卸/运维全流程、CI 边界、排障表 |
| v1.1 | 2026-11-09 | 真实安装/拆卸实测回写：顶层 `install.sh` / `uninstall.sh` 改为转调 `bin/` 的薄 wrapper；`ctl.sh` 与拆卸只在安装根带 `var/systemd-managed` 标记时才操作 systemd unit，避免误停别处实例 |
