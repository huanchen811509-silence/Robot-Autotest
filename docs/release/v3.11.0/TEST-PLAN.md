# v3.11.0 测试计划（RA-TP-311）

> 判据来自 `PRD.md`（RA-PRD-311）。**无测试计划不许进入开发后期**；每条 TC 写明证据形态与判定命令。
> 放行条件：§2 全部 PASS + §4 横向回归全绿 + 门禁末行 `GATES_RESULT FAILS=0`。

## 1. 取证策略（三层，不互相替代）

| 判据类型 | 取证手段 | 理由 |
|---|---|---|
| 主机清单 10 类 / 命令白名单 | 门禁第 29 步 `check_host_inventory.py` + 契约脚本真 HTTP + 真库 | 静态契约防漂移、证据 JSON 防「只有夹具没有真机」 |
| 采集/解析逻辑分支 | `HostInventoryCollectorTest` 离线夹具 | 真机只有一种形态（无 GPU），边界必须靠夹具钉 |
| 页面接真实接口 / 只读副作用 | CDP 探针录 `Network.requestWillBeSent` | 源码静态扫描判不出运行时请求 |
| 页面接线 / 纯函数 | Vitest 源码级断言 + 桩 | 探针点不到 happy-dom 里的虚拟列表；源码断言防接线退化 |
| 迁移可逆 | 集成库真实 down→up 演练 | `DownScriptGuardTest` 只验配对与语法，不真执行 down |

**缺口必须写明**：哪条判据探针测不出、由什么补，写在 `docs/evidence/v3.11.0/README.md`。

## 2. 用例清单

| TC | 判据 | 内容 | 证据形态 | 判定命令 |
|---|---|---|---|---|
| TC-v3.11-01 | FR-v3.11-01① | 命令白名单正好 10 类、顺序与 `CATEGORIES` 一致、每条只读 | `EW311-01/commands.json` + `HOST_INVENTORY PASS` | `python3 tools/check_host_inventory.py` |
| TC-v3.11-02 | FR-v3.11-01①② | 真实采集 10 类齐全，每项带 `command` 与 `collectedAt` | `EW311-01/host-inventory.json` + `contract.log` 判据③ | `bash scripts/host-inventory-contract.sh` |
| TC-v3.11-03 | FR-v3.11-01③ | 未采到项必有原因、无占位值、status 由有值项数推出 | 同上判据④⑤；本机 GPU 未采到 → `PARTIAL 9/10` | 同上 |
| TC-v3.11-04 | FR-v3.11-01④ | 命令无写操作、可执行名全在只读白名单；接口自述与源码逐字一致 | 检查器判据 A/B + 20 组负向自证 | `python3 tools/check_host_inventory.py --self-check` |
| TC-v3.11-05 | FR-v3.11-01⑤ | 采集只能显式 POST；`GET /collect` 回 405；非项目成员 403 | `contract.log` 判据⑦⑨ | 同上 |
| TC-v3.11-06 | FR-v3.11-01（数据模型） | V11_26 down→up：整表消失再回来、13 列定义一致、行数不变 | `EW311-01/down-up.log` | `bash scripts/migration-down-up-drill.sh V11_26__v3_11_host_inventory` |
| TC-v3.11-07 | FR-v3.11-01（解析） | 10 类解析、无 GPU/无 RoCE/全失败三态、速率换算、命令表只读 | `HostInventoryCollectorTest` 10 条 | `mvn -o -B test -Dtest=HostInventoryCollectorTest` |
| TC-v3.11-08 | FR-v3.11-02① | 5 页各自命中期望的 GET 数据接口（不是「任意 /api/」） | `EW311-02/env-pages.json` 的 `requests` | `node scripts/env-pages-probe.mjs` |
| TC-v3.11-09 | FR-v3.11-02② | 同一环境两份真实快照可创建；列表查到 2 份；diff 出变化 | `EW311-02/fixtures.json` + `env-pages.json` 的 `diffRequests` | `bash scripts/env-fixtures.sh` + 探针 |
| TC-v3.11-10 | FR-v3.11-02②（数据模型） | V11_27 索引 down→up：旧唯一键恢复再消失、新索引消失再回来、行数不变 | `EW311-02/index-drill.log` | `bash scripts/index-migration-drill.sh` |
| TC-v3.11-11 | FR-v3.11-02③ | 8 页统一 `*-empty` / `*-failed` 双态与必现控件 | `env-pages.json` / `maintenance-pages.json` + 两份 spec | `npx vitest run src/views/environment/pages.spec.ts src/views/maintenance/pages.spec.ts` |
| TC-v3.11-12 | FR-v3.11-02④ / FR-v3.11-03④ | 只读 6 页加载期 0 个写请求；采集/拓扑两页加载期也 0 写 | 探针逐页 `writes` 数组 | `node scripts/env-pages-probe.mjs` |
| TC-v3.11-13 | FR-v3.11-02⑤ | 「立即采集」「按当前资产建快照」点一次只进确认态、0 写请求 | 探针 `confirming` + 交互期请求 | 同上 |
| TC-v3.11-14 | FR-v3.11-03① | 恢复记录打到真实 `GET /audit`，视图模型不取 `detail` | `maintenance-pages.json` 的 `requests` + spec 断言 | 探针 + `pages.spec.ts` |
| TC-v3.11-15 | FR-v3.11-03② | 只读找回检索只发 `GET /search`，0 写请求、不产生新 run | `maintenance-pages.json` 的 `searchRequests` | 探针 |
| TC-v3.11-16 | FR-v3.11-03③ | 恢复记录不渲染命令正文、不含 `.detail` 属性访问 | `pages.spec.ts` | `npx vitest run src/views/maintenance/pages.spec.ts` |
| TC-v3.11-17 | 横向回归 | 前端全量、后端全量、编辑器包、`vue-tsc --noEmit`、`vite build`、i18n 键集 | 命令输出 | 见 §4 |
| TC-v3.11-18 | 门禁 | 29 步全 PASS；`--selfcheck` 含「删 28/29 步必变红」 | `docs/evidence/gates/` 日志 | `bash scripts/gates.sh` + `bash scripts/gates.sh --selfcheck` |

## 3. 已知取证缺口（不掩盖）

| 判据 | 探针能不能判 | 由什么补 | 说明 |
|---|---|---|---|
| 10 类采集命令在**异构真机**上的输出 | 不能 | `HostInventoryCollectorTest` 离线夹具 | 本机只有 Ubuntu 24.04 / 无 GPU / 有 2 个 RoCE 口一种形态 |
| 远程主机采集 | **不能**（未实现） | 代码返回带原因的「未采集到」 | L-311-01：本版只支持本机，不伪造本机数据顶替远程 |
| 恢复记录非空渲染 | 不能 | `isRecoveryAction` 单测 + 真审计接口打到 | 当前库里 `case.batch.undo.*` 为 0 条，显示 0 条是真值（ADR-311-12） |
| `NSelect`/虚拟列表下的选项交互 | 不能 | 原生 `<select>` + 源码级 spec | 沿用 v3.10.0 结论；本版未再复验 |
| 只读页被第三方组件间接写库 | 只能判 HTTP 方法 | 静态 spec 禁写函数名 | 探针录的是浏览器请求，后端内部副作用不在视野内；两处结合 |

## 4. 横向回归与放行

```bash
# 数据与取证
bash scripts/host-inventory-contract.sh
bash scripts/env-fixtures.sh
bash scripts/migration-down-up-drill.sh V11_26__v3_11_host_inventory
bash scripts/index-migration-drill.sh
node scripts/env-pages-probe.mjs
# 静态与单测
python3 tools/check_i18n.py
cd frontend && npx vue-tsc --noEmit && npx vitest run && npx vite build
cd ../packages/editor && npx vitest run
cd ../../backend && mvn -o -B test
# 门禁
cd .. && bash scripts/gates.sh --selfcheck && bash scripts/gates.sh
```

放行判据：`HOST_INVENTORY PASS`、`ENV_PAGES PASS FAILS=0`、两份 `RESULT PASS fails=0`、前端/后端/编辑器全绿、`vue-tsc` 无输出、i18n 键集相等、门禁 `GATES_RESULT FAILS=0`。

## 5. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-09 | 初版，18 条 TC |
| v1.1 | 2026-11-09 | 新增 TC-v3.11-10（V11_27 索引演练）与 TC-v3.11-13（二次确认）：前者由真实 HTTP 实测逼出，后者把「显式触发」从源码约定升为可判定判据 |
