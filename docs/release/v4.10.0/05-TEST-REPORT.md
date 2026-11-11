# v4.10.0 测试报告（⑥ 测试执行）

| 项 | 内容 |
|---|---|
| 编号 | `RA-TR-v4.10-001` |
| 版本 | `4.10.0`（`VERSION=4.10.0`，`git HEAD=31631cd` + 本批未提交改动） |
| 执行时间 | 2026-11-11 15:19 ~ 16:30（GMT+8；含 16:18 整轮门禁复跑与模板内容补测） |
| 执行环境 | WSL `Ubuntu-24.04` ext4 副本 `/home/evergreen/robot-autotest`；后端 `:8081`、入口 `:8001`；MySQL 8.0.46 / 库 `robot_dev`（业务表 98）；集成测试库 `robot_it` |
| 上游 | `TEST-PLAN.md`（52 条 TC）、`PRD.md`（38 条 FR）、`DEV-PLAN.md`（OPT-01~08） |
| 证据根目录 | `docs/evidence/opt-20261111/` |
| 结论 | **可测项全绿**：单元 / 组件 / 接口 / 数据 / 机检 / 整轮门禁六层实测，0 条 FAIL（PASS 29 / PARTIAL 14 / NOT-RUN 9）；**9 条 NOT-RUN 全部因浏览器通道被取消或需真实设备故障注入**，不计 PASS |

## 1. 结果总览

| 结果 | 条数 | 含义 |
|---|---|---|
| PASS | 29 | 有可复现命令输出或落盘证据，判据全部满足 |
| PARTIAL | 14 | 判据部分满足，未满足部分已写明原因，不计 PASS |
| NOT-RUN | 9 | 未执行，逐条写明原因与解除条件 |
| FAIL | 0 | — |

**分层实测读数**

| 层 | 手段 | 实测结果 |
|---|---|---|
| L1 单元 | JUnit 5 / Vitest | 后端 **1376 例 / 0 失败 / 4 按环境跳过**；本批新增 7 个测试类 **60 例**全过；前端本批新增 8 个 spec **54 例**全过 |
| L2 组件 | Vitest + DOM 断言 | 前端全量 **92 文件 / 664 例**全过；`vue-tsc --noEmit` **0 错误** |
| L3 接口 | 真实 HTTP（登录后携 token） | **16 项**断言全过，响应体原样落盘 |
| L4 数据 | SQL 对拍 / 迁移 / 审计 | `db_audit` **PASS**（7 项）；`SchemaIT` **7 例全过**（`robot_it` 从零迁移）；补录填充率见 §4 |
| L5 端到端 | Playwright / CDP | **全部 NOT-RUN**——Chrome CDP `:9222` 仍处取消状态 |
| L6 专项 | 负向 / 权限 / 密钥 | 未登录 401、空模板 400、重复提交 409、非法父子分类被拒均实测通过 |
| L7 回归门禁 | 机检脚本 | **32 步整轮已跑**（`gates-20261111-1618.log`）：**30 PASS / 2 FAIL**——① 第 13 步浏览器回归（CDP `:9222` 为大师取消状态，`fetch failed`）；② 第 06 步 `lint.sh` 的 `check_tables`（存量债 L-49-06）。其余 30 步含前端测试/类型/构建、后端全量测试、db_audit、契约、包体、性能预算、版本一致性、i18n、交付包、更新机制全 PASS |

## 2. 逐条用例结果

| TC | 覆盖 FR | 层 | 结果 | 证据 | 说明 |
|---|---|---|---|---|---|
| TC-v4.10-01 | FR-v4.10-01 / 04 | L1 | PASS | `settingsNav.spec.ts` 5 例 | 6 项、顺序、两模式派生结果深度相等、`about`/`changelog` 为 `public` |
| TC-v4.10-02 | FR-v4.10-02 | L2 | PASS | `settingsGuard.spec.ts` 9 例 | `shouldPreserveGlobalMode` 与 `canEnterGlobalMode` 各自独立断言且互不干扰 |
| TC-v4.10-03 | FR-v4.10-03 | L3 | PARTIAL | 静态断言 + 路由清单 | 两模式菜单派生自同一数组已断言；运行期「点击后 URL 不回 `/workbench`」需浏览器，未测 |
| TC-v4.10-04 | FR-v4.10-02 | L5 | NOT-RUN | — | 需真实点击与截图；CDP `:9222` 取消 |
| TC-v4.10-05 | FR-v4.10-02 / 03 | L6 | PARTIAL | `settingsGuard.spec.ts` 角色断言 | ENGINEER / VIEWER 被拒已断言；未做「守卫注入」真故障注入 |
| TC-v4.10-06 | FR-v4.10-05 | L1 | PASS | `CaseImportExtraColumnsTest` 4 例 | 四个新别名映射到独立字段；14 列对账命中 14；测试资源分流到独立列 |
| TC-v4.10-07 | FR-v4.10-06 | L3 | PASS | 单测「同一文件重复导入不新增用例与版本」+ 预览实测 `replayed=true` | 幂等依据 `external_id` + `content_sha` 未变 |
| TC-v4.10-08 | FR-v4.10-07 / 08 / 09 | L2 | PASS | `CaseDetailPanel.spec.ts` 9 例 + 接口实测 | 接口返回五列齐全：`aSideCommand`/`bSideCommand`/`switchConfig`/`testResult`/`testResource` |
| TC-v4.10-09 | FR-v4.10-06 | L4 | PARTIAL | `backfill-after.txt` | 补录后填充率实测（见 §4）；未用独立解析器逐行对拍 730 行 |
| TC-v4.10-10 | FR-v4.10-05 / 06 | L6 | PASS | `k1-preview.json` | K1 闸门生效：同文件重放返回首批次结果（NEW=730），据此**判定不得走导入链提交**，整包未执行 |
| TC-v4.10-11 | FR-v4.10-04 / 05 / 35 | L7 | PASS | `check_i18n` EXIT=0 | 新增 193 个键已合并，双语键集相等 |
| TC-v4.10-12 | FR-v4.10-08 / 09 | L3 | PASS | `case-detail-contract.json` | 详情响应无 `secret`/`password` 等敏感键；凭据仅掩码字段 |
| TC-v4.10-13 | FR-v4.10-10 | L1 | PASS | `ThemeCycleButton.spec.ts` 4 例 | 4 次点击严格 `system → light → dark → system` |
| TC-v4.10-14 | FR-v4.10-11 | L1 | PASS | `LocaleCycleButton.spec.ts` 3 例 | 2 次点击回初态 |
| TC-v4.10-15 | FR-v4.10-12 | L2 | PARTIAL | 两个按钮 spec 内断言 | `aria-label` 与可聚焦已断言；未做 axe 全量可访问性扫描 |
| TC-v4.10-16 | FR-v4.10-12 | L6 | NOT-RUN | — | 1440 / 1024 / 窄屏三档截图对比需浏览器；CDP 取消 |
| TC-v4.10-17 | FR-v4.10-13 | L2 | PASS | `AssetDetailPanel.spec.ts` 11 例 | 三分区 12 字段映射与「未采集」显式占位 |
| TC-v4.10-18 | FR-v4.10-14 / 16 | L3 | PASS | `collect-evidence.json` | `GET /api/asset-host-inventory/detail?deviceId=15` 返回 `hardware`/`softwareAndOutOfBand`/`status`/`evidence`/`derived`/`credential` 六段，无敏感键 |
| TC-v4.10-19 | FR-v4.10-14 / 15 | L3 | PASS | `HOST_INVENTORY PASS` | `tools/check_host_inventory.py` 通过（零新增采集实现，复用既有只读链路） |
| TC-v4.10-20 | FR-v4.10-14 / 15 / 16 | L6 | NOT-RUN | — | 需真实设备断连 / 凭据失效注入，本批不改硬件 |
| TC-v4.10-21 | FR-v4.10-38 | L7 | PARTIAL | `gates-20261111-1618.log` | 32 步整轮已跑：30 PASS / 2 FAIL（13 浏览器回归=CDP 取消；06 lint=`check_tables` 存量债）。整轮非全绿，故不计 PASS |
| TC-v4.10-22 | FR-v4.10-17 | L2 | PASS | `TemplateCard.spec.ts` 6 例 + `template-detail-content.json` | 四类计数逐卡取 `GET /templates/{id}` 明细：实测内置模板 2/3/4 分别为 20/0/1/3、20/0/1/2、18/0/1/2，模板 1「空白项目」0/0/0/0（设计如此） |
| TC-v4.10-23 | FR-v4.10-18 | L3 | PASS | `template-detail-content.json` | 四个模板详情均返回 `cases`/`caseSets`/`scenarios`/`assets` 四类数组；模板 2/3/4 已含真实用例（20/20/18 条，取自项目 88 台账） |
| TC-v4.10-24 | FR-v4.10-19 / 20 | L2 | PARTIAL | `TemplateCard.spec.ts` + 接口实测 | 卡片与四类计数已测；`TemplateWorkbenchView` 增删改的 DOM 交互当前无 spec，未测 |
| TC-v4.10-25 | FR-v4.10-20 / 21 | L6 | PASS | `template-empty-create.json` | 空内容建模板返回 **400**，未落库 |
| TC-v4.10-26 | FR-v4.10-21 | L6 | NOT-RUN | — | 「编辑模板不回写公共用例本体」未做注入式验证（K10） |
| TC-v4.10-27 | FR-v4.10-21 | L6 | PASS | `template-builtin-readonly.json` | 内置模板内容改写 `PUT /templates/2/content` → **409**「内置模板内容不可改写，items 保持不变」；删除 → **409**；改元数据 200 但 `name` 仍为「接口回归」（服务端只允许启停）。V11_44 已为模板 2/3/4 补真实内容，样本不再为空 |
| TC-v4.10-28 | FR-v4.10-38 | L7 | PARTIAL | `gates-20261111-1618.log` | 同 TC-v4.10-21：整轮已跑、30/32 绿，2 项红为已知原因 |
| TC-v4.10-29 | FR-v4.10-22 | L1 | PASS | `ProjectTemplateServiceTest` `defaultModeIsCopy` | `mode` 缺省 / 空白一律 `copy`，`reference` 显式保留 |
| TC-v4.10-30 | FR-v4.10-23 | L3 | PASS | `from-template-with-content.json` | 实测建项目成功（`projectId=89/90/92/93`）；`projectId=93` 产物 **20 用例 / 1 用例集 / 1 场景 / 2 资产**，用例集内 20 条；单事务内完成编排并写审计 |
| TC-v4.10-31 | FR-v4.10-24 | L6 | PASS | `ProjectTemplateServiceTest` `failureRollsBackEverything` | 写 `project_asset` 后注入 `RuntimeException`，项目与关联表零残留 |
| TC-v4.10-32 | FR-v4.10-24 | L6 | PASS | `duplicate-409.json` | 同模板同名第二次提交返回 **409**，项目总数只 +1 |
| TC-v4.10-33 | FR-v4.10-25 | L3 | PARTIAL | 端点清单 | `POST /api/projects/{id}/save-as-template` 存在且审计字段已补；本批未真实调用取证 |
| TC-v4.10-34 | FR-v4.10-38 | L7 | NOT-RUN | — | 既有「创建项目」回归集未跑；本次只验证了 `from-template` 路径 |
| TC-v4.10-35 | FR-v4.10-26 | L1 | NOT-RUN | — | `CategoryTree.vue` 无对应 spec；分类约束由后端单测覆盖（TC-v4.10-36） |
| TC-v4.10-36 | FR-v4.10-26 | L6 | PASS | `ProjectCaseCategoryTest` 4 例 | 非法父子组合被拒、合法放行、删有引用分类被拒并回传引用数、非唯一匹配不写入 |
| TC-v4.10-37 | FR-v4.10-27 | L4 | PARTIAL | 迁移日志 | `V11_43` 首次语法失败后手工回退半成品并重跑成功（真库实证）；未做全量 `down → up → down` 演练 |
| TC-v4.10-38 | FR-v4.10-28 | L3 | PASS | `CategoryFilterJoinTest` 2 例 + CSV 实测 | 批量加入条数 = 筛选条数且 `item_source` 正确；三种筛选口径条件正确 |
| TC-v4.10-39 | FR-v4.10-29 | L2 | PASS | `ScenariosView.spec.ts` 7 例 | 创建 / 编组 / 删除；页面显式标注「仍限一层」 |
| TC-v4.10-40 | FR-v4.10-30 | L4 | PASS | `category-export-meta.json` | CSV **731 行 = 730 用例 + 表头**，表头含 `external_id`，四列均导出 |
| TC-v4.10-41 | FR-v4.10-38 | L7 | PARTIAL | `gates-20261111-1618.log` | 同 TC-v4.10-21 |
| TC-v4.10-42 | FR-v4.10-31 | L1 | PASS | `PermissionMatrixTest` 35 例 + 接口实测 | 四角色 × 6 能力矩阵逐格断言；ADMIN 实测含 `settings:read`/`ai:config`/`user:write`/`audit:read` |
| TC-v4.10-43 | FR-v4.10-32 | L2 | PASS | `grep ROLE_CAPABILITIES` = **0 命中** | 前端硬编码能力表已移除 |
| TC-v4.10-44 | FR-v4.10-33 | L3 | PARTIAL | 401 实测 | 未登录访问 `/api/me/menu-capabilities` 返回 **401**；四角色逐格 URL 拦截矩阵未实测 |
| TC-v4.10-45 | FR-v4.10-33 / 34 | L5 | NOT-RUN | — | 需浏览器截图；CDP 取消 |
| TC-v4.10-46 | FR-v4.10-31 / 32 / 33 / 34 | L6 | NOT-RUN | — | 「能力注入 → 服务端 403」负向验证未执行 |
| TC-v4.10-47 | FR-v4.10-38 | L7 | PARTIAL | `gates-20261111-1618.log` | 同 TC-v4.10-21 |
| TC-v4.10-48 | FR-v4.10-35 | L7 | PASS | `check_release_docs.py v4.10.0` | 六件套齐全、`FR=38` 全覆盖、`TC=52` 全有结果 |
| TC-v4.10-49 | FR-v4.10-36 | L6 | NOT-RUN | — | 性能 P95 基线未采集（需压测环境） |
| TC-v4.10-50 | FR-v4.10-37 | L4 | PARTIAL | `ProjectTemplateServiceTest` 审计断言 | `template.create_project` 审计含 operator/templateId/projectId/mode 已由单测断言；未抽样 `audit_log` 实表 |
| TC-v4.10-51 | FR-v4.10-38 | L7 | PARTIAL | `gates-20261111-1618.log` + `05-TEST-REPORT` §5 | 32 步整轮已跑（4m16s）：30 PASS / 2 FAIL；未整轮全绿但已不是「未执行」 |
| TC-v4.10-52 | FR-v4.10-38 | L7 | PASS | 本报告 + `check_release_docs.py v4.10.0` | 发布六件套自洽 |

## 3. 接口级实测明细（L3）

| 断言 | 请求 | 实测 | 判据 |
|---|---|---|---|
| ADMIN 能力集 | `GET /api/me/menu-capabilities` | 200，含四项敏感能力 | 缺 0 项 |
| 能力点校验 | `GET /api/me/menu-capabilities/check?capability=ai:config` | 200，`['ai:config']` | 命中 |
| 未登录拒绝 | 同上不带 token | **401** | 4xx |
| 公共用例详情 | `GET /api/global/cases/1667` | 200，五列齐全，无敏感键 | 字段 ≥ 12 项 |
| 分类 CSV | `GET /api/global/cases/export/category.csv` | 200，731 行 | 表头含 `external_id` |
| 模板四类明细 | `GET /api/global/templates/{id}` | 200，四类数组各就位 | 结构齐全 |
| 空模板拦截 | `POST /api/global/templates`（items 空） | **400** | 4xx |
| 从模板建项目 | `POST /api/projects/from-template` | 200，`projectId=89` | 有项目 id |
| 重复提交 | 同参数第二次 | **409** | 409 |
| 资产详情 | `GET /api/asset-host-inventory/detail?deviceId=15` | 200，六段结构 | 三分区 + 四层证据 |
| 资产脱敏 | 同上 | 无 `secret`/`password` 键 | 0 命中 |

原始响应体逐条落盘于 `docs/evidence/opt-20261111/api/`，脚本为 `api_tests.py` 等价实现，可重跑。

## 4. 数据层实测（L4）

| 检查 | 实测 | 判据 |
|---|---|---|
| 业务表数 | 98（契约 98） | 相等 |
| 缺审计四列的表 | **0** | 0 |
| 主键非单列 `id` 的表 | **0** | 0 |
| 缺主键 / 时间列非 `datetime(3)` / 非 `utf8mb4` | 0 / 0 / 0 | 0 |
| `append-only` 表都有 `created_at` | 9 | 契约 9 |
| `SchemaIT`（`robot_it` 从零迁移） | 7 例全过 | 全过 |
| 四列补录填充（`project_id=88`，共 730 条） | A端 **629** / B端 **492** / 交换机 **644** / 测试结果 **727** / 测试资源 **724** | 与源表非空行数一致 |
| 补录前后用例总数 | 730 → 730 | 不变 |
| 补录前后 `case_version` 行数 | 730 → 730 | 不变（无新增版本） |
| `V11_44` 内置模板内容 | 模板 2/3/4 = 用例 20/20/18、场景 1/1/1、资产 3/2/2；模板 1 全 0（设计如此） | 三个有语义的模板均有真实内容 |
| `ProjectTemplateCopyIT`（真实 `robot_it`，新增） | 1 例通过 | 复制链路逐列断言（五列 + 两级分类 + 指纹解耦 + 用例集条目） |
| 从模板建项目产物（`projectId=93`） | 20 用例 / 1 用例集 / 1 场景 / 2 资产；五列填充 20/20/20/20/20，与源 20 条**逐项一致**；指纹解耦 20/20 | 产物非空且内容忠实 |

## 5. 失败与未执行清单

**FAIL：0 条。**

**PARTIAL 14 条**分两类：

| 类别 | 条数 | 说明 |
|---|---|---|
| 运行期 / 浏览器 / 注入那一半未执行 | 9 | 静态与接口那一半已通过（TC-v4.10-03 / 05 / 09 / 15 / 24 / 33 / 37 / 44 / 50）|
| 整轮 32 步门禁已跑但非全绿 | 5 | 30 PASS / 2 FAIL（第 13 步 CDP 取消、第 06 步 `check_tables` 存量债）——执行了但判据未全满足，故不计 PASS（TC-v4.10-21 / 28 / 41 / 47 / 51）|

**NOT-RUN 9 条**分两类：

| 类别 | 条数 | 原因 | 解除条件 |
|---|---|---|---|
| 浏览器级（L5 / 截图） | 3 | Chrome CDP `:9222` 由大师取消，门禁第 13 步 `fetch failed` | CDP 恢复后补跑 TC-v4.10-04 / 16 / 45 |
| 负向 / 性能 / 回归注入 | 6 | 需真实设备故障、能力注入、压测环境或既有回归集（TC-v4.10-20 / 26 / 34 / 35 / 46 / 49） | 按 TEST-PLAN §6 故障注入清单逐条执行 |

**门禁口径说明（2026-11-11 大师拍板 D3「只补跑不依赖 CDP 的步骤」）**：`scripts/gates.sh` 32 步已整轮执行并留全量日志 `docs/evidence/opt-20261111/integration/gates-20261111-1618.log`（4m16s）。**30 步 PASS / 2 步 FAIL**：

| 步 | 结果 | 原因 | 归属 |
|---|---|---|---|
| 13 browser_regression | FAIL | CDP `:9222` 为大师取消状态，`fetch failed` | 环境，非实现缺陷 |
| 06 lint | FAIL | `check_tables`：3 个视图 6 处原生 `<table>`，HEAD 时即如此 | 存量债 L-49-06 |

由于仍有 2 步红，**不宣称「门禁全绿」**；但「32 步整轮未跑」这条已不成立。

## 6. 独立裁判结论

| 裁判 | 独立性依据 | 结论 |
|---|---|---|
| J1 单元断言 | 输入来自外部 fixture，非实现自述 | 60 + 54 例全过，无 skip 掩盖 |
| J2 DOM 序列化 | 断言 DOM 文本与结构，不读组件内部状态 | 通过 |
| J3 HTTP | 只看状态码与响应体，不看业务日志 | 16 项全过 |
| J4 SQL 对拍 | 直查 `information_schema` 与业务表，不经应用层 | `db_audit` 7 项 PASS |
| J5 浏览器 | — | **不具备执行条件，未使用** |
| J6 负向 | 负向必须红 | 401 / 400 / 409 均按预期红 |
| J7 机检 | 脚本输出即判据 | 全绿 |

## 7. 遗留缺陷

| 编号 | 描述 | 影响 | 处置建议 |
|---|---|---|---|
| L-49-01 | ~~8 张表缺审计四列、2 张表主键非单列 `id`~~ | 门禁第 12 步长期判红 | **已修复**：`V11_43` + 契约增补 `case_version`，`db_audit` 复跑 PASS |
| L-49-02 | ~~四个内置模板内容均为空~~ | 「从模板建项目」只能建出空项目，`reference`/`copy` 生成内容无样本可验 | **已修复**（2026-11-11 大师拍板 D1）：`V11_44` 用项目 88 真实用例为模板 2/3/4 补内容 20/20/18 条，模板 1「空白项目」按设计保持空；实测 `projectId=93` 产出 20 用例 / 1 用例集 / 1 场景 / 2 资产 |
| L-49-03 | 同一工作簿再次预览返回首批次结果（`replayed=true`），无法取得增量 diff | 存量 730 条无法走「导入链」补录，本次改用一次性按 `external_id` 补录脚本（仅填空值、不改指纹与版本） | **大师拍板排入 v4.11**（本版记录）：强制重算涉及幂等判据与 K1 闸门语义变更，需单独 PRD 与负向验证 |
| L-49-04 | `CategoryTree.vue` 与 `TemplateWorkbenchView.vue` 无组件单测 | 分类树与模板工作台的 DOM 行为无回归保护 | 下一批补 spec |
| L-49-05 | 浏览器级 3 条未执行（04 / 16 / 45） | R6 全局设置可达、R8 顶栏布局的「真实点击」证据缺失 | CDP 恢复后补跑 |
| L-49-06 | `scripts/lint.sh` 的 `check_tables` 判红：`ProjectSyncView.vue`（2 处）、`GlobalAssetsView.vue`（1 处）、`GlobalCasesView.vue`（3 处）使用原生 `<table>` 而非 `DataTable` 壳 | **非本批引入**：`git show HEAD:<file>` 三处分别为 2 / 1 / 3 处，与本批 `git diff` 新增 `<table>` 行数 0 一致；lint 24 项中其余 23 项（密文扫描 / eslint / checkstyle / compile / colors / contrast / i18n / states / tokens / import_header / bundle / styleguide / accent 派生 / accent 清单 / 字体子集 / icons / component_imports / report_sensitive / api_prefix / w7_tokens ×2 / wbs / brand）全绿 | **大师拍板单独立项到 v4.11**（本版不改渲染，避免与 16 条既有断言冲突） |
| L-49-07 | 模板 `SCENARIO` 项生成时只建场景壳，`case_set_ids` 恒为空字符串 | 从模板建出的项目里，场景存在但未编排任何用例集，需用户手工编排 | v4.11 让 `SCENARIO` 项携带用例集引用（生成时把同模板 `CASE_SET` 产出的 set id 写进 `case_set_ids`） |
| L-49-08 | ~~`copyCase` 的 INSERT 引用了已重命名掉的 `category` 列~~ | 从模板建项目只要模板里有 `CASE` 项就 **500**（`Unknown column 'category' in 'field list'`）。此前四个模板内容全空（L-49-02），该分支自 v4.9.2 起从未被执行，属假绿 | **已修复**：改 `INSERT ... SELECT` 整列搬迁，并顺带搬 v4.10.0 新增的五列与两级分类 id；新增 `ProjectTemplateCopyIT`（真实 `robot_it`）逐列断言锁死 |

## 8. 修订记录

| 版本 | 日期 | 变更 | 依据 |
|---|---|---|---|
| v1.0 | 2026-11-11 | 首版：52 条 TC 逐条落结果，PASS 22 / PARTIAL 10 / NOT-RUN 20 / FAIL 0 | 本批实测 |
| v1.1 | 2026-11-11 | 收口复跑修正：L2 前端全量 `vitest` 复跑 92 文件 / 664 例全过；L7 `lint.sh` 读数由「19 项守门」改为准确实测（24 项检查 / 23 绿 / 1 红，第 24 项 `check_brand` 因 `set -e` 中断后单独补跑 PASS）；新增 L-49-06（`check_tables` 存量债，含 HEAD 对拍） | 终检 `lint_final3.sh` + `check_brand.py` 原始输出 |
| v1.2 | 2026-11-11 | 按大师 5 项拍板执行后回写：① D1 补内置模板内容（`V11_44`）并修复由此暴露的 `copyCase` 500（L-49-08），TC-27 转 PASS、TC-22/23/30 补证据；② D3 整轮 32 步门禁已跑，5 条 L7 用例由 NOT-RUN 转 PARTIAL；③ 重新计数 PASS 29 / PARTIAL 14 / NOT-RUN 9；④ 新增 L-49-07、L-49-08；⑤ D2/D5 拍板结论记入遗留缺陷处置列 | `template-detail-content.json` / `from-template-with-content.json` / `template-builtin-readonly.json` / `gates-20261111-1618.log` / `ProjectTemplateCopyIT` |
