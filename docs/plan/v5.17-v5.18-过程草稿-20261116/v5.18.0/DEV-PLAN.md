# v5.18.0 开发计划（DEV-PLAN）：缺口闭合（二）建设与清场

> 流程位置：④ 开发计划（草案）。**待 v5.17.0 收口后开工**。
> 上游：`PRD.md`（7 FR + 8 NFR）、`../v5.17.0/01-ASSESSMENT.md`（D-1~D-4 拍板 + 清场盘点）。
> 数据库为 MySQL / MariaDB：用 `scripts/db-audit.sh` / `db-migrate.sh` 与 `mysql` CLI，**不用 `psql`**。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | 导入五步向导 | W68-01 ~ W68-03 | 是 | 可与 W2 并行（改动面不重叠） |
| W2 | 行数收敛 | W68-04 ~ W68-06 | 是 | 必须在 W1 之后（导入块要被抽出去） |
| W3 | 拓扑三件 | W68-07 ~ W68-10 | 是 | 07/08/09 同波（图例要与规划源同源）；10 独立 |
| W4 | 运维真机动作 | W68-11 ~ W68-13 | 是 | **需大师授权**，可与 W3 并行 |
| W5 | **清场** | W68-14 ~ W68-16 | 是 | 依赖 W1~W4 全部完成（清的就是它们造的残留） |
| W6 | 契约与收口 | W68-17 ~ W68-18 | 否（最终波） | 依赖 W1 ~ W5 |

**并行纪律**：W1 与 W3-10 可与 W2 并行；W2 必须等 W1（导入块要抽）；W3 的 07/08/09 必须同波，否则图例标了「规划」但规划源没出图；
**W5 清场必须最后做**——它要清的是前四波造的全部残留，提前清等于白清。

---

## 2. W1 导入五步向导（W68-01 ~ W68-03）

### W68-01 `ImportWizard.vue` 五步组件

| 项 | 内容 |
|---|---|
| 目标 | 选文件 → 字段映射 → 冲突预览 → 提交 → 结果；资产与用例共用；映射页显示前 3 行样例 |
| 改动面 | 新增 `components/ImportWizard.vue` + spec；步态三态与回退 |
| 出口判据 | ① 五步可前进/回退；② 改列后样例与预览数字变化；③ 取消不发写请求；④ 结果可跳 `?view=imports` |
| 验证命令 | `cd frontend && npx vitest run src/components/ImportWizard.spec.ts` |
| 依赖 | — |

### W68-02 资产接线 + 后端 mapping

| 项 | 内容 |
|---|---|
| 目标 | `GlobalAssetsView` 两步弹窗换 `ImportWizard`；preview 接受可选 `mapping`，返回 `toCreate/toUpdate/conflicts` |
| 改动面 | `views/global/GlobalAssetsView.vue`、`api/globalAssets.ts`、后端 preview、契约、spec |
| 出口判据 | ① 预览三数与提交结果一致；② 不传 `mapping` 老行为不变；③ 导航叶数不变；④ 42 条资产回归仍绿 |
| 验证命令 | `cd frontend && npx vitest run src/views/global/GlobalAssetsView.spec.ts`；`python3 tools/gen_api_contract.py --check` |
| 依赖 | W68-01 |

### W68-03 用例接线 + 旧行为回归

| 项 | 内容 |
|---|---|
| 目标 | `GlobalCasesView` 导入换同一组件；`force`（回放）语义保留 |
| 改动面 | `views/global/GlobalCasesView.vue`、`api/globalCases.ts`、spec |
| 出口判据 | ① 五步走通；② `force` 透传仍生效；③ 结果可在导入记录查到；④ 既有导入测试不减 |
| 验证命令 | `cd frontend && npx vitest run src/views/global/GlobalCasesView.spec.ts` |
| 依赖 | W68-02 |

---

## 3. W2 行数收敛（W68-04 ~ W68-06）

### W68-04 抽取拓扑块与租约块

| 项 | 内容 |
|---|---|
| 目标 | 抽出 `AssetTopologyBlock.vue`、`AssetLeaseBlock.vue` |
| 改动面 | 2 个新组件 + 两页装配 + spec |
| 出口判据 | ① 组件独立 spec 覆盖渲染与空态；② 两页行为不变；③ 行数下降有读数 |
| 验证命令 | `cd frontend && npx vitest run src/components/AssetTopologyBlock.spec.ts src/components/AssetLeaseBlock.spec.ts` |
| 依赖 | W68-03（页面已定型） |

### W68-05 抽取采集画像块与导入块

| 项 | 内容 |
|---|---|
| 目标 | 抽出 `AssetCollectBlock.vue`、`AssetImportBlock.vue`（后者包 W68-01 的向导） |
| 改动面 | 2 个新组件 + 两页装配 + spec |
| 出口判据 | ① 采集画像三列与入口行为不变；② 导入入口仍在操作列；③ 行数下降有读数 |
| 验证命令 | `cd frontend && npx vitest run src/components/AssetCollectBlock.spec.ts` |
| 依赖 | W68-04 |

### W68-06 压到 ≤2200 与回归

| 项 | 内容 |
|---|---|
| 目标 | 三文件合计 ≤2200、单文件 ≤800；42 条回归一条不减 |
| 改动面 | 收尾调整 + `assetWorkspaceMatrix` 断言迁移 |
| 出口判据 | ① `wc -l` 达标；② 42 条回归全绿；③ 12 条矩阵 + 4 条反证全绿；④ 九项功能面走查无回退 |
| 验证命令 | `wc -l frontend/src/views/AssetsView.vue frontend/src/views/global/GlobalAssetsView.vue frontend/src/components/AssetWorkspace.vue`；`cd frontend && npx vitest run src/views/AssetsView.spec.ts src/views/global/GlobalAssetsView.spec.ts src/config/assetWorkspaceMatrix.spec.ts` |
| 依赖 | W68-04、W68-05 |

---

## 4. W3 拓扑三件（W68-07 ~ W68-10）

### W68-07 图例四类与「仅实测」过滤

| 项 | 内容 |
|---|---|
| 目标 | 图例（实测/人工/规划/冲突）+ 开关；边数与接口 MEASURED 一致；过滤态落 URL |
| 改动面 | `views/environment/TopologySnapshotView.vue`、图例组件、i18n、spec |
| 出口判据 | ① 四类可见颜色线型固定；② 仅实测边数 = 接口数；③ 四类之和 = 总数；④ URL 可复现；⑤ 空态自解释 |
| 验证命令 | `cd frontend && npx vitest run src/views/environment/pages.spec.ts -t 图例` |
| 依赖 | — |

### W68-08 `topology_node` 规划源聚合

| 项 | 内容 |
|---|---|
| 目标 | 聚合纳入 `topology_node` 作为规划层；DTO 带 `source` / `nodeType` |
| 改动面 | 拓扑聚合服务 + DTO + 单测；零迁移 |
| 出口判据 | ① 两种节点都出图并标「规划」；② 写入 count > 0；③ 空项目不报错 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=TopologyAggregationTest test` |
| 依赖 | W68-07 |

### W68-09 同对端优先级与来源标注

| 项 | 内容 |
|---|---|
| 目标 | 同对端只一条，`MEASURED > MANUAL > PLANNED`；边上标来源 |
| 改动面 | 聚合去重 + 前端标注 + spec |
| 出口判据 | ① 双来源只一条且来源 = MEASURED；② 只有规划边时 = PLANNED；③ 图例计数含规划 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=TopologyAggregationTest test` |
| 依赖 | W68-08 |

### W68-10 交换机 / BMC 探针包

| 项 | 内容 |
|---|---|
| 目标 | `ProbePackService` 三套包；命令取自 `COMMANDS`；未知类型 400 |
| 改动面 | `ProbePackService`、必要时补 `COMMANDS` 的 `NET_`/`BMC_`、单测 |
| 出口判据 | ① 三套包命令 ⊆ 白名单；② 未知类型 400 不回落；③ `required` 按类独立；④ 真机无样本 → BLOCKED 并写明 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ProbePackServiceTest test` |
| 依赖 | 开工首日核实 `COMMANDS` 是否已有 `NET_`/`BMC_` |

---

## 5. W4 运维真机动作（W68-11 ~ W68-13，需授权）

### W68-11 真机备份 `--apply`

| 项 | 内容 |
|---|---|
| 目标 | 真实备份并校验 hash |
| 改动面 | 无代码；证据落 `docs/evidence/v5.18.0/ops/` |
| 出口判据 | ① 产物存在且 `sha256sum -c` 通过；② 清单条数 = 目录文件数；③ 落 `ops_task`（`dryRun=0`） |
| 验证命令 | `bash scripts/ops/backup.sh --apply`；`sha256sum -c "$BACKUP_DIR"/*.sha256` |
| 依赖 | **大师授权** |

### W68-12 恢复到 `robot_it` 与 10 表比对

| 项 | 内容 |
|---|---|
| 目标 | 真实恢复并逐表比对 |
| 改动面 | 无代码；用 `scripts/backup-drill.sh`（自带备份与还原） |
| 出口判据 | ① 比对结论明确（一致 / 差异点名）；② 非 `_it` 库仍 400；③ `robot_dev` 行数不变 |
| 验证命令 | `bash scripts/backup-drill.sh`；`bash scripts/db-audit.sh` |
| 依赖 | W68-11、授权 |

### W68-13 `update-drill.sh` 四阶段

| 项 | 内容 |
|---|---|
| 目标 | 四阶段各出结论，不改升级编排 |
| 改动面 | 无代码 |
| 出口判据 | ① 四阶段逐条结论；② 失败阶段点名；③ `update.sh` 零改动 |
| 验证命令 | `bash scripts/ops/update-drill.sh`；`git diff --stat scripts/ops/update.sh` |
| 依赖 | W68-12、授权 |

---

## 6. W5 清场（W68-14 ~ W68-16）

### W68-14 临时对象盘点清单

| 项 | 内容 |
|---|---|
| 目标 | 产出逐对象清单：类型 / 名称 / 判定信号 / 保留或删除理由 / 依赖引用数 |
| 改动面 | 新增 `scripts/ops/inventory-temp.sh`（只读，输出 markdown 表）；清单落 `docs/evidence/v5.18.0/purge/` |
| 出口判据 | ① 覆盖全部账号与项目；② 每个对象都有判定信号与理由；③ 三类分组齐全（保留 / 候选 / 待确认）；④ 零写库（只读） |
| 验证命令 | `bash scripts/ops/inventory-temp.sh`；`bash scripts/db-audit.sh` |
| 依赖 | W1 ~ W4 完成 |

### W68-15 清理执行（含备份与还原路径）

| 项 | 内容 |
|---|---|
| 目标 | 备份 → dry-run → 授权 → `--apply` → 逐表断言；失败可还原 |
| 改动面 | 扩 `scripts/ops/purge-demo.sh` 为 `purge-temp.sh`（三信号 + 清单驱动 + 备份校验 + 删除顺序） |
| 出口判据 | ① 备份存在且 hash 通过；② dry-run 零写入；③ `--apply` 后候选对象全 0；④ 保留对象计数与名称零变化（含 `demo` 238 / `demo-pytest` 12 用例逐字不变）；⑤ 逐表删除顺序无外键错误 |
| 验证命令 | `bash scripts/ops/purge-temp.sh`（dry-run）；`PURGE_TEMP_CONFIRM=YES bash scripts/ops/purge-temp.sh --apply` |
| 依赖 | W68-14、大师授权 |

### W68-16 防复发机检

| 项 | 内容 |
|---|---|
| 目标 | `check_demo_data` 扩为「发版号 + 任一临时信号 >0 → 判红」；新建临时对象强制标记 |
| 改动面 | `tools/check_demo_data.py`（或新增 `check_temp_objects.py`）+ 门禁注册 + `--self-test` |
| 出口判据 | ① 注入一条未标记临时对象 → 判红；② 清掉 → 判绿；③ 门禁步数 +1 且总 FAILS 不增；④ 文档写明「新建临时对象必须带 `is_demo=1` 或登记前缀」 |
| 验证命令 | `python3 tools/check_demo_data.py --self-test`；`bash scripts/gates.sh 2>&1 \| grep GATES_RESULT` |
| 依赖 | W68-15 |

---

## 7. W6 契约与收口（W68-17 ~ W68-18）

### W68-17 契约与迁移收口

| 项 | 内容 |
|---|---|
| 目标 | 契约重生成 `--check` PASS；确认零迁移或补 `V11_79` |
| 改动面 | `docs/contracts/openapi.json`（WSL 内重生成） |
| 出口判据 | ① `--check` PASS；② `endpoints/paths` 读数写进报告；③ 若加迁移，up/down 各演练一次 |
| 验证命令 | `python3 tools/gen_api_contract.py --check`；`bash scripts/db-migrate.sh` |
| 依赖 | W68-02、W68-08 |

### W68-18 门禁全量与文档回写

| 项 | 内容 |
|---|---|
| 目标 | `FAILS=0 UNJUDGED=0`；六份齐全；台账全部结清 |
| 改动面 | `docs/release/v5.18.0/*`、`评估报告` §6.1 台账 |
| 出口判据 | ① 门禁 `FAILS=0`；② `check_release_docs.py v5.18.0` PASS；③ 六份表格机检 PASS；④ 台账 13 行全部为「已关账 / 明示降级 / BLOCKED」，**无一行「待补」** |
| 验证命令 | `bash scripts/gates.sh`；`python3 tools/check_release_docs.py v5.18.0`；`python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/release/v5.18.0/*.md` |
| 依赖 | W1 ~ W5 全部 |

---

## 8. 接口与数据变更清单

| 变更 | 类型 | 影响 | 处理 |
|---|---|---|---|
| `/global/assets/import/preview` 加 `mapping` | 请求新增可选 | 不传即老行为 | 契约重生成 + 旧行为回归 |
| `/global/cases/import/preview` 同 | 请求新增可选 | 同上 | 同上 |
| preview 响应加 `toCreate/toUpdate/conflicts` | 响应新增 | 老前端忽略 | 契约重生成 |
| 拓扑响应加 `source` / `nodeType` | 响应新增 | 只读 | 契约重生成 |
| 探针包按 `deviceType` 分流 | 语义收紧 | 未知类型由回落变 400 | 契约不变，补单测 |
| 数据库 | **预期零迁移** | — | 若必须从 `V11_79` 起 |

---

## 9. 风险与应对

| # | 风险 | 触发 | 应对 |
|---|---|---|---|
| R1 | 抽组件回退 | 42 条回归任一减少 | 抽一块跑一次全量；矩阵断言跟着搬 |
| R2 | 映射错列静默写错 | 映射页无样例行 | 强制前 3 行样例；提交前二次确认 |
| R3 | 真机动作误伤 | 确认变量缺失或指错库 | 双闸 + 库名白名单 + 失败即还原 |
| R4 | **清场误删真实数据** | 按名字匹配或跳过引用面 | 三信号 + 清单复核 + 执行前备份 + 真对象零变化断言 |
| R5 | 清场后探针失效 | 误删 `qa-e2e-*` 或夹具项目 | 保留清单写进脚本常量，删除前二次校验 id |
| R6 | 清场不彻底 | 只清 `is_demo=1` | 判据 4 按前缀 + 账号名 + id 三重断言 |

---

## 10. 与相邻版本的边界

1. 本版**不重做** 5.17 已关账的 7 项。
2. 本版**不动导航结构**、不加菜单。
3. 本版**不承诺** 1530 行（D-2 第一台阶是 2200）。
4. **清场是本版的收口前置条件**：W68-15 未完成，W68-18 不得开工。

---

## 11. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-16 | 首版：按 D-1 拆版新建；6 波 18 WBS；清场三波（盘点 / 执行 / 防复发）放在最后；D-2 第一台阶 2200、D-3 探针包边界 | 大师 2026-11-16 拍板 + `robot_dev` 盘点 |
