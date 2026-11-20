# 开发计划：用例脚本绑定可见性、执行软校验与快速组集

> 日期：2026-11-20 ｜ 撰写：学徒 → 交大师评审
> 对应 PRD：`01-PRD.md`（`FR-READY-01 ~ 11`）；上游 `00-评估报告.md`（有条件可行 P1–P4）
> 开工条件：大师已批准 PRD（2026-11-20）；O-1/O-2/O-3 按 PRD 建议口径闭合，O-4 已查证闭合（见 §0）
> 编号规范：WBS 临时前缀 **`W-READY-xx`**；FR 临时前缀 `FR-READY-xx`。**进 `docs/release/` 前统一替换为正式 `WXX-xx` / `FR-vX.Y-xx`**（`detect_prefix` 只认 `v<数字>`）。

---

## 0. 开放项闭合结论（开工前置）

| ID | 闭合方式 | 结论 | 依据 |
|---|---|---|---|
| O-1 | 大师批准 PRD，采纳建议写法 | 「用例集有脚本」= **成员脚本覆盖率**（`test_set` 无脚本字段，绑定只挂 `test_case`） | PRD §10 O-1 |
| O-2 | 大师批准 PRD，按本文独立推进 | 独立主题，临时 `FR-READY-xx`；不与 v5.24 合流。FR-v5.24-01/04 保持不变，本计划只提供筛选维度与数据源 | PRD §10 O-2 |
| O-3 | 大师批准 PRD，采纳建议 | 「可执行集」准入以 **`BOUND_VERIFIED`** 为准；`BOUND_UNVERIFIED` 单独提示不入集 | PRD §10 O-3 |
| O-4 | 查证闭合（只读源码） | platform 项目 **无任何绑定写入路径**：无自动同步（定时任务是 `ProjectSyncService` 且显式排除 platform）、无人工覆盖接口（`update`/`deleteById` 零调用）、迁移无 INSERT；`test_kernel='robot'` ⇒ 公共库脚本状态**恒 `NOT_APPLICABLE`**（`PlatformProjectService.java:48-52`；`CaseScriptView.java:40-47`；`PytestProjectController.java:80-92`）。**风险口**：通用 `POST /api/projects/{id}/pytest/bindings/sync` 对 kind 零校验（`ProjectKind.isPytestSuite` 全仓无调用），若给 platform 配 `pytest.repo_path` 会误写 pytest 绑定 ⇒ W-READY-01 加 kind 守卫 | 本目录取证 + `CaseScriptBindingRepositoryJdbc.java:99-113` |

**由此产生一条对 PRD 的实质修正**（已回写 PRD §2.2 / FR-READY-02）：大师裁定「双端可见」成立，但公共库当前**恒 `NOT_APPLICABLE`**、无绑定可筛——FR-READY-02/06 的公共端在「有绑定仓库」被配置前，实测只能验证「统一状态文案 + 不误判 + 不误拦」，不能验证「筛出已绑定公共用例」。该限制写入 TEST-PLAN 降级判定。

---

## 1. 通用约束

1. **只读绑定表**：不改 `case_script_binding` 表结构、不新增绑定表；所有筛选/预检/组集只消费现有绑定（`V11_47__case_script_binding.sql:18-45`）。
2. **分页前过滤**：`bindingState` 必须在 repository 层 JOIN，禁止分页后在内存里筛（否则 `total` 与结果不一致）。
3. **统一状态源**：四态字符串只由 `CaseScriptView`（`CaseScriptView.java:25-31`）产出，前后端不得各自拼装（FR-v5.11-05 判据 6 不回归）。
4. **构造器注入**：依赖一律构造器注入，一个类只能一个公开构造器（`ArchitectureTest` R6）。
5. **安全闸不绕过**：软校验只决定「是否下发 nodeid」，不碰 G-1 授权、ADR-0013 real fail-closed（`PytestNicRunController.java:508-515`）。
6. **类型白名单**：`bindingState` 入参四态白名单，非法值 400；未传时行为与现网逐字一致。
7. **迁移只追加**：若需索引，写新迁移，不改历史文件。
8. **同步顺序**：E 盘改 → cp 到 WSL → 编译测试（E 盘无 ssh 密钥）；9P 上禁止构建测试。
9. **shell 纪律**：复杂 shell 写 `.sh` 文件执行，不在 `bash -lc '...'` 里用 `$VAR`（跨层展开坑）。
10. **门禁纪律**：跑门禁期间不改其读取的工作树文件；收口前 `git restore` 已跟踪副产物。

---

## 2. WBS

### W-READY-01 设计契约 + platform kind 守卫（前置）
- **对应**：FR-READY-02/07/10；闭合 O-4 风险口
- **交付**：`docs/design/用例脚本绑定可见性与软校验契约.md`
- **要点**：
  1. 定四态判定函数（统一入口：`CaseScriptView.supportsScript` + `kernelOf`），明确「可执行=BOUND_VERIFIED、BOUND_UNVERIFIED 提示、NOT_APPLICABLE 不计分母」。
  2. 定 `bindingState` 请求参数白名单与分页前 JOIN SQL 形态。
  3. **加 kind 守卫**：`PytestBindingSyncService.sync()` 或 `PytestProjectController.sync()` 入口断言 `ProjectKind.isPytestSuite(project.kind)`，非 pytest-suite 一律 400，堵住 platform 误写入口（`PytestProjectController.java:88-92`）。
  4. 定软校验清单结构 `{ boundCount, unbound:[{caseId,caseCode,reason}], notApplicableCount }` 与三入口消费方式。
- **验收命令**：`python docs/evidence/v1.0/policy-20261106/check_md_tables.py <契约>`；`mvn -q test -Dtest=ProjectKindTest`
- **判据**：契约写明四态判定、JOIN SQL、清单结构；对 platform 调 sync 端点返回 400 且绑定表无新增。

### W-READY-02 项目用例库 bindingState 过滤（后端）
- **对应**：FR-READY-01
- **改动类**：`CaseController.page`（`CaseController.java:77-106`）、`TestCaseService.page`（`:170-176`）、`CaseRepositoryJdbc`（或对应仓储）、新增 filter 常量
- **要点**：
  1. list 入参加 `bindingState`；repository 分页前 `LEFT JOIN case_script_binding b ON b.case_id = tc.id AND b.project_id = ? AND b.kernel = ?`。
  2. 内核用 `CaseScriptLookup.kernelOf(projectId)` 同款逻辑（`CaseScriptLookup.java:36-42`）；RF 内核下 `UNBOUND`→0 条、`NOT_APPLICABLE`→全部。
  3. 保留「脚本三件套分页后装配」（`CaseController.java:95-106`）用于展示，筛选判定走 JOIN，二者口径同源。
  4. 非法 `bindingState` → 400。
- **验收命令**：`mvn -q test -Dtest=CaseScriptLookupTest,CaseScriptViewTest,CaseListBindingFilterTest`（后者新建）
- **判据**：四态逐态对真值；`total` = SQL COUNT；默认无回归。

### W-READY-03 公共库同口径过滤（后端 + 前端）
- **对应**：FR-READY-02
- **改动类**：`GlobalCaseController.page`（`GlobalCaseController.java:57-66`）、`GlobalCaseService`（`:260-278`）、前端 `GlobalCasesView.vue`（`:76-100`）、`api/globalCases.ts`
- **要点**：
  1. projectId 固定 `platform.id()`（`GlobalCaseService.java:43`）；platform kernel=robot ⇒ 四态过滤结果只能是 NOT_APPLICABLE 全量，其余 0（这是现状正确行为，不是缺陷）。
  2. 文案与项目库同源（`CaseScriptView`）。
  3. 前端加脚本状态筛选下拉；`globalCaseApi.page` 参数同步。
- **验收命令**：`mvn -q test -Dtest=GlobalCaseServiceTest`；`cd frontend && npm run build` + `npx vitest run src/views/global/GlobalCasesView.spec.ts`
- **判据**：同一 case_code 双端状态文案逐字相同；platform 不被误判 pytest-suite；公共筛选不串项目数据。

### W-READY-04 项目库筛选 UI + 保存视图
- **对应**：FR-READY-01（前端半）、FR-READY-11（隐藏 H7）
- **改动类**：`frontend/src/views/CasesView.vue`（`:97-103,1160-1200`）、`api/cases.ts`（`:193-205,137-147`）、`composables/useSavedViews.ts`（`:28-37`）
- **要点**：FilterBar 加「脚本状态」四态下拉；`caseApi.page` 与 `CaseSelectionSpec` 加 `bindingState`；`useSavedViews` payload 加该字段（含 `@` 的 i18n 串按坑转义）。
- **验收命令**：`cd frontend && npx vitest run src/views/CasesView.spec.ts src/views/CasesView.views.spec.ts` + `npm run build`
- **判据**：四态下拉可见可筛；筛选条件可保存/恢复；`check_i18n` PASS。

### W-READY-05 集成员脚本状态筛选 + 覆盖率概览
- **对应**：FR-READY-03
- **改动类**：`CaseSetsView.vue`（`:119-137,1042-1049`）、`GlobalCaseSetsView.vue`（`:389-392`）、集详情 API（返回覆盖率聚合）
- **要点**：成员表 `bindingState` 列筛选；详情显示「已绑定 X / 共 Y 条」（X 含 VERIFIED+UNVERIFIED，NOT_APPLICABLE 不计分母）；公共集同样显示（无绑定显示 `0/N`）。
- **验收命令**：`cd frontend && npx vitest run src/views/CaseSetsView.spec.ts src/views/global/GlobalCaseSetsView.spec.ts` + `mvn -q test -Dtest=CaseSetBatchTest`
- **判据**：3 已绑定+2 未绑定的集概览 `3/5`、筛未绑定得 2 条；公共集概览非空白。

### W-READY-06 通用「按筛选条件批量入集」端点
- **对应**：FR-READY-05
- **改动类**：新增 Controller 端点 + Service，参考 `CaseSetService` / `api/cases.ts:328-338` `addByCategoryFilter`
- **要点**：
  1. 入参 `CaseSelectionSpec + bindingState + excludeIds`；服务端按同一 filter 真值选中（分页前查询，非分页内存）。
  2. 批量写 `test_set_item`，`seq_no` 从 `MAX(seq_no)+1` 连续续排（`V1__baseline.sql:320-334` 唯一键 `(test_set_id, seq_no)`）。
  3. 按 `(test_set_id, test_case_id)` 幂等去重；返回 `{ matched, added, itemSource }`。
  4. 权限、安全级校验沿用 `addByCategoryFilter` 口径，不绕过。
- **验收命令**：`mvn -q test -Dtest=GenericBatchSelectionTest,CaseSetBatchTest`
- **判据**：筛 BOUND_VERIFIED 入集零 UNBOUND；seq 连续无冲突；重复调用不重复成员。

### W-READY-07 用例库勾选 → 加入用例集（前端）
- **对应**：FR-READY-04
- **改动类**：`CasesView.vue`（`:941-997,1276`）、新增「加入用例集」动作复用 `caseSetApi.batchAddItems`（`api/cases.ts:301-308`）
- **要点**：筛后勾选（含 `selectAllMatching` 跨页全选）→ 新建集或追加已有集；一次提交完成（≤3 API 调用）；追加走 W-READY-06 端点保证 seq 续排。
- **验收命令**：`cd frontend && npx vitest run src/views/CasesView.spec.ts` + `npm run build`
- **判据**：20 条勾选一次入集成员 +20；跨页全选加入全部匹配项。

### W-READY-08 公共集按筛选批量加成员（前端）
- **对应**：FR-READY-06
- **改动类**：`GlobalCaseSetsView.vue`（`:230-240,367-378`）、`api/platformCaseSets.ts`
- **要点**：公共集详情加「从公共用例库筛选加入」，复用 W-READY-06（platform projectId）；保留单条填 ID 高级用法；写集时保持 `owner_scope='PLATFORM'` 且 `project_id IS NULL`（`V9_3__case_source_version.sql:15-27`）。
- **验收命令**：`cd frontend && npx vitest run src/views/global/GlobalCaseSetsView.spec.ts src/api/platformCaseSets.spec.ts` + `mvn -q test -Dtest=PlatformCaseSetWriteServiceTest`
- **判据**：public 集成员写入不写坏 scope；公共集显示脚本状态。

### W-READY-09 统一执行前脚本预检
- **对应**：FR-READY-07
- **改动类**：新增/收敛 precheck 服务；接入 `RunLaunchController`（`:111,132`）、`RunController`（`:118,139`）、`PytestNicRunController`（`:70-84`）
- **要点**：
  1. 三入口共用同一清单函数：`{ boundCount, unbound:[{caseId,caseCode,reason}], notApplicableCount }`。
  2. `reason` 三值：`UNBOUND` / `NODEID_EMPTY` / `BINDING_STALE`（后者见 W-READY-11）。
  3. pytest 内核算 unbound；RF 内核全量归 notApplicableCount、不拦。
  4. **与 CommandPreviewService 同源**：预览与实际下发同一 filter 真值（`CommandPreviewService.java:54-64`），修 `RunLaunchController.java:41-44` 的接线偏差。
- **验收命令**：`mvn -q test -Dtest=RunPrecheckServiceTest,CaseSelectionResolveTest,ExecutionSelectionFreezeTest,PytestNicRunControllerTest`
- **判据**：三入口同选择清单逐字一致；RF 不拦；reason 分类正确。

### W-READY-10 确认面板「确认跳过」与执行收敛
- **对应**：FR-READY-08
- **改动类**：沿用 v5.24 确认面板组件、`PytestRunLaunch.vue`（`:68-79,154-163,332-334`）、`RunSelectionPanel.vue`、`useRunSelection.ts`
- **要点**：面板展示 W-READY-09 清单（编号+原因+「如何补齐」链接，复用 v5.23 文案 `zh-CN.ts:2382-2383`）；「确认跳过未绑定用例」勾选未勾不得提交；勾选后实际下发清单不含 unbound；all-bound 时提交路径无回归；real 闸原样保留。
- **验收命令**：`cd frontend && npx vitest run src/views/pytest/PytestRunLaunch.spec.ts src/components/RunSelectionPanel.spec.ts src/composables/useRunSelection.spec.ts`
- **判据**：未勾选无新 run；勾选后下发清单剔除 unbound；real 安全闸仍 fail-closed。

### W-READY-11 绑定可信度门槛（STALE 判据）
- **对应**：FR-READY-10/11
- **改动类**：`CaseScriptView`、`CaseScriptLookup`、预检服务（W-READY-09）、过滤服务（W-READY-02）
- **要点**：
  1. `BOUND_VERIFIED` 准入；`BOUND_UNVERIFIED` 可筛但提示「未校验」。
  2. `BINDING_STALE`：绑定行存在但脚本文件缺失 → 该用例按未绑定处理并记 STALE。
  3. 跨内核按当前项目内核取首选绑定，沿用 `CaseScriptLookup.pick`（`:72-97`），公共库固定 platform 内核不串用。
- **验收命令**：`mvn -q test -Dtest=CaseScriptLookupTest,CaseScriptViewTest,PytestReadinessServiceTest`
- **判据**：verified=0 不入可执行集；删脚本文件后预检报 STALE 且不下发；跨项目按目标内核重算。

### W-READY-12 跳过清单可追溯 + 四元组同源
- **对应**：FR-READY-09
- **改动类**：`run.log` 写入、`skipReasons` 聚合、报告首屏跳过区（`FR-v5.23-06/07`）、`reportSelection` 前端
- **要点**：每次软校验跳过写 `{reason,count,samples:[caseCode]}` 到 run.log 与 skipReasons；报告首屏「跳过 K」按 reason 分组展示样例；四元组 N/M/K/T 与 `FR-v5.23-09` 同源，不新增 NOT_RUN 结果行。
- **验收命令**：`mvn -q test -Dtest=RunFailureBreakdownServiceTest,ReportSnapshotCasesTest`；`cd frontend && npx vitest run src/utils/reportSelection.spec.ts`
- **判据**：K=清单条数；run.log 含编号；报告无 NOT_RUN 行；四处 N/M/K/T 一致。

### W-READY-13 机检扩展 + 全量门禁 + 文档回写
- **对应**：FR-READY-11（G 门禁部分）
- **改动类**：新增 `scripts/guard/ready_script_guard.sh`（或扩 `eht_pytest_realchain.sh`）；回写本目录三件套
- **要点**：机检四态筛选真值一致性、批量入集 seq 连续、三入口清单一致、跳过可追溯；`--self-test` 真注入→判红→还原；改动冻结后跑全量门禁 `FAILS=0 UNJUDGED=0`；证据包落盘。
- **验收命令**：`bash scripts/guard/ready_script_guard.sh --self-test`；全量门禁脚本；`check_md_tables.py` 三文档
- **判据**：机检全绿 + self-test 真红真绿；门禁全绿；三文档表格 PASS；TEST-PLAN 实测回写。

---

## 3. 依赖关系

```
W-READY-01 设计+kind守卫 ─┬─► W-READY-02 后端过滤 ─┬─► W-READY-03 公共后端 ─► W-READY-08 公共前端
                          │                        └─► W-READY-04 前端筛选 ─► W-READY-07 勾选入集 ─┐
                          │                        └─► W-READY-05 集概览 ────────────────────────┤
                          │                                                                        ├─► W-READY-06 批量端点
                          └─► W-READY-09 统一预检 ─┬─► W-READY-10 确认面板                         │
                                                   └─► W-READY-11 STALE判据 ─► W-READY-12 追溯      │
                                                                                                    ▼
                                                              W-READY-13 机检+门禁+回写 ◄───────────┘
```

**关键路径**：W-READY-01 → W-READY-02 → W-READY-04/05 → W-READY-06/07 → W-READY-13。
W-READY-09/10/11/12（执行侧）可与筛选侧并行，但 W-READY-10 依赖 W-READY-09 清单、W-READY-11 依赖 W-READY-09 预检结构。

---

## 4. 风险与对策

| ID | 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|---|
| R1 | 分页后装配改分页前 JOIN 的性能/排序回归 | 中 | 中 | WSL ext4 上以 61 条样本实测；JOIN 只取判定位不取整行；必要时新迁移加索引 |
| R2 | 软校验与 real fail-closed 冲突 | 中 | **高** | 清单只决定下发范围，不碰 G-1/ADR-0013；W-READY-10 判据覆盖 |
| R3 | 公共库恒 NOT_APPLICABLE，「双端可筛已绑定」在公共端无法实测 | **高**（已核实） | 中 | TEST-PLAN 列降级判定：公共端只验统一文案/不误判/不误拦；需真验时先给 platform 配 repo_path（另案） |
| R4 | platform sync 无 kind 守卫的潜在误写 | 中 | 中 | W-READY-01 加断言，判据含「platform 调 sync 返回 400」 |
| R5 | 与 v5.24-01/04 重复建设 | 低（已定独立） | 低 | 本计划只提供筛选维度与数据源；不改 v5.24 FR |
| R6 | i18n 同名键插错命名空间 | 中 | 低 | 插键前 `grep -n` 定位；`check_i18n` PASS |
| R7 | 门禁期改工作树致假红 | 高 | 低 | 冻结改动后再跑；收口前 restore 副产物 |

---

## 5. 验收命令总表

```bash
# 环境（每次先加载）
set -a; . ~/.config/robot-autotest/env; set +a

# 后端单测（在 WSL ~/robot-autotest/backend）
mvn -q test -Dtest=ProjectKindTest,CaseScriptLookupTest,CaseScriptViewTest,GlobalCaseServiceTest
mvn -q test -Dtest=CaseListBindingFilterTest,CaseSetBatchTest,GenericBatchSelectionTest
mvn -q test -Dtest=RunPrecheckServiceTest,CaseSelectionResolveTest,ExecutionSelectionFreezeTest
mvn -q test -Dtest=PytestNicRunControllerTest,PytestReadinessServiceTest,ReportSnapshotCasesTest

# 前端
cd frontend && npx vitest run src/views/CasesView.spec.ts src/views/CaseSetsView.spec.ts \
  src/views/global/GlobalCasesView.spec.ts src/views/global/GlobalCaseSetsView.spec.ts \
  src/views/pytest/PytestRunLaunch.spec.ts src/utils/reportSelection.spec.ts && npm run build

# 机检（含 self-test）
bash scripts/guard/ready_script_guard.sh --self-test

# 全量门禁（改动冻结后，约 25 分钟）
<全量门禁脚本>

# Markdown 表格机检
python docs/evidence/v1.0/policy-20261106/check_md_tables.py \
  docs/plan/用例脚本绑定与快速组集-20261120/00-评估报告.md \
  docs/plan/用例脚本绑定与快速组集-20261120/01-PRD.md \
  docs/plan/用例脚本绑定与快速组集-20261120/02-DEV-PLAN.md \
  docs/plan/用例脚本绑定与快速组集-20261120/03-TEST-PLAN.md
```

---

## 6. FR → WBS 覆盖矩阵

| FR | WBS |
|---|---|
| FR-READY-01 | W-READY-02 / 04 |
| FR-READY-02 | W-READY-01 / 03 |
| FR-READY-03 | W-READY-05 |
| FR-READY-04 | W-READY-07 |
| FR-READY-05 | W-READY-06 |
| FR-READY-06 | W-READY-08 |
| FR-READY-07 | W-READY-09 |
| FR-READY-08 | W-READY-10 |
| FR-READY-09 | W-READY-12 |
| FR-READY-10 | W-READY-01 / 11 |
| FR-READY-11 | W-READY-02 / 11 / 13 |

**覆盖核对**：11 条 FR 全部有 WBS；13 个 WBS 全部挂 FR 或门禁。**禁补号**：新增一律追加到本表末尾（取下一个未用序号），不插号重排。

---

## 7. 执行结果回写（第 ⑦ 环，2026-11-20）

> 开发在 WSL 权威副本（`~/robot-autotest`）ext4 上完成，E 盘文档工作区同源（LF 规范化后 36 文件 sha 一致）。
> 后端全量单测 2236 PASS；前端 vitest 74+21 PASS + build + vue-tsc PASS；全量门禁 `FAILS=0 UNJUDGED=0`（68 步）。

| WBS | 内容 | 实际 | 证据 / 偏差 |
|---|---|---|---|
| W-READY-01 | 设计契约 + platform kind 守卫 | 达成 | `PytestProjectController.sync` 加 `isPytestSuite` 断言；真链路 platform(id=88) sync→400 |
| W-READY-02 | 项目库 bindingState 分页前 JOIN 过滤 | 达成 | `BindingStateFilter` 单一真源 + `TestCaseRepositoryJdbc` 别名分支；四态和=469=全量 |
| W-READY-03 | 公共库同口径过滤 | 达成 | `GlobalCaseService` 加 bindingState；platform 恒 NOT_APPLICABLE（O-4 查证） |
| W-READY-04 | 用例库筛选 UI + 保存视图 | 达成 | `CasesView` 四态下拉 + `useSavedViews` payload 扩字段；vitest PASS |
| W-READY-05 | 集成员筛选 + 覆盖率 | 达成 | `CaseSetsView` 成员 facet + `set-script-coverage` 概览 |
| W-READY-06 | 通用按筛选批量入集端点 | 达成 | `TestSetService.addByBindingFilter` + Controller `/binding-filter`；真链路 61 条入集幂等 |
| W-READY-07 | 勾选→加入用例集 | 达成 | 新增 `AddToSetButton.vue`；新建/已有两模式 + 跨页筛选 |
| W-READY-08 | 公共集批量加成员 | 部分达成 | 成员显示脚本状态+保留单条填 ID；按筛选批量加因 platform 无绑定数据载体降级（见 TEST-PLAN TC-09） |
| W-READY-09 | 统一执行前脚本预检 | 达成 | 新增 `ExecutionScriptPrecheck` + `POST /runs/precheck-scripts`；三入口共用 resolver 清单 |
| W-READY-10 | 确认跳过面板 | 达成 | 主执行页跳过清单+确认勾选（未勾禁用）；real 安全闸未动 |
| W-READY-11 | STALE/跨内核判据 | 达成 | 预检 reason 三值（UNBOUND/NODEID_EMPTY/BINDING_STALE）；解耦后直读 KV |
| W-READY-12 | 跳过可追溯 + 四元组 | 达成（部分降级） | 预检清单可信来源在确认面板展示；不写 NOT_RUN 结果行（沿用 v5.14 口径） |
| W-READY-13 | 机检 + 门禁 + 回写 | 达成 | `ready_script_guard.sh` 正常 5/5 + self-test 判红；全量门禁第二轮 FAILS=0 |

### 偏差与过程说明

1. **W-08 部分达成**：大师裁定「双端可见」成立，但 O-4 查证 platform 内核恒 robot、无绑定写入路径 ⇒ 公共库无「已绑定用例」可筛，按 PRD 诚实降级，非实现缺陷。
2. **解耦返工**：机检 39 步曾因 `ExecutionScriptPrecheck` 引用适配包 `PytestProjectSettings` 判红；改为直读 `project_setting` 的 `pytest.repo_path`（`tools/check_kernel_decoupling.py` 口径）后转绿。
3. **契约同步**：新增 3 个 API 变化（bindingState 参数 / binding-filter / precheck-scripts）后重生成 `docs/contracts/openapi.json`（488 端点/405 路径），门禁 20 转绿。
4. **未 bump 版本**：按大师裁定临时编号 `FR-READY/W-READY/TC-READY`，进 `docs/release/` 前统一替换；当前 VERSION 仍 5.23.0，不称 GA。
