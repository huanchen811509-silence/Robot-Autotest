# v5.15.0 开发计划（DEV-PLAN）：环境与拓扑重写 —— 快照是对象，拓扑有置信度

> 流程位置：④ 开发计划。**无开发计划不许开工编码**。
> 上游：`PRD.md`（12 FR + 8 NFR）、`05-环境拓扑与运维评估.md` §一~§二、白泽调研报告 §4/§5。
> 本文件给 WBS 编号、改动面与**可复制的验收命令**；不给「大约几天」，只给依赖关系与可独立验收的边界。
> 授权范围：本版**允许借鉴白泽源码**（`new-baize/backend/.../service/Topology.java`、`TopologyGraph.java`、`SnapshotCompare.java`、`DeviceSnapshotIndex.java`），但必须记录「抄了哪几行、改了什么」。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | 快照对象化 | W65-01 ~ W65-03 | 是 | 必须最先（后面三波都读快照） |
| W2 | 按项对比 | W65-04 ~ W65-05 | 是 | 依赖 W1 |
| W3 | 探针包 | W65-06 ~ W65-08 | 是 | 依赖 W1（采集写快照） |
| W4 | 拓扑分级 | W65-09 ~ W65-13 | 是 | 依赖 W1（LLDP 来自快照） |
| W5 | 页面收敛 | W65-14 | 是 | 依赖 W2/W3/W4（新页要能承载能力） |
| W6 | 执行前闸门 | W65-15 ~ W65-17 | 是 | 依赖 W1（读最新快照）；与 W4 并行 |
| W7 | 项目侧采集 | W65-18 | 是 | 依赖 5.13 的凭证逻辑 |
| W8 | SSH 安全核对 | W65-19 | 是 | 独立（只核对不改编排） |
| W9 | 收口 | W65-20 ~ W65-21 | 否（最终波） | 依赖 W1 ~ W8 全部出口判据 |

**并行纪律**：W1 硬前置；W2/W3/W6 可并行；W4 必须在 W1/W3 之后（LLDP 需要快照项）；
W5 是最后一个功能波（页面要能承载前面所有能力）；W8 随时可插。

**本版迁移 3 支**（`V11_75` 快照两表、`V11_76` 探针包、`V11_77` link 置信度）。
**旧数据只读兼容**：`env_profile.snapshot_json` 不迁移不删除（NFR-02），旧端点保留。

---

## 2. W1 快照对象化（W65-01 ~ W65-03）

### W65-01 `V11_75` 快照两表

| 项 | 内容 |
|---|---|
| 目标 | `device_snapshot`（含 `content_hash` + `UNIQUE(device_id, content_hash)`）与 `device_snapshot_item`（`UNIQUE(snapshot_id, item_key)`） |
| 改动面 | 迁移两支；索引：`(device_id, captured_at DESC)`、`(snapshot_id, item_key)` |
| 出口判据 | ① 空库/既有库各跑一次；② down 后两表 DROP 且 `env_profile` 不受影响；③ 内容相同两次采集复用同一 `content_hash`（不重复存储） |
| 验证命令 | `psql "$DB_URL" -c "\d device_snapshot" -c "\d device_snapshot_item"` |
| 依赖 | — |

### W65-02 快照读写 API（按项）

| 项 | 内容 |
|---|---|
| 目标 | `GET /api/environment/snapshots/{id}` 返回 `items[]`（非 JSON 字符串）；`GET .../items?key=` 按项查；快照不可变 |
| 改动面 | 新增 Controller/Service/Repository；无更新端点（不可变由「不存在该端点」保证） |
| 出口判据 | ① 响应为按项结构；② 按 key 可查单值；③ 更新请求 → 404/405 或 409「快照不可修改」；④ 500 项写库 ≤3s、按项查 ≤200ms |
| 验证命令 | `curl -s "$API/api/environment/snapshots/1" \| jq '.items[0:3]'` |
| 依赖 | W65-01 |

### W65-03 采集写快照 + 读路径切换

| 项 | 内容 |
|---|---|
| 目标 | 采集完成写「1 快照 + N 项」；`env_profile.snapshot_json` 不再写；「采集记录分析」页改读新表（**非双写**） |
| 改动面 | 采集服务（写快照）、旧页读路径切换、`source=COLLECT/IMPORT` 标记 |
| 出口判据 | ① 一次采集新增 1 行 + N 行（N = 包内项数）；② `snapshot_json` 旧行不变（前后比对）；③ 采集失败不产生快照；④ 旧页能读到新数据 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=SnapshotWriteTest test` |
| 依赖 | W65-02 |

---

## 3. W2 按项对比（W65-04 ~ W65-05）

### W65-04 对比服务

| 项 | 内容 |
|---|---|
| 目标 | 输入两个快照 id → `{added[], removed[], changed[{key, old, new}]}`；自己与自己对比三项全空 |
| 改动面 | `SnapshotCompareService`（可借鉴白泽 `SnapshotCompare.java`，记录借鉴行号）+ 单测 |
| 出口判据 | ① 构造差异样本，三项计数与构造一致；② 同一快照自比三项为空；③ 1000 项 ≤1s |
| 验证命令 | `cd backend && mvn -o -B -Dtest=SnapshotCompareTest test` |
| 依赖 | W65-02 |

### W65-05 对比页三栏

| 项 | 内容 |
|---|---|
| 目标 | 前端三栏（新增/消失/变化），变化项「旧值 → 新值」；可选任意两份快照（不限于最近两份） |
| 改动面 | 对比页或拓扑页内区块 + spec |
| 出口判据 | ① 三项分组可见；② 变化项双值显示且与表逐项一致；③ 快照选择器可选任意两份 |
| 验证命令 | `cd frontend && npx vitest run -t 快照对比` |
| 依赖 | W65-04 |

---

## 4. W3 探针包（W65-06 ~ W65-08）

### W65-06 `V11_76` 探针包

| 项 | 内容 |
|---|---|
| 目标 | `probe_pack`（`pack_key` / `display_name` / `device_type` / 版本 / 启用）+ `probe_pack_item`（`item_key` / `command` / `parser` / `required`） |
| 改动面 | 迁移两支 + 种子（服务器包、交换机包） |
| 出口判据 | ① 迁移幂等；② 种子可重复执行不产生重复；③ 重复 `item_key` 在加载时被拒并点名 |
| 验证命令 | `psql "$DB_URL" -c "select pack_key,device_type,count(*) from probe_pack p join probe_pack_item i on i.pack_key=p.pack_key group by 1,2"` |
| 依赖 | W65-01 |

### W65-07 按 device_type 选包与 required 语义

| 项 | 内容 |
|---|---|
| 目标 | 采集按 `device_type` 选包；`required=true` 失败 → 整体失败点名；`required=false` 失败 → 整体成功 + 该项记「未采到」+ 原因 |
| 改动面 | 采集编排读包配置、失败分类、快照项写「未采到」状态 |
| 出口判据 | ① 交换机与服务器项集不同（项名断言）；② 非必需失败不阻塞且留痕；③ 必需失败点名该项（不静默降级） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ProbePackTest test` |
| 依赖 | W65-06、W65-03 |

### W65-08 现有 10 条命令迁入包

| 项 | 内容 |
|---|---|
| 目标 | 现有硬编码的 10 条命令全部迁入包配置；**新增一项不改代码** |
| 改动面 | 命令常量删除 → 包种子；回归单测 |
| 出口判据 | ① 同一设备两次采集项集一致（迁移前后比对）；② 配置加一条项 → 采集结果出现该项（不改代码，重启或热加载即可） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ProbePackMigrationTest test` |
| 依赖 | W65-07 |

---

## 5. W4 拓扑分级（W65-09 ~ W65-13）

### W65-09 `V11_77` link 置信度与来源

| 项 | 内容 |
|---|---|
| 目标 | `link.confidence VARCHAR(16)`、`link.source VARCHAR(16)`；存量行默认 `PLANNED` / `MANUAL`，**不出现 NULL** |
| 改动面 | 迁移两支 + 回填 |
| 出口判据 | ① 迁移后无 NULL；② down 可回滚；③ 既有拓扑图仍能渲染（不因新列报错） |
| 验证命令 | `psql "$DB_URL" -c "select source,confidence,count(*) from link group by 1,2"` |
| 依赖 | W65-01 |

### W65-10 优先级与折叠

| 项 | 内容 |
|---|---|
| 目标 | 同一对端口多来源时图上只显示最高置信度（`LLDP` > `SAME_SUBNET_SPEED` > `PLANNED` > `undefined`）；人工确认可覆盖但标注不一致 |
| 改动面 | 拓扑图数据装配（可借鉴白泽 `TopologyGraph.java`）+ 单测 |
| 出口判据 | ① 同对端口有 LLDP 与规划 → 只显示一条 `LLDP`；② 其余折叠可查；③ 人工确认覆盖时标注「与实测不一致」且可看实测值 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=TopologyConfidenceTest test` |
| 依赖 | W65-09 |

### W65-11 LLDP 邻居导入与去重

| 项 | 内容 |
|---|---|
| 目标 | 解析 `lldpctl` / `show lldp neighbors` → 写 `link`（`source=LLDP`）；同源同目标同端口唯一；对端未登记标 `peer_unresolved` |
| 改动面 | 解析器 + 幂等 upsert + 单测（含无输出场景） |
| 出口判据 | ① 邻居数与真实一致；② 重复采集不产生重复边；③ 未登记对端可见且标记；④ 无输出 → 记录「未获取 LLDP」，不造假边 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=LldpImportTest test` |
| 依赖 | W65-03、W65-10 |

### W65-12 图例与过滤

| 项 | 内容 |
|---|---|
| 目标 | 四类置信度图例（色/线型）+ 过滤（设备类型 / 置信度 / 仅实测）+ 边详情（两源数据与采集时间） |
| 改动面 | 拓扑图组件 + spec |
| 出口判据 | ① 图例与图上一致（截图）；② 过滤后节点/边数与接口一致（DOM 断言）；③ 点边显示来源/置信度/两源/时间；④ 100 节点 300 边 ≤2s、fps ≥40 |
| 验证命令 | `cd frontend && npx vitest run -t 拓扑图例` |
| 依赖 | W65-10 |

### W65-13 `topology_node` 启用为规划源

| 项 | 内容 |
|---|---|
| 目标 | `topology_node` 不再死表：录入人工规划 → 生成 `source=PLANNED` 的 link |
| 改动面 | 规划录入接口/页面 + 写 link 的映射 + 单测 |
| 出口判据 | ① 录入 A.port1 ↔ B.port2 → 图出现 `PLANNED` 边；② LLDP 出现同一条后图上只显示 LLDP；③ 表有真实写入（计数 >0） |
| 验证命令 | `psql "$DB_URL" -c "select count(*) from topology_node"` |
| 依赖 | W65-10 |

---

## 6. W5 页面收敛（W65-14）

### W65-14 六页合四页

| 项 | 内容 |
|---|---|
| 目标 | 环境概览 / 设备感知（采集 + 记录分析）/ 环境准备 / 拓扑与连接（快照 + 对比 + 图）；旧路由重定向 |
| 改动面 | 导航配置、4 个页面容器、旧路由 `redirect`、`check_navigation.py` 叶数 |
| 出口判据 | ① 导航二级项 4 个（原 6）；② 逐能力走查清单无遗漏；③ 旧路由重定向（非 404）；④ 12 个端点无孤儿（有调用方或明确下架） |
| 验证命令 | `python3 tools/check_navigation.py`；`cd frontend && npx vitest run -t 环境` |
| 依赖 | W65-05、W65-08、W65-12 |

---

## 7. W6 执行前闸门（W65-15 ~ W65-17）

### W65-15 用例 `env_require` 声明与解析

| 项 | 内容 |
|---|---|
| 目标 | 用例扩展元数据 `env_require: [{key, op, value}]`（不新建用例表列）；执行前取该用例**最新快照**按 key 查值比较 |
| 改动面 | 用例元数据读写（复用既有扩展机制）、解析器（`>=` / `<=` / `==` / `!=`）、单测 |
| 出口判据 | ① 声明可存取；② 解析支持四类 op；③ 取最新快照（按 `captured_at` 倒序，同刻取 id 大）；④ **无推断**：代码无按用例名/分类猜需求的实现 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=EnvRequireParseTest test` |
| 依赖 | W65-03 |

### W65-16 拦截与顺序

| 项 | 内容 |
|---|---|
| 目标 | 任一不满足 → 409 + 点名 `key / 期望 / 实际 / 快照 id`；无快照 → 拒绝并提示先采集；**在 ADR-0013 安全闸之后**执行 |
| 改动面 | 执行入口编排（安全闸 → 环境闸门）、错误码 |
| 出口判据 | ① 400G 声明 vs 100G 快照 → 409 且四项信息齐全；② 满足则 200；③ 无快照 → 拒绝且文案含「请先采集」；④ `confirmReal` 缺失 → 400 且错误信息**不含**环境字段 |
| 验证命令 | `curl -s -X POST "$API/api/runs" -d '{"caseId":1,"confirmReal":true}' \| jq` |
| 依赖 | W65-15 |

### W65-17 无声明路径留痕

| 项 | 内容 |
|---|---|
| 目标 | 未声明环境需求的用例不拦截，但执行记录与报告注明「未声明环境需求，未做环境校验」 |
| 改动面 | 执行记录字段/报告节 + spec |
| 出口判据 | ① 无声明用例执行通过；② 记录里该文案可见（DB + 报告）；③ 报告不出现「环境校验通过」的误导表述 |
| 验证命令 | `psql "$DB_URL" -c "select env_check_note from run order by id desc limit 1"` |
| 依赖 | W65-16 |

---

## 8. W7 项目侧采集（W65-18）

### W65-18 项目侧采集传 `deviceId`

| 项 | 内容 |
|---|---|
| 目标 | `api/environment.ts:65-70` 从只传 `host` 改为可传 `deviceId`，由后端取 5.13 的绑定凭证；共用同一段取凭证逻辑 |
| 改动面 | 前端调用、后端端点参数、凭证解析复用 |
| 出口判据 | ① 已绑设备采集成功且验证事实凭证 = 绑定；② 未绑 → 400 且文案与 5.13 一致；③ 无第二处 `management_credential_id` 读取（代码检查） |
| 验证命令 | `cd frontend && npx vitest run -t 项目采集` |
| 依赖 | 5.13 W63-05 |

---

## 9. W8 SSH 安全核对（W65-19）

### W65-19 五项逐条核对

| 项 | 内容 |
|---|---|
| 目标 | 口令走 askpass（不进 argv）、每设备独立 `known_hosts` + `StrictHostKeyChecking=yes`、老算法用 `+` 追加、连接/命令超时分开、命令原文进审计且口令密钥不进日志 |
| 改动面 | 采集执行器（若已有则只核对并补测试）；证据：`ps aux` 抓取、`known_hosts` 路径、grep 日志 |
| 出口判据 | ① 采集中 `ps aux` 看不到口令；② 主机密钥不匹配 → 失败并指名；③ 日志/审计 grep 口令密钥 0 命中；④ `probe_oneshot_secret` 六处 PASS |
| 验证命令 | `ps aux \| grep -c "sshpass\|-p "`（采集并发时抓取，须为 0） |
| 依赖 | — |

---

## 10. W9 收口（W65-20 ~ W65-21）

### W65-20 契约与旧端点兼容

| 项 | 内容 |
|---|---|
| 目标 | `openapi.json` 重生成；旧快照端点仍可用（NFR-02） |
| 改动面 | 契约快照（WSL 内重生成） |
| 出口判据 | ① `--check` PASS；② 旧端点回归通过；③ `endpoints/paths` 读数写进测试报告 |
| 验证命令 | `cd backend && python3 tools/gen_api_contract.py --check` |
| 依赖 | W65-02、W65-06、W65-09 |

### W65-21 门禁全量与文档回写

| 项 | 内容 |
|---|---|
| 目标 | `GATES_RESULT FAILS=0`；六份齐全；总纲状态回写；白泽借鉴记录归档 |
| 改动面 | `docs/release/v5.15.0/*`（从 `_draft/` 迁入）、`docs/evidence/v5.15.0/baize-borrowing.md` |
| 出口判据 | ① 门禁 `FAILS=0`；② `check_release_docs.py v5.15.0` PASS；③ 表格机检全 PASS；④ 借鉴记录含文件/行号/改动 |
| 验证命令 | `python3 tools/check_gates.py 2>&1 \| tail -3` |
| 依赖 | W1 ~ W8 全部 |

---

## 11. 接口与数据变更清单

| 变更 | 类型 | 影响 | 处理 |
|---|---|---|---|
| `GET /api/environment/snapshots/{id}`（按项） | 新端点 | 所有快照消费方 | 契约重生成 |
| `GET .../snapshots/{id}/items?key=` | 新端点 | 按项查询 | 契约重生成 |
| `GET .../snapshots/compare?a=&b=` | 新端点 | 对比页 | 契约重生成 |
| `probe_pack` 相关端点 | 新端点 | 包管理 | 契约重生成 |
| `link` 响应加 `confidence`/`source` | 响应新字段 | 拓扑图 | 契约重生成 |
| 执行入口新错误码 `ENV_REQUIREMENT_UNMET` | 错误码 | 执行失败提示 | 登记错误码表 |
| `V11_75` / `V11_76` / `V11_77` | 3 支迁移 | 快照两表、探针包、link 列 | 每支配 `.down.sql` |

---

## 12. 风险与应对

| # | 风险 | 应对 |
|---|---|---|
| R1 | 抄白泽代码带入其数据模型假设 | 只抄算法骨架（对比/优先级/图构建），不抄表结构；借鉴记录逐处写「改了什么」 |
| R2 | 执行前闸门做成「按名字猜需求」 | W65-15 判据 4 明确禁止推断；无声明就不拦截并留痕 |
| R3 | 快照内容寻址导致「两次采集同 hash」被误当同一次 | `device_snapshot` 仍每次插新行（id 不同），只复用 `content_hash` 存储 |
| R4 | 页面合并丢能力 | W65-14 要求逐能力走查清单 + 12 端点无孤儿 |
| R5 | 探针包迁移改变既有采集结果 | W65-08 用「同设备两次采集项集一致」做回归 |
| R6 | SSH 安全项「看起来对」 | W65-19 用 `ps aux` 与 grep 取证，不看代码判断 |

---

## 13. 与相邻版本的边界

1. 本版**不做**运维（备份/恢复/升级）——属 5.16。
2. 本版**不改** 5.13 的凭证逻辑，只复用其 Service。
3. 本版**不改**报告体系；环境校验结论只进执行记录与报告的一行注记。
4. `env_profile.snapshot_json` 保留只读，不迁移数据（NFR-02）。

---

## 14. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：9 波 21 个 WBS；快照对象化先行；闸门「声明才校验」写进 WBS | `PRD.md` v5.15.0 + 评估 05 + 白泽调研 |
