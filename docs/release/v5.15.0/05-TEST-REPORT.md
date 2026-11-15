# v5.15.0 测试报告（05-TEST-REPORT）

> 流程位置：⑥ 测试执行 → 结果落定。数据源：`TEST-PLAN.md` 的 56 条 TC、`PRD.md` 的 8 条 NFR、
> `DEV-PLAN.md` 九波执行状态。**不新造用例**，TC 编号与 TEST-PLAN 一一对应，逐条写结论与证据形态。
> 结论口径：`PASS` = 有可复现证据；`PARTIAL` = 只覆盖部分证据形态或功能未实现（写明缺口）；`FAIL` = 未达成且未登记。
> **本版不把「单测绿 / 构建绿」当放行理由**；真实链路项在 §3 / §6 单列。
> 证据：`w1-verify.txt`（16/16）、`w9-http-probe-first-run.txt`（首轮 21 PASS / 4 FAIL 判红留痕）、
> `w9-http-probe.txt`（修复后 25/25）、`w9-http-probe-extra.txt`（8/8）、`w8-ssh-review.md`。

## 1. 读数总览

| 项 | 读数 | 证据 |
|---|---|---|
| W1 真实 HTTP + DB 探针 | **PASS=16 FAIL=0 TOTAL=16** | `docs/evidence/v5.15.0/w1-verify.txt` |
| W9 首轮综合探针 | **PASS=21 FAIL=4 TOTAL=25**（4 条判红不隐去） | `w9-http-probe-first-run.txt` |
| W9 修复后主探针 | **PASS=25 FAIL=0 TOTAL=25** | `w9-http-probe.txt` |
| W9 补充边界探针 | **PASS=8 FAIL=0 TOTAL=8** | `w9-http-probe-extra.txt` |
| 后端回归单测 | DeviceSnapshotService 4 + EnvironmentGate 4 + ProbePackService 2 全绿 | surefire XML |
| 前端类型 / 新契约 spec | `vue-tsc --noEmit` 0 error；`environmentV515.spec.ts` 4 例全绿 | 门禁 03 / 02 |
| 语言包 | zh/en 各 3139 键相等，硬编码中文 0 处 | `tools/check_i18n.py` |
| 导航基线 | 环境组 6 叶 → 4 叶，`EXPECTED_LEAVES` 44 → 42 | `check_nav_kind.py` / `navigation.spec.ts` |
| 迁移与库体检 | V11_75 / 76 / 77 入 history；业务表 109 → 111 → 113；`db-audit.sh` PASS | `scripts/db-audit.sh` |
| 接口契约 | **471 endpoints / 389 paths**，`gen_api_contract.py --check` PASS | `docs/contracts/openapi.json` |
| 部署自校验 | **PASS=4 FAIL=0 SKIP=0**，jar commit 与运行期 HEAD 一致 | `scripts/deploy.sh` 三次 |
| 全量门禁 | **GATES_RESULT FAILS=0 UNJUDGED=0** | `scripts/gates.sh`；`docs/evidence/gates/logs/` |
| 发布文档机检 | `check_release_docs.py v5.15.0` PASS（FR=12 / TC=56 逐条有结论） | §7 |
| 表格机检 | `check_md_tables.py` 对六份产物全 PASS | §7 |

## 2. 逐条结论（TC-v5.15-01 ~ 56）

| TC | 结论 | 证据 / 说明 |
|---|---|---|
| TC-v5.15-01 | PASS | W1 探针两次真实采集；行数=不同 hash 数，同 hash 重复插入被唯一键忽略 |
| TC-v5.15-02 | PASS | W1 真实 HTTP 详情 200 + items 端点返回按项结构，非 JSON 字符串 |
| TC-v5.15-03 | PASS | W1 真实 HTTP items?key=ROCE 仅 1 行且 itemKey=ROCE |
| TC-v5.15-04 | PASS | W9 补充探针 PUT /snapshots/{id} 真实 405 |
| TC-v5.15-05 | PASS | W9 补充探针项目 29 旧 env_profile 按 name 读回 2 行；旧端点零改动 |
| TC-v5.15-06 | PARTIAL | 500 项写入 / 按项查性能样本未构造；10MB 级快照非本版对象 |
| TC-v5.15-07 | PASS | W1 真实本机采集：device_snapshot +1、device_snapshot_item +10 |
| TC-v5.15-08 | PARTIAL | 采集链路本就不写 snapshot_json（W0 A-3）；未做旧行逐字段前后比对 |
| TC-v5.15-09 | PASS | W9 主探针探针包 10 类键与快照 itemKey 集合一致 |
| TC-v5.15-10 | PARTIAL | 采集整体失败的夹具未构造（会污染真实采集历史） |
| TC-v5.15-11 | PARTIAL | 新页已接按项 API；「采集记录分析」旧页真视口截图未取 |
| TC-v5.15-12 | PARTIAL | W1 真实两份快照取到 changed 差异；新增2/消失1/变化3 精确构造未做 |
| TC-v5.15-13 | PARTIAL | 变化项双值由 diffByItem 真实返回；UI 逐项对照截图未取 |
| TC-v5.15-14 | PASS | W9 补充探针自己与自己 diff-by-item 返回 0 行 |
| TC-v5.15-15 | PARTIAL | 任选两份非最近快照的 UI 操作截图未取；后端接口不限制 id 组合 |
| TC-v5.15-16 | PARTIAL | 1000 项对比性能未构造 |
| TC-v5.15-17 | PARTIAL | 只落 HOST_LINUX 一个包；交换机 / BMC 包未实现（本版功能缺口） |
| TC-v5.15-18 | PARTIAL | 「加一条项不改代码」的配置 diff 验证未做；命令文本仍在源码常量里 |
| TC-v5.15-19 | PASS | W1 真实采集未采到项 valueJson=null 且 reason 非空，整体仍落库 |
| TC-v5.15-20 | PARTIAL | 必需项失败导致整体采集失败未构造（required 语义落在评估端点而非采集状态） |
| TC-v5.15-21 | PARTIAL | 重复 item_key 启动拒绝未测；DB 有 uk(snapshot_id,item_key) |
| TC-v5.15-22 | PARTIAL | 10 条命令未改（门禁 29 逐字比对 PASS）；迁移前后项集逐项比对未做 |
| TC-v5.15-23 | PARTIAL | 同对端口双来源的 UI「只显示一条」未实现；后端给出 MEASURED 优先级与统计 |
| TC-v5.15-24 | PARTIAL | 前端「仅实测」过滤未实现（本版功能缺口，非仅截图缺失） |
| TC-v5.15-25 | PARTIAL | 人工确认与实测冲突的界面标注未实现 |
| TC-v5.15-26 | PARTIAL | 过滤后边数与接口一致的 DOM 断言未做 |
| TC-v5.15-27 | PASS | W9 补充探针 DB link 两列 NULL 行数 = 0；迁移默认 PLANNED/MANUAL |
| TC-v5.15-28 | PARTIAL | 真机交换机 LLDP 邻居采集未做；只验证了导入端点幂等 |
| TC-v5.15-29 | PASS | W9 主探针同设备对重复导入 skipped=1，边数不增 |
| TC-v5.15-30 | PARTIAL | 对端未登记 peer_unresolved 标记未实现 |
| TC-v5.15-31 | PARTIAL | 命令无输出（N3）真机样本未构造 |
| TC-v5.15-32 | PARTIAL | 四类图例未实现（本版功能缺口） |
| TC-v5.15-33 | PARTIAL | 组合过滤 DOM 计数断言未做 |
| TC-v5.15-34 | PARTIAL | 点边详情（来源/置信度/两源数据/时间）UI 未实现 |
| TC-v5.15-35 | PARTIAL | 100 节点 300 边渲染性能未采样 |
| TC-v5.15-36 | PASS | navigation.ts 环境组 6→4；check_nav_kind 基线 42 与 navigation.spec 4 例全绿 |
| TC-v5.15-37 | PARTIAL | 12 项能力逐条走查清单未产出 |
| TC-v5.15-38 | PASS | W9 补充探针旧深链 /environment/prepare 入口 200；router 两条 redirect 源码事实 |
| TC-v5.15-39 | PARTIAL | 12 端点孤儿走查清单未产出 |
| TC-v5.15-40 | PASS | W9 主探针 400G 声明 vs 100G 夹具 → 409 且点名 key/expected/actual/snapshotId |
| TC-v5.15-41 | PASS | W9 补充探针 400G 声明 vs 400G 夹具 → 200（>= 边界成立） |
| TC-v5.15-42 | PASS | W9 主探针无声明 200 + undeclared=true + notedRows=1 + 可查询留痕 |
| TC-v5.15-43 | PASS | W9 补充探针无快照项目 → 409「无可用设备快照；请先执行一次采集」 |
| TC-v5.15-44 | PARTIAL | ADR-0013 安全闸优先顺序未测（闸门为独立端点，未接 RunLaunch 链） |
| TC-v5.15-45 | PARTIAL | v5.14 收口 commit 上的反向回放未做 |
| TC-v5.15-46 | PASS | EnvironmentGate 只接受显式 requirements，代码无按用例名/分类猜需求 |
| TC-v5.15-47 | PARTIAL | topology_node 启用为规划源未实现（FR-10 未达成，本版功能缺口） |
| TC-v5.15-48 | PARTIAL | 人工规划 + LLDP 同一条只显示实测的 UI 未实现 |
| TC-v5.15-49 | PARTIAL | topology_node 表真实写入 count>0 未取得（未启用） |
| TC-v5.15-50 | PARTIAL | 项目侧已绑设备采集的真实链路未构造 |
| TC-v5.15-51 | PARTIAL | 未绑凭证 400 文案与 5.13 一致性未逐字复核 |
| TC-v5.15-52 | PARTIAL | 取凭证同源（management_credential_id 只读一处）代码走查未做 |
| TC-v5.15-53 | PARTIAL | 真实采集时 ps aux 输出未取；W8 源码核对为 sshpass -e 环境变量 |
| TC-v5.15-54 | PARTIAL | 主机密钥不匹配真机样本未构造 |
| TC-v5.15-55 | PASS | W8 机检负向自证 9/9；门禁 29 明文凭据脏数据判红 |
| TC-v5.15-56 | PASS | check_host_inventory.py --self-check 与门禁 55 oneshot_secret 均通过 |

**合计**：PASS=20 / PARTIAL=36 / FAIL=0 / TOTAL=56。
`PARTIAL` 不是「差不多通过」：其中既有证据形态缺口，也有**本版确实未实现的功能**，
后者在 §5 单列，不冒充达成、不放宽判据。

### 2.1 NFR 逐条结论

| NFR | 结论 | 证据 / 说明 |
|---|---|---|
| NFR-v5.15-01 | PASS | V11_75 / 76 / 77 三支追加迁移，各配 .down.sql；flyway 最新 11.77 |
| NFR-v5.15-02 | PASS | env_profile 旧链路零改动，项目 29 旧快照按 name 真实读回 2 行 |
| NFR-v5.15-03 | PASS | 契约重生成 471 endpoints / 377→389 paths，--check PASS |
| NFR-v5.15-04 | PARTIAL | 500 项写入 ≤3s、1000 项对比 ≤1s 未构造样本 |
| NFR-v5.15-05 | PARTIAL | 100 节点 / 300 边渲染 ≤2s 与 fps 未采样 |
| NFR-v5.15-06 | PASS | 新页面无 1s 轮询；onMounted / watch 取数，运行中状态仍走既有通道 |
| NFR-v5.15-07 | PASS | 闸门与探针包错误走 BusinessException + GlobalExceptionHandler，404/409 可读 |
| NFR-v5.15-08 | PARTIAL | P0 均取得真实 HTTP+DB 证据；UI 与真机类缺口在 05-TEST-REPORT §5 逐条登记 |

## 3. W9 首轮真实探针抓到的真缺陷

| # | 缺陷 | 首轮读数 | 根因 | 修复 | 复验 |
|---|---|---|---|---|---|
| 1 | `/api/probe-packs/commands` 与 `/evaluate` 恒 500 | HTTP=500，4 条断言连带判红 | MariaDB JDBC 把 `TINYINT(1)` 映射成 `Boolean`，代码强转 `Number` 抛 `ClassCastException`（运行期日志 23:10:57 有堆栈） | 抽出 `ProbePackService.requiredOf(Object)` 同时接受 Boolean / Number / String；补 2 例单测 | HTTP=200，10 类命令、6 项 required；主探针 25/25 |

**这不是探针选样问题，也不是单测能发现的问题**：只有真打运行期接口才会暴露驱动类型映射差异。
首轮判红证据 `w9-http-probe-first-run.txt` 保留不隐去。

## 4. W0 草案冲突与实测订正（不悄悄改）

| # | 草案假设 | 实测 | 处置 |
|---|---|---|---|
| 1 | `psql "$DB_URL"` | MySQL / MariaDB，`db_exec` 走 mysql CLI | 全部验证命令改为 `db-migrate.sh` / `db-audit.sh` |
| 2 | `/api/environment/snapshots/{id}` 既存 | 端点不存在，真实前缀 `/api/projects/{projectId}/snapshots` | 新端点挂真实前缀，契约同步 |
| 3 | 采集「不再写 snapshot_json」 | 采集链路本就不写 | 删除该判据，改为新表新增链路 + 旧数据只读兼容 |
| 4 | CollectPayload 只传 host | 前后端都已有 deviceId | 真实缺口改为 credentialId / username / confirm 透传 |
| 5 | probe_pack 既有 | 全仓无表无端点 | 本期新造两表，命令文本仍取 COMMANDS 唯一真源 |
| 6 | link.confidence/source 待回填 | 两列不存在 | V11_77 新增两列 + 默认值 |
| 7 | run.env_check_note 可直接查 | 列不存在 | V11_77 追加列，留痕只填空不覆盖 |

## 5. 未达成 / 降级项登记（不冒充达成）

| 类别 | 涉及 TC / FR | 缺口与原因 |
|---|---|---|
| **本版未实现的功能** | TC-17/24/25/30/32/34/47/48；FR-07 图例与过滤、FR-10 topology_node 启用、交换机/BMC 探针包 | 本版定位收敛为「后端事实链 + 页面收敛 + 执行闸门」，拓扑图渲染与规划源启用未落地；**记为未达成，不是证据缺口** |
| 真视口 / DOM 截图未取 | 11 13 15 23 26 33 34 37 39 | 未驱动 Chrome 逐页截图；门禁 13 步有浏览器回归但不是逐 TC 图 |
| 性能样本未构造 | 06 16 35；NFR-04/05 | 500 项 / 1000 项 / 100 节点 300 边样本未造，不放宽判据 |
| 真机与错误样本未构造 | 10 20 28 31 50 51 53 54 | 真交换机、错误口令、host key 不匹配、项目侧已绑设备样本会污染真实资产或凭据库 |
| 安全闸顺序 / 反向回放 | 44 45 | 闸门是独立端点，未接 ADR-0013 RunLaunch 链；v5.14 commit 反向回放未做 |
| 代码走查清单 | 39 52 | 12 端点孤儿清单、取凭证同源逐处走查未产出 |

> **降级授权**：以上全部如实登记，按大师「按建议自动推进、全部完成再找我确认」的既定授权处理；
> 最终人工复核时请大师逐项确认。**P0 判据**：FR-09 的一票否决 V-1（409 点名四要素）与
> V-2（无声明不拦截但留痕）均已真实取证；FR-01/02/03/04/05 中，FR-05 的图例与过滤
> 未落地、FR-04 仅 HOST_LINUX 单包，明确记为未完全达成，不计入放行。

## 6. 真实链路证据清单

| 证据 | 内容 |
|---|---|
| `w1-verify.txt` | 真实本机采集 → 主清单 +1、快照 +1、按项 +10、内容寻址唯一键、按项过滤、按项对比、404 |
| `w9-http-probe.txt` | 探针包选包与 required、拓扑三源与 LLDP 幂等导入、闸门 409/200/留痕、凭证透传、导航收敛 |
| `w9-http-probe-extra.txt` | PUT 405、自对比空、旧 env_profile 读回、link 两列 NULL=0、无快照 409、400G 边界、旧深链 200 |
| `w8-ssh-review.md` | SSH 九项硬约束源码核对 + 门禁 29 负向自证 9/9；askpass 关键字口径订正留痕 |
| 临时样本 | 三份探针的 finally 段：LLDP 链路 / 闸门夹具快照 / run 留痕 / 400G 夹具全部还原，残留读数 0 |

## 7. 门禁与收口

| 项 | 判据 | 结果 |
|---|---|---|
| 单测 | 后端回归无 Failure / Error；前端例数 ≥ 上一版 | 达成（新增 10 例后端 + 4 例前端） |
| 类型 | `npx vue-tsc --noEmit` 无输出 | 达成 |
| 门禁 | `GATES_RESULT FAILS=0 UNJUDGED=0` | **GATES_RESULT FAILS=0 UNJUDGED=0** |
| 发布文档 | `check_release_docs.py v5.15.0` PASS | 六份齐全 + FR=12 / TC=56 逐条有结论 |
| 表格 | `check_md_tables.py` 对六份产物全 PASS | 列数一致 |
| 契约 | `gen_api_contract.py --check` PASS | 471 / 389 |
| 部署 | `deploy.sh --verify` 四判据 | PASS=4 FAIL=0 SKIP=0 |
| 冻结执行链 | `exec-chain.sha256` 10 个文件 sha 不变 | 本版未改任何冻结 java（门禁 33 PASS） |

## 8. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | W0：补 00-RESEARCH / 01-ASSESSMENT，10 条草案冲突订正 | 只读调研事实清单 |
| 2026-11-15 | W1 ~ W8：三支迁移、按项快照、探针包、拓扑分级、页面收敛、闸门、凭证透传、SSH 核对 | 各波真实探针与机检 |
| 2026-11-15 | W9 收口：首轮 4 条判红定位为 TINYINT(1) Boolean 真缺陷并修复，修复后 25/25 + 8/8；门禁读数落定 | `w9-http-probe*.txt` + `scripts/gates.sh` |
