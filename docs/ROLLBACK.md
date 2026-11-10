# Robot-Autotest 回滚预案与演练手册（v1.0 / HRD-04）

**对应需求** FR-v1.0-19 / FR-v1.0-20 ｜ **脚本** `scripts/rollback.sh` ｜ **配套** `docs/OPS-MANUAL.md`

> **状态声明（2026-11-03）**：本文的流程与脚本已完成，**真实回滚演练尚未执行，RTO 未计时**。
> 本文所有“耗时/结果”栏必须由本地 WSL 上的真实演练填写，**不得预估充数**；演练完成后由大师签字。
> 干跑证据见 `docs/evidence/v1.0/rollback-dryrun.log`（退出码 0，未改任何状态）。

---

## 1. 三种回滚与默认策略

| 场景 | 做法 | 数据影响 | 默认 |
|---|---|---|---|
| 代码缺陷、新版本起不来 | 回退 jar + dist，**schema 保持**（`--schema=keep`） | 不丢数据 | ✅ **默认** |
| 新版本写入了不兼容数据 / 必须撤销表结构 | 回退代码 **再** 执行 `db/down`（`--schema=down`） | **删列/删表，不可逆** | ❌ 需审批 |
| 单次运行/单条数据错误 | 不回滚，按 `OPS-MANUAL §7.2` 取消运行或用备份单表恢复 | 局部 | — |

**升级失败自动回滚（`bin/update.sh`，FR-v4.0-05）**：`update.sh --apply` 在停服/覆盖/起服/健康抽样任一步失败时，
自动调用内部 `rollback()` 回到升级前的程序文件与版本；`--schema=keep` 模式下不动库，破坏性迁移则恢复 `--apply`
前生成的 DB 备份（RPO = 0）。回滚结论写入 `<prefix>/UPDATE-HISTORY.md` 与 `var/update/state`
（`last_result=ROLLED_BACK`），`bin/status.sh` 会显示「上一可用版本」。回滚本身失败时，
脚本以退出码 2 中止，此时按本文 §2 的三步流程人工恢复。

**为什么默认 `keep`：**

1. 本库迁移是**只增不删**（加列/加表/加索引），V1~V7_1 没有 rename 或类型收窄；旧代码不读新列，天然兼容。
2. `--schema=down` 会 `DROP COLUMN` / `DROP TABLE`（见 §4），删掉的数据只能从备份恢复，恢复时间不可控。
3. 先回代码能把 RTO 压到分钟级；确认确需回 schema 后，再单独走一次 `--schema=down`，风险与责任更清楚。

---

## 2. 三步回滚流程

**前置条件（本地 WSL 运行环境）：**

- 上一版发布包已归档：`~/robot-autotest/releases/<版本标签>/{robot-autotest-backend.jar,dist/}`；
- 环境文件可读：`~/.config/robot-autotest/env`（含 `DB_PASSWORD`，权限 600）；
- 执行人已拿到变更审批；**避开正在跑用例的时段**（回滚会停服）。

```bash
# 0) 只读预检：版本包、服务、库、端口
ls -l "$HOME/robot-autotest/releases/<版本标签>/"
systemctl --user status robot-autotest.service --no-pager
MYSQL_PWD="$DB_PASSWORD" mysql -h127.0.0.1 -P3306 -urobot -e "SELECT 1" robot_dev
ss -ltnp | grep -E ':8081|:8080'
```

### 第 1 步：备份当前状态

脚本自动做四件事，全部落在 `~/robot-autotest/backups/rollback-<时间戳>/`：

1. `db.sql.gz`：`mysqldump --single-transaction --routines --triggers --events`，并用 `gzip -t` 校验；
2. `artifacts-manifest.tsv`：产物文件相对路径 + 字节数；
3. `current-jar.jar` / `current-dist.tar.gz`：当前版本原样保存（**用于回退本次回滚**）；
4. `rollback-target.txt`：本次目标标签。

```bash
bash scripts/rollback.sh --to <版本标签> --dry-run   # 先看命令
bash scripts/rollback.sh --to <版本标签> --yes       # 真实执行第 1~3 步
```

**重入**：失败后修好原因，用同一命令重跑即可；每次会生成新的备份目录，旧备份不覆盖。
**重入边界**：备份、停服、jar/dist 部署、起服都可重复执行；`--schema=down` 的脚本**不全是幂等的**——`V7_1` / `V5_x` 用 `DROP COLUMN IF EXISTS` 可重跑，`V6_3` / `V6_1` / `V4_x` 含无 `IF EXISTS` 的 `DROP INDEX`/`DROP COLUMN`。若 down 执行到一半失败，**不要直接重跑整轮**：先按报错确认该文件是否已部分生效，再手工标记或跳过已成功的文件。
**磁盘**：DB dump 体积约为库大小的 1/5（gzip），回滚前确认 `df -h` 至少留 20% 空闲。

### 第 2 步：停服 → 部署上一版 →（可选）down 迁移

脚本顺序固定，不要手工调换：

```bash
systemctl --user stop robot-autotest.service
cp  <版本标签>/robot-autotest-backend.jar  app/robot-autotest-backend.jar.new
mv  app/robot-autotest-backend.jar.new     app/robot-autotest-backend.jar   # 原子替换
rsync -a --delete <版本标签>/dist/          dist/                            # dist 可选
# 仅 --schema=down：按 db/down/ 版本倒序执行（V7_1 → V6_3 → …）
```

- **先停服再动文件**：jar 被运行中的 JVM 占用时覆盖会失败或产生半包。
- **先部署旧代码，再执行 down**：顺序反了会出现“旧 schema + 新代码”启动失败。
- `--schema=down` 还需要二次确认：TTY 里输入 `DOWN`；非交互环境设 `ROLLBACK_DOWN_CONFIRM=1`（脚本仍要求 `--yes`）。

### 第 3 步：起服 → 健康检查 → 抽样

```bash
systemctl --user start robot-autotest.service
# 脚本内置：最多 30 次 × 2s 重试 /api/_ping，必须 200
curl -sS -o /dev/null -w '%{http_code}\n' "http://127.0.0.1:${SERVER_PORT:-8081}/api/_ping"
```

然后**人工**完成 §6 抽样清单。脚本跑完不等于回滚成功，抽样通过才算。

---

## 3. 脚本参数

```bash
bash scripts/rollback.sh --to <标签> [--dry-run] [--schema=keep|down] [--yes]
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--to` | 必填 | 目标版本标签，需在 `releases/` 下有对应目录；例 `v0.6-automation` |
| `--dry-run` | 关 | 只打印将执行的命令，不改任何状态，退出 0 |
| `--schema` | `keep` | `keep` 只回代码；`down` 额外执行 `db/down` 倒序迁移 |
| `--schema-to` | 由 `--to` 推导 | 显式指定迁移天花板（如 `6.3`），用于非标准标签 |
| `--yes` | 关 | **真实执行必须显式带**；不带时等同干跑，只打印并退出 0 |
| `--app-dir` | `~/robot-autotest` | 应用根目录 |
| `--release-dir` | `<app-dir>/releases` | 历史发布包目录 |
| `--backup-dir` | `<app-dir>/backups` | 回滚备份根目录 |
| `--artifacts-dir` | `<app-dir>/artifacts` | 产物清单来源 |
| `--jar` / `--dist` | `<app-dir>/app/robot-autotest-backend.jar` / `<app-dir>/dist` | 线上包路径 |
| `--src-jar` / `--src-dist` | `<release-dir>/<标签>/...` | 上一版包路径（可覆盖） |
| `--service` | `robot-autotest.service` | systemd **用户级** unit |
| `--base-url` | `http://127.0.0.1:${SERVER_PORT:-8081}` | 健康检查基址 |

环境变量：`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` / `SERVER_PORT`。
**脚本不写死口令**；缺 `DB_PASSWORD` 时真实执行直接拒绝（exit 2）。

**退出码**：`0` 干跑/成功；`1` 中途失败（已提示下一步）；`2` 参数/依赖/发布包缺失；`3` down 未确认被拒绝。

---

## 4. `--schema=down` 风险表（按真实 `db/down/` 文件）

> 执行顺序 = 版本倒序；停服状态下执行，仍会持有表锁。v1.0 回退到 v0.6 只会动 V7_1；回退越早，删得越多。

| down 文件 | 动作 | 风险 | 丢失的数据 |
|---|---|---|---|
| `V7_1__audit_summary.down.sql` | `audit_log` 删 `ip/summary/operator` | 低 | 审计来源 IP 与操作摘要 |
| `V6_3__ci_trigger.down.sql` | `DROP ci_trigger`；`api_token` 删 `scopes/project_id` | **中高** | CI 触发配置；令牌从项目级退化为全局 |
| `V6_2__plan_dependency.down.sql` | `DROP execution_plan_dependency` | **高** | 计划间依赖关系 |
| `V6_1__execution_plan.down.sql` | `DROP execution_plan`；`run` 删 `plan_id/triggered_by_plan_at` | **高** | 全部定时/CI 计划；run 与计划关联 |
| `V5_5` / `V5_4` / `V5_3` / `V5_1` | 删报告、导出、日志定位、metadata 九列 | **高** | `exportable`、十项 metadata、导出 sha256/大小、日志偏移 |
| `V4_3` / `V4_2` / `V4_1` | `DROP run_attempt`、`resource_lease_history`；删租约/并发/优先级/分片列 | **高** | 重试历史、租约历史、并发与排队信息 |
| `V2_5` / `V2_4` / `V2_1` | `DROP editor_keyword`、审计索引、IAM 字段 | **高** | 编辑器关键词元数据、部分 IAM 扩展字段 |
| `V1__baseline.down.sql` | 清空基线全部表 | **灾难** | 整库；**禁止在运行环境执行** |

**共性风险：**

| 风险 | 说明 | 缓解 |
|---|---|---|
| 删表/删列不可逆 | down 脚本没有“再往上”的 up 路径（只能重跑 migration，但数据回不来） | 先备份（脚本第 1 步）；down 仅在审批后执行 |
| 锁表 | `ALTER TABLE ... DROP COLUMN` 需重建表，大表会持锁数分钟 | 停服后执行；避开业务时段；先看表行数 |
| 旧版读新列 | 旧代码不引用新列 → 兼容；**新代码读旧 schema → 启动/查询失败** | 严格“先旧代码、后 down”；down 后不要再起新代码 |
| 外键/索引依赖 | 删列前需先删索引（脚本文件已按此顺序写） | 不要手工拆开执行 |
| 数据保留需求 | 审计/报告数据可能受合规约束 | down 前确认保留期；必要时只 restore 单表 |

---

## 5. 回退本次回滚（把“回滚”再撤回）

第 1 步已保存 `current-jar.jar`、`current-dist.tar.gz`、`db.sql.gz`：

```bash
# 代码回到回滚前
cp  ~/robot-autotest/backups/rollback-<时间戳>/current-jar.jar app/robot-autotest-backend.jar
tar -xzf ~/robot-autotest/backups/rollback-<时间戳>/current-dist.tar.gz -C ~/robot-autotest
systemctl --user restart robot-autotest.service

# schema 若已 down，只能从 db.sql.gz 恢复（会覆盖回滚后新产生的数据）
# 步骤见 docs/OPS-MANUAL.md §6，先恢复到临时库验证，再切 DB_NAME。
```

---

## 6. 回滚后抽样验证清单（必须逐项记录）

| # | 检查项 | 期望 | 实际 | 通过 |
|---|---|---|---|---|
| 1 | 管理员登录 | 能登录，菜单/项目可见 | | ☐ |
| 2 | 打开一个历史报告 | 页面打开，metadata 十项齐全 | | ☐ |
| 3 | 跑一条轻量用例 | 状态到 `passed`/`failed`，不卡 `running` | | ☐ |
| 4 | 健康检查 3 条 curl | `/api/_ping` 200、首页 200、未授权 401 | | ☐ |
| 5 | 数据库版本 | `flyway_schema_history` 最新版本符合预期（keep=不回退） | | ☐ |
| 6 | 产物可下载 | 抽样下载 1 个 log/报告文件，大小非 0 | | ☐ |

任一项失败 → 停止，按 `OPS-MANUAL §7` 定位；必要时用 §5 回退本次回滚。

---

## 7. 演练记录表（空表，本地 WSL 实测后填写）

**演练要求**：在本地 WSL 上、用**真实上一版包**、按 §2 完整走一遍；从第 1 步第一条命令开始计时，到最后一项抽样通过结束。**合计 RTO ≤ 30 分钟**才算达标。

| 序号 | 步骤 | 开始时间 | 结束时间 | 耗时 | 结果（通过/失败） | 执行人 | 备注 |
|---|---|---|---|---|---|---|---|
| 1 | 预检（版本包/服务/库/端口） | | | | | | |
| 2 | 备份（DB + 产物清单 + 当前包） | | | | | | |
| 3 | 停服 | | | | | | |
| 4 | 部署上一版 jar | | | | | | |
| 5 | 部署上一版 dist（如适用） | | | | | | |
| 6 | down 迁移（仅 `--schema=down`） | | | | | | |
| 7 | 起服 + 健康检查 200 | | | | | | |
| 8 | 抽样：登录 | | | | | | |
| 9 | 抽样：打开历史报告 | | | | | | |
| 10 | 抽样：跑一条用例 | | | | | | |
| **合计** | **RTO** | | | | **≤30min 才算通过** | | |

**签字**：执行人 __________ ｜ 复核人 __________ ｜ 大师 __________ ｜ 日期 __________

> 本表当前为空。禁止把干跑时间（`rollback-dryrun.log`，仅打印命令）填入 RTO。

---

## 8. 正向升级路径（与回滚对称，FR-v1.0-20）

1. 归档当前包到 `releases/<当前版本>/`（jar + dist），保留最近 3 个版本；
2. 备份库（`OPS-MANUAL §5.1`）并验证可恢复；
3. 停服 → 部署新 jar/dist → 按 `db/migration/` 正序执行新迁移 → 起服；
4. 健康检查 3 条 + 抽样（登录、看报告、跑一条用例）；
5. 观察若干轮试点迭代记录（每轮记录崩溃/重启/内存趋势/连接数；不设 24h 硬门槛）；异常按 §2 回滚到上一版。

**迁移只增不删**是默认 `keep` 能成立的前提；若未来引入 rename/收窄，必须在本文件补充对应 down 风险并重新评审默认策略。
