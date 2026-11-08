# RoboScope + 测试框架迁移可行性评估

> **状态：已于 2026-11-02 执行完成。** 149 上服务 active + enabled，重启实测自动拉起；
> 平台库 28 runs 全套迁移无损；**唯一未完成项是实网用例**——执行时 DUT（10.202.54.106/107）
> 从 147 与 149 同时不可达，判定为机房侧掉线，恢复后需补跑。
> 执行细节见 `AGENTS.md` §5.5。
>
> **后续决定（同日晚）：147 将下线删除，149 成为唯一主力环境。**
> 因此本文中「147 可作回退」的说法作废；**删机前须先抢救 147 上的
> `~/probe/`、`~/roboscope-src/`、infra-monitor 备份包、看板快照**，
> 清单与判据见 `AGENTS.md` §5.6。

- 现役机：`192.168.110.149`（Ubuntu 26.04.1 / Python 3.14.4）
- 退役机：`192.168.110.147`（Ubuntu 24.04.5 / Python 3.12.3）— **将下线删除，不作回退**
- 评估日期：2026-11-02
- 评估方式：不是纸面推演，在 149 上做了完整试运行（重建运行时 + 实跑用例 + 平台端到端触发）

---

## 一、结论

**可行，无硬阻塞。** 核心链路（RF 执行 → 连 DUT → 产报告 → 平台入库）在 149 上已全程跑通。

但**不是「打包拷过去就能跑」**：有 6 处必须适配，其中 2 处（venv 不可拷贝、平台库里的 python_version）不做会直接起不来。

迁移体量很小：**必迁资产约 76 MB**（压缩后约 15 MB），运行时全部重建（约 1~2 分钟）。

---

## 二、两台机器对比

| 项 | 现役 147 | 目标 149 | 影响 |
|---|---|---|---|
| OS | Ubuntu 24.04.5 LTS | **Ubuntu 26.04.1 LTS** | 内核 6.8 → 7.0 |
| Python | 3.12.3 | **3.14.4** | 全部二进制依赖需 cp314 wheel |
| CPU | 8 | 8 | 无差异 |
| 内存 | 3868 MB（可用 2573） | **3350 MB**（可用 2805） | 少 518 MB，需留意并发 |
| 磁盘 | 98 G，用 22 G | 98 G，用 7.4 G | 富余充足 |
| 时区 | Asia/Shanghai | Etc/UTC → **已改为 Asia/Shanghai** | 不改会让 metadata 时间差 8 小时 |
| locale | zh_CN.UTF-8 | en_US.UTF-8 | 项目解析都在 DUT 侧，不影响 |
| 到 DUT | 通，RTT 11 ms | **通，RTT 15 ms** | 经 192.168.180.2，路径一致 |
| 本机 RDMA 卡 | 无 | 无 | 两者都只做控制面，无需 IB |
| 防火墙 | 未启用 | 未启用（ufw inactive） | 一致 |

---

## 三、已实测通过的 8 项（附证据）

### 1. 运行时可在 3.14 上完整重建

`~/rf-stack/requirements/lock.txt` 共 145 项：

- **144 项在 Python 3.14.4 上安装成功**，耗时 **49 秒**（清华源）；直连 pypi.org 时 8 分钟没装完
- 所有 C 扩展都有 cp314 wheel，**没有一个触发源码编译**：
  `lxml 6.1.3` / `pillow 12.2.0` / `cryptography 50.0.2` / `cffi 2.1.1` /
  `uvloop 0.23.0` / `httptools 0.9.0` / `pydantic_core 2.50.1` / `greenlet 3.2.5` / `rpds-py`
- 机器上没装 gcc/rustc 也能装完 → 说明只吃 wheel

### 2. 唯一缺项有解

`robotframework-roboscopeheal==0.2.2` **不在 PyPI**（上游 vendored 在 `backend/vendor/`）。
离线包 `wheels/` 里有 `robotframework_roboscopeheal-0.2.2-py3-none-any.whl`（纯 Python），
本地安装即通过。

### 3. RF 实跑通过（真连被测机）

```
RDMA-PRE-001 RoCEv2 前置条件核查                    | PASS |
1 test, 1 passed, 0 failed        (Robot Framework 7.5 / Python 3.14.4)
```

metadata 10 项完整落盘：

```
BASELINE / CASE_ID / CASE_KIND / DUT / LINK / OPERATOR
QA_PROJECT / RUN_ID / STARTED_AT / SW_BUILD
```

### 4. 平台后端启动干净

uvicorn 拉起后无异常：APScheduler 5 个作业正常注册、`rf-mcp` 在 9090 起、前端产物正常出首页、
`/health` `/api/health` `/api/v1/health` `/docs` 全部 200。

### 5. 平台侧执行闭环打通

走 API：登录 → `POST /api/v1/runs` → subprocess runner → RF 子进程 →

```
输出目录 /home/evergreen/.roboscope/reports/run_37_4729b217/
  output.xml (1659 B)  report.html (245 KB)  log.html (238 KB)  stdout.log
run status = passed
```

### 6. robotdashboard 可用

`robotframework-dashboard 2.5.0` 启动成功，HTTP 200，能正常读取旧的 `rf_results.db`。

### 7. systemd 用户服务在 26.04 上正常

`loginctl enable-linger` + unit 安装后 `active`，`/health` 返回 200，`rf-mcp` 正常挂起。

### 8. 平台数据库可直读迁移

`roboscope.db` 副本校验：

```
execution_runs 28 / reports 24 / test_results 368 / repositories 4 / users 1
```

---

## 四、必须适配的 6 处

| # | 问题 | 具体动作 | 不做会怎样 |
|---|---|---|---|
| 1 | **venv 不能拷贝或移动** | 必须 `python3 -m venv` 原地重建 | 实测：`mv` 后 `bin/robot` 报 `bad interpreter: No such file or directory`（shebang 硬编码绝对路径） |
| 2 | `environments.python_version` 记的是 `3.12` | 改成 `3.14`（库里只有 1 行） | 平台执行环境校验可能拒绝或误判 |
| 3 | `tools/link.py:41` 硬编码 `192.168.110.147` | 改默认值或设 `QA_HOST`；README 两处 URL 同步 | 三方关联查询指向旧机 |
| 4 | 26.04 默认时区 UTC | 已改为 Asia/Shanghai | `STARTED_AT` / RUN_ID 时间差 8 小时 |
| 5 | pip 直连 pypi 极慢 | 写 `~/.config/pip/pip.conf` 指清华源 | 重建运行时从 49 秒变 8 分钟以上 |
| 6 | locale 是 en_US | 无需改，但要知情 | 若有脚本解析本地中文输出会失败（本项目无） |

---

## 五、迁移清单

### A 组：必迁（约 76 MB，压缩后约 15 MB）

| 路径 | 大小 | 说明 |
|---|---|---|
| `~/robot-test/gpu-nic-qa` | 26 M | 测试框架 + 用例；**是 git 仓，含 3 个未提交改动**（README / PERF-IPERF-200G-001.yaml / inventory/lab.yaml） |
| `~/robot-test/demo` | 11 M | robotdashboard 的 `db/rf_results.db` |
| `~/robot-test/infra-monitor` | 13 M | 监控脚本 |
| `~/.roboscope/reports` | 19 M | 平台历史执行产物（report.html / output.xml） |
| `~/roboscope-offline-linux/roboscope.db` | 500 K | 平台库（28 runs / 368 结果） |
| `~/roboscope-offline-linux/{src,frontend_dist,migrations,pyproject.toml,alembic.ini,.env}` | ~7 M | 后端源码（含我们的 3 个补丁）+ 前端产物 + **`.env`（SECRET_KEY / LAB_PWD）** |
| `~/.config/systemd/user/{roboscope,robotdashboard}.service` | 4 K | 服务单元 |

### B 组：可选（约 570 MB）

- `~/roboscope-offline-linux/wheels`（272 M）：只为离线重装；149 有外网，可不带
- `~/roboscope-src`（247 M）：只有要重编前端才需要；26.04 apt 里有 `nodejs 22.22`
- `~/tools/node`（203 M）：同上，apt 装即可
- `~/roboscope_offline_linux.zip`（287 M）：安装包原件，不需要

### C 组：不迁（原地重建）

| 路径 | 大小 | 重建方式 |
|---|---|---|
| `~/rf-stack/.venv` | 415 M | `venv + pip install -r lock.txt`，49 秒 |
| `~/ride-env` | 429 M | RIDE 图形界面，按需重建（迁移不影响测试执行） |
| `.roboscope/venvs/roboscope-default` | 31 M | 平台自管环境，起服务时按需重建 |

---

## 六、风险与注意事项

1. **内存少 518 MB**。149 空载 544 MB，起 uvicorn 后 889 MB，可用 2.4 GB。
   单条用例执行没问题（实测），但「平台 + dashboard + 多条 RF 并发」时需盯 `free`。
2. **Python 3.14 不在 RF 7.5 的官方测试矩阵**。RF 7.5 声明 `>=3.9`，未列 3.14。
   核心链路已实测通过，但 `seleniumlibrary` / `robotframework-hub` / `sshlibrary`
   只在导入层验证，未做功能验证——**当前项目没用到这三个**。
3. **平台 DB schema 不走 alembic**（`create_all` + 自研 `_migrate_sqlite`）。
   同版本直接拷 db 文件可行，但**顺序不能错**：先在 147 停服务 → 拷 db → 在 149 起服务。
   不能先在新机用空库跑起来再覆盖旧库。
4. **`.env` 的 `SECRET_KEY` 必须原样带过去**，否则已签发的 token / JWT 全部失效。
5. **Apache-2.0 合规**：前端产物是我们改过的（`.msg-text` 加 `pre-wrap`），
   对外分发时必须带 `LICENSE` 并标注修改。
6. **149 当前已做的系统级改动**：装 `python3.14-venv python3-pip`、`timedatectl set-timezone Asia/Shanghai`、
   `loginctl enable-linger evergreen`。这三项是迁移必需，留着无害。

---

## 七、建议的切换步骤

```
1. 147：systemctl --user stop roboscope robotdashboard      # 停写，保证 db 一致
2. 147：tar 打包 A 组（约 15 MB）
3. 149：解到相同路径 /home/evergreen/...                    # 用户名一致，路径可直接对齐
4. 149：重建 ~/rf-stack/.venv（lock.txt + heal wheel）       # 49 秒
5. 149：改 environments.python_version=3.14、pip.conf、link.py、README URL
6. 149：装两个 systemd unit，enable --now，验证 /health
7. 149：跑 RDMA-PRE-001 + 一条 iperf，核对 metadata 10 项和报告
8. 观察 24 小时；147 保持不关机、只停服务（**此步已作废——147 将直接下线删除**）
```

**回滚**：147 上没有任何删除动作，`systemctl --user start` 两个服务即恢复；
149 侧全是新增目录，可直接删。

> ⚠️ **上述回滚路径仅适用于 147 删除之前。** 一旦 147 下线，回滚手段不复存在，
> 149 出问题只能就地修。所以 §5.6 的三条删除前置条件（尤其「149 实跑通过一条实网用例」）
> 必须先满足。

---

## 八、149 上现有试运行现场（可复用，也可清掉）

```
~/pilot/robot-test/gpu-nic-qa   项目副本（已跑过 RDMA-PRE-001）
~/pilot/rs                     平台后端副本（src + frontend_dist + db 副本）
~/rf-stack/.venv               已按目标路径重建好的运行时（148 包，可留用）
~/robot-test/gpu-nic-qa        软链 → ~/pilot/robot-test/gpu-nic-qa
~/robot-test/demo              软链 → ~/pilot/robot-test/demo
~/mig-test/                    lock.txt / lock_noh.txt（安装清单）
```

注意：`~/robot-test/*` 目前是**软链**，正式切换时要替换成真实目录。

清空现场：`rm -rf ~/pilot ~/mig-test ~/robot-test ~/rf-stack`
