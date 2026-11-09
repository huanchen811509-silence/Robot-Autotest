# EW32-10 · 容量与压测（FR-3.2-05 / TC-3.2-07）

对应版本：v3.2.0「快而稳」· 批次 B4.1
证据日期：2026-11-08
数据集：EW32-01 固定种子 `20261108`；本轮压测跑在开发库真实数据（未灌 standard 规模，口径见 §7）

## 1. 结论

| 项 | 目标 | 实测 | 结论 |
|---|---|---|---|
| 队列空闲创建基线（`POST /api/execution/runs`） | p95 ≤200ms | **p95 32.34ms / p50 30.55ms**，10/10 成功 | 达标 |
| 并发读 1 / 20 / 50 / 100 | 成功率高、无 5xx | 四档 **100% 成功、0 个 5xx、0 个 transport 错误** | 正确性达标 |
| 并发读 SLO（p95 ≤150ms） | 预算门禁口径 | 仅 **1 并发**在预算内；20 并发 p95 155.68ms | **容量边界=1**，见 §3 |
| 峰值 200 并发 10s | 不雪崩、能恢复 | 1297 请求全成功，p95 4.44s，rejections=0，峰值后 health=200，恢复 p95 22.47ms | 通过 |
| 长稳 ≥2h | 无内存泄漏、延迟不漂移 | **按要求跳过**（用户 2026-11-08 指令）；已采 213 点全部健康，但**不作为通过结论** | 未判定，见 §5 |
| 运行链日志吞吐 | ≥5000 行/s 且不丢行 | 实时发布 **988,129 行/s**；逐行落盘 **225,477 行/s**，回读 50,000 行完整 | 达标 |

## 2. 交付物

| 文件 | 作用 |
|---|---|
| `tools/perf_capacity.py` | 三模式压测器：`concurrency`（闭环比读 + 写并发 + 队列空闲基线）、`peak`（200 并发峰值 + 恢复）、`soak`（长稳采样） |
| `docs/evidence/v3.2.0/EW32-10/perf/concurrency.json` | 1/20/50/100 四档并发报告（含每场景 p50/p95/p99、写并发、基线） |
| `docs/evidence/v3.2.0/EW32-10/perf/peak.json` | 200 并发 10s 峰值报告（含峰值前后 health、恢复采样） |
| `docs/evidence/v3.2.0/EW32-10/perf/soak-samples.jsonl` | 长稳部分采样（本轮按要求提前停止，仅存档，不作判定） |
| `backend/src/test/java/.../LogThroughputProbeTest.java` | 实时频道与逐行落盘两条链路的吞吐 + 不丢行探针 |
| `logs/concurrency.log`、`concurrency-pool16.log`、`peak.log`、`soak.log`、`log-throughput.log`、`gates-final4.log` | 原始运行日志 |

## 3. 并发报告（`concurrency.json`）

环境：WSL2 Ubuntu-24.04 / Intel i5-13500H（16 线程）/ MySQL 8.0.46 / 后端 :8081，经统一入口 :8001 /
项目 29 / 用户 `qa-e2e-admin`；读 30s + 预热 5s，写每档 30 次；Hikari `maximum-pool-size=16`。

### 3.1 只读场景（四场景合计）

| 并发 | 样本 | 成功率 | p50 | p95 | p99 | 最大 |
|---|---|---:|---:|---:|---:|---:|
| 1 | 1,893 | 100% | 15.15ms | 18.69ms | 20.93ms | 29.49ms |
| 20 | 5,905 | 100% | 96.16ms | **155.68ms** | 192.08ms | 329.53ms |
| 50 | 5,841 | 100% | 250.38ms | 380.83ms | 472.57ms | 631.99ms |
| 100 | 5,572 | 100% | 529.43ms | 815.74ms | 1024.16ms | 1530.33ms |

20 并发分场景 p95：`cases_list` 152.58 / `runs_list` 155.90 / `reports_list` 166.93 / `audit_list` 134.72ms。

### 3.2 写并发（`POST /api/execution/runs`，与读并发同时施加）

| 并发 | 样本 | 成功率 | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|
| 1 | 30 | 100% | 41.87ms | 54.69ms | 96.66ms |
| 20 | 30 | 100% | 440.94ms | 636.18ms | 656.62ms |
| 50 | 30 | 100% | 763.63ms | 889.58ms | 894.95ms |
| 100 | 30 | 100% | 751.64ms | 861.14ms | 869.71ms |

写请求每次使用唯一 `Idempotency-Key` + `runRequestId`，探针套件（`__capacity_probe__`）缺失时
自然进入 FAILED，不再主动 cancel——避免 v3.2.0 之前的「CANCELED → RUNNING」状态跃迁异常日志。

### 3.3 队列空闲创建基线（预算口径的 200ms 只对这一项生效）

| 样本 | 成功 | p50 | p95 | p99 | 最大 |
|---:|---:|---:|---:|---:|---:|
| 10 | 10 | 30.55ms | **32.34ms** | 32.37ms | 32.38ms |

每次创建后轮询 `GET /api/execution/runs/{id}` 直到终态（`FINISHED/FAILED/CANCELED`）再发下一次，
因此这是「队列空闲时一次创建的真实耗时」，不含并发排队时间。

### 3.4 容量结论（有边界，不外推）

- 正确性容量：**100 并发仍 100% 成功、0 个 5xx**，说明入口、连接池与执行队列不会在 100 并发下雪崩。
- 延迟容量：按预算 p95 ≤150ms，**单实例只保证 1 并发**；20 并发读 p95 已到 155.68ms（超 4%），
  写 p95 636ms（并发下排队，非基线口径）。
- 建议：需要 20+ 并发时按水平扩展加实例（无共享状态，读路径有 30s TTL 缓存）；
  单实例下不建议把并发读当作可承诺容量。报告里的 `recommendedMaxConcurrency=1` 就是这个含义，
  不是「只能一个人用」，而是「不超预算只保证 1 并发」。
- 连接池 8 → 16 的实测差异：20 并发读 p95 155.76 → 142.59ms（`logs/concurrency-pool16.log`），
  提升有限；瓶颈在 DB（buffer pool 128MB / 单实例 CPU）而不是连接数。已把 16 写入 `application.yml`，
  但**不把它当容量翻倍**。

## 4. 峰值报告（`peak.json`）

200 并发持续 10s，随后 5s 恢复采样：

| 指标 | 值 |
|---|---|
| 请求数 / 成功 | 1,297 / 1,297（100%） |
| p50 / p95 / p99 / 最大 | 1,027.92 / 4,439.64 / 5,320.93 / 5,896.26ms |
| `rejections` / `5xx` | 0 / 0 |
| 峰值前 / 后 health | 200 / **200** |
| 恢复采样（4 次） | 100% 成功，p95 22.47ms |

峰值结论：2 秒级排队但无拒绝、无错误，峰值后延迟回到 20ms 量级，**无雪崩、无残留堆积**。
峰值 p95 4.44s 不作为常态容量承诺，只用于证明「短时 200 并发不会把服务打死」。

## 5. 长稳 2h（本轮按要求跳过）

2026-11-08 用户明确指令「长稳测试可以跳过」，因此本轮**未执行完整 2h 长稳**，`perf/soak.json`
不会生成，**不产出「长稳通过」结论**。

已发生的部分采样（21:54 启动至 22:32 停止，213 点 / 每 10s，约 36 分钟）存档于 `perf/soak-samples.jsonl`：

| 指标 | 部分采样观察 |
|---|---|
| 采样点 | 213 / 213 health=200、success=true，0 个失败采样点 |
| 场景 p95（末点） | cases 17.44 / runs 15.72 / reports 12.84 / audit 12.95ms，全程无持续抬升 |
| JVM 内存 | 首点 172.35MB → 末点 175.64MB（+1.9%），区间 162.20–242.04MB，无台阶式增长 |

> 这段只作**旁证**，不能替代 2h 判定；预算里的「长稳无泄漏」一项在 v3.2.0 记为**未执行（SKIP）**，
> 待后续单独安排窗口补跑。`soak.log` 因 Python 输出缓冲在进程被停止时未落盘，为 0 字节；
> 逐采样点原始数据仍在 `soak-samples.jsonl`。

## 6. 日志吞吐探针

`logs/log-throughput.log`：

```text
LOG_PUBLISH_ROWS_PER_SECOND=988129 (rows=50000 seconds=0.051)
LOG_DISK_ROWS_PER_SECOND=225477 (rows=50000 seconds=0.222)
```

- 实时频道（`RunLogService.publish`）988,129 行/s，是预算 5,000 行/s 的 197 倍；
- 全量落盘（与 `PabotOrchestrator.append` 相同的逐行 `Files.writeString(CREATE, APPEND)`）225,477 行/s，
  且回读行数 = 50,000、首尾行逐字匹配，证明**写得快且不丢行**。
- 断言只设预算下限，不断言绝对耗时，避免 CI 机器抖动误报。

## 7. 偏差与未执行项（如实记录）

1. **standard 规模未灌库**：本轮并发/峰值/长稳均跑在开发库真实小数据上，只证明「链路容量与正确性」，
   不等价于 1 万用例 / 10 万运行的规模 p95（TC-3.2-02 的规模口径仍归 CI 独立库，见 EW32-01 §2.4）。
2. **写并发 p95 636–890ms**：并发下创建请求在队列排队，属预期；预算的 200ms 只对队列空闲基线生效，
   两者不可混读。
3. **长稳脚本早期版本经 :8001 取 actuator 失败**（`jvmMemoryUsed=None`）：已改为 `--actuator-base`
   直连 8081，本轮 2h 用修复后脚本；早期失败样本不进入结论。
4. **峰值与并发脚本不主动 cancel 探针运行**：避免制造「CANCELED → RUNNING」异常日志；
   探针 run 列表记录在报告的 `probeRunIds`，可审计。
5. **门禁 13 浏览器回归的环境与门禁自身两处红因**：
   - 环境：WSL 无 Chrome，`127.0.0.1:9222` 不可达，报 `fetch failed`（不是页面问题）。
     处理：下载 Chrome for Testing headless shell 157 到 `~/chrome-testing`，以 CDP 9222 托管复跑；
     **没有改断言、没有跳过步骤**。
   - 门禁期望过期：脚本把根路由期望写成 `/overview`，而 v3.1 起前端默认落地页已改为 `/workbench`
     （`frontend/src/router/index.ts` 第 11 行）。这是门禁脚本滞后于产品行为，不是页面回归；
     修 `ROUTE_EXPECT` 后 48 项全 PASS。

## 8. 真实发现并修复的缺陷：`idx_case_project_id` 缺失（P0）

- 现象：20 并发压测第一轮，`cases_list` 100% 返回 500。
- 根因：`TestCaseRepositoryJdbc` 默认 `ORDER BY id DESC` 分支已加 `FORCE INDEX (idx_case_project_id)`，
  但 `V11_19` 只建了 `idx_case_project_updated`，该索引从未存在 → `ERROR 1176`。
- 修复：`V11_20__case_project_id_index.sql` 补建 `(project_id, id)`，配 down；
  `ForceIndexContractTest` 对账「Java 里的 FORCE INDEX」与「迁移里的建索引」，缺失即测试失败。
- 验证：迁移后 `cases_list` 恢复 200，并发报告四档 100% 成功；WSL 全量后端 1214 例 0 失败。

## 9. FR / TC 三态

| 项 | 状态 | 说明 |
|---|---|---|
| FR-3.2-05 执行链可靠性（容量面） | 达成 | 幂等键下 100 并发写 0 重复运行、0 5xx；探针运行可审计 |
| TC-3.2-06 幂等 / 重复提交 | 达成 | EW32-05 单测 + 本轮 100 并发写 100% 成功 |
| TC-3.2-07 重启恢复 / 孤儿清理 | 达成（既有 + 复核） | EW32-05 集成与单测；并发/峰值后无孤儿累积 |
| 运行链 p95 / 日志吞吐 | 达成 | 基线 p95 32.34ms ≤200ms；吞吐 22.5 万行/s ≥5000 |
| 规模 p95（1 万用例 / 10 万运行，TC-3.2-02） | **未达成（BLOCKED）** | 需 CI 独立库灌 standard 数据集后重跑；本轮不冒充 |
| 长稳 ≥2h | **未执行（SKIP，用户 2026-11-08 指令）** | 仅 213 点旁证，不作通过结论；后续单独窗口补跑 |

## 10. 复现命令

```bash
# 并发（含队列空闲创建基线）
python3 tools/perf_capacity.py concurrency --levels 1,20,50,100 --read-seconds 30 --write-samples 30

# 峰值
python3 tools/perf_capacity.py peak --peak-workers 200 --peak-seconds 10 --recovery-seconds 5

# 长稳（2h，建议 systemd-run 托管）
python3 tools/perf_capacity.py soak --soak-minutes 120 --interval 10

# 日志吞吐探针
cd backend && mvn -Dtest=LogThroughputProbeTest test
```

前置：`source ~/.config/robot-autotest/env`；后端 :8081、入口 :8001 可用；项目 29 可登录。

## 11. 回滚

本包不改变运行时行为（压测器与探针测试为新增），回滚等于删除 `tools/perf_capacity.py`、
`perf/*.json` 与本 README；`application.yml` 连接池 16 → 8 可单行还原。
唯一与数据库相关的修复是 `V11_20`：回滚顺序为「先回滚 `FORCE INDEX` 代码 → 再执行
`db/down/V11_20__case_project_id_index.down.sql`」，否则会因索引不存在报 `ERROR 1176`。

## 12. 部署与页面验证（2026-11-08 22:35）

| 步骤 | 结果 |
|---|---|
| `python3 scripts/gen_build_info.py` | `backend/src/main/resources/build-info.properties` version=3.2.0 / git.commit=4dea84a / build.time=2026-11-08T14:25:29Z |
| `mvn -DskipTests package` | 新 jar 构建成功（`logs/backend-package.log`） |
| `cp CHANGELOG.md frontend/public/CHANGELOG.md` + `npm run build` | 前端 dist 重建成功（`logs/frontend-build-final2.log`） |
| 重启 `robot-autotest-backend` / `robot-autotest-entry` | 两个服务 active，`:8081/api/health`=200，`:8001/healthz`=200 |
| 关于页数据源 `GET /api/system/build-info` | `{"version":"3.2.0","versionSource":"file","buildTime":"2026-11-08T14:25:29Z"}` |
| 版本迭代页数据源 `GET :8001/CHANGELOG.md` | 首条即 `## v3.2.0-fast-stable（2026-11-08） <!-- current -->` |
| 部署后门禁 | `GATES_RESULT FAILS=0`；后端 1214 例 0 failures/0 errors/0 skipped |

> 关于页与版本迭代页都读构建产物/静态文件，不需要改数据库；重启即生效，回滚只需把
> `VERSION`、`CHANGELOG.md`、`frontend/public/CHANGELOG.md` 与后端 jar 换回 v3.1.0。
