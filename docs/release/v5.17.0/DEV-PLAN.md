# v5.17.0 开发计划（DEV-PLAN）：缺口闭合（一）取证与接线

> 流程位置：④ 开发计划。**无开发计划不许开工编码**。
> 上游：`PRD.md`（7 FR + 8 NFR）、`01-ASSESSMENT.md`（大师 D-1~D-4 拍板）。
> 本文件给 WBS 编号、改动面与**可复制的验收命令**；不给「大约几天」，只给依赖关系与可独立验收的边界。
> 数据库为 MySQL / MariaDB：用 `scripts/db-audit.sh` / `db-migrate.sh` 与 `mysql` CLI，**不用 `psql`**。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | 取证先行 | W67-01 ~ W67-03 | 是 | **不依赖任何代码改动**，第一天可并行开跑 |
| W2 | 向导补步 | W67-04 ~ W67-07 | 是 | 与 W3 改动面不重叠 |
| W3 | 采集与画像 | W67-08 ~ W67-11 | 是 | 与 W2 并行 |
| W4 | 契约与收口 | W67-12 ~ W67-13 | 否（最终波） | 依赖 W1 ~ W3 全部出口判据 |

**并行纪律**：W1 的三条与代码解耦，先跑；W2 只动 `ProjectWizardView` 与 `WizardContentPicker`；
W3 只动采集历史与画像仓储；两处不重叠，可同时推进。
W4 收口前必须完成「本版造数自清」（NFR-08），否则会把残留带进 5.18 的清场。

**本版零迁移**（最新 `V11_78`）；若 FR-02 的 kind 校验需要落列，从 `V11_79` 起并配 `.down.sql`。

---

## 2. W1 取证先行（W67-01 ~ W67-03）

### W67-01 演示对象 purge 覆盖取证

| 项 | 内容 |
|---|---|
| 目标 | 造 1 个 `is_demo=1` 演示项目（2 用例集 / 10 用例 / 1 报告）→ dry-run 覆盖 13 个对象 |
| 改动面 | `scripts/ops/purge-demo.sh`：补 `test_set` 统计、加 `--json` 逐条清单、加从属数据前置门禁、修正「走接口」指引（**实测必需，见 §10 回写**）；证据落 `docs/evidence/v5.17.0/demo/` |
| 出口判据 | ① 清单含 13 对象；② dry-run 前后逐表计数一致；③ 真实项目不出现；④ `--apply` 后 `is_demo=1` 回 0 |
| 验证命令 | `bash scripts/ops/purge-demo.sh`；`PURGE_DEMO_CONFIRM=YES bash scripts/ops/purge-demo.sh --apply` |
| 依赖 | 运行中后端 + 开发库 |

### W67-02 套件数三样本与深链取证

| 项 | 内容 |
|---|---|
| 目标 | 造 R1（单套件）/ R2（多套件）/ R3（含空 `case_key`）三个 run，`suite_count` 落真值；列表与详情树逐条比对 |
| 改动面 | 夹具走**真实上报链路**（`POST /api/agent/result`）；补前端深链断言（`SuiteTree.spec.ts` / `ReportDetailView.spec.ts`）；证据落 `docs/evidence/v5.17.0/report/` |
| 出口判据 | ① R1/R2 非 NULL 且等于一级套件数、R3 为 NULL；② 列表数 = 详情树一级子节点数逐条相等；③ 历史 NULL 显示「—」；④ 深链高亮 + 自动滚动；⑤ `robot_dev` 行数回到基线 |
| 验证命令 | `npx vitest run src/components/report/SuiteTree.spec.ts src/views/ReportDetailView.spec.ts src/views/ReportsView.spec.ts` |
| 依赖 | 运行中后端 + 真实登录态（**`robot_it` 不可用，改在本库隔离项目做**，见 §10） |

### W67-03 长列表与分类树性能读数

| 项 | 内容 |
|---|---|
| 目标 | 1000 行数据集（每页 100 行）fps ≥50；3000 分类展开 P95 ≤500ms |
| 改动面 | `scripts/perf-longlist.mjs` 加 `--route cases` 与分类展开打点；`tools/perf_longlist.py` 汇总 |
| 出口判据 | ① ≥3 轮字段齐全；② `min_fps ≥ 50` 才 PASS；③ 展开 P95 读数；④ `--selfcheck` 仍判红；⑤ manifest 含行数 + sha256 |
| 验证命令 | `node scripts/perf-longlist.mjs --route cases --rows 100 --dataset 1000 --rounds 3 --seconds 3 --out docs/evidence/v5.17.0/perf/longlist-raw.json`；`python3 tools/perf_longlist.py --selfcheck` |
| 依赖 | 浏览器 + PERF 数据集 —— **实测不具备，判 BLOCKED（见 §10 与 `evidence/v5.17.0/perf/03`）** |

---

## 3. W2 向导补步（W67-04 ~ W67-07）

### W67-04 第 1 步接真模板列表

| 项 | 内容 |
|---|---|
| 目标 | 第 1 步由静态 `PROJECT_TEMPLATES` 改 `templateApi.list()` |
| 改动面 | `views/ProjectWizardView.vue`（`:24` 静态 import 降为 fallback）、`api/globalTemplates.ts`、spec |
| 出口判据 | ① 网络日志见 `GET /api/global/templates`；② 新建模板出现在第 1 步；③ 空列表有「去新建模板」；④ 后端不可用回退静态卡并标「离线模板」 |
| 验证命令 | `cd frontend && npx vitest run src/views/ProjectWizardView.spec.ts -t 模板` |
| 依赖 | — |

### W67-05 模板详情带出内核与计数

| 项 | 内容 |
|---|---|
| 目标 | 选中后 `templateApi.detail(id)` 带出 `kernel` + 四类计数；第 3 步默认内核随之设置 |
| 改动面 | `ProjectWizardView.vue`（`selectTemplate` 改异步详情）、复核步计数、spec |
| 出口判据 | ① pytest 模板 → 第 3 步 pytest；robot → robot；② 计数与详情一致；③ 双内核模板允许切换 |
| 验证命令 | `cd frontend && npx vitest run src/views/ProjectWizardView.spec.ts -t 内核` |
| 依赖 | W67-04 |

### W67-06 场景分区与 kind 校验（D-4 路径 A）

| 项 | 内容 |
|---|---|
| 目标 | `WizardContentPicker` 增 `scenario` 分区；场景 id 仍走 `caseSetIds`；服务端按 `test_set.kind` 分流并校验 |
| 改动面 | `components/WizardContentPicker.vue`、`ProjectWizardView.vue` 提交体、后端内容关联服务（kind 校验）、spec |
| 出口判据 | ① 场景分区可选可计数；② 2 集 + 5 例 + 2 场景 → 合计 9 项；③ 误把 `case_set` 标为场景 → 400 点名 kind；④ 两向导行为一致 |
| 验证命令 | `cd frontend && npx vitest run src/components/WizardContentPicker.spec.ts`；`cd backend && mvn -o -B -Dtest=ProjectContentKindTest test` |
| 依赖 | W67-05 |

### W67-07 原子快照语义

| 项 | 内容 |
|---|---|
| 目标 | 建项目后改源集成员 → 已建项目成员不变（用断言钉住，不是改代码） |
| 改动面 | 后端快照路径若已是「按 id 展开」则只补断言；否则补快照写入 + spec |
| 出口判据 | ① 源集 +3 → 项目成员不变；② 有 spec 锁住；③ 若产品要「跟随源」须显式开关并标注 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ProjectTemplateServiceTest test` |
| 依赖 | W67-06 |

---

## 4. W3 采集与画像（W67-08 ~ W67-11）

### W67-08 采集历史掩码凭证名

| 项 | 内容 |
|---|---|
| 目标 | 历史行加 `credentialId` / `credentialMaskedName`；SQL 左连最新 `credential_verification` + `credential.name` 并掩码 |
| 改动面 | `DeviceCollectProfileController`（SQL + DTO）、前端历史面板列、i18n、spec |
| 出口判据 | ① 两次不同凭证采集分别指向；② 无事实显示「未记录」；③ 响应不含 `secretRef`/`secret_ref`/明文 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=DeviceCollectProfileTest test` |
| 依赖 | — |

### W67-09 泄漏探针与反向回放

| 项 | 内容 |
|---|---|
| 目标 | 六处零泄漏复跑；在 `876da9b` 上回放证明旧版无该字段 |
| 改动面 | 探针参数化 + 证据 |
| 出口判据 | ① `probe_oneshot_secret` 6/6 PASS；② 回放日志显示旧响应无该字段；③ DTO 字段评审记录 |
| 验证命令 | `python3 tools/probe_oneshot_secret.py` |
| 依赖 | W67-08 |

### W67-10 500 台压测样本与计时

| 项 | 内容 |
|---|---|
| 目标 | 造 500 台 `PERF-` 设备 → `collect-profiles` P95（≥5 次） |
| 改动面 | `tools/perf_seed.py` 扩设备规模参数 + 计时脚本 + 证据 |
| 出口判据 | ① P95 读数；② 既有工程/用例计数前后不变；③ `--purge` 后残留 0 |
| 验证命令 | `python3 tools/perf_seed.py --devices 500 --project PERF-PROFILE`；`bash scripts/db-audit.sh` |
| 依赖 | 独立压测项目 |

### W67-11 聚合 SQL 优化（条件触发）

| 项 | 内容 |
|---|---|
| 目标 | 若 P95 >800ms：改「1 次主表 + 2 次分组聚合 + 应用层合并」，不建物化表 |
| 改动面 | `DeviceCollectProfileRepositoryJdbc.SELECT_SQL` 拆分 + 合并 + 单测 |
| 出口判据 | ① 前后响应逐字段一致；② P95 ≤800ms；③ 单测断言查询次数（无 N+1） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=DeviceCollectProfileQueryTest test` |
| 依赖 | W67-10（**未触发则记「不执行」并附读数**） |

---

## 5. W4 契约与收口（W67-12 ~ W67-13）

### W67-12 契约与造数自清

| 项 | 内容 |
|---|---|
| 目标 | 契约重生成并 `--check` PASS；本版造的演示/压测数据全部清到残留 0 |
| 改动面 | `docs/contracts/openapi.json`（WSL 内重生成）；清理命令 + 证据 |
| 出口判据 | ① `--check` PASS；② `endpoints/paths` 读数写进测试报告；③ `is_demo=1` 只剩 5.18 待清对象；④ `PERF-` 项目与 500 台设备残留 0 |
| 验证命令 | `python3 tools/gen_api_contract.py --check`；`mysql ... -e "SELECT COUNT(*) FROM device WHERE name LIKE 'PERF-%'"` |
| 依赖 | W67-06、W67-08 |

### W67-13 门禁全量与文档回写

| 项 | 内容 |
|---|---|
| 目标 | `FAILS=0 UNJUDGED=0`；六份产物齐全；台账 13 行状态更新 |
| 改动面 | `docs/release/v5.17.0/*`（从 `_draft/` 迁入）、`评估报告` §6.1 台账 |
| 出口判据 | ① 门禁 `FAILS=0`；② `check_release_docs.py v5.17.0` PASS；③ 六份表格机检 PASS；④ 台账 7 行转「已关账/明示降级/BLOCKED」，另 6 行改归属 5.18 |
| 验证命令 | `bash scripts/gates.sh`；`python3 tools/check_release_docs.py v5.17.0`；`python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/release/v5.17.0/*.md` |
| 依赖 | W1 ~ W3 全部 |

---

## 6. 接口与数据变更清单

| 变更 | 类型 | 影响 | 处理 |
|---|---|---|---|
| 采集历史响应加 `credentialId`/`credentialMaskedName` | 响应新增字段 | 只读，老前端忽略 | 契约重生成 |
| 项目内容关联服务按 `test_set.kind` 校验 | 语义收紧 | 误标 kind 由静默接受变 400 | 契约不变，补单测 |
| 数据库 | **预期零迁移** | — | 若必须，从 `V11_79` 起 |

---

## 7. 风险与应对

| # | 风险 | 触发 | 应对 |
|---|---|---|---|
| R1 | 凭证名泄露 | DTO 回整名或 `secret_ref` | 只回掩码；六处探针复跑 |
| R2 | 500 台污染统计 | 未用 `PERF-` 隔离 | `--purge` + 前后计数 |
| R3 | 隔离库差异掩盖问题 | `robot_it` 与源库 10 表差异 | 先记录差异基线，只看相对变化 |
| R4 | 性能口径走回老路 | 用 harness 1000 行同屏冒充 | 判据写死「每页 100 行」 |
| R5 | 场景 kind 混淆 | 服务端不校验 | 按 kind 分流 + 400 点名 |
| R6 | 本版造数带进 5.18 清场 | 收口前没清 | W67-12 强制「残留 0」才允许收口 |

---

## 8. 与相邻版本的边界

1. 本版**不做**导入向导、行数收敛、拓扑三件、运维真机动作 —— 全部在 5.18。
2. 本版**不清理未打标的临时账号与项目** —— 归 5.18 FR-07（清场要等全部开发结束）。
3. 本版**不动 `update.sh`**、不改导航结构、不加菜单。

---

## 9. W1 执行回写（2026-11-16 08:04 ~ 08:44）

| WBS | 计划 | 实际 | 偏差与原因 |
|---|---|---|---|
| W67-01 | 造演示对象 → dry-run 覆盖 13 对象 | **完成**，四项判据全 PASS；但查出并修复 4 处缺陷 | 计划写「无产品代码」，实际必须改脚本——不改则判据 1/3 落不了地、`--apply` 在真实数据上 1451 |
| W67-02 | `robot_it` 造三样本 + 深链截图 | **完成核心判据**（列表 = 树逐条相等）；截图降级为组件断言 | `robot_it` 不可用（假哈希 + 落后 14 迁移）→ 改本库隔离项目；无浏览器 → 截图降级 |
| W67-03 | 1000 行 fps + 3000 分类展开 | **BLOCKED** | 环境无 Chrome/CDP/Playwright，脚本自报「环境不可用」；不拿 harness 口径顶替 |

### 9.1 W67-01 查出的 4 处缺陷（均已修，代码在 `scripts/ops/purge-demo.sh`）

| 编号 | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| D-67-01-1 | 只统计 4 类从属表，**漏 `test_set`** | 13 个对象里 2 个用例集不在覆盖范围 | 补 `demo_sets` 统计与输出 |
| D-67-01-2 | 只打印计数，**无对象清单** | 「清单可人工复核」判据落不了地 | 新增 `--json` 逐条清单（只读，`--apply` 也先输出再退出） |
| D-67-01-3 | 有从属数据时 `DELETE FROM project` **1451 失败**，且成员关系已删 → **半删状态** | 库进入不可重跑的状态 | 加前置门禁：任一从属表 > 0 → `exit 4` 并打印逐表条数 |
| D-67-01-4 | 注释指引「走项目删除接口」，但该接口有内容时 **409 只许归档** | 把人引到死路，且误以为接口能级联 | 改为「先清空用例/报告/执行，再跑本脚本」，并写明 409 事实 |

外键普查结论：**26 张表引用 `project(id)`，仅 `project_asset` / `project_group_role` 是 CASCADE**，
其余 24 张 RESTRICT。正确删除顺序：`test_report → test_result → run → test_case → test_set → 再跑脚本`。

### 9.2 两处口径变更（已同步 PRD / TEST-PLAN）

| # | 原口径 | 改为 | 依据 |
|---|---|---|---|
| C-1 | W67-02 在 `robot_it` 造夹具 | 在本库建隔离项目（`W67-02-SUITE`），收尾清零并逐表回到基线 | `robot_it` 无可用账号且 schema 落后，改造它比在本库做可逆隔离更贵 |
| C-2 | 三样本 `suite_count` **均非 NULL** | R1/R2 非 NULL；**R3 必须为 NULL** | R3 的设计目的就是验 NULL 语义；要求它也非 NULL 与设计自相矛盾 |

### 9.3 对 5.18 的硬影响（必须带过去）

| 影响 | 说明 |
|---|---|
| 清场要「先清内容」 | 10 个候选项目里 8 个有内容；只调 `purge-temp.sh` 一定 1451 |
| 需要内置删除顺序 | `purge-temp.sh` 必须按 §9.1 的顺序清内容，否则重演半删 |
| 「归档」≠「删除」 | 项目删除接口对有内容项目 409，对外口径不能混用 |
| 清场脚本要 `--json` | 5.18 判据要求「清单逐对象可复核」，沿用本次的 `--json` 实现 |

### 9.4 遗留

| 编号 | 内容 | 状态 |
|---|---|---|
| L-67-04 | 深链真视口截图（滚动到位、高亮对比度） | 待有浏览器环境补证 |
| L-67-05 | 长列表 fps 与 3000 分类展开读数（FR-04） | BLOCKED，前置清单见 `evidence/v5.17.0/perf/03` |

---

## 10. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-16 | 首版：7 波 26 WBS（单版方案） | 首轮评估 |
| 2026-11-16 | **按 D-1 拆版重写**：本版 4 波 13 WBS（取证 3 + 向导 4 + 采集 4 + 收口 2）；新增 W67-12「造数自清」；D-4 定路径 A | 大师 2026-11-16 拍板 |
| 2026-11-16 | **W1 执行回写**：W67-01 完成并修 4 缺陷；W67-02 完成核心判据、截图降级；W67-03 BLOCKED；新增口径变更 C-1 / C-2 | W1 实测 |
