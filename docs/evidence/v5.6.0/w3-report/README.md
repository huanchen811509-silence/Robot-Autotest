# W3 报告域证据（v5.6.0 / W56-24 复测与抖动、W56-25 结构归并）

测量日期：2026-11-14（GMT+8）
入口：`http://127.0.0.1:8001`（后端 `:8081`，WSL systemd --user 托管）
真机运行目录：`/tmp/eht-real-runs/w56-24-retry-2`

## 一、W56-24 真机复测（`IM-RDM-022`，`retry=1`）

对象：`tests/nic/new_scripts/test_im_rdm_022.py::test_im_rdm_022_dual_up_pfc_counters`
（`IM-RDM-022`，安全级 L1，`gpu01@10.202.54.101`）。选它是因为它在 v5.5.0 的 59 条实跑里
就是 FAIL——**只有会失败的用例才能让复测真的跑起来**；拿会通过的用例取证，
复测分支一次也触发不到，等于没测。

| 观测项 | 实测值 | 证据 |
|---|---|---|
| 请求 | `POST /api/projects/95/pytest/runs`，`mode=real`、`confirmReal=true`、`retry=1` | `raw/run-response.json` |
| 批次数 | `batches=2`（首轮 + 复测 1 轮） | `raw/run.log` |
| 首轮退出码 | 1，产物 `junit.xml` | `raw/run.log` |
| 复测退出码 | 1，产物 `junit-retry-1.xml` | `raw/run.log` |
| 两份产物是否互相覆盖 | 否；并存且 `sha256` 不同（`3a3a56c6…` vs `552b2970…`） | `raw/run-dir-listing.txt` |
| 收敛判据 | `[retry] 第 1 轮复测完成：提交 1 条，剩余失败 1 条` → 停止，不再复测 | `raw/run.log` |
| 落库判定 | `verdict=FAIL`、`retry_count=1`、`flaky=0` | `raw/report-tree.json` |
| 摘要统计 | `flaky=0`、`failed=1`、`topFailures[0].reproRate=0.1` | `raw/report-summary.json` |
| 报告可见性 | 跑完**不手工入库**，报告树直接返回该用例（`retryCount=1`、`flaky=false`） | `raw/report-tree.json` |

结论：三条判定口径里的「各轮都失败 → 取末轮结论、`flaky=false`、`retry_count` 记事实」
**在真机链路上走通**，且复测产物与首轮互补覆盖。

「首轮 FAIL → 复测 PASS → `FLAKY`」这一条**没有真机样本**：本机没有已知抖动用例，
人为造抖动要往用例库塞临时脚本并改绑定，污染面大于收益。该分支目前只有构造序列的单测
`RetryMergeTest` 覆盖，已如实登记为遗留。

## 二、三条判定口径与覆盖方式

| 情形 | 判定 | 覆盖方式 | 状态 |
|---|---|---|---|
| 只在后轮出现的失败 | 按后轮记（不制造幽灵失败） | `RetryMergeTest` 构造序列 | 单测 |
| 各轮都失败 | 取末轮结论，`flaky=false`，`retry_count` 记次数 | 真机 `w56-24-retry-2` + `RetryMergeTest` | 真机已验 |
| 首轮失败、复测通过 | 记通过 + `flaky=true` + 轮次 | `RetryMergeTest` 构造序列 | 仅单测 |

口径要点：`flaky` 是**判定结果**不是次数，`retryCount` 是**事实**不是配置。
只落 `retryCount` 让前端自己推断抖动，同一个 run 在不同页面会算出不同的抖动数。

## 三、本轮抓到的真缺陷：执行成功但报告空白

**现象**：界面走完一次 fake 执行，进度面板显示「已完成 1/1 · 通过 1」，
点「去报告页看结果」进去是「本报告暂无可统计的用例结果」，十项 metadata 全部「缺失」。

三个根因是**依次暴露**的，修掉前一个才看得见后一个：

| 序号 | 根因 | 表现 | 修法 |
|---|---|---|---|
| 1 | pytest 入口跑完不入库（历史遗留 L-55-01） | 报告页读 `test_result`，`test_result` 里 0 行 | 控制器收尾自动调 `ReportIngestService.parseAndIngest`，入库失败的 note 与 WARN 日志都留 |
| 2 | 异步线程没有租户上下文 | 同步入口修好后仍空白；日志 `BusinessException: 未认证`（`TenantContext.requireUserId`） | 异步任务用 `TenantContext.runAs(callerId, callerName, projectId, …)` 接管身份，`finally` 复原 |
| 3 | 进度终态上报早于入库 | 页面看到「已完成」立刻点报告，此时还没写完 | `progress.complete` 挪到入库之后——**收尾顺序本身就是界面体验** |

**为什么之前一直是绿的**：E2E 的 `e2e-09` 只断言「页面有『用例』字样且不报不存在」，
空白报告页照样满足。修完把断言加强成「必须出现用例编号 `IM-XXX-000`」，
再跑一次才真正把这条缺陷挡住（`../effect-metrics/e2e-smoke-20261114.json`）。

## 四、W56-25 docx 结构归并

对象：21 条用例的真实 run `EHT-REAL-L0-20261113-220048-01`，导出 `mode=internal`、`format=docx`。

| 观测项 | 实测值 | 证据 |
|---|---|---|
| 导出结果 | 200，4412 B，`qualityGate={passed:true, label:"FINAL"}` | `raw/docx-export-response.json` |
| 章节标题数 | 2（`一、十项 metadata`、`二、用例明细`） | `raw/docx-heading-stat.json` |
| 明细承载方式 | 单表，表行 22 = 表头 1 + 用例 21 | `raw/docx-heading-stat.json` |
| 判定 | 2 ≤ 脚本文件数 21 + 固定章节数，远低于「每条用例一个标题」的旧口径 | 同上 |

改动前一条用例一个二级标题，21 条就是 21 个标题、报告目录被用例名撑满；
现在标题只随**章节**增长，用例改由表格承载，条数再涨也不影响目录。

注：该导出物只有两章（metadata + 用例明细），缺「结论/风险/建议」章节。
那属于 FR-v5.6-33（对外报告叙述，后续版本），不在本版范围，未见章节残缺当缺陷。

## 五、复现命令

```bash
# 1) 真机复测（会连 DUT；confirmReal=true + ADR-0013 安全闸照常生效）
curl -sS --noproxy '*' -X POST http://127.0.0.1:8001/api/projects/95/pytest/runs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"runId":"w56-24-retry-2","mode":"real","nodeIds":["tests/nic/new_scripts/test_im_rdm_022.py::test_im_rdm_022_dual_up_pfc_counters"],"hostKeys":["gpu01@10.202.54.101"],"confirmReal":true,"retry":1}'

# 2) 读判定（跑完已自动入库，不需要手工 parse）
curl -sS --noproxy '*' "http://127.0.0.1:8001/api/reports/w56-24-retry-2/tree?projectId=95" \
  -H "Authorization: Bearer $TOKEN"

# 3) 只有入库失败时才需要手工补
curl -sS --noproxy '*' -X POST "http://127.0.0.1:8001/api/reports/w56-24-retry-2/parse?projectId=95" \
  -H "Authorization: Bearer $TOKEN"

# 4) 导出 docx 并数章节
curl -sS --noproxy '*' -X POST \
  "http://127.0.0.1:8001/api/reports/EHT-REAL-L0-20261113-220048-01/export?projectId=95&mode=internal&format=docx" \
  -H "Authorization: Bearer $TOKEN"
```
