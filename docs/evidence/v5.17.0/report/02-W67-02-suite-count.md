# W67-02 套件数三样本与深链取证（v5.17.0）

> 对应：`FR-v5.17-07` / `TC-v5.17-46 ~ 51`；来源缺口 G9（5.14 FR-02 / FR-03）。
> 取证时间：2026-11-16 08:24 ~ 08:40（GMT+8）。
> 环境：HEAD `876da9b`，后端 `:8081`，库 `robot_dev`。

---

## 1. 结论

**套件数链路是通的，且列表与详情树逐条相等**——这一条此前九版都没做到（5.14 只验到"字段存在 + 历史 NULL"）。
深链的三条行为用组件断言锁住；**截图未做**，本环境无浏览器（见 §5 降级说明）。

| 判据 | 结果 | 证据 |
|---|---|---|
| 1 三样本 `suite_count` 落真值 | **PASS（口径修正）** | R1=1 / R2=3 / **R3=NULL（设计预期，见 §4）** |
| 2 列表数 = 详情树一级子节点数 | **PASS** | 三条样本逐条相等：1=1、3=3、NULL=0 |
| 3 历史 NULL 显示「—」不显示 0 | **PASS（组件断言）** | `ReportsView.spec.ts` 已有断言 `suite-unknown-*` = `—` |
| 4 深链高亮 + 自动滚动 | **PASS（组件断言，截图降级）** | 新增 5 条断言：`data-suite-target` 标记 + `scrollIntoView` 调用 |
| 5 非法 `suite` 参数可解释提示 | **PASS（组件断言）** | 新增断言：`suite-deeplink-notice` 含非法名，页面不白屏 |
| 6 `robot_dev` 行数前后不变 | **PASS** | 收尾后逐表回到基线（§6） |

---

## 2. 走的真实链路

```
POST /api/projects                    → 建隔离项目 W67-02-SUITE(id 129)
POST /api/nodes/register              → 节点 id=33，返回明文 token
INSERT run ×3（夹具，绑 node_id）      → RUN-W67-R1/R2/R3
POST /api/agent/result + X-Node-Token → 上报 output.xml
   └ ReportIngestService.parseAndIngestForAgent
      └ ReportRepositoryJdbc.replace → insertSuite → CaseKeyPath.topLevelSuiteCount → UPDATE run.suite_count
GET /api/reports?projectId=129        → 列表 suiteCount
GET /api/reports/{runId}/tree         → 详情树
```

`suite_count` **不是脚本算的、也不是直接写库的**，是入库链路按 `CaseKeyPath` 从真实写入的 `case_key` 算出来的。
树的一级子节点用的是同一个 `CaseKeyPath.split/segments`，所以两者同源——本组样本正是要证明它真的同源。

---

## 3. 三样本结果

| 样本 | output.xml 结构 | 期望 | 实测 `suite_count` | 树一级子节点 | 一致 |
|---|---|---|---|---|---|
| R1 单套件 | root(`run-R1`) → `SuiteA` → 2 用例 | 1 | **1** | `['SuiteA']` = 1 | 是 |
| R2 多套件 | root(`run-R2`) → `S1`/`S2`/`S3` → 各 1 用例 | 3 | **3** | `['S1','S2','S3']` = 3 | 是 |
| R3 空 case_key | root(`run-R3`) → 空名 test | NULL | **NULL** | `[]` = 0 | 是 |

关键细节：root 名取 `run-<runId>` 时，`rootBasePath()` 返回空串，套件路径不带 root，
一级套件才等于 root 的子节点数（否则第一段恒为 root 名、永远算 1）。这一点本次实测确认。

---

## 4. 判据 1 的口径修正（必须登记）

PRD 原文：「三样本 `suite_count` 非 NULL」。这条**与 R3 的设计目的冲突**：
R3 存在的意义就是验证「空 / 不可靠 `case_key` → NULL → 界面显示「—」」，
如果它也非 NULL，就没法证明 NULL 语义。

正确口径：**R1 / R2 必须非 NULL 且等于一级套件数；R3 必须为 NULL**（`CaseKeyPath` 对空 key 显式返回 null，
不猜 0——0 是「确认没有子套件」的事实，未知不是事实）。

已按此口径实测通过。PRD 该条判据措辞需在回写时更正（不是放宽，是把矛盾消掉）。

---

## 5. 判据 3/4/5 的证据形态降级

| 项 | 原定 | 实际 | 理由 |
|---|---|---|---|
| 历史 NULL 显示「—」 | 截图 + 断言 | 组件断言（`ReportsView.spec.ts` 既有） | 环境无浏览器 |
| 深链高亮 + 滚动 | 截图 ×2 | 组件断言（新增 5 条） | 同上 |
| 非法 suite 提示 | 截图 | 组件断言（新增） | 同上 |

环境实测：`chrome` / `chromium` / `playwright` / `puppeteer` **均不可用**（CDP `:9222` 无响应，
`~/.cache/ms-playwright` 为空，`frontend/node_modules` 无 playwright）。这与九版里约 60 条截图缺口的根因是同一个。

**这不是"截图不重要"**：`data-suite-target` 标记 + `scrollIntoView` 调用被断言，能证明"页面把高亮下发了、
找到了目标、发出了滚动"；但证明不了"滚完之后目标真的在视口正中、高亮色对比度够"。后者仍需真视口截图，
在补齐浏览器环境后作为 `L-67-04` 补证。

---

## 6. 新增断言与清理核对

| 文件 | 新增 |
|---|---|
| `frontend/src/components/report/SuiteTree.spec.ts` | 3 条：命中名字打标记 / `all` 只标 depth=1 / 无 highlight 不标 |
| `frontend/src/views/ReportDetailView.spec.ts` | 4 条：合法名定位+滚动 / `all` 提示 / 非法名提示不白屏 / 无参数不出提示 |

前端用例：`vitest run` 三份文件 **45 passed**（原 38 + 新增 7）。

收尾后 `robot_dev` 计数（逐表回到基线）：

| 表 | 造数前 | 收尾后 |
|---|---|---|
| `app_user` | 9 | 9 |
| `project` | 14 | 14 |
| `project_member` | 17 | 17 |
| `test_set` | 47 | 47 |
| `test_case` | 12491 | 12491 |
| `run` | 546 | 546 |
| `test_result` | 725 | 725 |
| `test_report` | 3 | 3 |
| `execution_node` | 9 | 9 |

---

## 7. 复跑命令

```bash
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest
python3 <W67-02 脚本>            # 建项目/节点/run → 上报 → 列表 vs 树对比
cd frontend && npx vitest run src/components/report/SuiteTree.spec.ts \
  src/views/ReportDetailView.spec.ts src/views/ReportsView.spec.ts
```

---

## 8. 顺带确认的环境事实（供后续版本复用）

| 事实 | 值 |
|---|---|
| 后端真实端口 | **8081**（`8080` 是另一层代理，直连会 401） |
| 可登录账号 | `qa-e2e-admin` + `QA_E2E_PASSWORD`；`admin` 的密码未在 env 中 |
| 项目删除接口 | 有内容时 **409 拒绝**（只能归档），不能当"清内容"用 |
| `robot_it` | flyway `11.64`（落后 14 个迁移），**13 个用户全是假哈希**，不可直接当隔离环境 |
