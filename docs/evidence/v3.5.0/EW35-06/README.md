# EW35-06 · 真实交互性能基线（CWV）

| 项 | 内容 |
|---|---|
| 命令 | `node scripts/ux-probe.mjs`（含 `--selfcheck`） |
| 方法 | CDP + `PerformanceObserver`，4 路由 × 3 次导航，取 p75；Tab 触发真实输入事件 |
| 证据 | `cwv-probe.json`（含每轮原始采样） |
| 结论 | **PARTIAL**：实验室三项达阈；真实用户 RUM 未采，不构成用户体感结论 |

| 指标 | p75 实测 | 阈值 | 判定 |
|---|---:|---:|---|
| LCP | 148 ms | ≤ 2500 ms | PASS |
| INP | 16 ms | ≤ 200 ms | PASS |
| CLS | 0.015 | ≤ 0.1 | PASS |

> 与既有 API p95 / 包体预算**并列、互不替代**；本读数是实验室采样，不是真实用户 p75。
> LCP 偏低与本地单机、静态入口和小体积首屏有关，不得外推为生产环境结论。
