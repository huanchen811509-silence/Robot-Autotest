# v5.14.0 开发计划（DEV-PLAN）：报告体系 —— 先分家，再让报告能被看见

> 流程位置：④ 开发计划。**无开发计划不许开工编码**。
> 上游：`PRD.md`（11 FR + 8 NFR）、`04-报告体系评估.md` §一~§四。
> 本文件给 WBS 编号、改动面与**可复制的验收命令**；不给「大约几天」，只给依赖关系与可独立验收的边界。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | 口径统一与套件数 | W64-01 ~ W64-03 | 是 | 必须最先（名称是后面所有截图的基准） |
| W2 | 深链与预览 | W64-04 ~ W64-06 | 是 | 依赖 W1（套件数列与列表标题就位） |
| W3 | 导出与入口 | W64-07 ~ W64-09 | 是 | 依赖 W2（详情页存在） |
| W4 | 模板分维度 | W64-10 ~ W64-13 | 是 | 与 W2/W3 并行（改的是后端模板与种子） |
| W5 | 编写逻辑 | W64-14 ~ W64-15 | 是 | 依赖 W4（内核模板）、W2（预览页承载三段式） |
| W6 | 列表与复制 | W64-16 ~ W64-17 | 是 | 依赖 W3（导出下拉占位一致） |
| W7 | 收口 | W64-18 ~ W64-19 | 否（最终波） | 依赖 W1 ~ W6 全部出口判据 |

**并行纪律**：W1 硬前置（改名会影响所有截图基线，先改后测）；W2 → W3 串行（导出与打印挂在详情页）；
W4 与 W2/W3 改动面不重叠可并行；W5 必须在 W4 之后（要用 kernel 模板与预置 EHT 模板）。
**本版迁移 2 支**（`V11_73` suite_count、`V11_74` template.kernel）。

---

## 2. W1 口径统一与套件数（W64-01 ~ W64-03）

### W64-01 名称统一为「执行报告 / 测试报告」

| 项 | 内容 |
|---|---|
| 目标 | 菜单、页面标题、跳转按钮、i18n 中英四处统一；代码/表名**不改** |
| 改动面 | `navigation.ts`、`ReportsView.vue`、`TestReportsView.vue`、`locales/{zh,en}.json`、面包屑 |
| 出口判据 | ① 全站 grep「对外报告」= 0；② 三处命名一致（3 张截图）；③ 英文词条同步；④ 反向回放：v5.13 commit 上 grep 至少 2 处命中 |
| 验证命令 | `cd frontend && grep -rn "对外报告" src \| wc -l`（须为 0） |
| 依赖 | — |

### W64-02 `V11_73` 套件数入库

| 项 | 内容 |
|---|---|
| 目标 | `run.suite_count INT NULL`；`ReportIngestService` 入库时写；切分函数与 `ReportTreeService` **共用同一实现** |
| 改动面 | 迁移两支 + 切分函数抽为 `CaseKeyPath.split()` + `ReportIngestService` 调用 + `ReportTreeService:18-26,100-122` 改为调用同一函数 |
| 出口判据 | ① 三类样本（RF 单套件 / RF 多套件 / pytest 参数化）入库值 = 详情树一级子节点数；② 单测断言两处算法对同一 `case_key` 集合得到相同前缀数；③ 不可靠切分写 NULL 不猜 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=SuiteCountTest,CaseKeySplitTest test` |
| 依赖 | — |

### W64-03 三处列表显示套件数

| 项 | 内容 |
|---|---|
| 目标 | `/reports`、`/test-reports` 关联的执行报告、项目看板三处显示套件数；历史 NULL 显示「—」 |
| 改动面 | 三个列表页列配置 + 后端列表响应补字段 + spec |
| 出口判据 | ① 三处各一张截图；② NULL 显示「—」不显示 0；③ 列表值 = 详情树一级子节点数（≥3 样本） |
| 验证命令 | `cd frontend && npx vitest run src/views/ReportsView.spec.ts` |
| 依赖 | W64-02 |

---

## 3. W2 深链与预览（W64-04 ~ W64-06）

### W64-04 套件数深链

| 项 | 内容 |
|---|---|
| 目标 | 列表套件数渲染为链接 `/reports/{runId}?suite=<path>`；详情页展开并高亮该节点 + 滚动到可视区；非法 path 给提示不白屏 |
| 改动面 | 列表单元格渲染、`ReportDetailView` 读参 + 树组件定位 API + spec |
| 出口判据 | ① 点击后节点展开且高亮（截图）；② 不在首屏时自动滚动（滚动前后截图）；③ 非法 path → 提示「未找到该套件」；④ 不新增路由 |
| 验证命令 | `cd frontend && npx vitest run src/views/ReportDetailView.spec.ts -t 深链` |
| 依赖 | W64-03 |

### W64-05 测试报告详情页（快照预览）

| 项 | 内容 |
|---|---|
| 目标 | `/test-reports/:id`：左栏分节锚点 + 右栏 `snapshot_html` 直出（`iframe sandbox`，**无** `allow-same-origin`）；列表点行可进、可返回 |
| 改动面 | 新增 `views/TestReportDetailView.vue` + 路由 + 后端详情端点（若缺）+ spec |
| 出口判据 | ① iframe 内容长度 >1000 且与 `snapshot_html` 一致；② `sandbox` 属性不含 `allow-same-origin`；③ 锚点跳转与滚动联动；④ 冻结后改模板内容不变（hash 比对） |
| 验证命令 | `cd frontend && npx vitest run src/views/TestReportDetailView.spec.ts` |
| 依赖 | W64-03 |

### W64-06 草稿态与生成快照

| 项 | 内容 |
|---|---|
| 目标 | 无快照时显示「尚未生成快照」+「生成快照」按钮；点击后生成并刷新可预览 |
| 改动面 | 详情页草稿分支 + 生成端点接线（若缺）+ spec |
| 出口判据 | ① 草稿态两张截图（生成前/后）；② 生成后重新加载可预览；③ 生成失败有明确提示（非空文件/白屏） |
| 验证命令 | `cd frontend && npx vitest run src/views/TestReportDetailView.spec.ts -t 草稿` |
| 依赖 | W64-05 |

---

## 4. W3 导出与入口（W64-07 ~ W64-09）

### W64-07 三格式导出

| 项 | 内容 |
|---|---|
| 目标 | 列表与详情各一个导出下拉（HTML / PDF / DOCX），接既有 `exportBlob`；给出文件名与大小 |
| 改动面 | `api/testReports.ts:69-73` 的消费者（当前零调用）、两个页面下拉、导出文件名规则 |
| 出口判据 | ① 三格式各下载一次且能打开；② 文件名含编号与时间、无路径分隔符；③ 导出内容结论层与预览逐字一致；④ 无快照时明确提示；⑤ `exportBlob` 有调用点 |
| 验证命令 | `cd frontend && npx vitest run -t 导出`；`ls -l ~/Downloads/*.{html,pdf,docx}` |
| 依赖 | W64-05 |

### W64-08 执行报告打印页

| 项 | 内容 |
|---|---|
| 目标 | 详情页「打印」→ `POST /api/reports/{runId}/print-token` → 打开 `/print?token=`；token 一次性/短时效 |
| 改动面 | 打印按钮、token 端点接线、打印页样式（无导航） |
| 出口判据 | ① 新窗口打开可打印视图；② 旧 token 二次使用被拒（401/403）；③ 打印页无导航按钮；④ 未授权取 token → 403 |
| 验证命令 | `curl -s -X POST "$API/api/reports/EHT-1/print-token" -H "Authorization: Bearer $TOKEN" \| jq` |
| 依赖 | W64-05 |

### W64-09 自动生成开关

| 项 | 内容 |
|---|---|
| 目标 | 项目设置页补 `report.auto_generate` 开关（后端 `ReportAutoGenerator:47-52` 已有） |
| 改动面 | 设置页开关 + 保存接线 + spec |
| 出口判据 | ① 保存后 `GET` 设置返回所选值；② 开启后跑一次执行出现「自动报告」前缀记录；③ 关闭后不生成；④ 非管理员改开关 → 403 |
| 验证命令 | `cd frontend && npx vitest run -t 自动生成` |
| 依赖 | W64-05 |

---

## 5. W4 模板分维度（W64-10 ~ W64-13）

### W64-10 `V11_74` 模板 kernel 列

| 项 | 内容 |
|---|---|
| 目标 | `report_template.kernel VARCHAR(32) NOT NULL DEFAULT 'robot'`；存量行全部落 `robot`，无 NULL |
| 改动面 | 迁移两支 + 模板读写层补字段 |
| 出口判据 | ① `SELECT kernel,count(*) GROUP BY kernel` 无 NULL；② 旧模板默认 `robot` 不影响历史报告；③ down 可回滚 |
| 验证命令 | `psql "$DB_URL" -c "select kernel,count(*) from report_template group by 1"` |
| 依赖 | — |

### W64-11 选择规则逐级回退

| 项 | 内容 |
|---|---|
| 目标 | 项目默认（kernel 匹配）→ 平台默认（kernel 匹配）→ 平台默认（`any`）→ 平台默认（任意）；回退**可见**（提示「当前使用通用模板」） |
| 改动面 | 模板选择服务 + 前端提示 + 单测（四级各一条） |
| 出口判据 | ① 四级回退各有单测；② 回到 `any` 时界面有提示（截图）；③ EHT pytest 工程取到 EHT 预置模板 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ReportTemplateResolveTest test` |
| 依赖 | W64-10 |

### W64-12 字段白名单补 pytest 字段

| 项 | 内容 |
|---|---|
| 目标 | `ReportFieldWhitelist` 加 pytest 专属（判据结论、nodeId、参数化 id、junit message、artifact 路径）；RF 专属保留 |
| 改动面 | 白名单类 + 单测（允许/拒绝各一类） |
| 出口判据 | ① 白名单内字段可引用；② 未在白名单的字段被拒（默认拒绝）；③ RF 字段仍可用 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ReportFieldWhitelistTest test` |
| 依赖 | W64-10 |

### W64-13 EHT 预置模板种子

| 项 | 内容 |
|---|---|
| 目标 | 平台种子新增「EHT-PYTEST 默认模板」（8~10 字段，非 21 字段全量）；EHT 工程首次打开提示「可一键应用」 |
| 改动面 | 种子 SQL/迁移 + 前端提示 + 应用动作接线 |
| 出口判据 | ① 新建报告 `templateId` = 该模板且字段数 8~10；② 标准 RF 工程行为不变；③ 种子可重复执行不产生重复模板 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ReportTemplateSeedTest test` |
| 依赖 | W64-10 |

---

## 6. W5 编写逻辑（W64-14 ~ W64-15）

### W64-14 EHT pytest 三段式

| 项 | 内容 |
|---|---|
| 目标 | 结论层（1 页：四态计数 + 判据未定清单点名 nodeId + 与上次差异）/ 证据层（nodeId + 判据行原文 + 实测值 + 证据路径可点）/ 附录（metadata 十项 + 环境） |
| 改动面 | 报告生成器（模板渲染 + 数据装配）、复用 `CaseScriptLink` 路径组件、spec/快照测试 |
| 出口判据 | ① 三节可辨识且锚点可跳；② 四态计数和 = 用例总数；③ 未定项点名 nodeId（≥1 真实样本）；④ 证据路径可点且 ≥2/3 真实存在；⑤ RF 报告标「不适用」不留空 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=EhtPytestReportTest test` |
| 依赖 | W64-11、W64-12 |

### W64-15 与上次执行差异

| 项 | 内容 |
|---|---|
| 目标 | 结论层显示「新增失败 / 修复 / 仍失败」三类差异（跨 run 比对） |
| 改动面 | 差异计算服务 + 报告数据装配 + 单测 |
| 出口判据 | ① 有两次真实执行时至少识别一条「新增失败」或「修复」；② 无历史时显示「无上次执行」而非空表；③ 降级方案（只给跳转）须在修订记录留痕 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ReportDiffTest test` |
| 依赖 | W64-14 |

---

## 7. W6 列表与复制（W64-16 ~ W64-17）

### W64-16 报告批量删与影响面

| 项 | 内容 |
|---|---|
| 目标 | `/reports` 批量删 + 影响面（N 结果 / M 产物 / 冻结态）；冻结的测试报告不可删且提示解冻；文案区分执行记录与测试报告 |
| 改动面 | `ReportsView.vue` 批量选择与删除、影响面弹窗、后端批量删端点（若缺） |
| 出口判据 | ① 3 条批量删后软删成功；② 冻结报告删除被拒且提示「已冻结，请先解冻」；③ 删除文案不含歧义；④ 删除后分页不出现空页 |
| 验证命令 | `cd frontend && npx vitest run src/views/ReportsView.spec.ts -t 批量删` |
| 依赖 | W64-03 |

### W64-17 复制平台模板为项目模板

| 项 | 内容 |
|---|---|
| 目标 | 平台模板行「复制为项目模板」动作（`ReportTemplatesView.vue:12` 注释承诺但未实现） |
| 改动面 | 前端动作 + 后端复制端点（若缺）+ spec |
| 出口判据 | ① 执行后本项目出现同名副本可改；② 副本与源逐字段一致；③ 改副本不影响平台模板；④ 权限行为明确（非管理员允许或拒绝，二者择一并留痕） |
| 验证命令 | `cd frontend && npx vitest run src/views/ReportTemplatesView.spec.ts` |
| 依赖 | W64-05 |

---

## 8. W7 收口（W64-18 ~ W64-19）

### W64-18 契约与预览性能

| 项 | 内容 |
|---|---|
| 目标 | `openapi.json` 重生成；10MB `snapshot_html` 首屏 ≤3s；导出 ≥50MB 不整读内存 |
| 改动面 | 契约快照（WSL 内重生成）；`docs/evidence/v5.14.0/perf/` |
| 出口判据 | ① `--check` PASS；② 预览耗时记录；③ 导出内存曲线（流式或分块）说明 |
| 验证命令 | `cd backend && python3 tools/gen_api_contract.py --check` |
| 依赖 | W64-05、W64-07 |

### W64-19 门禁全量与文档回写

| 项 | 内容 |
|---|---|
| 目标 | `GATES_RESULT FAILS=0`；六份齐全；总纲状态回写 |
| 改动面 | `docs/release/v5.14.0/*`（从 `_draft/` 迁入）、总纲交付表 |
| 出口判据 | ① 门禁 `FAILS=0`；② `check_release_docs.py v5.14.0` PASS；③ 表格机检全 PASS |
| 验证命令 | `bash scripts/gates.sh 2>&1 \| grep GATES_RESULT`（仓库里**没有** `tools/check_gates.py`，5.12/5.13 已两次踩过） |
| 依赖 | W1 ~ W6 全部 |

---

## 9. 接口与数据变更清单

| 变更 | 类型 | 影响 | 处理 |
|---|---|---|---|
| 报告列表响应加 `suiteCount` | 响应新字段 | 三处列表 | 契约重生成 |
| `GET /api/test-reports/{id}`（预览用） | 新端点或扩字段 | 详情页 | 契约重生成 |
| 生成快照端点 | 新端点 | 草稿态按钮 | 契约重生成 |
| 模板创建/查询带 `kernel` | 请求/响应扩展 | 模板选择 | 契约重生成 |
| 复制模板端点 | 新端点 | 项目模板 | 契约重生成 |
| `V11_73` / `V11_74` | 2 支迁移 | `run.suite_count`、`report_template.kernel` | 每支配 `.down.sql` |

---

## 10. 风险与应对

| # | 风险 | 应对 |
|---|---|---|
| R1 | 套件数算法与详情树不一致，列表与详情打架 | 切分函数**只留一份**（W64-02 判据 2 单测锁死）；不可靠切分写 NULL 不猜 |
| R2 | 预览 `iframe` 引入 XSS | `sandbox` 无 `allow-same-origin`；历史脏 HTML 走服务端清洗（降级预案需登记规则） |
| R3 | 改名波及大量截图与 i18n | W64-01 先做、先冻结基线，后续波次截图才可比 |
| R4 | 模板四级回退难验证 | 四级各一条单测 + 界面回退提示截图；`any` 分支必须有样本 |
| R5 | 导出 50MB 内存爆 | `exportBlob` 走流式；证据给内存曲线而非「试过了」 |

---

## 11. 与相邻版本的边界

1. 本版**不改表名/代码命名**（NFR-02）：`run`、`test_report` 保持，只改展示文案。
2. 本版**不做**报告模板的向导化（5.10 已做项目/模板向导，报告模板是另一对象）。
3. 本版**不把采集结果并入报告**（5.13 已交付画像；报告只引用证据路径）。
4. 套件数深链依赖详情树节点 path 稳定，若 5.15 重写拓扑不影响本版。

---

## 12. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：7 波 19 个 WBS；改名先行；套件数算法与树共用一份切分函数 | `PRD.md` v5.14.0 + 评估 04 |


---

## 13. 开工前 WBS 重定义（依据 01-ASSESSMENT，W1）

> WBS 编号 W64-01 ~ W64-19 **一个不改**，只把「草案以为要造的能力」改成真实工作量。
> 依据 `01-ASSESSMENT.md` §1 逐需求评估与 §6 被推翻假设；不删波次、不改验收命令的可复制性。

| WBS | 草案定位 | 真实定位（开工口径） | 容量变化 |
|---|---|---|---|
| W64-02 | 新增切分函数 + 两套内核规则 | 抽 `CaseKeyPath` **唯一**函数，`ReportTreeService.buildTree` 与落列共用；不读 kernel | 减：少一套算法 |
| W64-03 | 三处列表显示套件数 | `/reports` + 项目看板 `digest` 两处（同源 `run.suite_count`）；`/test-reports` 列表展示的是 `test_report` 对象，**不加**该列 | 减：一处 |
| W64-05 | 新增 `GET /api/test-reports/{id}` | 端点已存在；改为新增**只读 HTML 快照通道** + `iframe sandbox` 详情页与路由 | 增：后端 HTML 通道 |
| W64-06 | 草稿态 + 生成快照按钮 | DRAFT 创建即物化快照；改为**冻结动作接线**与历史脏数据空态 | 减：不新建快照能力 |
| W64-07 | 三格式导出 | 统一**四格式**（html/pdf/docx/xlsx），接 `exportBlob` | 平 |
| W64-08 | 开发打印页 | 页面 / 路由 / 端点已存在；只补按钮 + `issuePrintToken` 封装 | 减：降级接线 |
| W64-10~13 | 模板四级回退从零补 | 已有两级 + `TemplateRef`；扩 kernel 维度、白名单分组仍单一真源；断言改 `TemplateRef` | 增：白名单连带改动面 |
| W64-14/15 | 三段式前端接线 | 需改 `ReportSnapshotService` 或新增渲染层，否则分节锚点无落点 | 增：后端渲染 |
| W64-16 | 开发批量删与影响面 | 5.9 已交付；**降级为回归验证 + 文案统一** | 减：零开发 |
| W64-18 | 契约与预览性能 | 契约重生成；10MB / 50MB 样本无采样环境则如实标 `PARTIAL` | 平 |
| W64-19 | 门禁全量与文档回写 | 唯一门禁是 `scripts/gates.sh`，判据 `GATES_RESULT FAILS=0 UNJUDGED=0` | 平 |

### 13.1 W1 实际完成（W64-01 ~ W64-03）

| WBS | 完成内容 | 验收命令 / 读数 |
|---|---|---|
| W64-01 | i18n 中英 4 处改名 + 2 处注释；键名 / 表名 / 代码命名不动 | `grep -rn "对外报告" frontend/src \| wc -l` = 0；`check_i18n.py` 5/5，zh/en 3099 键 |
| W64-02 | `V11_73` + `.down.sql`；`CaseKeyPath`；`ReportRepositoryJdbc` 从实际写入的 `case_key` 算并落列；`ReportTreeService` 改用同一函数 | `mvn -o -B -Dtest=CaseKeyPathTest,SuiteCountTest test` BUILD SUCCESS；迁移 `11.73/11.68`、表数 109 |
| W64-03 | `ReportRecord` / 列表 / 详情 / digest DTO 带 `suiteCount`；`/reports` 与看板两列；NULL 显示「—」 | `vue-tsc --noEmit` 0 error；`ReportsView.spec.ts` 6/6；`gen_api_contract.py --check` PASS（456/374）；`check_dto_columns.py` PASS |

### 13.2 W1 偏差登记

| # | 偏差 | 原因 |
|---|---|---|
| B-1 | 「三处列表」实际收敛为两处 | `/test-reports` 展示的是 `test_report`（对外报告对象），不是 `run`；给它加执行报告的套件数，恰好制造 FR-01 要消除的口径混淆 |
| B-2 | 单测初版断言「无分隔符 key 不产生一级节点」判红 | 实测 `split()` 在既无 `/` 也无 `.` 时把整个 `case_key` 当 `suitePath`，树会建一级节点；修正断言为 2 并补同源核对，不是放水 |
| B-3 | `MetadataOverlay.apply` 顺手补齐 | 它原用 11 参兼容构造器重建 `ReportRecord`，会把判据汇总冲成「未知」；补录后连新加的 `suiteCount` 也会丢，改为完整构造 |

### 13.3 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：7 波 19 WBS | `PRD.md` v5.14.0 + 评估 04 |
| 2026-11-15 | 开工前重定义 WBS 内容、修正 W64-19 门禁命令、登记 W1 完成与偏差 | `01-ASSESSMENT.md`、W1 真实命令输出 |


---

## 14. W2 实际完成（W64-04 ~ W64-06）

| WBS | 完成内容 | 验收读数 |
|---|---|---|
| W64-04 | `ReportsView` 套件数列改为入口（`?suite=all`）；`ReportDetailView.focusSuite()` 支持 `all` / 精确一级套件名 / 非法参数提示；`SuiteTree` 加 `highlight` 控制默认展开与 `data-suite-target` 高亮，滚动到可视区 | `ReportsView.spec.ts` 7/7、`ReportDetailView.spec.ts` 14/14；`vue-tsc` 0 error |
| W64-05 | 新增 `GET /api/test-reports/{id}/snapshot`（`TestReportService.SnapshotView`）；`ReportHtmlSanitizer` 服务端清洗；新增 `TestReportDetailView.vue` + 路由 `/test-reports/:id` + 列表标题入口；`iframe sandbox=""` | `ReportHtmlSanitizerTest` 4 例；`TestReportDetailView.spec.ts` 2 例；契约 `457/375` `--check` PASS |
| W64-06 | 按 R-10 重定义：DRAFT 已有快照，页面给「冻结报告」接既有 `POST /{id}/freeze`；`hasSnapshot=false` 只给可解释空态，不摆假按钮 | 前端 spec 2/2；`TestReportsView.spec.ts` 16/16 零回归 |

### 14.1 W2 偏差登记

| # | 偏差 | 原因 |
|---|---|---|
| B-4 | 草案 W64-05「新增 `GET /api/test-reports/{id}`」未做 | R-9：端点已在契约内且返回字段 Map；真实缺口是 HTML 通道，新增的是 `/{id}/snapshot` |
| B-5 | 草案 W64-06「草稿态 + 生成快照按钮」未做 | R-10：创建 DRAFT 即物化快照；按真实状态机改为冻结接线 |
| B-6 | 列表深链只带 `suite=all`，不带具体套件名 | 列表 DTO 只有 `suiteCount` 一个数字，没有套件名列表；精确 `?suite=<name>` 作为可粘贴深链独立支持（TC-14）。若为此给列表加 `suiteNames` 会引入第二套真值，得不偿失 |
| B-7 | 左栏锚点当前只有「报告内容 / 快照信息」两项 | R-14：`snapshot_html` 仍是字段 `<dl>`，没有结论/证据/附录分节；三段式分节锚点留 W64-14 改生成器后补 |
| B-8 | 本波未做真实 HTTP 探针 | 后端 jar 未重建；按版本纪律统一在 W7 重建 + 重启后跑两轮真实探针，此处不冒充 |


---

## 15. W3 实际完成（W64-07 ~ W64-09）

| WBS | 完成内容 | 验收读数 |
|---|---|---|
| W64-07 | `ReportExportBar.vue` 单一实现，四格式 html/pdf/docx/xlsx 与后端 `ReportExportService.extension()` 同源；列表行（含冻结态）与详情页都接上；`exportFileName` 补 xlsx；`exportBlob` 不再是零消费者 | `ReportExportBar.spec.ts` 3 例；`vue-tsc` 0 error |
| W64-08 | `reportsApi.issuePrintToken` 封装 + 执行报告详情页「打印」按钮；复用既有 `/reports/:runId/print?token=`，不新建页面；失败给人话提示 | `ReportDetailView.spec.ts` 14/14 零回归；真实 token 请求留 W7 |
| W64-09 | 发现并补真实缺口：`SystemSettingService` 项目设置白名单原**不含** `report.auto_generate`，只加 UI 会被 400 拒；补键、默认值 `false`、boolean 校验分支；设置页加 boolean select | `SystemSettingAutoGenerateTest` 3 例、`SystemSettingServiceTest` 5 例、`ReportAutoGeneratorTest` 11 例全绿；`SettingsView.spec.ts` 项数断言按真实变更更新为 4 |

### 15.1 W3 偏差登记

| # | 偏差 | 原因 |
|---|---|---|
| B-9 | R-15「后端已有，只缺 UI」不准确 | `ReportAutoGenerator` 读的是 `ProjectSettingRepository.findAll(projectId,"report.")`，而设置页 PUT 走 `SystemSettingService.putProject` 白名单；该键不在白名单，写入必被拒。本版补的是**写入链**，不是纯接线 |
| B-10 | 列表页导出用共用组件而非「下拉」 | `NSelect`/`NDropdown` 在 DataTable 单元格里会引入 teleport 与虚拟层，happy-dom 下不可测；用原生 `<select>` + 按钮，四格式可见可选，行为等价 |
| B-11 | 默认 `report.auto_generate=false` | 升级后不能悄悄改变既有项目的执行产物数量；默认值与「开关默认关」一致 |
| B-12 | 导出件能否打开（PDF 页数 / DOCX 可解析）、打印 token 一次性真实请求、非管理员 403 | 未部署新 jar，属 `FILE`/`HTTP` 证据，留 W7 真实探针，不冒充 |


---

## 16. W4 实际完成（W64-10 ~ W64-13）

| WBS | 完成内容 | 验收读数 |
|---|---|---|
| W64-10 | `V11_74` 加 `report_template.kernel`（NOT NULL DEFAULT 'robot'）+ `.down.sql`；`ReportTemplate` 加第 10 参并保留 9 参兼容构造器；MAPPER / SELECT / INSERT / withFields 全链路带 kernel | 迁移后 `GROUP BY kernel` 无 NULL；旧 `new ReportTemplate(...9 参)` 调用零改动 |
| W64-11 | `ReportTemplateRepository.findDefaultForRun` 用 **default 方法**回落旧两级实现（旧伪仓储 / mock 不受影响）；JDBC 覆盖实现，从 `run.kernel` 取内核，SQL CASE 四级回退；`rank()` 静态方法作单一真值 | `ReportTemplateKernelTest.fourFallbackLevelsAreStrictlyOrdered` 四级各一条 |
| W64-12 | `ReportFieldWhitelist` 仍是**一个类**：新增 `PYTEST_ORDER` 5 项并入 `EXTERNAL_FIELDS`（允许集合），**不并入** `EXTERNAL_ORDER`（默认顺序 / 平台 DEFAULT 字段集保持 21 项）；`selectable(kernel)` 按维度给清单；`GET /fields?kernel=` 开放 | 单测：robot/any 21 项、pytest 26 项、未知内核默认拒绝；`allows()` 仍默认拒绝 |
| W64-13 | 迁移幂等种子 `EHT-PYTEST`（9 字段 = 6 必填 + 结论 + 两计数，`INSERT IGNORE` + 唯一键）；`TemplateRequest/TemplateCommand` 带 kernel 且严格校验；`ReportTemplateView` 透出 kernel；前端类型与模板卡片显示 | 单测 5 例；`ReportTemplateServiceTest` / `ReportTemplateFreezeTest` 回归 |

### 16.1 W4 偏差登记

| # | 偏差 | 原因 |
|---|---|---|
| B-13 | 草案「四级回退从零补」改为「两级扩到四级」 | R-12：项目默认 → 平台 DEFAULT 已存在；本版只加 kernel 匹配与 any 分支 |
| B-14 | EF 专属字段**不进** `EXTERNAL_ORDER` | 该列表同时决定默认渲染顺序与平台 DEFAULT 字段集，加一项会让所有标准 RF 报告多一列，直接违反 FR-08 判据 2 |
| B-15 | 内核绑定创建时定，`update` 不改 kernel | 已冻结报告的快照回指的是模板 id + version；中途换内核会让「当时按哪个内核出的报告」不可追溯 |
| B-16 | `findDefaultForRun` 用接口 default 方法 | 全库有多处伪仓储直接 `implements ReportTemplateRepository`；加抽象方法会一次性打破全部测试，default 回落让旧链路零改动 |
| B-17 | 前端只加 kernel 显示 / 可选参数，未做分组筛选 UI | 判据 3「回退到通用模板要可见」目前由列表卡片 kernel 标签 + 后端 `TemplateRef` 承担；界面提示语留 W7 真实探针后按证据补齐 |


### 16.2 W4 收尾波：W64-11「回退可见」做实

| 项 | 内容 |
|---|---|
| 触发 | W64-11 出口判据 ②「回到 `any` 时界面有提示」与 FR-08 判据 3 属 **P0**，B-17 把它留到 W7 会导致收口时判 FAIL |
| 实现 | 新建 `TemplateSelection`（application 层）作四级 rank/source 的单一真值；`ReportTemplateRepository.runKernel` 用 default 方法回落；`TemplateRef` 追加 `kernel` / `templateSource` **查询期元信息**；`TestReportDetailView` 显示模板名与内核，回退时给「当前使用通用模板」提示 |
| 边界 | **不写进快照 JSON**：快照是冻结事实，字段集合与 `snapshot_hash` 由 TPLX-02 保护；加一个界面提示不该改所有历史报告的 hash。平台模板只读、项目模板 kernel 创建后不可改（B-15），现场推断不漂移 |
| 验收 | 后端 `TemplateSelection` 6 例断言（含「RF/EHT 命中不误报、内核不匹配才提示」）；前端详情页 3 例；真实库四级回退 SQL 探针 4 组 |

### 16.3 W4 偏差补记

| # | 偏差 | 原因 |
|---|---|---|
| B-18 | B-17 的「界面提示语留 W7」提前到 W4 落地 | P0 判据不能等到收口波才补；实现只增元信息与一个提示条，不动冻结快照 |
| B-19 | `templateSource` 未写进 `snapshot_json`，而是查询期由 `/template` 端点算 | 写进快照会让每份历史报告的 hash 变化，且「选择落在哪一级」是展示元信息不是报告字段 |


---

## 17. W5 实际完成（W64-14 ~ W64-15）

| WBS | 完成内容 | 验收读数 |
|---|---|---|
| W64-14 | 新增 `EhtSections`（结论四态 / 未定项 / 证据行 / 环境 / 差异）+ `EhtSectionRepository` 接口 + `EhtSectionRepositoryJdbc`（4 条只读 SQL）；`ReportSnapshotService` 加 4 参重载生成三段式 HTML（结论 / 证据 / 附录 + 目录锚点），**旧 2/3 参重载保持平铺不变**；`TestReportService` 装配并接线；新增 `GET /api/test-reports/{id}/sections`；详情页加证据层卡片复用 `CaseScriptLink` | 后端 `EhtPytestReportTest` 9 例 / `ReportDiffTest` 4 例；前端 `TestReportDetailView.spec.ts` 4 例；真实库 4 条 SQL 探针 2 样本 |
| W64-15 | 差异复用既有 `CompareService` 自动基线；`EhtSections.Diff.from()` 作映射单一真值，无基线一律 `none()` 并显示「无上次执行」 | `ReportDiffTest` 4 例（含有基线四类计数、无基线不许假报新增失败、null 结果） |

### 17.1 W5 偏差登记

| # | 偏差 | 原因 |
|---|---|---|
| B-20 | 第四态「未执行」改为「未判定」 | `test_result` 无 NOT_RUN 载体（A-16）；四值互斥穷尽反而让判据 2 可由 SQL 保证 |
| B-21 | 三段式只在新 4 参重载生效 | 旧重载被 `ReportSnapshotTest` 的平铺 HTML / hash 断言钉住（R-29），改它会让历史报告口径漂移 |
| B-22 | 锚点跳转放在 iframe 内部目录，不是外层左栏 | `sandbox=""` 无 allow-same-origin（A-21）；清洗器实测保留站内 `href="#"` |
| B-23 | 证据层 Vue 组件放在详情页外围卡片 | iframe srcdoc 是静态 HTML，无法渲染 Vue 组件（A-17） |
| B-24 | 附录执行环境四项只给有载体的真值，其余写「未采集」 | 无数据载体（R-26）；「不编造」优先于「看起来完整」 |
| B-25 | 差异基线沿用 DUT/LINK，不新增 kernel 过滤 | 既有 `CompareService` 服务执行报告链路，改它的基线口径会波及 v3.x 既有判据（A-19） |
| B-26 | 新增 1 个端点 `/{id}/sections` | 详情页外围证据卡片需要独立只读通道；契约由 457/375 升到 458/376 |
| B-27 | W5 零迁移 | 三段式是查询期装配（A-23），业务表数仍 109 |


## 18. W6 实际完成（W64-16 ~ W64-17）

| WBS | 完成内容 | 验收读数 |
|---|---|---|
| W64-16 | 5.9 既有批量删链路回归；新增 `frontend/src/utils/reportSelection.ts`（冻结判定与可删集合单一真源）+ 3 例单测；`TestReportsView` 勾选列冻结行改渲染「已冻结，请先解冻」、`toggleSelected` / `askDelete` / `batchDelete` 三处过滤；zh/en 两类删除文案明确区分「执行报告（运行记录）」与「测试报告」；后端冻结 409 文案统一到判据字面 | 前端 `reportSelection.spec` 3 + `TestReportsView.w57.spec` 8 + `ReportsView.spec` 7 + `TestReportsView.spec` 16；后端 `ReportLifecycleServiceTest` 15 |
| W64-17 | 新增 `POST /api/report-templates/{id}/copy` + `ReportTemplateService.copy()`；源只读、副本独立主表行与字段行、名称确定性顺延、内核与标题逐项复制、审计 `REPORT_TEMPLATE_COPY`；前端 `reportTemplates.ts.copy()` + 平台模板行按钮 + 就地重载与回执 | 后端 `ReportTemplateServiceTest` 15（含 4 条新增）；前端 `ReportTemplatesView.spec` 11（含 2 条新增）；契约 459/377 |

### 18.1 W6 偏差登记

| # | 偏差 | 原因 |
|---|---|---|
| B-28 | W64-16 从「开发批量删与影响面」降级为「回归 + 文案 + 前端防线」 | R-31：5.9 已全链路交付；重写等于造第二份实现 |
| B-29 | 后端冻结提示文案由「请先撤销冻结」改为「已冻结，请先解冻」，同步改 2 条既有单测 | W64-16 判据 ② 是字面口径，统一文案而非在文档里放松判据 |
| B-30 | 顺手修 `save/remove/makeDefault/copyToProject` 5 处回执被 `select()` 抹掉 | R-34 / A-27：W6 复制回执单测抓到的真实 UX 缺陷，不修则「复制成功」用户看不到 |
| B-31 | 复制权限择「与 `create` 同口径」：`bindProject` + `EXECUTION:UPDATE`，无权限 403 | 判据 ④ 允许二选一；不另造更宽通道，避免非管理员绕过模板创建权限 |
| B-32 | TC-v5.14-50 期望文案修订为「删除执行报告（运行记录）」 | W1 菜单口径 D-01 已是「执行报告 / 测试报告」，原「执行记录（含报告）」与之冲突；修订留痕在 `01-ASSESSMENT.md` A-27 |
| B-33 | 新增 1 端点 `/{id}/copy` | 复制是写动作，不能复用只读模板端点；契约由 458/376 升到 459/377 |


---

## 19. W7 实际完成（W64-18 ~ W64-19）

| WBS | 完成内容 | 验收读数 |
|---|---|---|
| W64-18 | 契约快照重生成与 `--check`；10MB / 4MB / 1MB 快照分级采样；10MB 快照上的导出耗时与 RSS 采样；读路径 kernel fail-closed 与三段式十项同源两个真缺陷修复 | 契约 459/377 PASS；10MB GET 1.570s ≤3s；导出 0.358s / 772 字节；`docs/evidence/v5.14.0/w7-http-probe.txt` |
| W64-19 | `scripts/gates.sh` 全量门禁；六份产物从 `_draft/v5.14.0/` 迁入 `docs/release/v5.14.0/`；PRD / DEV-PLAN / TEST-PLAN / 评估报告 / 调研报告回写；总纲 §5.3 进度回写 | GATES_RESULT FAILS=0 UNJUDGED=0；`check_release_docs.py v5.14.0` PASS；六份 `check_md_tables.py` PASS |

### 19.1 W7 偏差登记（B-34 ~ B-38）

| # | 偏差 | 原因 |
|---|---|---|
| B-34 | W7 从「只收口」变成「修复波 + 收口波」 | 首轮真实探针判红 3 条，其中 2 条是产品真缺陷；P0 判据不允许带着 fail-open 收口 |
| B-35 | 探针证据由 1 份扩成 4 份（首轮 / 主 / 补充 / 模板分级） | 首轮判红要留痕；模板分级 TC-36/37/38 属 P0，补独立真实 HTTP 探针 |
| B-36 | 10MB 性能采样直接写 `snapshot_html` 而非构造新报告 | DB 内 `REPEAT` 生成，避免 10MB 请求体；临时报告 finally 删除 |
| B-37 | 非管理员 403 用既有 `qa-e2e-viewer` 夹具账号，不新建账号 | 夹具口令从 env 读取，零凭据入仓、零账号污染 |
| B-38 | W7 修复后额外重建 jar 并重启一次 | `deploy.sh --verify` 判据 1 要求 jar commit == HEAD；不重建则「部署成功」是假绿 |
