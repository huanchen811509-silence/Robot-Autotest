# Robot-Autotest 运维手册（v1.0 / HRD-03）

**对应需求** FR-v1.0-13 / FR-v1.0-14 / FR-v1.0-15 ｜ **配套脚本** `scripts/rollback.sh`、`scripts/window-stats.sql`（运行统计）

> **两种形态必须分清，不要混用命令：**
> - **D = Windows 开发态**：本机 `E:\workspace\自动化测试\Robot-Autotest`，后端 jar 跑在 8081、Vite 跑在 5173、MySQL 在 WSL `Ubuntu-24.04`（Windows 经 `127.0.0.1:3306` 访问）。这是**历史记录**的开发形态，现以 P 形态为主。
> - **P = 本地 WSL 常驻态**：本地 WSL Ubuntu + systemd **用户级** unit。已于 2026-11-12 部署并复核，unit 名、路径与端口见 §1.2（按 `systemctl --user cat` 实读回填）。后端端口 **8081**（`application-prod.yml` 的 `${SERVER_PORT:8081}`），统一入口 **8001**，不要改回 8080。
>
> **验证状态（2026-11-03 / 2026-11-12 复核）**：D 的命令已在本机逐条只读执行过；P 形态三个 unit（`robot-autotest-backend` / `robot-autotest-entry` / `robot-autotest-frontend`）均 `active running`，`Linger=yes`，本节命令已在 WSL 实测。
> **禁止**把 D 的 `java -jar` / `npm run dev` 当成生产启动方式；也禁止把 P 的 `systemctl` 命令拿到 Windows 上跑。

---

## 1. 服务清单

### 1.1 D 形态（Windows 开发机，实测）

| 服务 | 端口 | 进程 / 启动方式 | 数据 / 日志位置 |
|---|---:|---|---|
| 后端 Spring Boot | **8081** | `java.exe -jar backend\target\robot-autotest-backend-0.1.0-SNAPSHOT.jar`（仓库根启动） | 日志只到控制台；无落盘文件（`logs/` 当前为空） |
| 前端 Vite | **5173** | `frontend` 下 `npm run dev` | 控制台；`/api` 代理到 `127.0.0.1:8081` |
| MySQL | **3306** | WSL `Ubuntu-24.04` 内的 `mysqld` | 库 `robot_dev`；Windows 与 WSL 都走 `127.0.0.1` |

查看进程与端口（PowerShell，只读）：

```powershell
Get-Process java | Select-Object Id, ProcessName, WorkingSet64
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select-Object ProcessId, CommandLine
Get-NetTCPConnection -State Listen -LocalPort 8081, 5173 |
    Select-Object LocalAddress, LocalPort, OwningProcess
```

### 1.2 P 形态（本地 WSL 常驻态，2026-11-12 实测回填）

| 服务 | 端口 | systemd 用户 unit | 关键路径 / 启动命令 |
|---|---:|---|---|
| 后端 Spring Boot（API） | **8081** | `robot-autotest-backend.service` | `/usr/bin/java -jar ~/robot-autotest/backend/target/robot-autotest-backend-0.1.0-SNAPSHOT.jar --planning.scheduler-enabled=true`；WorkingDirectory `~/robot-autotest/backend`；EnvironmentFile `~/.config/robot-autotest/env` |
| 统一入口（静态 dist 与反代） | **8001** | `robot-autotest-entry.service` | `~/.local/bin/node ~/robot-autotest/scripts/serve-entry.mjs`；`ENTRY_PORT=8001`、`ENTRY_BACKEND=http://127.0.0.1:8081`；静态源 `~/robot-autotest/frontend/dist/` |
| 前端 Vite 开发服务 | **5273** | `robot-autotest-frontend.service` | `~/robot-autotest/frontend/node_modules/.bin/vite --host 127.0.0.1 --port 5273 --strictPort` |
| MySQL | 3306（仅本机监听） | `mysql`（系统 unit） | 库名以 `DB_NAME` 为准（开发库 `robot_dev` / 集成库 `robot_it`） |
| Robot 执行器 | 无监听端口 | 由后端 spawn | `~/rf-stack/.venv/bin/{robot,pabot}`，PATH 必须前置；pytest 内核另由 `EXECUTION_PYTEST_BIN` 指向 `~/venvs/deepseek-harness/bin/pytest` |

```bash
# 复核 unit 真实名字与 linger（用户级 unit 不加 linger，重启后不会自动拉起）
systemctl --user list-units --type=service --state=running | grep -E 'robot-autotest|nginx'
loginctl show-user "$USER" | grep Linger
systemctl --user cat robot-autotest-backend.service robot-autotest-entry.service robot-autotest-frontend.service
```

**回填记录（2026-11-12）**：上表 unit 名、后端与入口端口、Vite 端口、jar 与 dist 路径、env 文件均取 `systemctl --user cat` 实读，
`Linger=yes`。部署统一走 `bash scripts/deploy.sh`（构建 → 停旧 → 启新 → `/healthz` 健康检查），
不要手工 `java -jar` 顶替；就绪探针必须打 `:8081`，`:8001/_ping` 是 node 入口自回 200 不算数。

---

## 2. 启停

### 2.1 D 形态（Windows）

```powershell
# 后端：在仓库根启动（当前已在跑，勿重复启动，否则 8081 端口冲突）
$env:JAVA_HOME = 'C:\Users\everg\.jdk\temurin-25\jdk-25.0.2'
$env:DB_HOST='127.0.0.1'; $env:DB_PORT='3306'; $env:DB_NAME='robot_dev'
$env:DB_USERNAME='robot'; $env:DB_PASSWORD='<从本机环境读取，勿写入仓库>'
java -jar backend\target\robot-autotest-backend-0.1.0-SNAPSHOT.jar

# 停：先按 PID 优雅停止（把 <PID> 换成 Get-Process java 的 Id）
Stop-Process -Id <PID>

# 前端
Set-Location frontend
npm run dev          # 启动（5173）
# 停止：在前端终端按 Ctrl-C；或按占用 5173 的 PID 停止
```

> 开发态**不允许**用 `systemctl`；Windows 没有用户级 systemd。

### 2.2 P 形态（本地 WSL）

```bash
UNIT=robot-autotest.service

systemctl --user status  "$UNIT"          # 看状态
systemctl --user start   "$UNIT"          # 启动
systemctl --user stop    "$UNIT"          # 停止
systemctl --user restart "$UNIT"          # 重启

# 开机自启（否则机器重启后服务不会起来）
loginctl enable-linger "$USER"
systemctl --user enable "$UNIT"

# 前端若由 Nginx 承载（系统级 unit，需要 sudo）
sudo systemctl restart nginx
```

**重启前先看有没有在跑的任务**（见 §7.2）。重启会让 `running` 状态的运行被 `RestartRecoveryService` 回收，未完成的 run 不会自动续跑。

---

## 3. 日志位置

| 形态 | 看什么 | 命令 |
|---|---|---|
| D | 后端控制台 | 启动它的那个 PowerShell 窗口；当前没有落盘日志，`logs/` 为空 |
| D | 前端 Vite | `npm run dev` 的终端 |
| D | MySQL | `wsl -d Ubuntu-24.04 -- sudo journalctl -u mysql -n 100` |
| P | systemd 启停与崩溃原因 | `journalctl --user -u robot-autotest -n 200 --no-pager` |
| P | 跟随查看 | `journalctl --user -u robot-autotest -f` |
| P | 应用日志 | `application-prod.yml` 的 `logging.file.name=${LOG_DIR:../logs}/backend.log`；按 `~/.config/robot-autotest/env` 的 `LOG_DIR` 定位，通常是 `~/robot-autotest/logs/backend.log` |
| P | Nginx | `sudo tail -n 100 /var/log/nginx/error.log` |

```bash
# P：一次看全（unit + 应用日志）
journalctl --user -u robot-autotest -n 200 --no-pager
tail -n 200 "${LOG_DIR:-$HOME/robot-autotest/logs}/backend.log"
```

**日志排查顺序**：`journalctl` 看进程有没有起来 → 应用日志看启动异常（DB 口令 / 端口 / 迁移）→ Nginx 日志看 502/404。

---

## 4. 健康检查（3 条 curl，各自有期望值）

### 4.1 D 形态（Windows，实测）

```powershell
# ① 后端存活：期望 200，body 里 "status":"UP"
curl.exe -sS -w "`nHTTP=%{http_code}`n" http://127.0.0.1:8081/api/_ping

# ② 前端存活：期望 200
curl.exe -sS -o NUL -w "HTTP=%{http_code}`n" http://127.0.0.1:5173/

# ③ 鉴权链存活：不带令牌访问受保护接口，期望 401（不是 200，也不是 500）
curl.exe -sS -o NUL -w "HTTP=%{http_code}`n" http://127.0.0.1:8081/api/execution/runs
```

2026-11-03 本机实测：① 返回 `{"code":0,"codeName":"OK","message":"OK","data":{"status":"UP","app":"robot-autotest","version":"v0.1"},"success":true}`；② 返回 200；③ 返回 401。

### 4.2 P 形态（本地 WSL）

```bash
PORT="${SERVER_PORT:-8081}"

# ① 后端存活：期望 200 + status UP
curl -sS -w '\nHTTP=%{http_code}\n' "http://127.0.0.1:${PORT}/api/_ping"

# ② 前端/静态资源：期望 200
curl -sS -o /dev/null -w 'HTTP=%{http_code}\n' "http://127.0.0.1:${PORT}/"

# ③ 鉴权链：期望 401
curl -sS -o /dev/null -w 'HTTP=%{http_code}\n' "http://127.0.0.1:${PORT}/api/execution/runs"
```

**判定**：三条中任一不符合期望，按 §7 处置。① 200 但 ③ 200 说明鉴权拦截器失效，属安全事故，立即停服查 `WebConfig`。

---

## 5. 备份

> 仓库不落口令：先 `export DB_PASSWORD='<口令>'`（P 从 `~/.config/robot-autotest/env` 读；D 从本机环境读）。

### 5.1 DB 每日全量

```bash
# 通用（D / P 的本地 WSL 都适用）
BACKUP_ROOT="${BACKUP_ROOT:-$HOME/robot-autotest/backups}"
STAMP="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_ROOT/db"

MYSQL_PWD="$DB_PASSWORD" mysqldump \
  --host="${DB_HOST:-127.0.0.1}" --port="${DB_PORT:-3306}" \
  --user="${DB_USERNAME:-robot}" \
  --single-transaction --routines --triggers --events --default-character-set=utf8mb4 \
  "${DB_NAME:-robot_dev}" \
  | gzip -9 > "$BACKUP_ROOT/db/${DB_NAME:-robot_dev}_${STAMP}.sql.gz"

gzip -t "$BACKUP_ROOT/db/${DB_NAME:-robot_dev}_${STAMP}.sql.gz" && echo "备份校验 OK"
```

D 形态在 PowerShell 里先进入 WSL 再执行（不要把口令拼进 PowerShell 历史）：

```powershell
wsl -d Ubuntu-24.04
# 进入 WSL 后按上面的 bash 块执行；DB_HOST 用 127.0.0.1
```

### 5.2 产物目录增量

```bash
SRC="${ARTIFACT_ROOT:-$HOME/robot-autotest/artifacts}"
DST="$BACKUP_ROOT/artifacts"
LATEST="$(ls -1d "$DST"/snapshot-* 2>/dev/null | tail -1 || true)"
mkdir -p "$DST/snapshot-$(date +%Y%m%d)"

# --link-dest 做硬链接增量：只新增/变化文件占空间，旧快照不动
if [[ -n "$LATEST" ]]; then
  rsync -a --delete --link-dest="$LATEST" "$SRC/" "$DST/snapshot-$(date +%Y%m%d)/"
else
  rsync -a "$SRC/" "$DST/snapshot-$(date +%Y%m%d)/"
fi
```

> 没有 `rsync` 的机器（Windows 本体）不要用本段；D 形态产物在仓库 `artifacts/`，复制一份到备份盘即可。

### 5.3 验证备份可用（必做，不是可选）

```bash
# 恢复到临时库（不要覆盖生产库）
SCRATCH="${DB_NAME:-robot_dev}_restore_check"
DUMP="$(ls -1t "$BACKUP_ROOT/db"/*.sql.gz | head -1)"   # 取最新一份；也可显式指定文件
MYSQL_PWD="$DB_PASSWORD" mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"${DB_USERNAME:-robot}" \
  -e "DROP DATABASE IF EXISTS \`$SCRATCH\`; CREATE DATABASE \`$SCRATCH\` CHARACTER SET utf8mb4;"

gzip -dc "$DUMP" \
  | MYSQL_PWD="$DB_PASSWORD" mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"${DB_USERNAME:-robot}" "$SCRATCH"

# 校验：表数、关键表行数、Flyway 版本
MYSQL_PWD="$DB_PASSWORD" mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"${DB_USERNAME:-robot}" --table "$SCRATCH" -e "
SELECT COUNT(*) AS tables FROM information_schema.tables WHERE table_schema='$SCRATCH';
SELECT COUNT(*) AS runs FROM \`run\`;
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
```

**通过判据**：表数 > 0、`flyway_schema_history` 有记录、关键表行数与备份前一致。校验完可删临时库（**只删 `_restore_check`**）：

```bash
MYSQL_PWD="$DB_PASSWORD" mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"${DB_USERNAME:-robot}" \
  -e "DROP DATABASE IF EXISTS \`$SCRATCH\`;"
```

### 5.4 保留 30 天

```bash
# 先 dry-run 看要删什么
find "$BACKUP_ROOT" -type f -mtime +30 -print | head -50
# 确认后执行
find "$BACKUP_ROOT" -type f -mtime +30 -delete
```

---

## 6. 恢复（从备份到新库 → 切配置 → 验证）

**适用场景**：业务库损坏、误删、迁移失败。**不要在运行库上直接试**；先在临时库走一遍。

```bash
# 1) 建新库（名字带日期，避免覆盖）
NEW_DB="robot_dev_restore_$(date +%Y%m%d)"
MYSQL_PWD="$DB_PASSWORD" mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"${DB_USERNAME:-robot}" \
  -e "CREATE DATABASE \`$NEW_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2) 导入备份
gzip -dc "$BACKUP_ROOT/db/<要恢复的>.sql.gz" \
  | MYSQL_PWD="$DB_PASSWORD" mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"${DB_USERNAME:-robot}" "$NEW_DB"

# 2b) 产物回灌（DB 恢复了但文件没回来，报告页仍会 404）
rsync -a "$BACKUP_ROOT/artifacts/snapshot-<日期>/" "${ARTIFACT_ROOT:-$HOME/robot-autotest/artifacts}/"

# 3) 切配置：只改环境文件，不改代码
#    ~/.config/robot-autotest/env 里 DB_NAME 改成 $NEW_DB 后重启
sed -i "s/^DB_NAME=.*/DB_NAME=${NEW_DB}/" "$HOME/.config/robot-autotest/env"
systemctl --user restart robot-autotest.service

# 4) 验证（三条 curl + DB 行数 + 登录一次）
curl -sS -o /dev/null -w 'HTTP=%{http_code}\n' "http://127.0.0.1:${SERVER_PORT:-8081}/api/_ping"
MYSQL_PWD="$DB_PASSWORD" mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"${DB_USERNAME:-robot}" -N "$NEW_DB" \
  -e "SELECT COUNT(*) FROM \`run\`; SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
```

**所需时间**：本机 `robot_dev` 为空库，未做真实恢复计时；本地 WSL 生产库必须实测后回填下表，**不许估数充数**。

| 步骤 | 实测耗时 | 记录人 / 日期 |
|---|---|---|
| 建库 + 导入 | 待本地 WSL 回填 | |
| 切配置 + 重启 | 待本地 WSL 回填 | |
| 验证 | 待本地 WSL 回填 | |
| **合计** | 待本地 WSL 回填 | |

**回退本次恢复**：把 `DB_NAME` 改回原库名并重启；新库先保留，确认无误后再删。

---

## 7. 五类故障处置

### 7.1 服务起不来

| 步骤 | D 形态 | P 形态 |
|---|---|---|
| 看进程 | `Get-Process java` | `systemctl --user status robot-autotest` |
| 看日志 | 启动终端 / `logs/backend.log`（P 标准） | `journalctl --user -u robot-autotest -n 200 --no-pager` |
| 查端口占用 | `Get-NetTCPConnection -State Listen -LocalPort 8081` | `ss -ltnp \| grep ':8081'` |
| 查配置缺失 | `DB_PASSWORD` 是否设置 | `test -r ~/.config/robot-autotest/env && grep -c DB_PASSWORD ~/.config/robot-autotest/env` |

常见原因与处理：
1. **端口占用**：`APPLICATION FAILED TO START ... Port 8081 was already in use` → 找占用进程停掉，或改 `SERVER_PORT`。
2. **配置缺失**：`Could not resolve placeholder 'DB_PASSWORD'` → 注入环境变量后重启；不要给配置文件加默认口令。
3. **DB 连不上**：见 §7.4。
4. **迁移/表缺失**：应用启动不自动跑 Flyway（`spring.flyway.enabled=false`），新库必须先 `DB_PASSWORD=... make db-migrate`。
5. **P 上 venv/PATH 丢失**：执行器报 `robot: command not found` → `source ~/rf-stack/.venv/bin/activate` 或把 `~/rf-stack/.venv/bin` 前置到 unit 的 `Environment=PATH`。

### 7.2 运行卡住

先分清「真卡」和「只是慢」：

```bash
# 1) 看运行记录状态
MYSQL_PWD="$DB_PASSWORD" mysql -h127.0.0.1 -P3306 -urobot --table robot_dev -e "
SELECT run_id, run_status, started_at, TIMESTAMPDIFF(MINUTE, started_at, NOW()) AS running_min
FROM \`run\` WHERE run_status='running' ORDER BY started_at;"

# 2) 看租约被谁占着
MYSQL_PWD="$DB_PASSWORD" mysql -h127.0.0.1 -P3306 -urobot --table robot_dev -e "
SELECT resource_key, lease_state, job_id, leased_at, expires_at FROM resource_lease
WHERE lease_state='held' ORDER BY leased_at;"
```

处置顺序（本版**没有**独立的「强制释放」HTTP 入口）：
1. 先看该 run 的日志：`GET /api/execution/runs/{runId}/log?projectId=<id>&limit=200`。
2. 确认无进展 → 调取消接口（取消会顺带释放租约）：
   `curl -X POST -H "Authorization: Bearer $TOKEN" -H "X-Project-Id: $PROJECT_ID" "http://127.0.0.1:${PORT}/api/execution/runs/<runId>/cancel?projectId=$PROJECT_ID" -H 'Content-Type: application/json' -d '{"reason":"stuck"}'`
3. 若进程已死、接口也调不动 → 重启后端；`RestartRecoveryService` 启动时会把中断的 run 回收并释放租约。
4. 最后才手工改库：`UPDATE resource_lease SET lease_state='released' WHERE resource_key='<key>' AND lease_state='held';` —— 改前先记录 `job_id` 与原因，事后补审计说明。

### 7.3 磁盘满

```bash
# 1) 定位
df -h /
du -sh ~/robot-autotest/artifacts/* 2>/dev/null | sort -h | tail -20

# 2) 清理产物：先 dry-run（默认 dryRun=true，只出清单不删）
curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "X-Project-Id: $PROJECT_ID" \
  "http://127.0.0.1:${PORT}/api/reports/retention/run?projectId=$PROJECT_ID&dryRun=true"
# 3) 看清清单后再执行（dryRun=false，需管理员）
curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "X-Project-Id: $PROJECT_ID" \
  "http://127.0.0.1:${PORT}/api/reports/retention/run?projectId=$PROJECT_ID&dryRun=false"

# 4) 日志轮转 / 旧备份（只删确认过的）
ls -lh ~/robot-autotest/logs/
find "$HOME/robot-autotest/backups" -type f -mtime +30 -print | head
```

注意：`report.retention.enabled` 默认 `false`，定时清理不会自动跑；磁盘满时必须人工触发上面的接口。清理前确认 `keep-recent-runs` 与 `max-days` 配置符合预期。

### 7.4 DB 连不上

```bash
# P：服务与端口
systemctl status mysql --no-pager
ss -ltnp | grep 3306

# 连通性 + 账号 + 当前连接数/上限
MYSQL_PWD="$DB_PASSWORD" mysql -h127.0.0.1 -P3306 -urobot -e "
SELECT 1;
SHOW VARIABLES LIKE 'max_connections';
SELECT COUNT(*) FROM information_schema.processlist;"
```

| 现象 | 判断 | 处理 |
|---|---|---|
| `Connection refused` | 库没起 / 端口不通 | `systemctl restart mysql`；防火墙只放本机 |
| `Access denied for user` | 口令或账号错 | 核对 `~/.config/robot-autotest/env` 的 `DB_USERNAME/DB_PASSWORD`，不要改代码 |
| `Too many connections` | 连接泄漏或池上限过高 | 查 `information_schema.processlist` 来源；临时调大 `max_connections`，再按 §7.2 查泄漏 |
| 应用日志 `HikariPool ... connectionTimeout` | 池拿不到连接 | 先看库是否可达，再看 `maximum-pool-size`（prod 16 / dev 4） |

### 7.5 报告导不出

报告导出依赖无头浏览器（`REPORT_PDF_CHROME` → Windows Chrome → Edge → `google-chrome` / `chromium`）。

```bash
# P：浏览器是否存在
command -v google-chrome chromium chromium-browser || true
echo "REPORT_PDF_CHROME=$REPORT_PDF_CHROME"

# 打印页基址必须可达（后端要能访问前端）
curl -sS -o /dev/null -w 'HTTP=%{http_code}\n' "${REPORT_PRINT_BASE_URL:-http://127.0.0.1:5173}/"

# 导出记录状态
MYSQL_PWD="$DB_PASSWORD" mysql -h127.0.0.1 -P3306 -urobot --table robot_dev -e "
SELECT id, run_key, export_format, export_status, file_path, created_at
FROM export_record ORDER BY id DESC LIMIT 10;"
```

处置：
1. 浏览器缺失 → 本地 WSL 安装 chromium 或显式设 `REPORT_PDF_CHROME=/usr/bin/chromium` 后重启。
2. `print-base-url` 不可达 → 改环境变量为前端实际地址；生产同域时指向 `http://127.0.0.1:${SERVER_PORT}`。
3. 单次导出超 90 秒（`report.export.timeout-seconds`）→ 看后端日志是否卡在页面加载；大报告先拆小。
4. `export_status=failed` → 按 `run_key` 找对应 run，重试一次；连续失败按缺陷记录。

---

## 8. 回滚

回滚不在本手册展开，**只认 `docs/ROLLBACK.md`**：

- 默认策略 `--schema=keep`（只回退代码，不回退 schema）；
- 三步：备份当前状态 → 停服/部署上一版 → 起服 + 健康检查 + 抽样；
- `--schema=down` 会删表，必须 `--yes` + 二次确认；
- 干跑：`bash scripts/rollback.sh --to <版本标签> --dry-run`。

**真实回滚演练尚未做，RTO 未计时**；演练记录表在 `docs/ROLLBACK.md`，完成后由大师签字。

---

## 附录 A：一页速查

| 要做的事 | 命令 |
|---|---|
| 后端活着吗 | `curl -sS http://127.0.0.1:8081/api/_ping`（P 默认也是 8081） |
| 重启（P） | `systemctl --user restart robot-autotest.service` |
| 看日志（P） | `journalctl --user -u robot-autotest -n 200 --no-pager` |
| 备份库 | §5.1 `mysqldump ... \| gzip` |
| 验证备份 | §5.3 恢复到 `*_restore_check` 并比对行数 |
| 磁盘满 | §7.3 先 `dryRun=true` 再 `false` |
| 回滚 | `docs/ROLLBACK.md` |

## v3.5+ 增量：运行手册 8 场景与默认值（2026-11-09）

| 项 | 位置 | 状态 |
|---|---|---|
| 8 个运行手册场景（现象 / 判据 / 诊断命令 / 处置 / 回滚 / 验证） | `docs/evidence/v3.7.0/EW37-01/runbook-8-scenarios.md` | 成文；高风险演练需维护窗口 |
| 归档 / 准入 / AI 配额 / 告警四类默认值、开关、降级、成本 | `docs/evidence/v3.7.0/EW37-01/defaults-and-cost.md` | 已记录；归档 `enabled=false` 未擅自开启 |
| 六类韧性注入计划与「注入必红」判据 | `docs/evidence/v3.7.0/EW37-02/resilience-inject-plan.md` | 成文；4 类高风险注入未执行 |
| 特性开关清单（默认关闭、可关闭、最小字段） | `docs/release/v3.5.0/FEATURE-FLAGS.md` | 已冻结 |

> 所有演练记录 `PASS` 必须有原始命令输出；未执行的一律标 `BLOCKED` 并写阻塞原因与所需条件。

## 5.0.X · SLI / SLO 与错误预算规则（W50-28，D5：只落规则不启用通知）

| 对象 | SLI | SLO | 错误预算消耗规则 |
|---|---|---|---|
| `:8001` 入口 | 静态资源与页面可用率 | 月度 99.9% | 连续 5 分钟不可用计 1 个预算单元；耗尽即冻结非修复类变更 |
| `:8081` 后端 | 就绪探针成功比率 | 月度 99.9% | 就绪探针必须打 `:8081`；`:8001/_ping` 是 node 入口自回 200，不算数 |
| 执行链 | 运行创建与状态回写成功率 | 月度 99.5% | 双内核任一失败率越线即停止批次推进并复核 `RUN_ID` 关联 |
| 报告生成 | 四格式导出成功率 | 月度 99.5% | 同一 `RUN_ID` 连续两次导出失败计 1 个单元 |

**D5 裁定**：本版只落上述规则与默认关闭的开关，**不外接任何告警通知通道**、不启用 RUM；
告警触发后的通知路径须大师单独授权。错误预算按自然月归零，读数来源为门禁汇总与运维日志，不做自动考核。
