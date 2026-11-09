# v3.11.0 测试报告（RA-TR-311）

| 项 | 内容 |
|---|---|
| 文档 | `05-TEST-REPORT.md` ｜ v3.11.0 ｜ 实测回写 |
| 上游 | `TEST-PLAN.md`（18 条 TC）／`PRD.md`（3 条 FR）／`DEV-PLAN.md`（3 个工作包） |
| 结论口径 | `PASS` 才算通过；`PARTIAL` 与 `BLOCKED` 均不得读作通过 |
| 证据根 | `docs/evidence/v3.11.0/`（索引见同目录 `README.md`） |
| 门禁批次 | B5 最终 `bash scripts/gates.sh` **29 步全 PASS / `GATES_RESULT FAILS=0`**（第 29 步为 v3.11.0 新增）；`--selfcheck` → `SELFCHECK_RESULT FAILS=0`，含删 28/29 步必变红实证 |

## 1. 逐条结果

| TC | 用例 | 状态 | 结论 / 实测读数 | 证据 |
|---|---|---|---|---|
| TC-v3.11-01 | 10 类命令齐全且只读 | PASS | `HOST_INVENTORY PASS`：10 类顺序 `OS,KERNEL,CPU,MEMORY,DISK,GPU,NIC,IP,ROCE,ROUTE`；写操作 0、非白名单可执行名 0；接口自述与源码逐字一致 | `EW311-01/commands.json` + `selfcheck.log` |
| TC-v3.11-02 | 每项带命令与采集时间 | PASS | 契约统计 `n=10 with_cmd=10 with_time=10`；`spec_json` 7847 字节原样导出 | `EW311-01/contract.log`、`host-inventory.json` |
| TC-v3.11-03 | 未采到必有原因、无占位值、status 自洽 | PASS | 本机 GPU 未采到，原因 `sh: 1: nvidia-smi: not found`；`placeholder=0`；`PARTIAL` 与 9/10 项有值一致 | 同上判据③④⑤ |
| TC-v3.11-04 | 命令白名单可判红 | PASS | 20 组正负样本全部符合预期：少一类 / 顺序错 / 无原因 / 占位值 / status 不符 / 命令漂移 / 缺时间 / 语义矛盾 / 写操作 / 非白名单 / 接口漂移 全 RED，干净数据 GREEN | `EW311-01/selfcheck.log`（`SELFCHECK_RESULT FAILS=0`） |
| TC-v3.11-05 | 采集显式触发、跨项目 403 | PASS | `POST /collect` 200 且拿到 id；`GET /collect` **405**；非成员账号读本项目/不存在项目均 **403** | `EW311-01/contract.log` 判据②⑦⑨ |
| TC-v3.11-06 | V11_26 down→up 演练 | PASS | `MODE=table` 生效：13 列消失再回来、定义一致、行数 0→0；`RESULT PASS fails=0` | `EW311-01/down-up.log` |
| TC-v3.11-07 | 解析逻辑离线夹具 | PASS | `HostInventoryCollectorTest` **10 条**：10 类齐全、六类/网络三类解析、无 GPU→PARTIAL、无 RoCE 记原因、全失败 FAILED、残缺返回 null、速率换算、命令表只读、超时常量 | `mvn -o -B test -Dtest=HostInventoryCollectorTest`（`EXIT=0`） |
| TC-v3.11-08 | 5 页各打到自己的真实数据接口 | PASS | 探针逐页核对期望 GET：总览 `/snapshots/profiles`+`/devices`+`/asset-host-inventory`、采集 `/commands`+列表、拓扑/准备 `/profiles`、分析 `/asset-host-inventory?limit=100`，**全部命中** | `EW311-02/env-pages.json` |
| TC-v3.11-09 | 两快照创建与对比 | PASS | 真实 HTTP 同名建 2 份快照（内容取自真实 `/devices`+`/links`）、列表查到 2 份、diff 出 `capturedAt` 1 项；浏览器点「对比」走 `POST /snapshots/diff` 且差异表可见 | `EW311-02/fixtures.log`、`env-pages.json` 的 `diffRequests` |
| TC-v3.11-10 | V11_27 索引 down→up 演练 | PASS | 7 项全 PASS：旧唯一键 `0→1→0`、新联合索引 `1→0→1`、行数 0→0；`RESULT PASS fails=0` | `EW311-02/index-drill.log` |
| TC-v3.11-11 | 8 页空态 / 错误态 / 必现控件 | PASS | 两份 `pages.spec.ts` 共 **16 条全绿**（含 `parseSpec` 容错、`isRecoveryAction` 过滤）；探针必现控件 `4/4,3/3,3/3,2/2,2/2,1/1,1/1,3/3` | vitest 输出 + `EW311-03/maintenance-pages.json` |
| TC-v3.11-12 | 只读 6 页 + 两交互页加载期 0 写 | PASS | `ENV_PAGES PASS FAILS=0`（34 条判据）：8 页加载期 `writes=[]` | `EW311-02/probe.log` |
| TC-v3.11-13 | 采集/建快照须二次确认 | PASS | 点一次 `confirming=true`、写请求 0；建快照点一次写请求 0 | 同上 |
| TC-v3.11-14 | 恢复记录来自真实审计 | PASS | 页面命中 `GET /api/audit?projectId=29&size=50`；视图模型只搬 4 字段、`.detail` 属性访问被 spec 禁掉 | `EW311-03/maintenance-pages.json` + `pages.spec.ts` |
| TC-v3.11-15 | 只读找回只发 GET、不产生新 run | PASS | 改关键字后命中 `GET /api/search?…types=run`，`写请求=0` | 同上 `searchRequests` |
| TC-v3.11-16 | 不展示命令正文 | PASS | spec 断言 `不含恢复命令正文`、无 `<th>恢复命令</th>`、无 `.detail` 访问 | `npx vitest run src/views/maintenance/pages.spec.ts` |
| TC-v3.11-17 | 横向回归 | PASS | 后端 **1272 / 0 失败 / 0 跳过**；前端 **80 文件 / 574 条**；编辑器 **24 文件 / 219 条**；`vue-tsc` 无输出；i18n **1680/1680 键**；`vite build` 成功 | 见 §3 |
| TC-v3.11-18 | 门禁 29 步 + 自校验 | PASS | B5 最终 `bash scripts/gates.sh` → **29 步全 PASS / `GATES_RESULT FAILS=0`**；`--selfcheck` → `SELFCHECK_RESULT FAILS=0`（含「删第 28/29 步必变红」） | `EW311-02/final-gates.log`、`final-selfcheck.log`（门禁原始日志在被 `.gitignore` 忽略的 `gates/logs/`，汇总已归档入库） |

## 2. 汇总

| 状态 | 条数 | 说明 |
|---|---:|---|
| PASS | 18 | 全部 TC 均有机检、真 HTTP、真浏览器或真实演练证据 |
| PARTIAL | 0 | 无 |
| BLOCKED | 0 | 本版不需要真实 DUT；远程采集属「未实现」而非「被阻塞」，已单列 L-311-01 |
| FAIL | 0 | 无 |

## 3. 横向回归读数

| 层 | 读数 | 命令 |
|---|---|---|
| 后端全量 | 1272 条 / 0 失败 / 0 跳过 | `bash scripts/gates.sh` 第 5 步（汇总见 `EW311-02/final-gates.log`） |
| 前端全量 | 80 文件 / 574 条全绿 | `cd frontend && npx vitest run` |
| 编辑器包 | 24 文件 / 219 条全绿 | `cd packages/editor && npx vitest run` |
| 类型检查 | `vue-tsc --noEmit` 无输出 | `cd frontend && npx vue-tsc --noEmit` |
| i18n | zh-CN / en-US 各 1680 键、键集相等、无硬编码中文 | `python3 tools/check_i18n.py` |
| 构建 | `vite build` 成功 | `cd frontend && npx vite build` |
| 迁移演练 | V11_26（建表）+ V11_27（索引）各 `RESULT PASS fails=0` | 两支 drill 脚本 |

## 4. 实测推翻的假设（回写）

| 原假设 | 实测 | 处置 |
|---|---|---|
| 既有快照接口可直接给 5 页复用 | 没有任何接口能列出环境名；`list(name)` 对空 name 抛 400 | 补 `GET /snapshots/profiles`（ADR-311-08） |
| 同一环境可存多份快照 | V1 唯一键让同名第二份 `POST /snapshots` 返回 500，`list` 最多 1 行，diff 判据不可能成立 | 追加 `V11_27` 换普通联合索引 + down + 真实演练（ADR-311-09） |
| 列表「不返回 specJson」= 响应里没有这个键 | Jackson 把 `null` 也序列化；按键名判出现过假红，且空串会让前端误读成「清单是空的」 | 仓储改回 `null`，契约改判**值** |
| `GET /collect` 应回 405 | `@GetMapping("/{id}")` 先吃掉字面路径，回「参数类型不正确：id」400 | 详情路由改 `{id:\d+}`，现回 405，并把针写进第 29 步静态契约 |
| 点搜索就一定发 GET | `http.ts` 对 GET 短时缓存，同参复搜不发请求 → 探针假红 | 检索前改关键字，判据改为「只发 GET /search 且 0 写」 |

## 5. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-09 | B5 收口实测回写：18 条 TC 全 PASS；补 V11_27、路由正则、列表 null、探针缓存四处由真实链路逼出的修正 |
| v1.1 | 2026-11-09 | 最终门禁回写：首跑 `FAILS=2`（第 6 步 lint：8 页 `color-mix` 硬编码色值 10 处 + 7 处非白名单 px；第 12 步 db_audit：新表让表数 87→88 且缺审计四列）；改用 `var(--fail)`/`var(--warn)` 语义 token、px 收敛到已有档位、`device_host_spec` 按 append-only 登记并把表数基线更新为 88 后，复跑 **29 步全 PASS / `FAILS=0`**。后端跳过数按最终门禁日志由 4 校正为 0；新增遗留 `L-311-04`（SchemaIT 口径漂移，排 v3.12） |
| v1.2 | 2026-11-09 | 最终门禁复跑再回写：第二轮 `FAILS=2`（第 5 步 `RunLogWebSocketE2ETest.incrementalPushArrives` 偶发「单帧 120 行」；第 19 步基线机检因后端日志缺 `BUILD SUCCESS` 级联判红）。根因是**测试竞态**：客户端 WS 连上不等于服务端 `subscribe` 完成，120 行被 `reconnect(200)` 当补发一帧推回；`LogBatcher.MAX_PER_FLUSH=50` 的增量切片本身未变，生产代码零改动。测试改为先发探针等订阅就绪、清空已收帧再专测增量，连跑 3 次 **4/4 全绿**（`EW311-02/ws-flaky-verify.log`），判据未放宽 |
