# EW32-05 执行链幂等、孤儿清理与稳定性证据

批次 B3.4 · 对应 PRD FR-3.2-05 · 日期 2026-11-08

## 1. 结论

| 判据 | 实现 / 证据 | 结果 |
|---|---|---|
| 提交侧 `Idempotency-Key`（Header 优先） | 4 个提交入口统一解析 | 通过 |
| 缺省请求指纹 | 端点 + 项目 + 参数 + 时间窗 SHA-256（64 hex） | 通过 |
| 重复提交 0 重复运行 | 10 并发 / 顺序重复单测 + Header 两次透传同一键 | 通过 |
| 非法幂等键 | 400 `INVALID_ARGUMENT`，不触发执行 | 通过 |
| 孤儿进程周期清理 | 按 DB 活跃运行对账 + 最小存活时间 + 杀进程树 | 通过 |
| 心跳限频 / 租约 / 并发上限 / 重启恢复 | 既有实现 + 既有单测复核 | 通过 |
| 后端全量测试 | **1211 例，0 失败 / 0 错误 / 4 跳过** | 通过 |

## 2. 幂等键设计

`RunIdempotency`（`application/execution`）按优先级解析：

1. `Idempotency-Key` 请求头：1~64 位 `[A-Za-z0-9._:-]`，非法直接 400；
2. 请求体 `runRequestId`：保留 v3.1 行为，只校验 ≤64 字符；
3. 都没有：`SHA-256(端点 | 业务参数 | 时间桶)`，输出 64 位十六进制，
   正好等于 `run.run_request_id VARCHAR(64)`，由唯一键 `uk_run_project_request` 兜底。

**时间窗**（默认 120 秒，`execution.idempotency-window-seconds`）：
- 双击 / 网络重试落在同一窗口 → 返回同一条运行（`findByRequestId` 先查，
  `DataIntegrityViolationException` 后回查，覆盖并发竞态）；
- 过了窗口的相同参数 → 视为有意重跑，生成新键，不会「同配置永远不能再跑」。

关闭开关（`execution.idempotency-fingerprint-enabled=false`）且无显式键时返回
`null`，回退 v3.1 的每次随机请求号。

接入入口（`RunController`）：
- `POST /api/execution/quick-run`
- `POST /api/execution/test-sets/{id}/run`
- `POST /api/execution/cases/run`
- `POST /api/execution/runs`（CI）

四个入口的指纹都包含各自端点的业务参数（套件列表、用例列表、并发、环境、
设备、超时、标签、优先级、节点等），不同端点相同参数不会互相去重。

## 3. 孤儿进程清理

`ExecutionOrphanSweeper`（`application/execution`）：
- 每 60 秒（`execution.orphan-sweep-interval-seconds`，0 关闭）读取 DB
  `preparing/running` 活跃运行；
- 对 `ProcessRegistry` 里不在活跃集合的登记项，且登记时间超过
  `orphan-min-age-seconds`（默认 30 秒，避开「快照后 run 才转 running」的竞态），
  杀进程树（5 秒宽限）并注销；
- 运行产物目录由既有 `RetentionScheduler` 按记录清理，二者共同保证「无孤儿累积」。

`ProcessRegistry` 增加首次登记时间 `registeredAt`，不改变既有注册/注销语义。

## 4. 既有能力复核

- **心跳限频**：`ExecutionNodeService` 的 `heartbeat-throttle-seconds`（默认 5 秒）
  窗口内重复心跳只回应不写库；`NodeHeartbeatMonitorTest` 覆盖漏 3 次才离线。
- **租约**：`LeaseServiceTest` 覆盖同资源互斥、释放后可再申请、部分失败回滚、
  过期回收写历史；`ShardFailoverTest` 覆盖节点失联后的分片重派。
- **并发上限**：`RunService.validate` 校验 `concurrency` 范围与套件/节点数量上限；
  `ProjectRunDispatcherTest` / `RunDispatchNodeTest` 覆盖调度侧。
- **重启恢复**：`RestartRecoveryTest` 覆盖活跃运行置 `interrupted`、释放租约、
  再次恢复不重复收尾。

## 5. 测试

```text
Tests run: 1211, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

原始日志：`logs/backend-full-test.log`。

本工作包新增 13 例：
- `RunIdempotencyTest` 7 例：Header 优先、body 兜底、同窗口稳定、参数/端点/跨窗区分、
  关闭开关、非法键 400、空白 body 视为未提供；
- `ExecutionOrphanSweeperTest` 3 例：清理孤儿、活跃不清理、未满最小存活时间跳过；
- `RunControllerTest` 3 例：Header 两次提交透传同一键、body 兜底、非法 Header 400。

既有幂等单测：`IdempotencyTest` 2 例（10 并发只落一条、顺序重复返回同一条）。

## 6. 偏差与风险

- 指纹去重窗口内的「相同参数」会被视为重试；CI 若在 2 分钟内重复投递同一 payload，
  第二次返回已有 run。需要每次新建运行的调用方应显式传 `Idempotency-Key`（或
  `runRequestId`）。
- 孤儿清扫只处理**本进程登记过**的进程；JVM 崩溃后登记的 PID 已丢失，
  这类 OS 级残留需依赖容器/部署层回收（当前 Windows/WSL 开发环境未做 PID 文件持久化）。
- 心跳限频是「写库限频」，不是拒绝请求：节点仍会收到 200 心跳响应。
- 幂等键列宽沿用 `run_request_id(64)`，未使用 `run.idempotency_key(128)`，
  避免同时维护两套唯一键。
