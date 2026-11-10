# EW43-00 · 基线快照（W43-00）

**日期** 2026-11-10 ｜ **版本** 4.2.0（改动前） ｜ **用途** 给本版所有「有没有变差」的判断留底

## 1. 门禁基线

| 项 | 读数 |
|---|---|
| 步数 | 32 |
| 结果 | `FAILS=2` |
| 红的两步 | `06-lint`（前端 lint 既有问题）、`13-browser_regression`（CDP 探针不可用） |
| 取法 | WSL `~/robot-autotest` 下 `bash scripts/gates.sh` |

> 这两步在**本版任何改动之前就是红的**，因此收尾复跑时「仍红」不等于本版引入；
> 但也不能据此声称本版 `FAILS=0`，报告里逐项写清差异。

## 2. 版本与契约基线

| 项 | 读数 |
|---|---|
| `VERSION` | `4.2.0` |
| `build-info` | `app.version=4.2.0` |
| OpenAPI 快照 | 291 endpoints / 232 paths |
| 业务表 | 89 张（`V11_28` 后） |
| 迁移版本 | `V11_28` 为最新 |

## 3. 测试基线

| 项 | 读数 |
|---|---|
| 前端 `vitest` | 80 文件 / 583 例 PASS |
| 后端单测 | 1301 例 / 0 失败 / 4 条件跳过 |
| `vue-tsc` | 0 错 |
| `vite build` | 成功 |

## 4. 本版起点缺口（与 00-RESEARCH 对应）

| 缺口 | 基线读数 |
|---|---|
| 工作台组子项 | 3 个（`/workbench`、`/workspace`、`/overview`），无 `/projects/new` |
| PM 的 PROJECT 动作 | `READ` + `UPDATE`，无 `CREATE` |
| `DevicePort` 字段 | 10 个，无 `nicName` / `nicIndex` |
| asset-info 接口 | 不存在 |
