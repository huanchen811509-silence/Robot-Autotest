# 运行看板 v1（EW32-07）

面向值班/自测的最小看板。两个入口：

## 1. 应用内看板（管理员）

```bash
curl -s http://127.0.0.1:8001/api/admin/observability/summary \
  -H "Cookie: <登录后的会话>" | jq
```

返回字段（`ApiResponse<Summary>`）：

| 字段 | 含义 | 阈值建议 |
|---|---|---|
| `cache.hitRate` | 能力索引缓存命中率 | 稳态 > 0.8；持续 < 0.5 说明 TTL 太短或写多读少 |
| `cache.size` / `cache.maxEntries` | 当前缓存条目 / 上限 | 接近上限说明项目数多，考虑调大 `app.cache.max-entries` |
| `cache.invalidations` | 写后失效次数 | 与写请求量同阶属正常 |
| `async.active` | 审计写入线程数 | 长期打满（= max-size）说明 DB 写慢 |
| `async.queued` | 队列积压 | 持续 > 200 需要关注；= queue-capacity 时开始同步降级 |
| `async.rejected` | 拒绝并降级为同步的次数 | 非 0 说明高峰期队列不够，先看 DB 延迟 |
| `slowQuery.measured` | 经过慢查询埋点的 SQL 次数 | 与列表请求量同阶 |
| `slowQuery.warned` / `errored` | >200ms / >500ms 次数 | `errored` 非 0 必须排查 |
| `slowQuery.failed` | SQL 异常次数 | 非 0 立即排查 |
| `uptimeSeconds` | 进程运行秒数（含所有实例重启） | 频繁归零说明进程不稳定 |

## 2. Actuator

```bash
curl -s http://127.0.0.1:8001/actuator/health
curl -s http://127.0.0.1:8001/actuator/health/readiness
curl -s http://127.0.0.1:8001/actuator/metrics/http.server.requests
```

`http.server.requests` 由 Spring Boot 自动埋点，是 API p95/p99 的线上来源；
`EW32-01` 的压测报告用的是同一口径的脚本采样。

## 3. 已知限制

- 只有单实例内存指标，没有跨实例聚合；多实例部署需要把指标推到 Prometheus（本版未做）。
- `/actuator/metrics` 未加鉴权（部署在本机/内网）；如需公网暴露，必须在网关层限制。
- 前端尚未渲染看板卡片，v1 只提供 JSON 端点与本文档。
