# EW32-04 · 有界异步与优雅停机（FR-3.2-04 / D32-4）

对应版本：v3.2.0「快而稳」· 批次 B3.3
证据日期：2026-11-08

## 1. 交付物

| 类型 | 路径 |
|---|---|
| 有界线程池 | `backend/.../common/async/BoundedExecutor.java` |
| 异步配置 | `backend/.../common/async/AsyncProperties.java`（`app.async.*`） |
| 执行器 Bean（停机排空） | `backend/.../infrastructure/AsyncExecutorConfig.java` |
| 审计写入切换 | `backend/.../application/iam/AuditService.java` |
| 优雅停机配置 | `application.yml`：`server.shutdown=graceful`、`spring.lifecycle.timeout-per-shutdown-phase=20s` |
| 单测 | `BoundedExecutorTest`（6 例） |
| 日志 | `logs/tests-async.log` |

## 2. 真实执行记录

```text
mvn "-Dtest=BoundedExecutorTest" test
→ Tests run: 6, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS（logs/tests-async.log）
```

## 3. FR / TC 三态

| 项 | 状态 | 说明 |
|---|---|---|
| FR-3.2-04 有界池 | 完成 | `ArrayBlockingQueue(1024)`；core 2 / max 4，参数非法启动即失败 |
| FR-3.2-04 拒绝降级 | 完成 | `AbortPolicy` → 捕获后调用线程同步执行；`rejected` 计数可观测 |
| FR-3.2-04 停机不丢审计 | 完成 | `destroyMethod=close`：`shutdown()` → 等待 10s → `shutdownNow()`；测试提交 10 条后 close，10 条全部执行 |
| FR-3.2-04 停机后提交 | 完成 | 不再入队，直接同步执行，不抛 `RejectedExecutionException` |
| FR-3.2-04 trace 透传 | 完成 | 提交时复制 MDC，执行前恢复；测试证明异步线程拿到提交方 traceId，且不污染调用线程 |
| FR-3.2-04 积压可观测 | 完成 | `active/queued/completed/submitted/rejected/shutdown` 由看板端点输出 |
| TC-3.2-06 异步单测 | 完成 | 6 例覆盖异步、拒绝、排空、停机后提交、MDC、非法配置 |

## 4. 实现要点

1. **不用无界队列**：`LinkedBlockingQueue` 无界时，高峰会把任务堆到 OOM；容量 1024 是
   「单条审计 ~2ms × 2 线程」约 1 秒的突发吸收量。
2. **拒绝不是丢**：`AbortPolicy` 故意抛错，由 `execute` 捕获后 `wrapped.run()` 同步执行。
   代价是请求变慢，收益是审计绝不丢——审计是合规数据，不能降级为「尽力而为」。
3. **MDC 显式传递**：`ThreadPoolExecutor` 不会继承父线程 MDC；这里在提交时抓取快照、
   执行前恢复、执行后还原，保证异步日志仍有 `traceId`。
4. **线程非 daemon**：配合 Spring 生命周期，由容器在停机阶段 `close()` 排空；daemon 线程
   会在 JVM 退出时被直接掐断，队列里的审计就丢了。

## 5. 偏差与未执行项

1. **通用事件总线未单独建设**：本版的异步链路就是「审计事件 → 有界执行器 → 仓储」，
   没有第二个订阅方，单独做 `EventBus` 只会留下空转代码。若后续出现通知/索引等订阅者，
   在 `BoundedExecutor` 之上加主题分发即可，不改变拒绝/停机语义。
2. **审计仍可能早于事务提交**：`AuditService.record` 在业务事务内异步提交，极端失败场景
   可能出现「业务回滚但审计已写」。这是既有行为，v3.2.0 未改动；如需严格一致，
   应改为 `TransactionSynchronization.afterCommit` 再入队（列入 v3.3）。
3. **峰值 5000 行/s 未压测**：EW32-01 的日志生成器可产 100 万行日志（66MB，24.4s），
   但「审计写入吞吐」需连真实库；B4.1 容量报告用独立库补测。

## 6. 复现命令

```bash
cd backend
mvn "-Dtest=BoundedExecutorTest" test
```

## 7. 回滚

- 调参：`APP_ASYNC_CORE` / `APP_ASYNC_MAX` / `APP_ASYNC_QUEUE` / `APP_ASYNC_SHUTDOWN_SECONDS`。
- 代码回滚：`AuditService` 恢复为 `CompletableFuture.delayedExecutor(...)` 并移除
  `AsyncExecutorConfig`；`server.shutdown` 改回默认 `immediate`。
