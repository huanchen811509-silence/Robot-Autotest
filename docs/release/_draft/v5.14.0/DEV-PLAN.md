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
| 验证命令 | `cd backend && mvn -o -B -Dtest=SuiteCountTest+CaseKeySplitTest test` |
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
| 验证命令 | `python3 tools/check_gates.py 2>&1 \| tail -3` |
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
