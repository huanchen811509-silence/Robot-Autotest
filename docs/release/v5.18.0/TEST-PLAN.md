# v5.18.0 测试计划（TEST-PLAN）：缺口闭合（二）建设与清场

> 流程位置：⑤ 测试计划（草案）。**待 v5.17.0 收口后进 ④**。
> 上游：`PRD.md`（7 FR / 8 NFR）、`DEV-PLAN.md`（6 波 18 WBS）。
> 编号：`TC-v5.18-xx` 与 PRD 判据一一对应（**禁补号**，插入即重排）。
> 结论四种：`PASS` / `PARTIAL`（写明缺什么）/ `BLOCKED`（写明环境或授权缺口）/ `FAIL`（未达成且未登记）。

---

## 1. 测试范围

| 类别 | 内容 |
|---|---|
| 测 | 7 条 FR 的 42 条判据；8 条 NFR；`评估报告` §6.1 台账剩余 6 行的关账 |
| 不测 | 5.17 已关账的 7 项（回归由全量单测覆盖） |
| 环境前提 | 运行中后端、`qa-e2e-admin`/`viewer` 登录态、`robot_it` 隔离库、浏览器（CDP）、`PERF-`/`UIAUTO-` 现存残留（清场用） |

---

## 2. TC 清单

### 2.1 FR-v5.18-01 导入五步向导（8 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.18-01 | 五步可前进/回退，步态正确 | UI | 截图 ×3 | P1 |
| TC-v5.18-02 | 映射页显示前 3 行样例，改列后变化 | UI | 截图 ×2 | P1 |
| TC-v5.18-03 | 预览三数与提交结果逐数一致 | HTTP + DB | 响应 + 计数 | P1 |
| TC-v5.18-04 | **取消 → 库零写入** | DB | 前后计数 | P1（不可降级） |
| TC-v5.18-05 | **不传 `mapping` 旧行为不变** | HTTP + UT | 回归单测 + 请求 | P1（不可降级） |
| TC-v5.18-06 | 导航组数叶数不变 | MECH | 机检输出 | P1 |
| TC-v5.18-07 | 结果页可跳 `?view=imports` | UI | 截图 | P2 |
| TC-v5.18-08 | 反向检查：`876da9b` 无 `ImportWizard.vue` | REPLAY | 代码检查 | P1 |

### 2.2 FR-v5.18-02 行数收敛（6 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.18-09 | 三文件合计 ≤2200 | MECH | `wc -l` | P1 |
| TC-v5.18-10 | 单文件 ≤800 | MECH | `wc -l` | P1 |
| TC-v5.18-11 | **42 条资产回归一条不减** | UT | `vitest run` | P1（不可降级） |
| TC-v5.18-12 | 矩阵 12 条 + 4 条反证全绿 | UT | spec | P1 |
| TC-v5.18-13 | 抽出组件各有独立 spec | UT | spec 文件 | P2 |
| TC-v5.18-14 | 九项功能面走查无回退 | UI | 清单 + 截图 | P1 |

### 2.3 FR-v5.18-03 图例与过滤（5 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.18-15 | 图例四类可见，颜色/线型固定 | UI | 截图 | P1 |
| TC-v5.18-16 | **仅实测边数 = 接口 MEASURED 数** | UI + HTTP | DOM + 响应 | P1（不可降级） |
| TC-v5.18-17 | 四类计数之和 = 总边数 | UI | DOM 断言 | P1 |
| TC-v5.18-18 | 过滤态落 URL 可复现 | UI | URL + 截图 | P2 |
| TC-v5.18-19 | 空数据 → 自解释空态 | UI | 截图 | P2 |

### 2.4 FR-v5.18-04 规划源（5 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.18-20 | 两种节点都出图并标「规划」 | HTTP + UI | 响应 + 截图 | P1 |
| TC-v5.18-21 | **同对端双来源只一条，来源 = MEASURED** | HTTP | 响应 | P1（不可降级） |
| TC-v5.18-22 | 只有规划边 → 来源 = PLANNED | HTTP | 响应 | P1 |
| TC-v5.18-23 | `topology_node` 写入 count > 0 | DB | 查询 | P1 |
| TC-v5.18-24 | 导入端点仍幂等 | HTTP + DB | 请求 + 计数 | P1 |

### 2.5 FR-v5.18-05 交换机 / BMC 探针包（5 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.18-25 | 三类包命令 ⊆ 白名单 | MECH + UT | 机检 + 单测 | P2 |
| TC-v5.18-26 | `deviceType` 空 → 要求指定或带 `assumedType` | HTTP | 响应 | P2 |
| TC-v5.18-27 | 未知类型 → 400 点名 | HTTP | 响应 | P2 |
| TC-v5.18-28 | `required` 按类独立 | UT | 单测 | P2 |
| TC-v5.18-29 | 真机采集（无样本则 BLOCKED 并写明） | REAL | 探针 / 登记 | P2 |

### 2.6 FR-v5.18-06 运维真机动作（6 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.18-30 | 备份产物 + `.sha256` 校验通过 | REAL | 命令输出 | P1 |
| TC-v5.18-31 | 恢复后 10 表比对有结论 | REAL + DB | 比对输出 | P1 |
| TC-v5.18-32 | `update-drill.sh` 四阶段各有结论 | REAL | 脚本输出 | P1 |
| TC-v5.18-33 | **`robot_dev` 行数前后一致** | DB | 前后计数 | P1（不可降级） |
| TC-v5.18-34 | 演练落 `ops_task`（`dryRun=0`） | DB | 查询 | P1 |
| TC-v5.18-35 | 非 `_it` 库仍被拒 400 | HTTP | 响应 | P1 |

### 2.7 FR-v5.18-07 清场（7 条）

| TC | 判据 | 方式 | 证据形态 | 优先级 |
|---|---|---|---|---|
| TC-v5.18-36 | 盘点清单逐对象含「信号 + 理由」，三类分组齐全 | MECH | 清单文件 | P0 |
| TC-v5.18-37 | **执行前备份存在且 `sha256sum -c` 通过** | REAL | 命令输出 | P0（不可降级） |
| TC-v5.18-38 | dry-run 零写入（逐表计数一致） | DB | 前后计数 | P0 |
| TC-v5.18-39 | `--apply` 后候选对象全 0（`is_demo` / `PERF-` / `UIAUTO-` / `PRJ-RF-202611` / 候选账号） | DB | 查询输出 | P0 |
| TC-v5.18-40 | **保留对象零变化**：保留账号/项目计数与名称逐字不变，`demo`/`demo-pytest` 用例数仍为 238 / 12 | DB | 前后计数 + 名称比对 | P0（不可降级） |
| TC-v5.18-41 | 防复发机检：注入未标记临时对象 → 判红；清掉 → 判绿 | MECH | 机检输出 | P0 |
| TC-v5.18-42 | 库内无外键错误与孤儿行（删除顺序正确） | DB | 孤儿查询 = 0 | P0 |

---

## 3. NFR 逐条判据

| NFR | 判据 | 方式 |
|---|---|---|
| NFR-v5.18-01 零迁移 | 最新迁移仍 `V11_78`（或 `V11_79` 已配 down） | MECH |
| NFR-v5.18-02 契约同步 | `--check` PASS | MECH |
| NFR-v5.18-03 门禁只增不减 | `FAILS=0 UNJUDGED=0` | MECH |
| NFR-v5.18-04 前端例数 | ≥ 5.17 收口读数 | UT |
| NFR-v5.18-05 后端例数 | ≥ 5.17 收口读数 | UT |
| NFR-v5.18-06 清场可回滚 | 备份 hash 通过；按备份可还原（演练一次） | REAL |
| NFR-v5.18-07 真实优先 | 每条 FR 至少一条 HTTP/DB/UI/REAL 级 | 评审 |
| NFR-v5.18-08 零泄漏 | 6/6 PASS | MECH |

---

## 4. 不可降级项（一票否决）

| # | TC | 判据 | 为什么不可降级 |
|---|---|---|---|
| V-1 | TC-v5.18-04 | 导入取消零写入 | 取消还写库 = 脏数据静默进 |
| V-2 | TC-v5.18-05 | 不传 mapping 旧行为不变 | 新参数不能打挂老调用方 |
| V-3 | TC-v5.18-11 | 42 条资产回归一条不减 | 行数收敛的唯一合法判据是行为零回退 |
| V-4 | TC-v5.18-16 | 仅实测边数 = 接口 MEASURED | 前端自算必然漂移 |
| V-5 | TC-v5.18-21 | 同对端只出一条且取实测 | 双线出图会让链路误判 |
| V-6 | TC-v5.18-33 | 演练期间生产库不变 | 运维动作最不可接受的后果 |
| V-7 | TC-v5.18-37 | 清场前备份可校验 | 删账号不可撤销，没备份就是事故 |
| V-8 | TC-v5.18-40 | 保留对象零变化 | 清场的头号风险是连带删真实数据 |

---

## 5. 环境与命令

```bash
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest

cd backend && mvn -o -B test
cd ../frontend && npx vitest run
npx vue-tsc --noEmit

python3 tools/gen_api_contract.py --check
python3 tools/check_navigation.py && python3 tools/check_nav_kind.py
python3 tools/probe_oneshot_secret.py

# 行数
wc -l frontend/src/views/AssetsView.vue \
      frontend/src/views/global/GlobalAssetsView.vue \
      frontend/src/components/AssetWorkspace.vue

# 运维（需授权）
bash scripts/ops/backup.sh --apply
bash scripts/backup-drill.sh
bash scripts/ops/update-drill.sh

# 清场（先备份，再 dry-run，再授权执行）
bash scripts/ops/inventory-temp.sh
bash scripts/ops/purge-temp.sh
PURGE_TEMP_CONFIRM=YES bash scripts/ops/purge-temp.sh --apply
bash scripts/db-audit.sh

bash scripts/gates.sh
```

---

## 6. 退出判据

| # | 判据 |
|---|---|
| 1 | 42 条 TC 全部有结论，无「待补」 |
| 2 | 8 条不可降级项全部 PASS |
| 3 | 7 条 FR 全部落定 |
| 4 | `评估报告` §6.1 台账 13 行全部结清，**无一行「待补」** |
| 5 | 门禁 `FAILS=0 UNJUDGED=0`；`check_release_docs.py v5.18.0` PASS；六份表格机检 PASS |
| 6 | 后端/前端例数只增不减 |
| 7 | **清场后库内临时对象为 0，且保留对象零变化** |

---

## 7. 证据目录

```text
docs/evidence/v5.18.0/
├── import/    # FR-01：五步截图、映射样例、预览与结果一致性
├── refactor/  # FR-02：wc -l 前后、42 条回归输出
├── topology/  # FR-03/04：图例截图、边数断言、规划节点响应
├── probe/     # FR-05：三套包机检输出
├── ops/       # FR-06：备份 hash、10 表比对、四阶段输出
└── purge/     # FR-07：盘点清单、备份校验、前后计数、孤儿查询
```

---

## 8. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-16 | 首版：按 D-1 拆版新建；42 TC；8 条不可降级；清场 7 条 TC 定为 P0（含备份、零变化、防复发） | 大师 2026-11-16 拍板 + `robot_dev` 盘点 |
