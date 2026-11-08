# SCEN-04 证据 · ED202 一级分类→场景对照（v2.2 / 2026-11-08）

判据：用例集条目数与 7 sheet 实测相等；730 / 83 分账可见；场景数等于对照表行数。
对应定案 `docs/plan/评审结论与拍板定案-v2.2-20261108.md` §4 C4、C5（P-3 / P-4）。

## 1. 落地内容（真实编码，非占位）

- `tools/ed202_organization.py`（新增，纯标准库 `zipfile` + `xml.etree.ElementTree`，系统 python3 无 openpyxl）：逐 sheet 找表头行，提取「用例编号 / 一级分类 / 二级分类」三列；适配差异表头（`测试编号` / `测试用例编号` / `测试分类`），适配集无二级分类列时记 None。所有计数由 xlsx 真实算出，不硬编码 730/83/813。
- `docs/plan/ED202一级分类-场景对照表-20261108.md`（脚本产出）：每行 = 一级分类 → 场景名 → 来源 sheet → 该来源用例数 → 合并依据；跨 sheet 同名一级分类合并为一行并写明依据。
- `docs/evidence/v3.0.1/v22/SCEN-04/ed202-organization.json`（脚本产出）：`project.name=ED202-COMMON`、7 条 `caseSets`、39 条 `scenarios`、`totals`。
- `backend/src/test/java/com/qa/autotest/application/caseasset/Ed202OrganizationTest.java`（新增 3 条纯单测，Jackson 读 JSON + 读 md 静态断言，不连库）：caseSets 数 = 7、totals 分账、场景数 = 对照表数据行数、每个场景至少 1 个来源 sheet。
- 只读快照 `fixtures/ed202/ED202测试用例_V0.4.xlsx`：脚本只读取、不改字节，SHA-256 `9b897b77564eb6d9cb6843fa350b1cd085e61d613a1b047e8ce5ceaa36316e39` 与定案一致。

## 2. 真实执行的命令

```
cd /home/evergreen/robot-autotest
python3 tools/ed202_organization.py
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/ED202一级分类-场景对照表-20261108.md
cd /home/evergreen/robot-autotest/backend
mvn -o -B test -Dtest=ScenarioSchemaTest,ExecutionSelectionFreezeTest,Ed202OrganizationTest
```

## 3. 真实输出摘要（摘自上述命令 stdout，原文归档 `raw-20261108/ed202-organization.log`）

```
快照 SHA-256: 9b897b77564eb6d9cb6843fa350b1cd085e61d613a1b047e8ce5ceaa36316e39
实测 sheet 数: 7
[sheet] 测试用例看板: 表头 r15 编号列=0 一级分类列=1 二级分类列=2 明细行=730 唯一用例编号=730 一级分类数=32 二级分类数=284
[sheet] Import详细测试流程: 表头 r3 编号列=0 一级分类列=1 二级分类列=2 明细行=414 唯一用例编号=414 一级分类数=9 二级分类数=116
[sheet] XCCL详细测试流程: 表头 r1 编号列=0 一级分类列=1 二级分类列=2 明细行=61 唯一用例编号=61 一级分类数=1 二级分类数=5
[sheet] AI参数网_详细测试流程: 表头 r1 编号列=0 一级分类列=1 二级分类列=2 明细行=67 唯一用例编号=67 一级分类数=8 二级分类数=51
[sheet] 存储_详细测试流程: 表头 r1 编号列=0 一级分类列=1 二级分类列=2 明细行=86 唯一用例编号=86 一级分类数=10 二级分类数=67
[sheet] HCI详细测试流程: 表头 r1 编号列=0 一级分类列=1 二级分类列=2 明细行=102 唯一用例编号=102 一级分类数=7 二级分类数=46
[sheet] UECGSE适配: 表头 r1 编号列=1 一级分类列=0 二级分类列=None 明细行=83 唯一用例编号=83 一级分类数=7 二级分类数=0
[totals] dashboard(主口径=5 流程 sheet 编号并集)=730 adapter(适配集)=83 uniqueTotal(7 sheet 并集)=813
[totals] 看板实测唯一编号=730 (汇总页，应等于主口径)
[totals] dashboard + adapter = 813
[check] caseSets=7 scenarios=39 sum(scenario.caseCount)=813 dashboard+adapter=813 uniqueTotal=813
扫描 1 份文档 / 1 个表格块 / 40 个数据行
[PASS] 表格列数一致性：全部表格块内列数一致（转义竖线已按非分隔符处理）
[INFO] Running com.qa.autotest.application.caseasset.Ed202OrganizationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.505 s -- in com.qa.autotest.application.caseasset.Ed202OrganizationTest
[INFO] BUILD SUCCESS
```

## 4. 读数与结论

- 7 个 sheet 实测唯一用例编号：看板 730、Import 414、XCCL 61、AI参数网 67、存储 86、HCI 102、UECGSE适配 83。
- 分账：主口径 dashboard = 5 个流程 sheet 编号并集 = 730；适配集 adapter = 83；合计 uniqueTotal = 7 sheet 并集 = 813；看板 730 为汇总页，与 5 流程 sheet 并集一致，不重复计入场景。
- `caseSets` 7 条与 7 sheet 一一对应；`scenarios` 39 行与对照表 39 个数据行一致；所有场景 `caseCount` 之和 = 813，与 totals 自洽。
- `check_md_tables.py` 对对照表机检 PASS（1 个表格块 / 40 行含表头，列数一致，无裸竖线）。
- **偏差：无（仅指本节 xlsx 解析与组织 JSON 口径）。** 730 / 83 / 813 全部由脚本从 xlsx 真实算出，与定案口径一致，无需标注偏差原因；JSON 的 totals 是变量实算值而非硬编码。落库层的偏差见 §8。

## 5. 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| sheet 数 | 7（看板、Import、XCCL、AI参数网、存储、HCI、UECGSE适配） | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 看板口径 | dashboard=730，看板页自身唯一编号亦为 730 | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 适配集 | adapter=83，二级分类列缺失（记为 None），一级分类数 7 | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 唯一总数 | uniqueTotal=813，且 dashboard＋adapter=813 | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 用例集条目 | caseSets=7 | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 场景数 | scenarios=39，等于对照表 39 个数据行 | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 场景规模合计 | sum(scenario.caseCount)=813 | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 产出物 | docs/plan/ED202一级分类-场景对照表-20261108.md（39 数据行）＋ SCEN-04/ed202-organization.json | docs/evidence/v3.0.1/v22/raw-20261108/ed202-organization.log |
| 组织单测 | Ed202OrganizationTest：Tests run 3、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 定向 mvn 汇总 | Tests run 49、Failures 0、Errors 0，BUILD SUCCESS（14 个类同批） | docs/evidence/v3.0.1/v22/raw-20261108/backend-targeted.log |
| 门禁全量 | 19 步全 PASS，GATES_RESULT FAILS=0（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=0 errors=0 | docs/evidence/gates/logs/05-backend_test.log、02-frontend_test.log、11-editor_test.log |
| 基线 floor | 1016、325、219（只增不减，实测均不低于 floor） | docs/evidence/gates/logs/19-evidence_integrity.log |

## 6. 全量 Markdown 机检命令

```
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py
```

本轮回填后全量机检 PASS（默认清单 7 份 ＋ 上述 9 份证据 README 同批校验，表格块列数一致）。

## 7. 组织层已落库（v2.2 V2 / V5 补齐，2026-11-08）

> 本节不再是「JSON 声明」，而是平台库里的真实数据。工具：`tools/seed_ed202_organization.py`
> （纯标准库 `urllib`，只走 HTTP，不直接写业务表；可重复执行，项目/用例集/场景按 code 或 name 复用）。
> 原始输出：`docs/evidence/v3.0.1/v22/raw-20261108/seed-ed202-organization.log`（脚本 stdout 原文
> ＋ SQL 只读实测 ＋ 部署读数，按节拼接）。

执行命令：

```
cd /home/evergreen/robot-autotest
python3 tools/seed_ed202_organization.py
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py
```

### 7.1 落库真实读数（项目 / 用例集 / 用例 / 场景）

| 层 | 真实读数（库内实测） | 原始件 |
|---|---|---|
| 项目 | id=77，code=ED202-COMMON，name=ED202 公共用例库；由 `POST /api/projects` 创建，`audit_log` 有 `project:create` id=1705729 | seed-ed202-organization.log §0 |
| 用例集 | `test_set` 7 行，kind=case_set：ED202-CS-DASHBOARD=730、ED202-CS-IMPORT=414、ED202-CS-XCCL=61、ED202-CS-AIPARAM=67、ED202-CS-STORAGE=86、ED202-CS-HCI=102、ED202-CS-UECGSE=83（条目数即 `test_set_item` 实测） | seed-ed202-organization.log §1、§4 |
| 用例 | `test_case`（project_id=77）=813；分账实测 730 主口径 + 83 适配集（按 `case_code` 前缀：UEC 18、GSE 14、ROCE 17、MODE 3、STO- 18、SCALE 13） | seed-ed202-organization.log §4 |
| 步骤 | `test_case_step` 3078 行，覆盖 813 条用例（按源表「测试步骤」编号行拆分，无编号续行并入上一步，与 `CaseImportService.splitSteps` 同口径） | seed-ed202-organization.log §4 |
| 场景 | `test_scenario` 39 行，`parent_scenario_id` 全空（一层编排），39 个场景共绑定 42 个用例集（3 个跨 sheet 合并场景各 2 个） | seed-ed202-organization.log §4 |
| 导入链路 | 6 次 `POST .../cases/import/commit`：preview totalRows=414/61/67/86/102/83、validRows 全等于 totalRows、commit imported 同值，合计 813；`audit_log` 有 6 条 `case.import` | seed-ed202-organization.log §1、§4 |
| 幂等复跑 | 第二次执行：preview validRows=0（813 条均判「编号已存在」）、imported=0、7 个用例集条目 0 增加、39 个场景全部复用、steps 跳过 813 | seed-ed202-organization.log §2 |
| 场景执行 | **未达成**：`POST /api/scenarios/7/execute?projectId=77` 返回 404「路径不存在」，两次调用一致；未产生 run，`run` 表 project_id=77 计数 0 | seed-ed202-organization.log §3、§5 |

> 注：§1 首次执行日志里显示「复用已存在项目 id=77」，是因为项目已在同一脚本的 `--dry-run` 演练中
> 由 `POST /api/projects` 创建（`audit_log` id=1705729，2026-11-08 08:41:26），见 §0 的项目创建读数。

### 7.2 口径与构造方式

| 判断 | 依据 |
|---|---|
| 看板不作为用例来源 | 脚本实测 `dashboard==detailUnion=True`：看板 730 与 5 个流程 sheet 编号并集完全相等；看板只建「引用同一批 730 条用例」的用例集，避免整本导入时被文件内编号去重判为 730 条重复 |
| 按 sheet 拆 6 个导入件 | 只读快照严禁改动，脚本在临时目录生成 6 个单 sheet xlsx（inlineStr），快照字节不动，SHA-256 仍为 `9b897b77…16e39` |
| 7 个用例集条目数 | 逐个 set 的 `test_set_item` 实测 = 730/414/61/67/86/102/83，脚本逐项打印 PASS |
| 适配集 83 条不丢 | UECGSE 适配单 sheet 导入 commit imported=83，前缀分布合计 83，与 `totals.adapter` 一致 |

### 7.3 JSON 回填

`ed202-organization.json` 增补了可对齐的落库读数：`project.id=77`/`code`、7 条 `caseSets[].setId`/`setCode`/`dbItemCount`、
39 条 `scenarios[].scenarioId`/`caseSetIds`、`seed` 区块；`totals` 保持 730/83/813 未变（回填前后断言相等，
不等则脚本拒绝写入）。

## 8. 未达成项与偏差（如实）

| 项 | 状态 | 原因与证据 |
|---|---|---|
| 场景执行（`POST /api/scenarios/{id}/execute` 200 + runId ×2） | **未达成** | 运行中的服务落后于源码：jar mtime 2026-11-07 21:22，`GroupController.java` mtime 2026-11-08 01:14；`javap` 该 jar 内 `GroupController` 只有 `createScenario`/`scenarios`，无 `executeScenario`，实测 404「路径不存在：api/scenarios/7/execute」。会话硬约束「不重启应用、不重建库」，未自行重建/重启绕过 |
| `test_scenario.step_order` 体现 1..39 顺序 | **未达成（列有值但全为 0）** | 39 行 `step_order` 均为 0（min=max=0）。`POST /api/scenarios` 请求体只有 `projectId`/`name`/`caseSetIds`（源码 `GroupController.ScenarioRequest`），没有 `stepOrder` 入参，服务端按默认值 0 落库；未用 SQL 直接改业务表 |
| `test_case.source` 保留 `excel` | **偏差（现为 manual）** | 旧版导入服务的 `ImportRow` 只有 11 个字段、无 steps，步骤只能由 `PUT /api/projects/{pid}/cases/{id}` 的 steps 补写；`TestCaseService.toCase` 在 update 时固定写 `source="manual"`，813 条被归一。导入事实以 6 条 `case.import` 审计 + commit imported 读数为证 |
| 按 sheet 的用例归属入库（`source_sheet`） | **未达成（列为 NULL）** | 旧版 jar 的 `ImportRow`/commit 无 `sourceSheet` 字段，扩展列不入库；sheet 归属改由 `test_set_item`（7 个用例集条目）与对照表/JSON 证明 |
| 执行选择（`execution_selection`）创建并执行一次 | **未验证（无 HTTP 入口）** | 源码与 jar 均只有 `ExecutionSelectionService`/仓储，`api/` 下没有任何 Controller 暴露 execution_selection，无法用 HTTP 链路创建 |

补充口径说明：

- HCI 页表头错位：第 5 列标「优先级」但取值是 功能/性能/稳定性，真实优先级在「测试类型」列（P0..P3）。脚本按真实优先级列取值，并在生成导入件时把 T0/T1/T2 归一为 P0/P1/P2（与 EDIM-02 规则一致），故 `test_case.priority` 分布为 P0 416 / P1 224 / P2 169 / P3 4。
- 快照无安全级列，813 条全部落 `LX`（ADR-0013 未判定，执行前须人工补判），`test_case.safety_level` 实测 LX=813。
- 清理：两次 execute 均 404，未创建任何 run，`run` 表 project_id=77 计数为 0，无需删除 `test_result`/`run`。
