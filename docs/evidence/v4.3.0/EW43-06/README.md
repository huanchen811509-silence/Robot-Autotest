# EW43-06 · 「连接与拓扑」页（W43-06）

**日期** 2026-11-10 ｜ **对应** `TC-v4.3-28`、`TC-v4.3-29`、`FR-v4.3-07`

## 1. 改动

| 位置 | 文件 | 改动 |
|---|---|---|
| 页面 | `frontend/src/views/environment/ConnectionsView.vue`（约 430 行） | 拓扑图区、连接明细表、元信息条（生成时间 / 来源 / 命中 / 过期）、刷新按钮、筛选、空态引导 |
| API 封装 | `frontend/src/api/assetInfo.ts` | `getAssetInfo(projectId, params)`、`refreshAssetInfo(projectId)` |
| 词条 | `frontend/src/locales/zh-CN.ts`、`en-US.ts` | 页面标题、列头、元信息、空态、错误提示中英同步 |

## 2. 命令与读数

```bash
# WSL ~/robot-autotest/frontend
./node_modules/.bin/vue-tsc --noEmit
./node_modules/.bin/vite build
```

| 项 | 读数 |
|---|---|
| 类型检查 | 0 错误 |
| 生产构建 | 成功（`frontend/dist` 已随交付包产出） |
| 页面装载 | 仅发 GET `asset-info`；筛选在前端内存内完成，不发请求（源码层依据） |

## 3. 说明

- **缺口（重要）**：页面读数、截图、筛选实测与「加载期 0 写请求」的 CDP `Network.requestWillBeSent` 清单**均未取**。
  `TC-v4.3-28` / `TC-v4.3-29` 记 `NOT-RUN`，不以源码断言替代。补齐方法见 `05-TEST-REPORT.md` §3。
