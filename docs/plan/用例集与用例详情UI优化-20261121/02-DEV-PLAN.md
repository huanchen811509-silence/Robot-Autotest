# 开发计划：用例集页面形态、详情继承、pytest 集执行与报告口径

> 日期：2026-11-21 ｜ 上游：`01-PRD.md`（`FR-UIE-01 ~ 13`）
> 编号：`W-UIE-xx`（临时）；不 bump `VERSION`
> 排序原则：先能看见（A）→ 再能跑（C）→ 再能信（D）→ 最后补数据（B）

---

## 0. 实施顺序与批次

| 批次 | WBS | 依赖 | 可独立验收 |
|---|---|---|---|
| A 页面形态 | W-UIE-01 ~ 06 | 无（纯前端） | 是：三档视口截图即可判 |
| C 执行分派 | W-UIE-07 ~ 09 | A 的集页可点开抽屉（不必等） | 是：接口 + run 记录 |
| D 报告口径 | W-UIE-10 ~ 13 | C 产生的 run 作为样本 | 是：同一份 junit 前后对照 |
| B 详情继承 | W-UIE-14 ~ 17 | A 的详情展示位 | 是：dry-run + 抽样逐字比对 |
| 收口 | W-UIE-18 ~ 19 | 全部 | 门禁 + 回写 |

> **为什么 B 最后**：回填是数据治理，一旦先做，A 批的详情改造会二次触碰同一组件；而 D 批的样本（10 条 skip）现在就有，早做早止损。

---

## 1. A 批 · 页面形态

### W-UIE-01 用例集「全宽列表 + 抽屉」壳

| 项 | 内容 |
|---|---|
| 改动 | 新增 `frontend/src/components/CaseSetFullListShell.vue`：上半为列表容器（宽度 100%），右侧为 `NModal`/自定义抽屉（宽度 `min(900px, 92vw)`，Esc 关闭，带 `aria-modal`）。保留 `CaseSetWorkspace.vue` 不删（其它入口与既有 spec 仍在用），只是在两个用例集页不再使用 |
| 判据 | 三档视口（1366/1440/1920）下 `列表宽度 ≥ 900px`；抽屉内无横向滚动 |
| 验收 | `node scripts/ux-probes/journey.mjs --route /cases/sets` + 截图 `docs/evidence/ui-eval-20261121/after/` |
| 估时 | 1.0 人日 |

### W-UIE-02 项目用例集页改造（`CaseSetsView.vue`）

| 项 | 内容 |
|---|---|
| 改动 | ① 去掉 `#nav` 里的 `DataTable` 全量与全选条，改为「简洁行列表」（6 列：代号/名称/成员数/覆盖/状态/操作）；② 分页与搜索留在列表底部；③ 「平台用例集库」卡片移到页头入口（打开二级抽屉或跳 `/g/case-sets`）；④ 行点击 → 打开抽屉（`?set=<id>`） |
| 判据 | PRD UIE-01/02/06；行高 ≤ 56px；每行 1 个主操作 |
| 验收 | `cd frontend && npm run test -- CaseSetsView`；`node scripts/ux-probes/set-row-actions.mjs` |
| 估时 | 1.5 人日 |

### W-UIE-03 全局用例集页改造（`GlobalCaseSetsView.vue`）

| 项 | 内容 |
|---|---|
| 改动 | ① 同上换成全宽列表 + 抽屉；② 删除 `.set-row/.member-row` 两套固定列 grid（`:518-533`），改用新壳的列表样式；③ 成员详情从 4 项扩为 `CaseDetailPanel`（`source='global'`），彻底去掉字段裁剪 |
| 判据 | PRD UIE-01/03；集名可见；成员行高 ≤ 56px；详情含预置条件/步骤/预期 |
| 验收 | 截图 + `npm run test -- GlobalCaseSetsView` |
| 估时 | 1.5 人日 |

### W-UIE-04 成员选择器（替代数字 ID 输入）

| 项 | 内容 |
|---|---|
| 改动 | 新增 `frontend/src/components/CaseSetMemberPicker.vue`：关键词 + 分类 + 脚本状态筛选、多选、一次提交；后端复用 `POST /{id}/items/batch`（项目侧已存在）；全局侧补 `POST /api/platform/case-sets/{id}/items/batch`（现只有单条 `addItem`） |
| 判据 | PRD UIE-04：选 10 条一次加完，`seq_no` 连续，重复计 `skipped` |
| 验收 | 接口：`added=10/skipped=0`；再提交同 10 条 → `added=0/skipped=10` |
| 估时 | 1.5 人日（含全局侧后端批量端点 0.5） |

### W-UIE-05 脚本状态与覆盖率装配

| 项 | 内容 |
|---|---|
| 改动 | ① 全局集成员接口补 `bindingState/scriptPath/nodeId`（对齐 `TestSetController.items` 的 `CaseListView` 装配）；② 删除 `GlobalCaseSetsView.vue:393-395` 的硬编码「不适用」；③ 集列表补 `已绑定 X/Y` 聚合（一条 SQL，`case_script_binding` 按 `case_id in (...)`） |
| 判据 | PRD UIE-05：三处文案逐字相同；覆盖率数字与绑定表一致 |
| 验收 | 抽查 5 条：`select count(*) from case_script_binding where case_id=?` 与页面一致 |
| 估时 | 1.0 人日 |

### W-UIE-06 空态与主操作唯一化

| 项 | 内容 |
|---|---|
| 改动 | 两页各保留 1 空态 + 1 主按钮；平台库入口改为链接；删除重复「新建用例集」按钮 |
| 判据 | PRD UIE-06 |
| 验收 | 空项目截图：按钮数 = 1 |
| 估时 | 0.5 人日 |

---

## 2. C 批 · pytest 用例集执行

### W-UIE-07 用例集执行按内核分派

| 项 | 内容 |
|---|---|
| 改动 | `TestSetRunService.run()`：`cases` 解析前先读 `project.test_kernel`；`pytest` 走新增 `PytestTestSetResolver`（读 `case_script_binding` 得 nodeId），`robot` 走原 `CaseScriptResolver`；未绑定文案按内核切换 |
| 判据 | PRD UIE-09：pytest 集合产生 `kernel='pytest'` 的 run；robot 集合行为不变（回归 `TC-v5.23-17`） |
| 验收 | 样本集 id=108（7 条已判安全级）→ 202/200 + run `total_tests=7` |
| 估时 | 2.0 人日 |

### W-UIE-08 安全闸在同一入口复用

| 项 | 内容 |
|---|---|
| 改动 | pytest 分支复用 `SafetyLevel.blocksExecution()` 与 `PytestNicRunController` 的 real 闸（`confirmReal` + ADR-0013），不新造判断 |
| 判据 | LX 集合仍 403；real 无 `confirmReal` 仍拒；fake 不要求 |
| 验收 | 集 id=107 → 403 且列出 3 条 LX；补 `confirmReal=true` 走 real 仍 400（未绑 nodeId 时 fail-closed） |
| 估时 | 0.5 人日 |

### W-UIE-09 安全级角标与 403 结构化回执

| 项 | 内容 |
|---|---|
| 改动 | ① 集行/成员行角标 `LX N 条`；② 403 回执 `data.blocked=[{caseCode,safetyLevel}]`（现只有拼接字符串）；③ 前端把清单渲染成可点条目 |
| 判据 | PRD UIE-10 |
| 验收 | `curl` 回执含 `blocked` 数组；页面可点进定级页 |
| 估时 | 1.0 人日 |

---

## 3. D 批 · 报告口径

### W-UIE-10 进度计数纳入 skipped

| 项 | 内容 |
|---|---|
| 改动 | `PytestNicRunService.countJunit()` 返回 `[passed, failed, skipped, total]`，`passed = total - failed - skipped`；进度 DTO 加 `skipped` |
| 判据 | PRD UIE-11：10 条 skip 的 junit → `passed=0 skipped=10` |
| 验收 | 用 `docs/evidence/ui-eval-20261121/junit.xml` 做单测夹具（已有真实样本） |
| 估时 | 0.5 人日 |

### W-UIE-11 结论判定表

| 项 | 内容 |
|---|---|
| 改动 | `ReportSummaryService:98-100` 改为判定表；新增枚举值 `INCONCLUSIVE`；报告详情页/列表徽章读同一字段 |
| 判据 | PRD UIE-12；`pass=0 skip=10 → 无有效结论` |
| 验收 | 同一 run 的三个接口返回值一致（脚本断言 `detail.criteriaConclusion`/`summary.conclusion`/列表徽章） |
| 估时 | 1.0 人日 |

### W-UIE-12 跳过原因入库与展示

| 项 | 内容 |
|---|---|
| 改动 | `PytestResultNormalizer` 在 `verdict=SKIP` 时把 `<skipped message>` 写入 `test_result.failure_msg`（**不新增列**）；报告页 SKIP 行展示该文本；删除"未采集跳过原因"风险文案的固定分支 |
| 判据 | PRD UIE-13 第 1–2 条 |
| 验收 | 报告页每条 SKIP 可见「Plan-only：AW 未真实执行，跳过功能断言」 |
| 估时 | 1.0 人日 |

### W-UIE-13 导出门禁与表单同源

| 项 | 内容 |
|---|---|
| 改动 | `PytestRunLaunch.vue` 的 `META_FIELDS` 由 7 项扩为 `MetadataGate.REQUIRED` 10 项（补 `STARTED_AT`、`CASE_KIND`；`RUN_ID` 自动）；提交前校验，缺项提示"本次报告不可对外交付"（不阻断执行） |
| 判据 | PRD UIE-13 第 3–4 条 |
| 验收 | 全填 10 项后 `exportable=true`（外部门禁回落）；只填 7 项时提交前有提示 |
| 估时 | 1.0 人日 |

---

## 4. B 批 · 详情继承

### W-UIE-14 回填批次表（迁移）

| 项 | 内容 |
|---|---|
| 改动 | 新增 `db/migration/V11_xx__case_backfill_batch.sql`：`id / source_project_id / target_project_id / hit_count / skip_count / operator / created_at`；另给 `test_case` 增 `inherited_from` bigint NULL（**仅这一列**，用于来源标记） |
| 判据 | 迁移可重复执行（Flyway 版本号唯一）；不改既有列 |
| 验收 | `bash scripts/db-migrate.sh` 后 `installed_rank` 递增，表存在 |
| 估时 | 0.5 人日 |

### W-UIE-15 回填服务（dry-run + 幂等 + 不覆盖）

| 项 | 内容 |
|---|---|
| 改动 | `CaseBackfillService`：按 `(project_id, case_code)` 命中公共库（`project_id=88`）；只填 `precondition/expected/case_kind` 与 `test_case_step`；目标非空即跳过；每 100 条一批事务；写 `inherited_from` |
| 判据 | PRD UIE-07 全部 6 条；基线 `将填充 414 / 跳过 71` |
| 验收 | dry-run 输出与基线一致；二次执行 `将填充 0`；抽样 10 条逐字比对一致 |
| 估时 | 2.0 人日 |

### W-UIE-16 回填端点

| 项 | 内容 |
|---|---|
| 改动 | `POST /api/projects/{id}/case-backfill/preview` 与 `/apply`；写操作需 `CASE:UPDATE` |
| 判据 | 只读用户 403；非 pytest 工程需显式 `projectId` |
| 验收 | `curl`：preview 返回计数与原因分布；apply 返回批次号 |
| 估时 | 0.5 人日 |

### W-UIE-17 详情三态文案与来源行

| 项 | 内容 |
|---|---|
| 改动 | `CaseDetailPanel.vue` 内容组加来源行；`text()` 与 `emptyValue()` 之外新增 `notInherited()`；本地覆盖徽章读 `source` 字段 |
| 判据 | PRD UIE-08 三条 |
| 验收 | 三个样本：已回填 / 未回填但公共库有值 / 本地改过 |
| 估时 | 1.0 人日 |

---

## 5. 收口

### W-UIE-18 门禁与证据

| 项 | 内容 |
|---|---|
| 改动 | 三档视口截图入 `docs/evidence/ui-eval-20261121/after/`；组件 spec 更新；`gates.sh` 全量 |
| 判据 | `FAILS=0`；`check_component_imports.py` / `check_i18n` / `check_md_tables.py` PASS |
| 验收 | `bash scripts/gates.sh` 退出码 0 |
| 估时 | 1.0 人日（含跑门禁与副产物还原） |

### W-UIE-19 文档回写（第 7 环）

| 项 | 内容 |
|---|---|
| 改动 | 评估报告 §3 补"实测推翻/确认"；PRD 每条 FR 标达成/降级；本计划补实际偏差；TEST-PLAN 补实测结果与遗留 |
| 判据 | 四份文档都有"修订记录"段 |
| 估时 | 0.5 人日 |

---

## 6. 合计与排期建议

| 批次 | 人日 |
|---|---|
| A（01–06） | 7.0 |
| C（07–09） | 3.5 |
| D（10–13） | 3.5 |
| B（14–17） | 4.0 |
| 收口（18–19） | 1.5 |
| **合计** | **19.5 人日** |

**建议切分**：A+D 先合并交付（10.5 人日，解决"看不见"和"不可信"），C 单独一次（3.5），B 最后（4.0）。

---

## 7. 验收命令清单（可直接复制）

| WBS | 命令 | 期望 |
|---|---|---|
| 全部前端 | `cd frontend && npm run test` | 0 failed |
| 全部前端 | `cd frontend && npm run build` | 0 error（`vue-tsc` 通过） |
| UIE-01/02/03 | `node scripts/ux-probes/journey.mjs`（或页面对应探针） | 0 FAIL |
| UIE-07 | `curl -s -H "Authorization: Bearer $TOKEN" -X POST "$APP/api/execution/test-sets/108/run?projectId=95"` | 200 且 `data.kernel=pytest` |
| UIE-09 | 同上对集 107 | 403 且 `data.blocked` 长度=3 |
| UIE-10 | `node -e` 读 `docs/evidence/ui-eval-20261121/junit.xml` 走 `countJunit` 单测 | `passed=0 skipped=10` |
| UIE-11 | `curl "$APP/api/reports/<runId>/summary?projectId=95"` | `conclusion=INCONCLUSIVE` |
| UIE-13 | 表单全填 10 项后 `curl "$APP/api/reports/<runId>?projectId=95"` | `exportable=true` |
| UIE-15 | `curl -X POST .../case-backfill/preview` | `fill=414 skip=71`，二次 `fill=0` |
| 门禁 | `set -a; . ~/.config/robot-autotest/env; set +a; bash scripts/gates.sh` | `FAILS=0` |
| 文档 | `python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/用例集与用例详情UI优化-20261121/*.md` | 0 error |

---

## 8. 偏差与风险处置

| 风险 | 处置 |
|---|---|
| 回填误覆盖 | 只填空字段 + `inherited_from` 标记；先 dry-run 交大师看一眼再 apply |
| pytest 分派绕过安全闸 | 复用同一 `SafetyLevel` 判定，real 仍需 `confirmReal`；专项 TC |
| 报告结论改动影响历史 | 只对新入库 run 生效，历史快照不重算 |
| 门禁期间改工作树造成假红 | 跑门禁前 `git status` 干净；收口前还原四份已跟踪副产物 |

---

## 9. 实施状态（2026-11-21）

| WBS | 状态 | 实际改动 |
|---|---|---|
| W-UIE-01 | 达成 | 未新造壳组件，改为给 `CaseSetWorkspace` 加 `drawer` 开关（两页恒真），列表铺满、抽屉浮层 |
| W-UIE-02 | 达成 | `CaseSetsView` 列表换 5 列静态表；编辑/立即执行/删除进「更多」；筛选改工具栏折叠区（保留 URL 直达） |
| W-UIE-03 | 达成 | `GlobalCaseSetsView` 重写版式；成员详情改浮层（`CaseDetailPanel source=global`） |
| W-UIE-04 | 达成 | 新增 `member-picker` 选择器；复用 `POST /{id}/items` 批量端点 |
| W-UIE-05 | 降级 | 项目页真实渲染；全局页去列改说明（理由见评估报告 §9.4） |
| W-UIE-06 | 达成 | 空态唯一；平台库 `<details>` 折叠 |
| W-UIE-07 | 达成 | `TestSetRunService` 分流 + `PytestSetRunLauncher`（同步执行 + 产物登记 + 入库 + 证据） |
| W-UIE-08 | 达成 | 复用 `SafetyLevel` 与 real 闸；单测 8 条通过 |
| W-UIE-09 | 部分 | 403 结构化清单达成；集行 LX 角标未做（进遗留） |
| W-UIE-10 | 达成 | `countJunit` 四元组 + 进度快照 `skipped` |
| W-UIE-11 | 达成 | 新增 `RunConclusion`；详情/摘要/列表同源 |
| W-UIE-12 | 达成 | `PytestKernel` 取 `<skipped message>`；入库加「跳过：」前缀 |
| W-UIE-13 | 达成 | 表单 9 项 + RUN_ID 自动；缺项提示 |
| W-UIE-14 | 达成 | `V11_94`（表+列）+ `V11_95`（审计列）+ 两份 down |
| W-UIE-15 | 达成 | `CaseBackfillService`：只填空字段、无内容可填即跳过、幂等 |
| W-UIE-16 | 达成 | preview/apply 两个端点，写操作要 `CASE:UPDATE` |
| W-UIE-17 | 达成 | 来源行 + 三态文案 + 本地覆盖徽章 |
| W-UIE-18 | 达成 | 门禁 `FAILS=0`；前端 1272 单测通过；探针三档全绿 |
| W-UIE-19 | 达成 | 本节与四份文档回写 |

**偏差**：19.5 人日估算未按日历执行（当日连续完成）；实际新增 1 个应用服务、1 个控制器、
2 条迁移、4 个工具白名单/判据调整，未改 `test_set` / `test_case` 之外的既有列。
