# v5.6.0 开发计划（DEV-PLAN）

> 流程位置：④ 开发计划。**无本计划不许开工编码**。
> 上游：`v5.6.0-产品PRD-可见性版本-20261113.md`（FR-v5.6-xx，23 条本版必做 + 1 条尽力）。
> 版本号 v5.6.0 暂定；本文件不修改 `VERSION`。

---

## 0. 开工前置（不通过则整版不动手）

| 编号 | 前置项 | 判据 | 阻塞范围 |
|---|---|---|---|
| W56-00 | **四项可用性验证**（`/sync`、`/plans`、`/environment/collect`、`/assets?tab=links` 在工程 95 打开后是否真能用） | 每项给出「200 + 数据合法」或「不可用 + 原因」的书面结论，落 `docs/evidence/v5.6.0/probe/`；不可用按缺陷登记 | FR-v5.6-05 |
| W56-00b | **D-01~D-05 拍板** | 大师确认 | 全部 |

W56-00 的验法（只读、不动数据）：

全量菜单后这四项**入口一定存在**，验的是「点进去能不能用」，不是「要不要给入口」。

| 项 | 命令/动作 | 期望 |
|---|---|---|
| `/sync` | `GET /api/projects/95/sync/preview` | 200 且返回差异集结构；404/500 判不可用并登记缺陷 |
| `/plans` | 建一条指向工程 95 用例集的计划 → 触发 → 看 `run` 是否走 pytest 内核 | 产生 `run` 且 `kernel=pytest` |
| `/environment/collect` | 打开页面 + 调其接口（`/api/environment/*`） | 200 且不因 `kind` 被拦 |
| `/assets?tab=links` | `GET /api/links?projectId=95` | 200；并查 `LINK` 是否有字典约束 |

---

## 1. 批次划分与依赖

| 批次 | 主题 | 含 FR | 依赖 | 可独立回滚 |
|---|---|---|---|---|
| W1 | 信息架构（删第二棵树 → 统一菜单 + 空态 + 台架就绪度） | FR-v5.6-01~05 | W56-00b（D-01 已拍板，仅需 D-02~05） | 是 |
| W2 | 执行体验（UI 发起 + 异步 + 取消 + 预检 + 开销） | FR-v5.6-11~15 | W1（入口就位才谈便捷） | 是 |
| W3 | 报告质量（命令/聚类/明细/重试/结构/复现率/自检） | FR-v5.6-21~27 | 无（可并行 W2） | 是 |
| W4 | 资产渲染（GPU/CPU/存储实体化） | FR-v5.6-41 | 迁移新号 | 是 |
| W5 | 运维与测试体系（部署校验/假绿/基线/契约/E2E） | FR-v5.6-62、63、65、72、73 | 无 | 是 |

**并行建议**：W1 与 W3 可同时开工（不同目录、不同测试面）；W4 涉及迁移，单独一批做完再走 W2 的真机回归。

---

## 2. WBS 明细

### W1 信息架构

| WBS | 任务 | 对应 FR | 验收命令 | 依赖 |
|---|---|---|---|---|
| W56-01 | 抽出 `pytestWorkbenchLeaf` 与 `withPytestWorkbench()`（把 `/pytest-workbench` 并入「工作台」组首位），返回 memo 化单例 | FR-v5.6-01 | `cd frontend && npm test`（`navigation.spec.ts` 集合相等断言） | D-01 已拍板 |
| W56-02 | 删除 `PYTEST_SUITE_NAVIGATION`，`navTreeFor` 只区分 global / project | FR-v5.6-02 | `grep -c "PYTEST_SUITE_NAVIGATION" frontend/src` 应输出 0；`npm test` 全绿 | W56-01 |
| W56-03 | 反转 `navigation.spec.ts` 那条禁用断言：由「不出现资产/环境/维护/节点/计划」改为「pytest 叶子集合 = 标准集合 ∪ {/pytest-workbench}」 | FR-v5.6-01/02 | `npm test`；断言失败信息要打出差异集合 | W56-02 |
| W56-04 | 核对 `tools/check_nav_kind.py` 的 `EXPECTED_LEAVES`（删树后 unique path 应为 39 不变，因为 `/pytest-workbench` 仍保留；若变化须显式改基线并说明） | FR-v5.6-02 | `python3 tools/check_nav_kind.py`（含 `--self-test`） | W56-02 |
| W56-05 | 五页空态自解释（`/nodes`、`/environment/topology`、`/maintenance/records`、`/environment/analysis`、`/plans`） | FR-v5.6-03 | `npm test`；五页各截图一次，文案非空且含下一步入口 | W56-02 |
| W56-06 | 台架就绪度：工作台首屏四项（设备/凭证/环境/绑定）+ 一键跳转 | FR-v5.6-04 | 就绪度接口单测；无设备时按钮直达 `/assets?tab=devices`（截图） | W56-02 |
| W56-07 | 四页可用性验证（W56-00 的结论落地为缺陷或通过） | FR-v5.6-05 | 四页各打开一次，结论落 `docs/evidence/v5.6.0/probe/` | W56-00 |

### W2 执行体验

| WBS | 任务 | 对应 FR | 验收命令 | 依赖 |
|---|---|---|---|---|
| W56-11 | 新增异步端点 `POST /api/projects/{id}/pytest/runs/async`（202 + runId），旧 `/runs` 语义不动 | FR-v5.6-12 | `cd backend && mvn -o -B -Dtest=PytestNicRunControllerTest test`；`python3 documents…/regression_gate.py` 扩展用例 | W1 |
| W56-12 | 进度端点 `GET .../{runId}/progress`（stage/total/finished/passed/failed/elapsedMs），字段单调不回退 | FR-v5.6-12 | 单测 + 真机 59 条批次轮询证据（落 `docs/evidence/v5.6.0/exec/`） | W56-11 |
| W56-13 | 取消：`POST .../{runId}/cancel`，进程 10s 内退出、状态 `CANCELLED`、已完成结果保留 | FR-v5.6-13 | 单测 + 实测（启动后 5s 取消，查无残留 pytest 进程） | W56-11 |
| W56-14 | 预检接口 + 前端一屏三查（配置/设备/安全级），缺项可跳转 | FR-v5.6-14 | 单测；未判定安全级时按钮禁用且列出编号 | W56-12 |
| W56-15 | 执行发起页（选用例集→选机→选模式→二次确认→提交→看进度→看结果） | FR-v5.6-11 | `npm test`；UI 冒烟脚本（W56-55）覆盖此路径 | W56-12 |
| W56-16 | 固定开销 ≤10s：`pytest --help` 契约探测与 inventory 渲染加缓存，配置变更即失效 | FR-v5.6-15 | 连续两次单条 fake，第二次端到端计时 ≤10s（证据落 metrics json） | W56-11 |

### W3 报告质量

| WBS | 任务 | 对应 FR | 验收命令 | 依赖 |
|---|---|---|---|---|
| W56-21 | `command_text` 采集：解析 junit `system-out/err` 中的命令约定行，落 `test_result.command_text/command_source` | FR-v5.6-21 | 重解析 59 条，非空占比 ≥90%（脚本输出统计） | 无 |
| W56-22 | 聚类键与展示名分离：`display` 取首样本原文，`clusterKey` 保留指纹 | FR-v5.6-22 | `mvn -o -B -Dtest=FailureCluster* test`；summary 响应断言含真实片段 | 无 |
| W56-23 | xlsx 增「用例明细」sheet（5 列固定） | FR-v5.6-23 | 导出后读 xlsx：sheet 数 ≥2、明细行数=用例数 | 无 |
| W56-24 | `retry=N` + `FLAKY` 标记（首次 FAIL 复测 PASS 不计对外失败） | FR-v5.6-24 | 单测（构造 FAIL→PASS 序列）+ 实测 `IM-NET-002` | W56-21 |
| W56-25 | 报告结构归并（同目录合并，正文走表格） | FR-v5.6-25 | 导出 docx 后统计二级标题数 ≤ 脚本文件数+固定章节 | 无 |
| W56-26 | 摘要 `firstSeenAt/consecutiveRuns/reproRate`（无历史返回 null） | FR-v5.6-26 | 单测 + 对 3 条用例算出值 | 无 |
| W56-27 | 导出前质量自检 `qualityGate{passed,missing[]}`，缺项标 `DRAFT` | FR-v5.6-27 | 单测（缺 metadata 时 `passed=false` 且文件标 DRAFT） | 无 |

### W4 资产与渲染

| WBS | 任务 | 对应 FR | 验收命令 | 依赖 |
|---|---|---|---|---|
| W56-41 | 资产实体（GPU/CPU/存储）+ 项目挂载 + 渲染下发 | FR-v5.6-41 | 新迁移号 `V11_56`（顺延，不改已应用）；`bash scripts/db-migrate.sh`；渲染后 `inventory.yaml` 的 `devices.gpus` 非空 | 无 |
| W56-42 | 用例库侧对齐：`IM-MIX-011` 由 ERROR 转为实际执行 | FR-v5.6-41 | 真机单跑该条，结论不再是 `UsageError: 资源池中没有可用的 GPU` | W56-41 |

### W5 运维与测试体系

| WBS | 任务 | 对应 FR | 验收命令 | 依赖 |
|---|---|---|---|---|
| W56-51 | 部署后自动校验：构建→重启→健康→`build-info` commit 与 `HEAD` 一致 | FR-v5.6-62 | `bash scripts/deploy.sh --verify`（新增开关）输出四项结论；故意不重启必须判红 | 无 |
| W56-52 | 消除假绿：性能门禁无采样不得算通过（输出 `SKIP(原因)` 并被总表计为「未判」） | FR-v5.6-63 | `python3 tools/check_perf_budget.py`（删掉采样文件后必须非 0 或明确 UNJUDGED）；`--self-test` 判红 | 无 |
| W56-53 | 效果类指标基线首次测量并归档 | FR-v5.6-65 | 三项各一份带日期证据文件；无样本写「未测」 | W2（执行链路稳定后） |
| W56-54 | 契约快照更新 | FR-v5.6-72 | `python3 tools/gen_api_contract.py --check` | W56-11 |
| W56-55 | UI 冒烟 E2E：登录→进工程→发起 fake→看报告 | FR-v5.6-73 | `node scripts/browser-regression.mjs`（或新增 e2e 脚本）跑通；失败产截图 | W2 |

---

## 3. 收口清单（每批做完必跑）

```bash
cd ~/robot-autotest
set -a; . ~/.config/robot-autotest/env; set +a

# 1) 单测
cd backend && mvn -o -B test && cd ..
cd frontend && npm test && npm run type-check && cd ..

# 2) 定向机检
python3 tools/check_nav_kind.py
python3 tools/gen_api_contract.py --check
python3 scripts/check_version_consistency.py

# 3) 全量门禁（52 步）
bash scripts/gates.sh 2>&1 | tail -5      # 只认尾部 GATES_RESULT FAILS=N

# 4) 门禁副产物还原（每次全量门禁都会重写这四份已跟踪文件）
git restore -- docs/evidence/v3.3.0/ai-eval.json \
  docs/evidence/v3.4.0/capacity-report.json \
  docs/evidence/v3.4.0/security-scan.json \
  docs/evidence/v5.0.0/ops/dora-trend.json

# 5) 文档表格机检（改了任何 md 都要跑）
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py <改动的 md 路径>
```

**判定纪律（本轮不可违反）**：

| 纪律 | 说明 |
|---|---|
| 只认尾部 `GATES_RESULT FAILS=N` | `docs/evidence/gates/logs/*.log` 是历史累积，`grep FAIL` 会把旧失败当本轮 |
| 新旧段分别登记 | 原 48 步段与新增 4 步段分别记录，**不得相加成「全绿」** |
| 构建后必须重启后端 | 否则旧进程懒加载类会 `NoClassDefFoundError`，表现为登录 30s 超时（`_ping` 仍 200，不能当健康） |
| 合并后重跑 `gen_build_info.py` 再打包 | 否则「关于」页与 build-info 报旧 commit |
| 文档补丁按内容匹配 | 绝不用行号定位 |

---

## 4. 风险与应对

| 编号 | 风险 | 触发信号 | 应对 |
|---|---|---|---|
| RK-1 | 删树后 `navTreeFor` 的引用相等断言（`toBe`）会失效 | 改完 `npm test` 红 | 给 pytest 模式返回 memo 化单例对象，`toBe` 仍成立；`App.vue` 签名不变 |
| RK-6 | 全量后多页在 pytest 工程长期空白，用户观感比隐藏更差 | 页面打开了但「没东西」 | W56-05 逐页落空态；空白页按缺陷登记（对应 C-7/R-7） |
| RK-2 | W56-11 异步化与孤儿清扫器冲突（`ExecutionOrphanSweeper` 按 DB 活跃 run 对账） | 异步提交后 60s 被 SIGTERM（`exitCode=143`） | 异步路径必须同 fake 路径一样先落 `RunRecord`（QUEUED→RUNNING） |
| RK-3 | W56-21 用例库不输出命令约定行 | 59 条 `command_text` 非空占比 < 90% | 平台侧退化为「未采集（原因）」，并把它列为外部依赖，不硬凑 |
| RK-4 | W56-41 迁移新号冲突 | `flyway validation failed` | 先查 `flyway_schema_history` 最大号（`ORDER BY installed_rank DESC`，不要用 `MAX(version)`）再顺延 |
| RK-5 | W56-52 一改，门禁可能立刻红 | `check_perf_budget` 返回非 0 | 这是**预期结果**（原来就是假绿），需先补齐采样再收口，不许改回默认通过 |

---

## 5. 交付节奏

| 阶段 | 内容 | 出口判据 |
|---|---|---|
| 阶段一 | W56-00 前置验证 + D-01~D-05 拍板 | 书面结论 + 大师确认 |
| 阶段二 | W1 + W3（可并行） | 各自批次单测绿 + 定向机检绿 |
| 阶段三 | W2 | 异步/取消/预检有真机证据 |
| 阶段四 | W4 + W5 | 迁移成功 + GPU 用例转跑 + 假绿消除 |
| 阶段五 | 全量门禁 + 四处 sha 一致 + 六份文档回写 | `GATES_RESULT FAILS=0`（或差异逐条说明） |

---

## 6. 与流程的关系

- 本计划对应流程第 ④ 道；**未获授权不进入第 ⑥ 道测试执行**。
- 编码开工前需大师确认 D-01~D-05 与 W56-00 结论。
- 每批完成后回写本计划「实际完成情况与偏差原因」（流程第 ⑦ 道）。

## 7. 实际完成情况与偏差原因（2026-11-13 第一轮，流程第 ⑦ 道）

### 7.1 已完成

| WBS | 内容 | 落地件 | 证据 |
|---|---|---|---|
| W56-00 | 四项入口可用性验证 | `docs/evidence/v5.6.0/probe/w56-00-availability.py/.json` | 四入口全 200；`/plans`、`/environment/collect`、`/assets?tab=links` 数据为空 → 归入空态需求，不记缺陷 |
| W56-01/02 | 全量菜单与唯一选树入口 | `frontend/src/config/navigation.ts`（删 `PYTEST_SUITE_NAVIGATION`，新增 `pytestWorkbenchLeaf` + `withPytestWorkbench()` memo 化）、`navTreeFor()` 单点判定 | `navigation.spec.ts` 51 例 + `App.spec.ts` 3 例；`check_navigation.py`、`check_nav_kind.py` 均 PASS（孤儿叶子 0，叶子 39） |
| W56-03 | 空态自解释 | `EmptyGuide.vue`、`DataTable.vue` 透传 `emptyAction`；Nodes / Topology / RecoveryRecords / CollectionAnalysis / Plans 五页 | 前端全量 114 文件 / 804 例全绿 |
| W56-04 | 台架就绪度 | `PytestReadinessService` + `GET /api/projects/{id}/readiness`，工作台首屏四项卡片与跳转 | `PytestReadinessServiceTest` 5 例 |
| W56-11/12/13 | 预检、异步执行、进度与取消 | `PytestNicRunController` 重写：`/precheck`、`/runs/async`(202)、`/runs/{id}/progress`（live→db→404）、`/runs/{id}/cancel`；`PytestRunProgressRegistry`（内存、单调、终态有界 50 条） | `PytestNicRunControllerTest` 15 例、`PytestRunProgressRegistryTest` 6 例 |
| W56-14/15 | 发起台与轮询 | `frontend/src/views/pytest/PytestRunLaunch.vue`、`api/pytestRuns.ts` | `PytestRunLaunch.spec.ts` 5 例 |
| W56-21 | 命令列不再恒 NONE | **真源改为框架命令索引** `<repo>/artifacts/<runId>/cases/<nodeid>/raw/*.commands.jsonl`；`PytestCommandIndex` + `PytestCommandEnricher`（report 层补录）；新增来源 `COMMAND_INDEX`；NONE 改显示「未采集（原因）」 | `PytestCommandIndexTest` 5 例；脱敏复用 `SecretRedactor` |
| W56-22 | 聚类键与展示名分离 | `FailureClusterView.Cluster.display`、`TopFailure.display/clusterKey`；摘要卡改读 `display`、`key` 仅作去重 | `FailureClusterServiceTest` 19 例、`ReportSummaryServiceTest` 7 例 |
| W56-23 | xlsx 用例明细 | `ReportExportService` 增「用例明细」sheet（编号/结论/耗时/失败原因/命令），空值写 `—`，耗时列保持数值型 | `ReportExportTest` 10 例 |
| W56-26 | 摘要复现率三字段 | `TopFailure.firstSeenAt/consecutiveRuns/reproRate`，窗口 10 轮，无历史返回 `null` | `ReportSummaryServiceTest` 3 例专测 |
| W56-27 | 导出前质量自检 | `ExportResult.qualityGate{passed,missing[],label}`；缺项降级 `DRAFT` 且文件名带 `_DRAFT` | `ExportQualityGateTest` 5 例、`ReportExportControllerTest` 7 例 |

### 7.2 第二轮已完成（W56-24/25、W56-41/42、W56-51~55）

| WBS | 内容 | 落地件 | 证据 |
|---|---|---|---|
| W56-24 | `retry=N` + `FLAKY` 标记（三条判定口径） | 迁移 `V11_57`（`retry_count` / `flaky`）、`domain/kernel/RetryMerge.java`、`PytestNicRunService` 复测循环、`PytestKernel.withRetries`、`DocxExporter` 两列、`PytestRunLaunch.vue` 复测下拉、`SuiteTree.vue` 抖动徽标 | `RetryMergeTest` 7 例；真机 run `w56-24-retry-2`（`IM-RDM-022`，`retry=1`）→ 复测 1 轮、`junit-retry-1.xml` 与首轮并存、`retry_count=1`/`flaky=0` 落库并在报告树可见：`docs/evidence/v5.6.0/w3-report/README.md` |
| W56-25 | 报告结构归并（正文走表格） | `DocxExporter.writeTree` 改「二、用例明细」单表，`flatten()` / `writeCasesTable()` / `verdictText()` | 21 条用例的 run `EHT-REAL-L0-20261113-220048-01` 导出：章节 2 个、明细单表 22 行（表头 + 21 条）；`docs/evidence/v5.6.0/w3-report/raw/docx-heading-stat.json` |
| W56-41 | 资产实体化 + 渲染下发 | **不新建资产表**：`PytestHostAssets` 从已采集的 `device_host_spec.spec_json` 派生 `gpuN`/`cpu0`/盘；`PytestGpuModelCatalog` 按用例库 `device_specs.yaml` 补 `model`/`vendor`；`HostInventoryRepository.latest()` 取完整记录；`PytestEnvRenderer` 池与 `selected_targets` 四类对齐 | `PytestEnvRendererTest` 11 例；真机 `inventory.yaml` 的 `devices.gpus/cpu/storage` 非空：`docs/evidence/v5.6.0/w4-host-assets/README.md` |
| W56-42 | `IM-MIX-011` 由 ERROR 转实际执行 | 同上（链路层修复） | 真机单跑 run `w56-42-real-005612`：**PASS**，2.65s，`reason=ok` |
| W56-51 | 部署后自动校验 | `scripts/deploy.sh --verify` 四判据（构建 commit == HEAD / 进程晚于 jar / 健康 / 运行期 version+commit），正常部署结尾也调用 | 只重建不重启 → 判据 2、4 红；重启后 4/4 PASS：`docs/evidence/v5.6.0/w5-ops/README.md` §一 |
| W56-52 | 消除假绿 | `check_perf_budget.py` 退出码 3 = UNJUDGED；`gates.sh` 单列未判并计入非绿，末尾输出 `GATES_RESULT FAILS=n UNJUDGED=m` | 删采样 → 3；`--require` → 1；`--self-test` 9 条（含「无采样必须未判」） |
| W56-53 | 效果类指标基线 | `tools/effect_metrics.py`（Top8 / create P95 / fps） | `docs/evidence/v5.6.0/effect-metrics/*.json`：Top8 100%（160 次）、create P95 44.5ms、fps **未测** |
| W56-54 | 契约快照更新 | `tools/gen_api_contract.py` 重生成 | 410 endpoints / 341 paths，`--check` 一致 |
| W56-55 | UI 冒烟 E2E | `scripts/e2e-smoke.mjs`（真实 Chrome / CDP） | 10 条断言全 PASS；顺带抓到 `/pytest-workbench` 整页空白的真缺陷（vue-i18n 把 `@` 当链接语法）并修复 |
| 追加（非 WBS） | 执行成功但报告空白 | `PytestNicRunController` 收尾自动入库 + 异步线程 `TenantContext.runAs` 接管身份 + `progress.complete` 挪到入库之后 | 三处根因依次暴露，修完 E2E 断言由「页面不报错」加强为「必须出现用例编号」；详见 `docs/evidence/v5.6.0/w3-report/README.md` §三 |

### 7.2.1 仍未做（不在本版范围）

| 项 | 状态 | 说明 |
|---|---|---|
| FR-v5.6-31 | 未启动 | 尽力项；归因要引用真实断言与目标机，本轮未投入 |
| FR-v5.6-42/43 | 未启动 | PRD 已定「后续版本」（资产巡检、inventory diff） |

### 7.3 偏差与门禁状态

- **门禁**：`GATES_RESULT FAILS=0 UNJUDGED=0`（详见测试报告 §门禁）。
  29 host_inventory 的判红经取证确认为**证据过期**（旧证据产出于 2026-11-09，
  而 v4.12.0 才给 10 类命令加 `export LC_ALL=C;` 前缀），按门禁自身设计
  重采到 `docs/evidence/v5.6.0/EW56-HI/` 后转绿，**没有改证据去凑源码**。
- **偏离计划三处（都是主动改方向，不是绕判据）**：
  1. W56-41 原计划「新迁移号 V11_56 + 新建资产实体表」，实际改为从
     `device_host_spec.spec_json` **派生**——采集是事实、渲染是投影，再建一套资产表等于
     同一份事实存两处，必然漂移。V11_56 因此未使用，`V11_57` 留给 W56-24 的两列。
  2. 命令索引原实现放在 pytest 适配包内，被 39 号机检判为「适配层反向依赖报告域」；
     已改为 report 层读取 + 控制器补录，方向纠正而非放宽判据。
  3. `selected_targets` 由「点名的网口」改为「本次授权清单」（四类对齐）：
     框架先查 `resource_pool` 再查 `selected_targets`，池里给了 gpus 而清单只写 nic，
     GPU 用例会在 fixture 抛 `PermissionError`。
- **判定纪律**：只认尾部 `GATES_RESULT`；未 bump `VERSION`（仍 5.0.0），未判 G 门，不得对外称 GA。

### 7.4 大师复核结论（2026-11-14）

复核按 PRD §13 六条判据逐条走，本轮先落定争议最大的一条，其余两条待大师逐项拍板。

| 序 | 复核项 | 结论 | 落地动作 |
|---|---|---|---|
| 1 | pytest 入口「跑完即入库」：执行结束自动写 `test_result`，`fake` 同样入库 | **认可**（大师 2026-11-14 06:57 口头确认） | 行为保留不改。判据是「报告页读库不读 junit」，不入库即「跑完但报告空白」。副作用（大批次同步入库拉长响应）转性能项，不再作为设计争议，见 `05-TEST-REPORT.md` L-56-06 |
| 2 | TC-03 / TC-06 以「同源单测 + 探针」替代人工截图 | 待复核 | 报告中已明确**不按满分计**，大师驳回即补做人工对照 |
| 3 | L-56-03 真机批次内取消未实测 | 待复核 | 需按测试计划 T-02 授权在真机批次中中断一次 |

第 1 条被认可后的连带影响：本版执行链路的验收口径从「跑出 junit」升级为
「跑完即可在报告页看到用例」——E2E `e2e-09` 的断言因此从「页面不报错」改为
「必须出现用例编号」，这条断言今后作为该行为的守卫，不允许回退。

