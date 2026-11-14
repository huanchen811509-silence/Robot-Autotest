# v5.7.0 复核缺陷修复 · 真实界面取证（2026-11-14）

> 产出脚本：`scripts/ui-flows-v57.mjs`（CDP 驱动真实 Chrome + 统一入口 `:8001`）
> 读数：`UI_FLOWS_RESULT PASS=35 FAIL=0`，`findings` 1 条（L-57-11）。
> 复跑：`node scripts/ui-flows-v57.mjs --case-id=1132`

## 为什么要有这一份

`06-ACCEPTANCE-AUDIT.md` 的核心批评是「单测绿 ≠ 能走通」：4 处 P0 全部发生在
「前端怎么调后端」这一层，而两侧单测各自都过。所以本波修完之后，判据不能停在单测，
必须有一条**真实浏览器 + 真实后端**的链路证据——这份目录就是。

脚本对每条链路都断言两件事：**后端真的收到了**（网络响应 2xx + 响应体字段）
与**界面真的变了**（DOM 状态）。不是「路由返回 200」那种壳检查。

## 五条链路与判据

| # | 链路 | 截图 | 关键断言（摘） |
|---|---|---|---|
| 1 | 向导建项目 | `01-wizard-content.png`、`02-wizard-review.png`、`03-wizard-created.png`、`04-wizard-listed.png` | `POST /api/projects` 200；`POST /api/projects/{id}/content` 200（**P0-2**：内容步选集后确实提交了 `caseSetIds`，后端认）；新项目出现在 `/g/projects` 列表 |
| 2 | 发起台四模式 | `05-launch-{all,set,case,mixed}.png` | 四种模式切换后 `mode-hint` 各自正确；`POST /projects/93/runs/preview` 四模式全部 200 且返回 `snapshotId`（2/3/5/4）。**不点「发起运行」**——本波无 DUT 派发授权（L-57-01） |
| 3 | 平台集加成员 | `06-set-member-added.png` | `POST /platform/case-sets/78/items` 200，响应体 `{"added":1,"skipped":0}`（**P0-3**：请求体是 `caseIds` 数组，`added` 不再是 0）；成员行数随之变化 |
| 4 | 报告结论 | `07-report-detail.png` | 报告页按真实 run 加载，`/api/reports/{runId}`、`/tree`、`/summary`、`/trend`、`/exports` 全 200。**但页面没有「结论」列** → 记 finding L-57-11（见下） |
| 5 | 运行记录备注 | `08-remark-saved.png`、`09-remark-reopened.png` | 保存 `PUT /api/runs/{runId}` 200；**关掉再打开读回同一串备注**（**P1-5**：打开即取真实备注，不再拿未加载的 `runMetas` 覆盖成空串） |

`result.json` 里另存了本轮捕获的 56 条 API 响应（URL + 状态 + 截断响应体），
用于复核上面每一条判据的原始读数。

## 本波取证新发现（都已一并修掉）

| 编号 | 现象 | 处理 |
|---|---|---|
| **P0-5**（新） | `platformCaseSetApi.list()` 把后端的分页信封 `{items,...}` 当裸数组返回，调用方 `rows.map(...)` 抛 `TypeError`，被 `catch` 吞掉 → `/g/case-sets` 列表与向导「内容」步的用例集分区**永远是空的** | `list()` 拆信封返回数组；`platformCaseSets.spec.ts` 加两条信封用例守门 |
| L-57-11 | 报告详情页（`/reports/:runId`）不展示判据结论：`criteriaConclusion` 只进了快照 / 导出 / 视图渲染，SPA 仍按 `status` 展示 | 登记遗留，归 v5.8.0；本波不把「报告结论改读判据汇总」写成「界面已验」 |
| L-57-10 | 判据写入链路（`PytestCriteriaExtractor` → `test_result.criteria_*`）**没有真实入库样本**：pytest fake 入口不写 `test_result`，real 入口需 DUT 授权 | 登记遗留；P0-4 的结论只按「单测 + 代码走查」记达成，**不写「界面已验」** |

## 取证环境

- 后端：`:8081`（本轮改动后重建 jar 并 `systemctl --user restart`），`/api/_ping` 200
- 前端：统一入口 `:8001`（本轮 `npm run build` 产物）
- 浏览器：Chrome 159（`--remote-debugging-port=9222`）
- 夹具账号：`qa-e2e-admin`；项目 93（发起台）、95（报告）、29（运行记录，485 条 run）
- 遗留物：`UIAUTO-*` 取证项目 5 个（118~122）。删除被后端 409 拒绝——项目下已导入
  36 条用例与 1 个用例集，按设计**只允许停用不允许直删**；保留即可，需要时在界面上归档。
