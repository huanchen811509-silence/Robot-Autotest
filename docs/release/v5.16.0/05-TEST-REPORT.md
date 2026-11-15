# v5.16.0 测试报告（运维管理）

> 版本定位：把运维动作**任务化 + 可审计 + 有护栏**，不新造能力。
> 结论口径：`PARTIAL` 不是「差不多通过」——它表示**判据未取得完整证据**或**功能本版未做真机验证**，
> 逐条写明缺什么、为什么缺，不冒充达成。

## 1. 结论摘要

| 项 | 读数 |
|---|---|
| 用例总数 | 55 |
| PASS | 29 |
| PARTIAL | 26 |
| FAIL | 0 |
| 全量门禁 | `GATES_RESULT FAILS=0 UNJUDGED=0` |
| 接口契约 | 484 endpoints / 401 paths |
| 迁移 | `V11_78`（业务表 109 → 115，含 `ops_task`） |
| 证据 | `docs/evidence/v5.16.0/` 4 份 |

## 2. 环境与口径

| 项 | 值 |
|---|---|
| 后端 | `http://127.0.0.1:8081`（jar commit 与 HEAD 一致，部署 `PASS=4 FAIL=0`） |
| 数据库 | MySQL 8.0 / MariaDB 驱动，`robot_dev`（115 表） |
| 演练库 | `robot_it`（存在，但与源库有 10 张关键表差异） |
| 项目 | `126`（探针用真实项目，非造数） |
| 账号 | `qa-e2e-admin`（正向）、`qa-e2e-viewer`（403 边界） |
| 版本 | `VERSION=5.7.0`（本版不 bump，按大师规矩留到统一收口） |

## 3. 用例逐条结论

| 用例 | 目标 | 结论 | 判据与理由 |
|---|---|---|---|
| TC-v5.16-01 | 四个二级页可达 | PARTIAL | 四页路由与「运维」一级组 5 叶齐备，`check_navigation.py`（7 组 / 28 叶）与 `check_nav_kind.py`（43 叶）PASS；真视口截图未取 |
| TC-v5.16-02 | 触发备份 2s 内返回 | PARTIAL | `POST /api/ops/tasks` 真实 200 且回 `taskId`；未测端到端耗时 |
| TC-v5.16-03 | 任务历史 | PASS | `GET /api/ops/tasks` 回 `ids=[5,3,2]`，刷新后仍在（HTTP + DB 双证） |
| TC-v5.16-04 | 任务失败信息 | PARTIAL | `result_json` 有 `DRY_RUN` 结构；未构造失败任务样本 |
| TC-v5.16-05 | 同类并发 409 | PARTIAL | 迁移有 `uk_ops_task_active` 互斥锁且机检覆盖；真实并发 409 未构造 |
| TC-v5.16-06 | 六类指标 | PASS | 六类齐备；`sizeMb=38`、`tables=115` 与库内实测一致 |
| TC-v5.16-07 | 停 DB 依赖 | PARTIAL | `databaseUp()` 失败分支会给 `hint` 且其余卡独立；未真停 DB |
| TC-v5.16-08 | 磁盘低阈值 | PARTIAL | 档位枚举实测 `OK`；未构造 <10% 场景 |
| TC-v5.16-09 | 只读 | PASS | 健康页只有刷新按钮，无写入口（代码检查） |
| TC-v5.16-10 | 页面加载 ≤2s | PARTIAL | 命中两个端点（`Promise.all`）；未计时 |
| TC-v5.16-11 | 清单条数 = 目录文件数 | PASS | `api=3 disk=3`（含 3 个临时样本） |
| TC-v5.16-12 | SHA-256 一致 | PASS | 样本用 `sha256sum` 实算后写入 `.sha256`，接口判 `VERIFIED` |
| TC-v5.16-13 | 校验态三态 | PASS | 三态一次取齐：`VERIFIED / MISMATCH / MISSING` |
| TC-v5.16-14 | 新建备份 | PARTIAL | 预演返回 `plan=[PRECHECK,EXECUTE,VERIFY]`；真 `--apply` 未对生产库执行 |
| TC-v5.16-15 | 备份失败阶段 | PARTIAL | 失败阶段字段存在；未构造磁盘/权限/DB 失败 |
| TC-v5.16-16 | 演练恢复到 `robot_it` | PARTIAL | 比对真实跑通 `compared=10`；恢复未执行（演练库与源库已有差异） |
| TC-v5.16-17 | 生产库行数不变 | PARTIAL | 归档预演 `run 543→543`；恢复演练未执行故未比对 |
| TC-v5.16-18 | 比对一致报告 | PARTIAL | 差异场景真实（10 表不一致）；一致场景未构造 |
| TC-v5.16-19 | 非 `_it` 库被拒 | PASS | `HTTP=400`，文案点名「恢复演练只允许以 _it 结尾的演练库」 |
| TC-v5.16-20 | 演练落 `ops_task` | PASS | 恢复演练端点已补 `tasks.create`；比对落行实测 `before=5 after=6` |
| TC-v5.16-21 | 归档策略预演零写入 | PASS | `run 543→543`，预演不改行数 |
| TC-v5.16-22 | 产物清理 dry-run | PARTIAL | 复用既有归档预演；清理单独场景未单测 |
| TC-v5.16-23 | 单入口 | PASS | `/admin/archive` 已改 `redirect: /ops/archive`（路由代码检查） |
| TC-v5.16-24 | 动作审计 | PASS | `GET /api/ops/audit` 五要素齐备，`rows=6` |
| TC-v5.16-25 | 信息一一对应 | PARTIAL | 三页组件被复用嵌入新页；逐条走查清单未出 |
| TC-v5.16-26 | 旧路由重定向 | PARTIAL | 三条 `/maintenance/*` 改 `redirect`，路由级已验；浏览器实跳未截 |
| TC-v5.16-27 | 导航项减少 | PASS | 运维组 5 叶（四页 + 存量同步），原 principles/records/recover 三叶已并入；项目树仍 7 组 |
| TC-v5.16-28 | 七项事实 | PASS | `version=5.7.0`、`installRoot=/home/evergreen/robot-autotest`、命令与保留版本齐备 |
| TC-v5.16-29 | VERSION 与 MANIFEST 一致 | PARTIAL | 本地无 `dist/MANIFEST.json`，接口如实回 `不可比` 而非冒充一致 |
| TC-v5.16-30 | 保留版本数 | PARTIAL | `releases/` 为真实目录扫描；本地条目数与磁盘一致但条目为空 |
| TC-v5.16-31 | doctor 结论可展开 | PARTIAL | 展开按钮与命令可见；`logs/doctor-latest.txt` 不存在故结论为空态 |
| TC-v5.16-32 | 升级中断不白屏 | PARTIAL | 预检只读不依赖升级过程；未注入中断 |
| TC-v5.16-33 | 三类问题各一条 | PARTIAL | `PACKAGE` 与 `MIGRATION` 各构造一条；`DISK` 未构造 |
| TC-v5.16-34 | 预检不改状态 | PASS | 预检前后 `VERSION` 均为 `5.7.0` |
| TC-v5.16-35 | 四阶段结论 | PARTIAL | 只生成命令与边界说明；`update-drill.sh` 未真跑 |
| TC-v5.16-36 | 演练不动生产库 | PARTIAL | 演练未执行，故无「前后行数」证据 |
| TC-v5.16-37 | 结果落 `ops_task` | PARTIAL | 预检是 `GET` 只读端点，不落任务行（与草案预期不同，如实登记） |
| TC-v5.16-38 | 长动作都建任务 | PASS | BACKUP / ARCHIVE / RESTORE_DRILL / BACKUP_COMPARE 四类均落行 |
| TC-v5.16-39 | 进度获取方式 | PASS | 页面无定时器，手动刷新；无 1s 固定轮询（代码检查） |
| TC-v5.16-40 | 中断超时判定 | PARTIAL | `sweep` 端点可跑（`swept=0`）；超租 RUNNING 行未构造 |
| TC-v5.16-41 | 杀进程后状态 | PARTIAL | `sweep` 静态判据与端点齐备；真杀进程未做 |
| TC-v5.16-42 | 日志查看与下载 | PASS | 任务历史表 + 单行日志展开 + `.log` 下载（空日志不给下载按钮） |
| TC-v5.16-43 | P1 dry-run 默认 | PASS | `backup.sh --dry-run` 零写入；任务 `dryRun` 缺省 true |
| TC-v5.16-44 | 无 `--apply` 零写入 | PASS | `backups/*.gz` 数 `0→0` |
| TC-v5.16-45 | P3 备份 hash | PASS | 清单每条带 `sha256State`，不一致醒目 |
| TC-v5.16-46 | P4 全量审计 | PASS | 审计五要素 + 比对落行 `before=5 after=6` |
| TC-v5.16-47 | 机检 `check_ops_guardrails` | PASS | 12 条判据逐条注入判红，干净副本判绿 |
| TC-v5.16-48 | 审计缺失判红 | PASS | `check_ops_audit --self-test` 3 注入（权限闸/审计调用/列名）全判红 |
| TC-v5.16-49 | 护栏违反点名 | PASS | 400 文案点名 `_it` 与「源库名不合法：robot_dev;DROP」 |
| TC-v5.16-50 | 「执行升级」按钮 | PASS | `disabled` + `title` 说明，非隐藏 |
| TC-v5.16-51 | 生成命令可跑 | PARTIAL | 命令拼接正确且可复制；`--dry-run` 端到端跑通未验证 |
| TC-v5.16-52 | 无真实升级端点 | PASS | `/api/ops` 下无升级触发端点（写端点清单 5 条已机检） |
| TC-v5.16-53 | 安装/拆卸说明区 | PASS | 三条边界文案（升级/拆卸/恢复）在页可见 |
| TC-v5.16-54 | 无可执行入口 | PASS | 界面无安装/拆卸按钮，亦无对应端点 |
| TC-v5.16-55 | 两条关键文案 | PASS | 「拆卸不删数据库」「恢复生产库一律走 CLI」中英齐备 |

## 4. NFR 结论

| 编号 | 判据 | 结论 | 证据 |
|---|---|---|---|
| NFR-v5.16-01 | 迁移只能追加 | PASS | `V11_78__v5_16_ops_task.sql` + `.down.sql`；表数 115 与 db-audit PASS |
| NFR-v5.16-02 | 契约同步 | PASS | 484 endpoints / 401 paths，`--check` PASS |
| NFR-v5.16-03 | 不改脚本编排 | PASS | `scripts/ops/update.sh` 零改动；`backup.sh` 只追加 manifest 段 |
| NFR-v5.16-04 | 长动作不阻塞 | PARTIAL | 真实返回 `taskId`；2s 阈值未计时 |
| NFR-v5.16-05 | 无 1s 轮询 | PASS | 页面无 `setInterval`，手动刷新 |
| NFR-v5.16-06 | 破坏性动作零默认 | PASS | `files 0→0`；`dryRun` 缺省 true；无 `--apply` 写入 0 行 |
| NFR-v5.16-07 | 审计完整 | PASS | 5 写端点全过权限闸并落审计；3 注入判红 |
| NFR-v5.16-08 | 真实优先 | PASS | 主探针 26/26 + 补充 7/7，均为真实 HTTP/DB 读数 |

## 5. W7 抓到的真实缺陷（已修）

| 编号 | 现象 | 根因 | 修复 | 回归 |
|---|---|---|---|---|
| B-39 | L1 事实 `version=None`、`installRoot=.../backend` | `appHome()` 直接取 `user.dir`，部署从 `backend/` 起进程 | `user.dir` 以 `backend` 结尾且父目录有 `VERSION` 时上跳一级 | 探针 `version=5.7.0 root=/home/evergreen/robot-autotest` |
| B-40 | dry-run 任务不落库（`ids=[]`、`before=0 after=0`） | `create(..., dryRun=true)` 直接返回 `taskId=0`，与 P4 全量审计冲突 | dry-run 也插一行（PRECHECK → SUCCESS，标 `DRY_RUN`），业务零写入 | `ids=[5,3,2]`、`before=5 after=6` |
| B-41 | 演练与比对两个写端点未落 `ops_task` | 只在服务层做守卫，没调 `tasks.create` | 两处补落行；被守卫拒绝时不落（不留脏审计） | `check_ops_audit` 由 `FAILS=2` → `PASS` |
| B-42 | 备份清单恒空 | 扫 `var/backups`，真实目录是 `<root>/backups` | 改路径；目录不存在返回空清单交前端空态 | `api=3 disk=3` |

## 6. 未达成与降级（不冒充）

| 项 | 现状 | 理由 | 归属 |
|---|---|---|---|
| D-7 运维菜单层级 | 已按 D-7 落地：项目树第 6 组升为「运维」（5 叶），项目树仍 7 组、全局仍 6 组 | 完成，无需拍板 | — |
| 真机备份 `--apply` | 只做 dry-run 与手工样本 | 避免对生产库做 `mysqldump` 污染 | PARTIAL |
| 真实恢复到 `robot_it` | 只验守卫与比对 | 演练库与源库已有 10 表差异，恢复会掩盖差异 | PARTIAL |
| `update-drill.sh` 四阶段 | 只生成命令与边界说明 | 本版不动升级编排（NFR-03） | PARTIAL |
| 真视口截图 | 未取 | 本波以 HTTP / DB / 机检为主 | PARTIAL |
| 异步执行器 | 同步阶段推进，无独立线程池 | 与「2s 内返回」不冲突，但长任务会同步占用请求线程 | 降级登记 |

## 7. 证据索引

| 文件 | 内容 | 读数 |
|---|---|---|
| `docs/evidence/v5.16.0/w7-http-probe.txt` | 主探针：健康 / 事实 / 备份 / 任务 / 演练 / 比对 / 预检 / 审计 / 403 | 26/26 PASS |
| `docs/evidence/v5.16.0/w7-http-probe-extra.txt` | 补充：P1 零写入 / SHA 三态 / 库名闸 / 任务详情 / 归档预演 | 7/7 PASS |
| `docs/evidence/v5.16.0/w7-http-probe-first-run.txt` | 首轮判红留痕（4 条） | 21/25 |
| `docs/evidence/v5.16.0/w7-machine-selfcheck.txt` | 护栏 12 条 + 审计 3 注入 + 自证 | `FAILS=0` |

## 8. 收口说明

- 本版**未** bump `VERSION`、未打 tag、未 push、未同步群晖 —— 按大师规矩留到 5.9~5.16 统一收口。
- 收口提交：5.16 W8 收口。
- 门禁共 57 步（新增第 58 步 `ops_guardrails`），判据 `FAILS=0 UNJUDGED=0`。
