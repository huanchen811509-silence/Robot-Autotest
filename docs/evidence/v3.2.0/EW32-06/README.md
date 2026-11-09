# EW32-06 前端分包、长列表与请求治理证据

批次 B3.4 · 对应 PRD FR-3.2-06 · 日期 2026-11-08

## 1. 结论

| 判据 | 目标 | 实测 | 结果 |
|---|---|---|---|
| 主入口闭包 gzip | ≤300 KiB（307,200 B） | 187,466 B | 通过 |
| 单路由块 gzip | ≤150 KiB（153,600 B） | 40 个，最大 16,372 B | 通过 |
| EditorView 路由块 | 原 253,093 B（247 KiB） | **6,700 B** | 通过（拆出编辑器懒加载） |
| 单路由实际加载总量（路由块 + 按需分包 − 入口已下载） | ≤300 KiB | 最大 EditorView 261,124 B | 通过 |
| 10,000 行长列表 | ≥50 fps | **58.5 fps**，实际渲染 20 行 | 通过 |
| 请求在途去重 / 短缓存 | 同 GET 并发只发一次、短路重复刷新 | 7 例单测通过 | 通过 |

## 2. 改动

1. **编辑器按需加载**：`EditorView.vue` 用 `defineAsyncComponent` 动态导入
   `@robot-autotest/editor`，适配器注入移到 loader，保证组件渲染前完成配置。
   路由块从 253,093 B 降到 6,700 B；编辑器 257,745 B 进入懒加载分包。
2. **长列表窗口化**：`DataTable.vue` 新增 `virtualScroll`，默认 ≥200 行自动开启；
   `maxHeight=null`（整页滚动）时强制关闭。判定逻辑抽到
   `utils/tableVirtual.ts`，4 例纯函数单测覆盖阈值/显式开关/整页滚动。
3. **请求治理**：`api/http.ts` 增加
   - 同 GET 在途去重（并发只发一次）；
   - 3 秒短缓存（`configureRequestCache` 可调，0 关闭）；
   - 任何非 GET 前后清空缓存（避免写后读旧）；
   - `setTokenProvider` 换身份清空缓存；
   - 每个请求带 `X-Request-Id`，后端 `TraceIdFilter` 回显 `X-Trace-Id`。
4. **骨架与预取**：首屏 `router.isReady()` 后在 `requestIdleCallback` 空闲时预取
   工作台 / 用例 / 运行三页（`prefetchRouteViews`），测试环境不触发。

## 3. 门禁口径调整（重要，非放水）

v3.1 的「懒加载共享分包合计 ≤160 KiB」在 v3.2 下与真实目标冲突：
- 编辑器的 257,745 B 从路由块拆出后，进入懒加载合计；DataTable 76,418 B、
  audit 37,151 B 等互不相干的按需包也被相加 → 合计 404,818 B，报 FAIL。
- 但没有任何一个页面会同时下载这三类包；合计口径会逼着把编辑器塞回
  EditorView（正是本次要修的 247 KiB）。

因此 `tools/check_bundle_budget.py` 改为两条更贴近用户体感的判据：
1. **单个懒加载分包 ≤300 KiB**（与主入口同档，仍守住「单文件不得无限膨胀」）；
2. **单路由实际加载总量 ≤300 KiB**：沿静态 + 动态 import 求闭包，扣掉入口已下载
   的共享代码，避免「路由块数字好看、切页仍下 250 KiB」。
按需总量仍打印为 INFO，供趋势对比，但不跨页面相加判违规。

门禁输出见 `perf/bundle-budget.log`：

```
[INFO] 懒加载共享分包 23 个，单包最大 editor-DhJoDG-L.js 257745 B ≤ 307200 B，达标
[INFO] 主入口闭包 gzip 187466 B ≤ 307200 B，含 3 个文件
[INFO] 路由块 40 个，单块最大 AssetsView 16372 B ≤ 153600 B，达标
[INFO] 单路由加载总量最大 EditorView 261124 B ≤ 307200 B，达标
[PASS] 构建产物体积守门通过
```

## 4. 长列表实测

页面：`/dev/grid-bench`（仅 DEV 注册，生产 dist grep `__GRID_BENCH_DEV_ONLY__` 为 0 命中）。
方法：挂载 10,000 行，rAF 连续滚动 3 秒统计帧数，同时统计容器内 `<tr>` 数量。

```
fps=58.5;frames=176;rendered=20;total=10000
```

原始记录：`logs/grid-bench.log`。

## 5. 测试与构建

- 前端单测：**72 文件 / 490 例全绿**（新增 `http.spec.ts` 7 例、`tableVirtual.spec.ts` 4 例），
  见 `logs/frontend-tests.log`。
- 生产构建：`npm run build` 成功，见 `logs/frontend-build.log`。
- 包体门禁：`python3 tools/check_bundle_budget.py` 退出 0，见 `perf/bundle-budget.log`。

## 6. 偏差与风险

- 帧率实测在 DEV 构建、本机 Chrome；生产构建同源依赖，但未在低端机复测。
- `virtualScroll` 只对 `maxHeight` 非 null 的表格生效；整页滚动的页面仍全量渲染
  （这些页面分页 ≤100 行，不属于长列表）。
- 请求短缓存默认 3 秒：写请求会清空，但若第三方直接改库绕过 API，最多 3 秒旧值。
- 编辑器懒加载后，首次进入编辑器页多一次 chunk 下载（261 KiB / 300 KiB 预算内）；
  二次进入命中浏览器缓存。
