# v5.17.0 测试计划（TEST-PLAN）：缺口闭合（一）取证与接线

> 流程位置：⑤ 测试计划。**无测试计划不许进入开发后期**。
> 上游：`PRD.md`（7 FR / 8 NFR）、`DEV-PLAN.md`（4 波 13 WBS）。
> 编号：`TC-v5.17-xx` 与 PRD 判据一一对应（**禁补号**，插入即重排）。
> 结论四种：`PASS`（一级证据）/ `PARTIAL`（写明缺什么）/ `BLOCKED`（写明环境或授权缺口）/ `FAIL`（未达成且未登记）。
> **单测绿不是放行理由**：每条 FR 至少一条 `HTTP` / `DB` / `UI` / `REAL` 级证据。

---

## 1. 测试范围

| 类别 | 内容 |
|---|---|
| 测 | 7 条 FR 的 37 条判据；8 条 NFR；`评估报告` §6.1 台账中归本版的 7 行关账状态 |
| 不测 | 5.18 的导入向导 / 行数收敛 / 拓扑三件 / 运维真机 / 清场（各自计划另行编写） |
| 环境前提 | 运行中后端（jar commit = HEAD）、`qa-e2e-admin`/`qa-e2e-viewer` 登录态、`robot_it` 隔离库、浏览器（CDP）、`PERF-` 压测项目 |

---

## 2. 方式字典

| 代号 | 含义 | 能证明什么 |
|---|---|---|
| `UT` | 单测 | 分支与结构 |
| `HTTP` | 真实请求 | 接口行为 |
| `DB` | 真实库查询 | 落库事实 |
| `UI` | 真实浏览器（截图 + DOM 断言） | 界面呈现 |
| `MECH` | 机检脚本（含 `--self-test`） | 不变量与结构约束 |
| `REAL` | 真实采集 / 真实脚本执行 | 端到端链路 |
| `REPLAY` | 在 `876da9b` 上回放 | 修前必红 |

---

## 3. TC 清单

### 3.1 FR-v5.17-01 接真模板（6 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.17-01 | 卡片来自 `GET /api/global/templates`，含本版前新建的模板 | HTTP + UI | 网络日志 + 截图 | P0 |
| TC-v5.17-02 | pytest / robot 真模板 → 第 3 步内核随之变化 | UI | 截图 ×2 | P0 |
| TC-v5.17-03 | 卡片 `itemCount` = 详情四类计数之和 | HTTP + UI | 响应 + DOM 断言 | P1 |
| TC-v5.17-04 | 模板为空 → 空态 + 「去新建模板」 | UI | 截图 | P1 |
| TC-v5.17-05 | 后端不可用 → 回退静态卡并标「离线模板」 | UI + 代码检查 | 截图 + 路径检查 | P1 |
| TC-v5.17-06 | 反向回放：`876da9b` 上无该请求 | REPLAY | 回放日志 | P0 |

### 3.2 FR-v5.17-02 场景与快照（6 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.17-07 | 「场景」分区可选可计数 | UI | 截图 | P1 |
| TC-v5.17-08 | 2 集 + 5 例 + 2 场景 → 合计 9 项 | UI | 截图 + 组件断言 | P1 |
| TC-v5.17-09 | 误把 `case_set` 标为场景 → 400 点名 kind | HTTP | 响应 | P1 |
| TC-v5.17-10 | **源集 +3 → 已建项目成员不变** | DB | 前后计数 | P1（不可降级） |
| TC-v5.17-11 | 两向导 import 同一组件，全选/清空/搜索一致 | 代码检查 + UI | 代码检查 + 截图 | P1 |
| TC-v5.17-12 | 反向回放：`876da9b` 上 `sectionKeys` 无 `scenario` | REPLAY | 回放日志 | P1 |

### 3.3 FR-v5.17-03 演示 purge 取证（4 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.17-13 | dry-run 清单含 13 个对象 | REAL | 脚本输出 | P2 |
| TC-v5.17-14 | **dry-run 前后逐表计数一致** | DB | 前后计数 | P2（不可降级） |
| TC-v5.17-15 | 真实项目不出现在清单 | REAL | 脚本输出 | P2 |
| TC-v5.17-16 | `--apply` 后 `is_demo=1` 归 0 | DB | 查询输出 | P2 |

### 3.4 FR-v5.17-04 性能读数（5 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.17-17 | 长列表 ≥3 轮，字段齐全 | UI | json | P1 |
| TC-v5.17-18 | **`min_fps ≥ 50` 才 PASS** | MECH | json + 机检 | P1（不可降级） |
| TC-v5.17-19 | 3000 分类展开 P95 ≤500ms | UI | json | P1 |
| TC-v5.17-20 | `perf_longlist.py --selfcheck` 仍判红 | MECH | 机检输出 | P1 |
| TC-v5.17-21 | manifest 含行数与 sha256 | MECH | manifest | P2 |

### 3.5 FR-v5.17-05 掩码凭证名（5 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.17-22 | 凭证 A 采集 → 首行指向 A，名字为掩码 | HTTP + DB | 响应 + 查询 | P1 |
| TC-v5.17-23 | 换凭证 B → 两条历史分别指向 | HTTP | 响应 | P1 |
| TC-v5.17-24 | 无事实 → null，界面「未记录」 | DB + UI | 截图 + 断言 | P1 |
| TC-v5.17-25 | **响应不含 `secretRef`/`secret_ref`/明文** | MECH | 正则扫描 + 6 处探针 | P1（不可降级） |
| TC-v5.17-26 | 反向回放：`876da9b` 响应无该字段 | REPLAY | 回放日志 | P1 |

### 3.6 FR-v5.17-06 画像 500 台（5 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.17-27 | 500 台下 P95 ≤800ms（≥5 次） | HTTP | 计时 json | P1 |
| TC-v5.17-28 | 若改 SQL：前后响应逐字段一致 | HTTP | 两次 diff | P1 |
| TC-v5.17-29 | 既有工程/用例计数前后不变 | DB | 前后计数 | P1 |
| TC-v5.17-30 | `--purge` 后压测残留 0 | DB | 查询输出 | P2 |
| TC-v5.17-31 | **不可达时如实 FAIL，不用 13 台推断** | MECH | 报告结论 | P1（不可降级） |

### 3.7 FR-v5.17-07 套件数与深链（6 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.17-32 | R1/R2 非 NULL 且等于一级套件数；**R3（空 `case_key`）为 NULL** | DB | 查询输出 | P1 |
| TC-v5.17-33 | **三样本「列表数 = 详情树一级子节点数」逐条相等** | DB + HTTP | 查询 + 响应 | P1（不可降级） |
| TC-v5.17-34 | 历史 NULL 显示「—」 | UI | 组件断言（截图降级，L-67-04） | P1 |
| TC-v5.17-35 | 深链自动滚动 + 高亮 | UI | 组件断言（截图降级，L-67-04） | P1 |
| TC-v5.17-36 | 非法 `suite` 参数 → 可解释提示 | UI | 组件断言（截图降级，L-67-04） | P2 |
| TC-v5.17-37 | `robot_dev` 行数前后不变 | DB | 前后计数 | P1 |

---

## 4. NFR 逐条判据

| NFR | 判据 | 方式 |
|---|---|---|
| NFR-v5.17-01 零迁移 | 最新迁移仍 `V11_78` | MECH |
| NFR-v5.17-02 契约同步 | `gen_api_contract.py --check` PASS | MECH |
| NFR-v5.17-03 门禁只增不减 | `FAILS=0 UNJUDGED=0` | MECH |
| NFR-v5.17-04 前端例数 | ≥ 1184 | UT |
| NFR-v5.17-05 后端例数 | ≥ 2208 | UT |
| NFR-v5.17-06 零泄漏 | 6/6 PASS；凭证名只出掩码 | MECH |
| NFR-v5.17-07 真实优先 | 每条 FR 至少一条 HTTP/DB/UI 级 | 评审 |
| NFR-v5.17-08 造数可逆 | `is_demo=1` 只剩 5.18 待清；`PERF-` 残留 0 | DB + MECH |

---

## 5. 不可降级项（一票否决）

| # | TC | 判据 | 为什么不可降级 |
|---|---|---|---|
| V-1 | TC-v5.17-10 | 源集加成员后已建项目成员不变 | 快照错了就是「项目静默跟着源集变」，用户无从发现 |
| V-2 | TC-v5.17-14 | dry-run 零写入 | dry-run 写库 = 预演本身就在改数据 |
| V-3 | TC-v5.17-18 | `min_fps ≥50` 才 PASS | 5.8 已踩过 harness 冒充的坑 |
| V-4 | TC-v5.17-25 | 凭证名只出掩码 | 泄漏不可回滚 |
| V-5 | TC-v5.17-31 | 不可达时如实 FAIL | 「13 台很快」不能推 500 台达标 |
| V-6 | TC-v5.17-33 | 三样本套件数逐条相等 | 这是 5.14 P0 的唯一放行判据 |

---

## 6. 环境与命令

```bash
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest

cd backend && mvn -o -B test                 # ≥ 2208 例
cd ../frontend && npx vitest run             # ≥ 1184 例
npx vue-tsc --noEmit

python3 tools/gen_api_contract.py --check
python3 tools/probe_oneshot_secret.py
python3 tools/check_navigation.py && python3 tools/check_nav_kind.py

node scripts/perf-longlist.mjs --route cases --rows 100 --dataset 1000 --rounds 3 --seconds 3 \
     --out docs/evidence/v5.17.0/perf/longlist-raw.json
python3 tools/perf_longlist.py --selfcheck
python3 tools/perf_seed.py --devices 500 --project PERF-PROFILE

bash scripts/ops/purge-demo.sh
bash scripts/gates.sh                        # GATES_RESULT FAILS=0 UNJUDGED=0
```

---

## 7. 退出判据

| # | 判据 |
|---|---|
| 1 | 37 条 TC 全部有结论，无「待补」 |
| 2 | 6 条不可降级项全部 PASS |
| 3 | 7 条 FR 全部落定（达成 / 明示降级 / BLOCKED 并写明） |
| 4 | `评估报告` §6.1 台账：7 行转结，6 行改归属 5.18 |
| 5 | 门禁 `FAILS=0 UNJUDGED=0`；`check_release_docs.py v5.17.0` PASS；六份表格机检 PASS |
| 6 | 后端 ≥2208、前端 ≥1184（只增不减） |
| 7 | 本版造数残留 0（`PERF-` 项目、500 台设备、演示项目） |

---

## 8. 证据目录

```text
docs/evidence/v5.17.0/
├── wizard/      # FR-01/02：真模板请求、截图、快照前后计数
├── demo/        # FR-03：dry-run 清单与前后计数
├── perf/        # FR-04/06：longlist-raw.json、manifest、500 台计时
├── collect/     # FR-05：掩码响应、零泄漏探针
└── report/      # FR-07：三样本 DB 读数、深链截图
```

---

## 9. W1 实测结果（2026-11-16 08:04 ~ 08:44）

| TC | 结果 | 实测证据 | 备注 |
|---|---|---|---|
| TC-v5.17-13 | **PASS** | `--json` 清单：test_set 2 + test_case 10 + test_report 1 = 13 | 原脚本漏 `test_set`，已补 |
| TC-v5.17-14 | **PASS** | dry-run 前后 8 张表计数 diff 为空 | 不可降级项，已达成 |
| TC-v5.17-15 | **PASS** | 清单 `project` 仅 3 个 `is_demo=1`，无真实项目 | — |
| TC-v5.17-16 | **PASS** | `--apply` 后 `project`/`app_user` 的 `is_demo=1` 均 0 | 需先清内容，见 D-67-01-3 |
| TC-v5.17-17 ~ 21 | **BLOCKED** | `perf-longlist.mjs` 自报「环境不可用：fetch failed」 | 无 Chrome/CDP/Playwright |
| TC-v5.17-32 | **PASS** | R1=1 / R2=3 / R3=NULL | 口径已修正（R3 预期 NULL） |
| TC-v5.17-33 | **PASS** | 1=1、3=3、NULL=0 逐条相等 | 不可降级项，已达成 |
| TC-v5.17-34 | **PASS** | `ReportsView.spec.ts` 断言 `suite-unknown-*` = `—` | 截图降级为组件断言 |
| TC-v5.17-35 | **PASS** | 新增 5 条断言：`data-suite-target` 标记 + `scrollIntoView` | 同上 |
| TC-v5.17-36 | **PASS** | 新增断言：非法名进 `suite-deeplink-notice`，页面不白屏 | 同上 |
| TC-v5.17-37 | **PASS** | 收尾后 9 张表逐字回到基线 | — |

**W1 合计**：12 条 TC → PASS 9 / BLOCKED 5（TC-17~21 一组）。

### 9.1 本轮暴露的遗留缺陷

| 编号 | 内容 | 归属 |
|---|---|---|
| L-67-04 | 深链真视口截图（滚动到位 + 高亮对比度） | 待浏览器环境 |
| L-67-05 | 长列表 fps / 3000 分类展开读数 | BLOCKED，前置清单见 `evidence/v5.17.0/perf/03` |

### 9.2 测试计划自身的两处修正

| # | 原写法 | 改为 | 原因 |
|---|---|---|---|
| 1 | TC-32「R1/R2/R3 非 NULL」 | R1/R2 非 NULL、R3 为 NULL | 原写法与 R3 的设计目的（验 NULL 语义）自相矛盾 |
| 2 | TC-34/35/36 证据形态「截图」 | 「组件断言（截图降级）」 | 环境无浏览器，如实标注而非默认截图可做 |

---

## 10. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-16 | 首版：72 TC（单版方案） | 首轮 PRD |
| 2026-11-16 | **按 D-1 拆版重写**：本版 37 TC；6 条不可降级；NFR 8 条逐条给判据；证据目录 5 组 | 大师 2026-11-16 拍板 |
| 2026-11-16 | **W1 实测回写**：12 条 TC → PASS 9 / BLOCKED 5；TC-32 口径修正；TC-34/35/36 证据形态改组件断言 | W1 实测 |
