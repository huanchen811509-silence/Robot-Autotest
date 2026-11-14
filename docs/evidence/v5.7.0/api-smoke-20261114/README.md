# v5.7.0 真实服务 HTTP 冒烟证据（2026-11-14）

## 环境

- 后端：`systemctl --user restart robot-autotest-backend` 之后运行的新 jar，`/api/_ping` 返回 `version=5.7.0`。
- 入口：`:8081`（后端）、`:8001`（静态托管），`deploy.sh --verify` 4/4 PASS。
- 身份：`qa-e2e-admin` 登录后取 Bearer token（口令来自 `~/.config/robot-autotest/env`，未写入任何证据文件）。
- 复跑脚本：`调研报告-白泽/tools/api_smoke_w570.sh`、`api_crud_smoke_w570.sh`。

## 只读冒烟（9 个端点）

| 端点 | HTTP | 关键返回 |
|---|---|---|
| `GET /api/platform/case-sets?page=1&size=5` | 200 | 含 `SAMPLE-32` 等样例集——**自动播种在真服务上生效** |
| `GET /api/global/cases/grouped` | 200 | 一级「AI应用业务联动」→ 二级「推理负载对照」count=6，分类树真实 |
| `GET /api/projects/95/cases/count` | 200 | `count=469` |
| `POST /api/projects/95/runs/preview` | 200 | `snapshotId=1` + `selectionSha256=e35a7578…`，**快照真实落库** |
| `POST /api/projects/95/runs/preview-commands` | 200 | `requested=0 / executable=0` + `skipReasons`，空清单不编造命令 |
| `GET /api/global/assets/1/credentials/options` | 200 | 返回带验证态的凭证候选 |
| `GET /api/projects/95/runs/RUN-NOPE/criteria` | 200 | `items=[]` + `coverage.total=0`，不存在的 run 不默认判 PASS |
| `GET /api/reports` / `GET /api/test-reports`（缺 projectId） | 400 | `缺少参数：projectId`——既有参数校验未被新端点破坏；补 `projectId=95` 后均 200 |

## 写闭环（`raw/crud-smoke.log`）

一次性集合 `W57-SMOKE-<epoch>`，全流程结束后删除，不留垃圾数据：

| 步骤 | HTTP | 结果 |
|---|---|---|
| `POST /api/platform/case-sets` | 200 | 建集 id=79 |
| `PUT /api/platform/case-sets/79` | 200 | 改名为「冒烟集-已改名」 |
| `POST /79/items` | 200 | `added=1, skipped=0` |
| `GET /79/items` | 200 | 返回真实用例 `AI-APP-001` |
| `DELETE /79/items/1667` | 200 | 移除成功 |
| `PUT /79/order`（空列表） | 400 | `排序列表不能为空`——**参数校验按预期判红**，不是缺陷 |
| `POST /79/items/category-filter` | 200 | `matched=7, added=7, itemSource=category_filter` |
| `POST /api/platform/case-sets/seed-samples?rebuild=false` | 200 | `created=0, skipped=32, addedItems=0`——32 个一级分类样例集已存在，**幂等成立** |
| `DELETE /79` | 200 | 删除成功 |
| `GET /api/platform/case-sets?keyword=<该编号>` | 200 | `items=[], total=0`，清理确认 |

## 结论与边界

- 结论：新端点在**真实运行的服务**上完成注册、鉴权、参数校验与读写闭环；不是只有单测里的 mock。
- 边界：本轮未做逐字段断言矩阵、未做浏览器逐页截图、未接真机 SSH、未做 10 万用例压测。
  这些 TC 在 `docs/release/v5.7.0/05-TEST-REPORT.md` §四 仍标「未执行」，不因本冒烟改判达成。
