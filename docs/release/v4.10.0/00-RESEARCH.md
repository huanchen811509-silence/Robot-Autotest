# v4.10.0 · ① 需求调研（事实清单）

> **发布归档（v4.10.0）**：本文件是 `docs/release/v4.10.0/` 六件套之一。
> 内容并入自原 `docs/plan/软件迭代与优化-*` 专题基线（2026-11-11 经大师批准），
> 编号由 `FR-49-xx` / `TC-49-xx` 一一重写为 `FR-v4.10-xx` / `TC-v4.10-xx`；`OPT-xx` 工作包号不变。
> **2026-11-11 大师拍板 D4「合并为 release 单源」：原 `docs/plan/` 4 份基线已删除，
> 删除前留档 `docs/evidence/opt-20261111/plan-baseline-20261111.tar.gz`；本目录此后为唯一源。**

本文件保留调研事实清单与可复现命令；评估结论见同目录 `01-ASSESSMENT.md`。

## 1. 调研事实清单（① 环节产物）

### 1.1 证据分级定义

| 标记 | 含义 | 本文用例 |
|---|---|---|
| `[已核实]` | 有命令输出、源码行号或文件字节为证 | 绝大多数现状结论 |
| `[推断]` | 有依据但未经运行时验证 | 改造成本、依赖关系、根因归属 |
| `[假设]` | 凭经验设定，须实测推翻或确认 | 「深圳移植」语义、工作量人日 |

> 按 `docs/process/版本开发流程.md:51`，`[假设]` 不得写成结论；本文所有 `[推断]` / `[假设]` 汇总于 §1.6 待验证清单。

### 1.2 版本与流程基线

| 事实 | 证据 | 标注 |
|---|---|---|
| 当前 `VERSION=4.9.2`，`v4.9.2-ga` 标 `<!-- current -->` | `VERSION:1`、`CHANGELOG.md:11` | `[已核实]` |
| 4.4~4.9.1 是工作包、未单独落号，统一并入 v4.9.2 | `CHANGELOG.md:14-20` 括注；`docs/release/README.md:461-478` | `[已核实]` |
| G4/G5/G7/G8、G9~G14 未判过；整仓 `FAILS=1` 未消，「不得据本行称任何一版已发」 | `docs/release/README.md:490`、`:390`、`:397` | `[已核实]` |
| 迁移 73 个 `.sql`，最新 `V11_39__import_batch_checksum_kind.sql`；`schema-contract.json` 期望 98 表 | `db/migration/`、`db/schema-contract.json:3` | `[已核实]` |
| 七道环节单向串行；② 必含评分 / ≥2 方案对比 / 风险登记册 / 前置条件 / 证据附录 / 不做清单 | `docs/process/版本开发流程.md:10-17`、`:61-65` | `[已核实]` |
| 机检 FAIL 级硬约束：评估报告须含「不做」「前置条件」「修订记录」；调研须见三档标注（WARN） | `tools/check_release_docs.py:179-191` | `[已核实]` |
| 后端为 Spring Boot 3 + `JdbcTemplate` 手写 SQL（domain 层是纯 `record`，非 JPA/MyBatis 实体） | `domain/caseasset/TestCase.java:8-22` | `[已核实]` |

### 1.3 参考 Excel 字段基线（`基本功能导入测试v0.5.0.0.xlsx`）

本地只读解析（openpyxl 3.1.5）。**表格编辑器通道曾报 `No workbook open` / `UNOPENABLE_LOCAL_PATH`，按路由规程回退到本地只读解析，未修改 xlsx**。

| 工作表 | 规模 | 表头行 | 结构 |
|---|---|---|---|
| `当前测试环境` | 199 行 × 17 列 | 第 4 行 | 12 个有效字段：主机名 / 管理IP / 角色 / 型号 / 操作系统 / 内核 / BMC地址·映射地址·账户 / 网口数 / 网卡配置汇总 / 登录方式·端口 / 账户·权限·凭据引用 / VF测试虚拟机；含 15 处合并单元格，冻结 `A3` |
| `测试用例看板` | 745 行 × 10 列 | 第 3 行（分区表头） | 状态汇总 + 非通过事项归类；派生页 |
| `Import详细测试流程` | 417 行 × 14 列 | **第 3 行** | 414 条 `IM-` 用例 |
| `XCCL详细测试流程` | 64 行 × 14 列 | 第 3 行 | 61 条 `XCCL-`；隐藏 I/J/K 列 |
| `AI参数网_详细测试流程` | 70 行 × 14 列 | 第 3 行 | 67 条 `AI-` |
| `存储_详细测试流程` | 89 行 × 14 列 | 第 3 行 | 86 条 `ST-` |
| `HCI详细测试流程` | 105 行 × 14 列 | 第 3 行 | 102 条 `HCI-` |

**五张明细表统一 14 列**：`用例编号 | 一级分类 | 二级分类 | 用例名称 | 用例分类 | 优先级 | 预置条件 | 测试步骤 | A端命令 | B端命令 | 交换机配置 | 预期结果 | 测试结果 | 测试资源`。合计 **730 条、编号零重复**（414+61+67+86+102=730）。

| 分布 | 实测 |
|---|---|
| 一级分类 | Import 9 类（以太网功能测试 138 / 基础管理测试 84 / 性能测试 69 / 虚拟化与容器 35 / RDMA 28 / 稳定性 22…）；全表合计 32 个（`CHANGELOG.md:21` 口径） |
| 二级分类 | Import 116 类；XCCL 5、AI 51、存储 67、HCI 46 |
| 优先级 | 两套口径并存：`T0/T1/T2`（Import 276/100/38、存储 46/30/10）与 `P0~P3`（XCCL、AI、HCI） |
| 测试结果 | Import：通过 301 / 跳过 50 / 部分完成 24 / 不通过 23 / 通过-人工 10 / 空 3 / 少量多行备注；AI·存储·HCI 三表均「待执行」 |
| 用例分类 | Import：导入 340 / 兼容性 20 / PRD 20 / 认证 18 / NA 16 |

**这份表就是主程序已导入的那一份**：`CHANGELOG.md:21` 逐字记载「样本工作簿 `基本功能导入测试v0.5.0.0.xlsx` 五张明细表全部落位（730 用例 / 2779 步骤 / 32 个一级分类），派生『测试用例看板』页自动跳过；提交后按用例反推并同步两级分类字典」。`[已核实]`

**列映射边界（`CaseImportService.java:71-73`、`:134-160`）**：`ED202_EXPECTED_COLUMNS` 只列 12 类；别名表覆盖「用例编号 / 用例名称 / 一级分类 / 二级分类 / 用例分类 / 测试归类 / 优先级 / 预置条件 / 测试步骤 / 预期结果 / CX7测试结果记录 / 测试资源 / ED202覆盖情况 / 安全级」。**A端命令、B端命令、交换机配置、测试结果（无 CX7 前缀的列）四列无别名 → 导入丢弃**；「测试资源」「CX7测试结果记录」「ED202覆盖情况」三者共用 `ed202_coverage` 一个 TEXT 列。`[已核实]`

### 1.4 现状能力矩阵（需求 ↔ 已有实现）

| 需求 | 已有（证据） | 缺口 | 标注 |
|---|---|---|---|
| R1 用例详情 | 项目用例行内展开 `CaseDetailPanel.vue:64-84`（前置条件/预期/描述/步骤表）；`GET /cases/{id}`；公共库字段 `category_l1/l2`、`case_class`、`test_category`、`ed202_coverage`、`source_sheet`、`content_sha`、`version_no`、`lifecycle_status` 均在库 | 公共用例无详情路由/页；分配副本无独立标识与详情；详情只展示 4 项；4 列数据导入即丢 | `[已核实]` |
| R2 两级分类 | `case_category`（`V11_32:3-18`，`level`+`parent_id`）；`GlobalCaseService.java:172-180` 硬校验仅允许 1/2 级；公共库列表按 L1/L2 筛选；`test_set` 三态（`V11_10`）、`test_scenario`（`V8_3:33-41`）、`test_set_item` 多对多 | 项目用例 `category` 仍是单字符串；用例集接口无分类参数（`TestSetController:42-48`）；无测试场景管理页/路由；`test_scenario.case_set_ids` 是逗号串、约束一层（`ScenarioService:44-68`） | `[已核实]` |
| R3 资产详情 | `device` 已有型号/厂商/序列号/操作系统/内核版本/BMC地址/登录方式/账户/状态（`V11_38`）；`credential` AES-256-GCM + `CRED_MASTER_KEY`；`project_asset` 引用语义（`V7_3:48-63`）；SSH 采集 `HostInventoryCollector` 10 类只读命令、结果入 `device_host_spec.spec_json`；全局资产「显式补采」弹窗 | 无 `/assets/:id`、`/g/assets/:id` 详情路由；采集结果前端按 `category` 通用渲染，未按硬件/软件/状态分区；「凭证→登录→采集→详情」未串成一条动作 | `[已核实]` |
| R4 模板卡片 | `GlobalTemplatesView.vue:149-176` 内联卡片：name/builtin/status/code/kernel/visibility/**itemCount**/description；后端 `project_template_item`（`V11_36:15-28`）含 `itemType/refId/mode/snapshot_json` | 卡片只有聚合计数、无用例/用例集明细；无 `/g/templates/:id` 路由；`templateApi.get` 无调用点；新建表单（`TemplateRequest`）不含用例关联字段 | `[已核实]` |
| R5 模板↔项目 | **项目转模板已实现**：`POST /api/projects/{projectId}/save-as-template` → `ProjectTemplateService#saveAsTemplate`（`V11_36`）；模板 CRUD/复制/启停齐（`TemplateController:36-81`）；创建向导内容步可挑公共用例与资产（`ProjectWizardView.vue:284-291`） | **模板建项目后端无接口**：`CreateProjectRequest` 无 `templateId`，全库检索 `create-from-template/templateId` 零命中；`applyContent` 只能灌已存在项目；前端 `saveAsTemplate` API 定义了但无调用点 | `[已核实]` |
| R6 全局系统设置 | 全局导航 5 个叶子、无二级（`navigation.ts:68-99`）；全局设置是 8 张卡片（`GlobalSettingsView.vue:10-40`）；项目模式系统设置是 6 项二级菜单（`navigation.ts:311-358`） | 卡片目标路由全不带 `meta.global`，点击被守卫切回项目模式；卡片缺 `AI 助手`；无一级/二级菜单结构 | `[已核实]` |
| R7 二级菜单权限 | 6 个二级项中 3 项带 `capability`/`adminOnly`；`visibleNavByCapabilities` 过滤生效；路由 `meta.capability` → `/403` | 用户管理/关于/版本迭代 3 项无控制；能力表前端硬编码；后端无 MENU 资源维度、无菜单权限表、无接口级注解（仅服务层 `AccessControlService` 显式调用） | `[已核实]` |
| R8 主题/语言 | `useTheme.ts:108-114` 有 `cycle()` 未被调用；账户菜单内 `AppearanceControls.vue` 三态按钮组 + 语言 `<select>` | 顶栏无快捷入口（v4.0 起全部收进账户菜单，`AppHeader.vue:5-11`）；未做点击循环切换 | `[已核实]` |
| R9 乱码目录 | 42 文件 / 29,464 B / 1.2 秒内生成 / 全 14~16 行；正牌无 `4.4.0~4.9.0` 目录与 CHANGELOG 小节；不受 git 管控 | 见 §3 | `[已核实]` |
| R10 实测资料库 | 2272 文件，`.py` 512 个 / 33,664 行；`06-pytest自动化` 306 py / 20,204 行，pytest 9.2 同栈；`checks.py` 596 行判据库；四层证据链 | 0 个 `.robot`；无 xdist / json-report / MySQL；强绑定 CX7/400G/内网/Windows；**8001 平台已迁移过且被叫停** | `[已核实]` |

### 1.5 关键补充发现：demo 平台才是 R2/R3 的现成参考

`测试自动化demo程序/04 8001平台/README.md` `[已核实]`：

- 「测试用例→用例集与测试场景：**跨总表按分类筛选并勾选用例**，指定服务器和 UP 网卡，保存后检查整组计划，再启动自动连续执行。后台 Worker 逐项预检、执行和核验恢复；页面关闭后仍由运行中的平台处理。失败或未知状态停止后续派发，按原键只读找回，不自动重跑。」
- 「测试总表看板按总表管理历史标签、完整流程版本与平台新执行结果……执行轮次可下载 JSON 及原始流 LOG，**LOG 逐命令给出步骤说明，缺失原始流明确拒绝伪造**。」
- 目录 `backend/niclab/api/` 有 `test_sets.py`、`case_environments.py`、`collection.py`、`resource_identity.py`、`test_set_runs.py` 等 50+ 模块，`collectors/` 有 `ssh.py`、`switch_cli.py`、`server_deep.py`。

即 R2 的「基于分类做用例集 / 测试场景」、R3 的「资产+凭证→硬件软件状态」在 demo 平台已有可对照的成品行为。**建议方案设计先做一次 demo 平台行为对照，再定 FR**，避免主程序另造一套不兼容的交互。

### 1.6 待验证清单（[推断] / [假设] 汇总）

| # | 条目 | 标记 | 验证方式 |
|---|---|---|---|
| V1 | 四列（A/B端命令、交换机配置、测试结果）确实在导入时丢弃 | `[推断]` | 用样本 xlsx 跑一次 preview，检查 `ImportRow` 对应字段是否为空；查库 `ed202_coverage` 内容 |
| V2 | R6 点击卡片会被切回项目模式 | `[推断]` | 真实浏览器 CDP 点 `/g/settings` 卡片，观察 URL 与 `ui_mode`。**注：CDP `:9222` 当前被大师取消、门禁第 13 步 FAIL，此项暂不可验** |
| V3 | 各工作包人日估算 | `[假设]` | 立项后由开发计划复核 |
| V4 | 「深圳移植」= 深度/充分移植 | `[假设]` | 向大师确认；若指「深圳实验室环境内移植」，§4 边界需重评 |
| V5 | 乱码目录由某次批量套模板脚本生成 | `[推断]` | 查 2026-11-10 19:03 前后会话/脚本日志 |
| V6 | 8001 平台「已吸收」的具体能力清单与其完成度 | `[推断]` | 逐项比对 `backend/niclab/` 与实测资料库模块，读 `data/development-handoff.json` |

---

## 附录 A 可复现命令

```bash
# 1) 版本与门禁真值
cd E:/workspace/自动化测试/Robot-Autotest
cat VERSION
grep -n "730\|v4.9.2-ga" CHANGELOG.md | head
grep -n "G4 / G5\|FAILS=1" docs/release/README.md

# 2) 参考 Excel 字段基线（本地只读解析；表格编辑器通道不可用时回退方案）
cd "E:/workspace/自动化测试/测试自动化demo程序/参考测试用例"
python -c "import openpyxl,sys; sys.stdout.reconfigure(encoding='utf-8'); wb=openpyxl.load_workbook('基本功能导入测试v0.5.0.0.xlsx',data_only=True); print(wb.sheetnames); [print(s.title,s.max_row,s.max_column,[c.value for c in s[3]]) for s in wb.worksheets if '详细测试流程' in s.title]"

# 3) 导入器列映射边界
grep -n "ED202_EXPECTED_COLUMNS\|alias(\|HEADER_ALIASES" backend/src/main/java/com/qa/autotest/application/caseasset/CaseImportService.java | head -30

# 4) 全局设置卡片与守卫
sed -n '10,45p' frontend/src/views/global/GlobalSettingsView.vue
sed -n '341,360p' frontend/src/router/index.ts
sed -n '311,358p' frontend/src/config/navigation.ts

# 5) 模板域现状
sed -n '15,45p' db/migration/V11_36__project_template_persist.sql
grep -rn "saveAsTemplate\|templateApi.get\|create-from-template" frontend/src backend/src/main/java | head -20

# 6) 乱码目录字节级枚举
cd "E:/workspace/自动化测试"
python -c "import os,sys; sys.stdout.reconfigure(encoding='utf-8'); print([repr(n) for n in os.listdir('.') if n.startswith('E') and not n.startswith('E:')])"

# 7) 8001 平台收口令
grep -n "最后一次代码合入\|迁移已终止\|尚待吸收" "测试自动化demo程序/04 8001平台/AGENTS.md" "测试自动化demo程序/04 8001平台/README.md"

# 8) 文档机检（改后必跑，显式传路径）
python docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/release/v4.10.0/00-RESEARCH.md`
python tools/check_release_docs.py v4.9.2
```

## 附录 B 本文引用文件清单

| 类别 | 路径 |
|---|---|
| 版本/流程 | `VERSION`、`CHANGELOG.md`、`docs/release/README.md`、`docs/process/版本开发流程.md`、`docs/release/v4.9.2/01-ASSESSMENT.md` |
| 数据库迁移 | `db/migration/V1__baseline.sql`、`V7_3__asset_pool.sql`、`V8_3__group_auth.sql`、`V11_3__test_case_ed202_fields.sql`、`V11_10__test_set_kind.sql`、`V11_26__v3_11_host_inventory.sql`、`V11_29__v4_3_device_port_nic.sql`、`V11_31__ui_mode_platform_project.sql`、`V11_32__global_case_library.sql`、`V11_34__case_version.sql`、`V11_35__asset_import_source.sql`、`V11_36__project_template_persist.sql`、`V11_37__project_sync.sql`、`V11_38__asset_status_deprecated.sql`、`V11_39__import_batch_checksum_kind.sql` |
| 后端 | `application/caseasset/CaseImportService.java`、`GlobalCaseService.java`、`ProjectSyncService.java`、`TestSetService.java`、`application/iam/ProjectTemplateService.java`、`ProjectService.java`、`AccessControlService.java`、`application/asset/GlobalAssetService.java`、`CredentialService.java`、`application/host/HostInventoryService.java`、`HostInventoryCollector.java`、`infrastructure/SystemSshClient.java`、`common/security/SecretCipher.java`、`domain/iam/PermissionMatrix.java`、`api/iam/TemplateController.java`、`ProjectContentController.java`、`ProjectController.java`、`api/caseasset/GlobalCaseController.java`、`TestSetController.java` |
| 前端 | `config/navigation.ts`、`router/index.ts`、`App.vue`、`components/AppHeader.vue`、`AccountMenu.vue`、`AppearanceControls.vue`、`ProjectCard.vue`、`CaseDetailPanel.vue`、`views/global/GlobalSettingsView.vue`、`GlobalTemplatesView.vue`、`GlobalCasesView.vue`、`GlobalAssetsView.vue`、`views/CasesView.vue`、`CaseSetsView.vue`、`AssetsView.vue`、`ProjectWizardView.vue`、`SettingsView.vue`、`api/globalTemplates.ts`、`api/cases.ts`、`composables/useTheme.ts` |
| 工具 | `tools/check_release_docs.py`、`docs/evidence/v1.0/policy-20261106/check_md_tables.py` |
| 外部资料 | `测试自动化demo程序/参考测试用例/基本功能导入测试v0.5.0.0.xlsx`、`测试自动化demo程序/04 8001平台/README.md`、`AGENTS.md`、`智能网卡实测/05-开发指导/`、`06-pytest自动化/`、`09-复核报告/` |
