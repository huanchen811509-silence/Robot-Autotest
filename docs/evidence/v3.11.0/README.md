# v3.11.0 证据索引

> 一个工作包一节：写了什么证据、每条判据由谁判、**哪些判不了并写了由什么补**。
> 判据来自 `docs/release/v3.11.0/TEST-PLAN.md`（RA-TP-311，18 条 TC）。
> 结论口径：`PASS` 才算通过；`PARTIAL` / `NOT-RUN` 一律不得读作通过。

## EW311-01 · 资产主机清单（`EW311-01/`）

| 文件 | 内容 | 判了什么 |
|---|---|---|
| `commands.json` | `GET /api/asset-host-inventory/commands` 的真实响应（10 类） | FR-v3.11-01④：命令白名单公开、分类顺序与源码逐字一致 |
| `commands.raw.json` | 同一响应的原始信封（含 `code` / `success`） | 证明不是手写文件，是接口返回值 |
| `host-inventory.json` | 真实 `POST /collect` 采到的 `spec_json` 原样导出（7847 字节） | 判据①②③：10 类齐全、每项带 `command`/`collectedAt`、GPU 未采到带原因 |
| `contract.log` | `scripts/host-inventory-contract.sh` 全量输出，`RESULT PASS fails=0` | 17 条 HTTP/真库判据：显式 POST、列表不拖 JSON、摘要一致、`spec_hash` 对得上、跨项目 403、GET /collect 405 |
| `down-up.log` | `V11_26` 在 `robot_it` 上的整表 down→up 演练 | `MODE=table` 分支真的生效；13 列定义一致、行数 0→0 |
| `selfcheck.log` | `check_host_inventory.py --self-check` 20 组正负样本 | 门禁第 29 步判据可判红，不是恒定放绿 |

本机实测：`status=PARTIAL`、9/10 项有值，缺的 GPU 原因是 `sh: 1: nvidia-smi: not found`。
**这个「缺一项」正是判据③的实证**，不需要制造异常。

**判不了 / 由什么补**：

| 缺口 | 补法 |
|---|---|
| 异构真机（有 GPU / 无 RoCE / 命令全失败） | `HostInventoryCollectorTest` 10 条离线夹具，覆盖六类解析、三态、速率换算 |
| 远程主机采集 | **未实现**（L-311-01）：`LocalHostCommandRunner` 对非本机返回带原因的「未采集到」，不拿本机数据顶替 |
| 页面上的采集交互 | `EW311-02/env-pages.json`：真浏览器录到「点一次只进确认态、0 写请求」 |

首跑抓到并修掉的两个真问题（写进 `DEV-PLAN.md` 偏差记录）：
1. `freeze` 之外的同类问题：列表把 `specJson` 返成空串 → 前端分不清「清单是空的」与「这一栏没查」，改为 `null`；
2. `@GetMapping("/{id}")` 把 `GET /collect` 吃掉，回「参数类型不正确：id」400 → 改 `{id:\d+}`，现回 405。

## EW311-02 · 环境与拓扑 5 页（`EW311-02/`）

| 文件 | 内容 | 判了什么 |
|---|---|---|
| `env-pages.json` | `scripts/env-pages-probe.mjs` 对 5 页的 DOM 读数 + 逐条 `/api/` 请求（方法 + URL） | 判据①：每页期望的数据 GET 全部命中；判据④：加载期 0 个写请求；判据⑤：采集/建快照点一次只进确认态 |
| `probe.log` | 8 页探针完整输出，`ENV_PAGES PASS FAILS=0`（34 条判据） | 同上，人可读版 |
| `fixtures.log` / `fixtures.json` | 两次真实采集 + 两份真实资产快照 + 列表 + diff 的输出与 id | 判据②：同一环境 2 份快照、`diff` 出 `capturedAt` 一项变化 |
| `index-drill.log` | `V11_27` 索引 down→up 演练，7 项全 PASS | 判据②的数据模型前置条件：唯一键恢复/消失、新索引消失/回来、行数不变 |

**同一环境两份快照首跑 500**，根因是 V1 基线的 `uk_env_profile_project_name (project_id, name)`：
这让 `EnvProfileService.list(name)` 最多回 1 行，`diff` 判据在数据层就不可能成立。补 `V11_27` 换普通联合索引后复跑，7 条夹具判据全 PASS。

**判不了 / 由什么补**：

| 缺口 | 补法 |
|---|---|
| 只读页被后端间接触发的副作用 | 探针只录浏览器请求方法；源码级 `pages.spec.ts` 禁写函数名出现，两者结合 |
| `NSelect` 虚拟列表下的选项交互 | 页面用原生 `<select>`；源码 spec 钉住必现控件；探针钉住真实 DOM 可见 |
| 快照 diff 的 HTTP 方法是 POST | 既有契约如此且**纯计算不写库**；探针把它归到「交互期」，只读判据按「加载期」判，不混为一谈 |
| 空态/错误态控件「可见」 | 条件渲染下本就不该可见；存在性由 `pages.spec.ts` 逐页断言，探针只判必现控件 |

## EW311-03 · 维护与恢复 3 页（`EW311-03/`）

| 文件 | 内容 | 判了什么 |
|---|---|---|
| `maintenance-pages.json` | 3 页的 DOM 读数 + 请求清单，含 `searchRequests` | FR-v3.11-03①：打到 `GET /api/audit`；②：检索只发 `GET /api/search` 且 0 写；④：3 页加载期 0 写 |

**恢复记录当前 0 条是真实读数**（库里 `case.batch.undo.*` 与 `run.retry_of_run_id` 均为 0），页面如实显示「0 条」，不造行——判据要的是「来自真实数据」，不是「必须有行」。

**判不了 / 由什么补**：

| 缺口 | 补法 |
|---|---|
| 恢复记录非空渲染 | `isRecoveryAction` 单测覆盖动作过滤；真接口命中已由探针证明；有真实撤销动作后页面自然渲染 |
| 命令正文是否泄漏 | 探针读不到「没渲染的字段」；`pages.spec.ts` 禁 `.detail` 属性访问 + 禁 `<th>恢复命令</th>` |
| 是否产生新 run | 探针断言 0 个写请求，并核对只命中 `/api/search`；`api/maintenance.ts` 不引入任何执行入口 |

## 横向回归（本版实测）

| 层 | 读数 | 命令 |
|---|---|---|
| 后端全量单测 | 1272 条 / 0 失败 / 0 跳过 | `bash scripts/gates.sh` 第 5 步 |
| 前端全量单测 | 80 文件 / 574 条全绿 | `cd frontend && npx vitest run` |
| 编辑器包单测 | 24 文件 / 219 条全绿 | `cd packages/editor && npx vitest run` |
| 类型检查 | `vue-tsc --noEmit` 无输出 | `cd frontend && npx vue-tsc --noEmit` |
| i18n | zh-CN / en-US 各 1680 键，键集相等、无硬编码中文 | `python3 tools/check_i18n.py` |
| 构建 | `vite build` 成功 | `cd frontend && npx vite build` |
| WebSocket E2E 竞态复验 | 修复后连跑 3 次 **4/4 全绿**、`BUILD SUCCESS`（生产代码零改动） | `mvn -o -B test -Dtest=RunLogWebSocketE2ETest` ×3 → `EW311-02/ws-flaky-verify.log` |
| 门禁 | **29 步全 PASS / `GATES_RESULT FAILS=0`**（含第 29 步 host_inventory；`--selfcheck` 亦 `FAILS=0`） | `bash scripts/gates.sh` → `EW311-02/final-gates.log`（29 步逐项读数已入库） |

## 遗留

| 编号 | 内容 | 处置 |
|---|---|---|
| L-311-01 | 远程主机采集未接入（只支持本机） | 排 v3.12 候选；当前返回带原因的「未采集到」，不伪造数据 |
| L-311-02 | 恢复/重试记录在当前库为 0 条 | 非缺陷；有真实撤销或重试动作后自然有行，页面已接真接口 |
| L-311-03 | 拓扑可视化画布未做 | 判据未涉及，排 v3.12 候选 |
| L-311-04 | 后端 `SchemaIT.java` 的 `EXPECTED_TABLES=75` 与 5 张 `APPEND_ONLY`，和 `scripts/db-audit.sh` 的 88 表 / 11 张豁免口径不一致 | 历史漂移（v3.3/v3.4 起），`@Tag("it")` 默认被 surefire 跳过故一直未暴露；本轮门禁第 12 步按 db-audit 口径收口，SchemaIT 对齐排 v3.12，不在本版改 IT 判据语义 |
| L-39-03 | `-u user:pass` 形式的命令脱敏 | **必须在 v3.12 命令复跑前补**，沿用 v3.9.0 遗留 |
| L-310-01~04 | 见 `docs/evidence/v3.10.0/README.md` | 沿用，不在本版范围 |
