# EW32-07 · traceId / Actuator / 看板 v1（FR-3.2-07）

对应版本：v3.2.0「快而稳」· 批次 B3.3
证据日期：2026-11-08

## 1. 交付物

| 类型 | 路径 |
|---|---|
| traceId 过滤器 | `backend/.../api/TraceIdFilter.java`（`X-Trace-Id` → `X-Request-Id` → 32 位 hex） |
| MDC 透传 | `BoundedExecutor` 提交时复制 MDC（EW32-04） |
| 日志格式 | `application.yml`：`%5p [%X{traceId:-}]` |
| 慢查询计数 | `SlowQuery.stats()`（measured / warned / errored / failed） |
| 看板端点 | `GET /api/admin/observability/summary`（仅管理员） |
| Actuator | `spring-boot-starter-actuator`；暴露 `health,info,metrics`；启用 liveness/readiness |
| 优雅停机 | `server.shutdown=graceful` + 20s 生命周期等待 |
| 看板文档 | `dashboard.md`（指标含义与阈值建议） |
| 单测 | `TraceIdFilterTest`（4 例）、`ObservabilityControllerTest`（2 例） |
| 日志 | `logs/tests-observability.log` |

## 2. 真实执行记录

```text
mvn "-Dtest=TraceIdFilterTest,ObservabilityControllerTest" test
→ Tests run: 6, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS（logs/tests-observability.log）
```

### 2.1 运行时真实验证（2026-11-08 重启后端后补录）

后端 jar 重新打包并重启（`systemctl --user restart robot-autotest-backend`，:8081），
经 :8001 入口采集：

```text
GET /actuator/health
→ {"status":"UP","groups":["liveness","readiness"]}

GET /actuator/metrics/jvm.memory.used
→ name=jvm.memory.used value=1.346376E8，tags 含 application=robot-autotest

GET /api/admin/observability/summary（qa-e2e-admin）
→ cache{enabled:true,maxEntries:512,ttlMillis:30000,hitRate:0.0,...}
  async{active:0,queued:0,submitted:0,rejected:0,shutdown:false}
  slowQuery{measured:1,warned:0,errored:0,failed:0}
  uptimeSeconds:56

请求 X-Request-Id: e2e-trace-1794142936
→ 响应头 X-Trace-Id: e2e-trace-1794142936（沿用原值，未重新生成）
```

原始文件：`logs/observability-check.log`、`logs/observability-summary.json`、
`logs/observability-headers.txt`、`logs/actuator-health.json`、
`logs/actuator-jvm-memory.json`、`logs/actuator-http-requests.json`。

## 3. FR / TC 三态

| 项 | 状态 | 说明 |
|---|---|---|
| FR-3.2-07 traceId | 完成 | 请求头沿用/回退/生成；响应头回写；MDC 生命周期测试覆盖 |
| FR-3.2-07 日志注入防护 | 完成 | 只接受 `[A-Za-z0-9._-]{1,64}`，非法值丢弃并重新生成 |
| FR-3.2-07 结构化日志 | 部分 | 前缀带 traceId，慢查询用 `key=value`；未引入 JSON encoder（见 §5） |
| FR-3.2-07 Actuator health/readiness | 完成（配置级） | `health`、`health/readiness`、`health/liveness` 已启用；运行时验证见 §5 |
| FR-3.2-07 指标 | 完成 | 缓存命中率、异步积压/拒绝、慢查询计数、运行时长 |
| FR-3.2-07 看板 v1 | 完成 | 管理员只读 JSON 端点 + `dashboard.md` |
| TC-3.2-08 traceId/observability 测试 | 完成 | 6 例 |

## 4. 看板字段

见 `dashboard.md`。核心判据：

- `cache.hitRate` 稳态 > 0.8；
- `async.queued` 持续 > 200 或 `async.rejected` 非 0 → 先查 DB 写延迟；
- `slowQuery.errored` 非 0 → 必须有 EXPLAIN 结论；
- `uptimeSeconds` 频繁归零 → 进程稳定性问题。

## 5. 偏差与未执行项

1. **Actuator 真实响应已补录**（见 §2.1）：重启后 `health` 为 UP，`jvm.memory.used` 与
   `http.server.requests` 可读；`/health/readiness` 的 groups 已包含在 health 响应中。
2. **未引入 JSON 日志编码器**：需要新增 logback encoder 依赖；当前 `key=value` + traceId 已能
   被 `grep`/`awk` 解析，满足值班排查。若接入 ELK 再切 JSON。
3. **指标未推 Prometheus**：仅进程内 Micrometer + `/actuator/metrics`；多实例聚合不在本版范围。
4. **`/actuator/metrics` 未鉴权**：默认部署在内网/本机；公网暴露需在网关限制。
5. **看板无前端页面**：v1 只做端点与文档，前端卡片列入 v3.3。

## 6. 运行验证（已执行）

真实输出摘录见 §2.1；复现脚本：`artifacts/verify_observability.sh`（登录后采集，
不打印口令）。核心命令：

```bash
curl -s http://127.0.0.1:8081/actuator/health
curl -s http://127.0.0.1:8081/actuator/metrics/jvm.memory.used
curl -si -H "Authorization: Bearer $TOKEN" -H "X-Request-Id: e2e-trace-1" \
  http://127.0.0.1:8001/api/admin/observability/summary
```

## 7. 复现命令（单测）

```bash
cd backend
mvn "-Dtest=TraceIdFilterTest,ObservabilityControllerTest" test
```

## 8. 回滚

- 移除 actuator 依赖与 `management.*` 配置 → 恢复无 Actuator 状态（traceId 过滤器可独立保留）。
- 删除 `TraceIdFilter` → 日志 pattern 的 `%X{traceId:-}` 输出 `-`，不影响启动。
- 删除 `ObservabilityController` → 看板端点 404；其余功能不受影响。
