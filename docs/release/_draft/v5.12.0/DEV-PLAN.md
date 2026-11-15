# v5.12.0 开发计划（DEV-PLAN）：用例集与项目资产 —— 合组件，但先写真值表

> 流程位置：④ 开发计划。**无开发计划不许开工编码**。
> 上游：`PRD.md`（9 FR + 7 NFR）、`02-列表与工作区交互评估.md` §四、`03-资产与采集闭环评估.md` §一~§二。
> 本文件给 WBS 编号、改动面与**可复制的验收命令**；不给「大约几天」，只给依赖关系与可独立验收的边界。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | 用例集三栏 | W62-01 ~ W62-02 | 是 | 复用 5.11 的 `DetailDock`；必须最先 |
| W2 | 成员幂等与脚本列 | W62-03 ~ W62-04 | 是 | 依赖 W1、5.11 的脚本 join 片段 |
| W3 | 批量动作与确认 | W62-05 ~ W62-06 | 是 | 依赖 W1 |
| W4 | 资产工作台合并 | W62-07 ~ W62-10 | 是 | 依赖 W3 收口（同批人不并行两条大改）；与 W1~W3 不相邻 |
| W5 | 授权边界与 CRUD | W62-11 ~ W62-14 | 是 | 依赖 W4（新组件就位才补动作） |
| W6 | 机检与收口 | W62-15 ~ W62-17 | 否（最终波） | 依赖 W1 ~ W5 全部出口判据 |

**并行纪律**：W1 → W2/W3 可并行；W4 是最大一块，必须单独排（它要删掉 2557 行里的两套组件）；
W5 必须在 W4 之后（动作挂在 `AssetWorkspace` 上）；**W6 的差异矩阵机检依赖 W4 的配置常量**，不能提前。

**本版预期零迁移**：若成员 join 需索引才追加 `V11_71`。
**回滚预案**：删除两套旧组件前打 tag `v5.12.0-pre-merge`，回滚按 tag 恢复（L-62-03）。

---

## 2. W1 用例集三栏（W62-01 ~ W62-02）

### W62-01 成员查询补脚本 join（后端）

| 项 | 内容 |
|---|---|
| 目标 | `TestSetRepositoryJdbc.java:197-215` 补 `LEFT JOIN case_script_binding`，与 5.11 **同一段 SQL 片段**（抽公共常量） |
| 改动面 | `CaseScriptJoin` 常量类（新增或复用 5.11 的）、`TestSetRepositoryJdbc`、成员 DTO、单测 |
| 出口判据 | ① 成员响应含 `scriptPath`/`scriptName`/`bindingState`；② SQL 片段与用例列表是同一常量（代码检查，无第二份 join 文本）；③ 不新增端点 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=TestSetMemberQueryTest test` |
| 依赖 | — |

### W62-02 用例集三栏布局

| 项 | 内容 |
|---|---|
| 目标 | 中栏 280px 集合列表（搜索 + 新建 + 平台库入口）；右工作区：集合头 + 批量动作条 + 成员表 + 成员 `DetailDock`；未选中空态 |
| 改动面 | `views/CaseSetsView.vue` 与 `GlobalCaseSetsView.vue` 重排（同一布局组件 `CaseSetWorkspace`）、spec |
| 出口判据 | ① 三态截图（空态/选中/详情展开）；② 未选中不默认选第一个；③ 平台库入口仍可达；④ 200+ 集合搜索 ≤300ms；⑤ `?set=&detail=` 刷新恢复 |
| 验证命令 | `cd frontend && npx vitest run src/views/CaseSetsView.spec.ts` |
| 依赖 | W62-01 |

---

## 3. W2 成员幂等与脚本列（W62-03 ~ W62-04）

### W62-03 成员列 ⊆ 成员 DTO 幂等

| 项 | 内容 |
|---|---|
| 目标 | 遍历成员列配置，断言每个 `key` ∈ 成员详情 DTO；注入假列名判红 |
| 改动面 | 成员列配置与 DTO 映射表、`caseSetIdempotency.spec.ts` |
| 出口判据 | ① 正常全绿；② 注入假列名判红（自检记录）；③ 详情字段数 ≥ 列数；④ 与用例清单同一条用例的关键字段一致 |
| 验证命令 | `cd frontend && npx vitest run src/views/__tests__/caseSetIdempotency.spec.ts` |
| 依赖 | W62-02 |

### W62-04 `CaseScriptLink` 接入成员行与详情

| 项 | 内容 |
|---|---|
| 目标 | 成员行「脚本」列 + 成员详情脚本行（路径/nodeId/状态三要素），复用 5.11 组件 |
| 改动面 | 成员表列配置、`DetailDock` 成员模板 |
| 出口判据 | ① 两处路径字符串与用例清单完全相同（三处一致性）；② 未绑定/RF 两态各一张截图；③ 复制成功 |
| 验证命令 | `cd frontend && npx vitest run -t 成员脚本` |
| 依赖 | W62-03 |

---

## 4. W3 批量动作与确认（W62-05 ~ W62-06）

### W62-05 批量动作条

| 项 | 内容 |
|---|---|
| 目标 | 补三个动作：批量移出、批量上移/下移（`sort_order` 落库）、导出成员清单 CSV（5 列） |
| 改动面 | `CaseSetsView.vue:673-690` 动作条、批量端点（若缺）、CSV 导出、spec |
| 出口判据 | ① 选中 N 条显示「已选 N 条」；② 批量移出后成员数减 N 且序号连续；③ 排序落库与显示一致；④ CSV 五列齐全；⑤ 未选中时禁用（非隐藏可点） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=CaseSetBatchTest test` |
| 依赖 | W62-02 |

### W62-06 删除与移除二次确认

| 项 | 内容 |
|---|---|
| 目标 | 删集合（含名与成员数）、移除成员（批量显数量）统一 `confirmAction`；取消后零写入 |
| 改动面 | `GlobalCaseSetsView.vue:281`、项目侧 `:801-803` |
| 出口判据 | ① 两个确认弹窗各一张截图；② 取消后 DB 行数不变；③ 全站 `window.confirm` 命中 0 |
| 验证命令 | `cd frontend && grep -rn "window.confirm" src \| wc -l`（须为 0） |
| 依赖 | W62-05 |

---

## 5. W4 资产工作台合并（W62-07 ~ W62-10）

### W62-07 差异矩阵配置化

| 项 | 内容 |
|---|---|
| 目标 | 把 PRD §3 的 20 行矩阵落成 `assetWorkspaceMatrix.ts`（列与动作的可见性唯一数据源） |
| 改动面 | 新增配置文件 + 类型定义；矩阵行数与 PRD §3 逐行对应 |
| 出口判据 | ① 行数与 PRD §3 表格一致（计数比对）；② 每行含 `key` / `projectScope` / `globalScope` / `note`；③ 无「代码里另写一份 v-if 判断」 |
| 验证命令 | `cd frontend && npx vitest run src/config/assetWorkspaceMatrix.spec.ts` |
| 依赖 | — |

### W62-08 `AssetWorkspace` 骨架与页签

| 项 | 内容 |
|---|---|
| 目标 | `AssetWorkspace(scope, projectId?)`：页签由矩阵驱动（project 4 / global 2），列与动作由矩阵驱动 |
| 改动面 | 新增 `components/AssetWorkspace.vue` + 子面板（设备/链路/凭证/租约）；矩阵读取 |
| 出口判据 | ① 两 scope 页签数 4/2（截图）；② 抽 6 行矩阵逐行界面可验「有/没有」；③ 模板里 `v-if="scope"` 整块数 ≤ 矩阵差异行数 |
| 验证命令 | `cd frontend && npx vitest run src/components/AssetWorkspace.spec.ts` |
| 依赖 | W62-07 |

### W62-09 两 scope 接入

| 项 | 内容 |
|---|---|
| 目标 | `/assets` 与 `/g/assets` 换用 `AssetWorkspace`；项目侧「引用平台资产/解除引用」保留；全局「导入导出」保留 |
| 改动面 | `AssetsView.vue`、`GlobalAssetsView.vue` 改为薄壳（传 props 即可） |
| 出口判据 | ① 两页与原功能逐条走查通过（走查清单）；② 前端全量单测全绿；③ 投影行数 = 矩阵行数 |
| 验证命令 | `cd frontend && npx vitest run && npx vue-tsc --noEmit` |
| 依赖 | W62-08 |

### W62-10 旧组件下线与行数收敛

| 项 | 内容 |
|---|---|
| 目标 | 删除被替换的旧页面主体；资产相关页面总行数 ≤1530（原 2557 的 60%） |
| 改动面 | 删除旧模板段落；`git tag v5.12.0-pre-merge` |
| 出口判据 | ① 行数统计达标；② 无死引用（`vue-tsc` 无输出）；③ tag 已打（回滚可恢复） |
| 验证命令 | `cd frontend && wc -l src/views/AssetsView.vue src/views/GlobalAssetsView.vue src/components/AssetWorkspace.vue` |
| 依赖 | W62-09 |

---

## 6. W5 授权边界与 CRUD（W62-11 ~ W62-14）

### W62-11 项目资产仅显授权（含越界直访）

| 项 | 内容 |
|---|---|
| 目标 | 验证前端不越界：未授权平台资产与其他项目私有资产不可见；按 id 直访详情返回 404/403 |
| 改动面 | 无后端改动（沿用 `AssetScopeSql`）；补越界用例与前端「来源」列 |
| 出口判据 | ① 3 台平台设备授权 2 台 → A 只见 2 台 + 私有；② B 的私有设备在 A 不可见（含搜索/筛选/直访）；③ 解除授权后消失且不影响他项目；④ 全局总数不受授权影响 |
| 验证命令 | `curl -s -o /dev/null -w "%{http_code}" "$API/api/projects/A/devices/B-private-id" -H "Authorization: Bearer $TOKEN"` |
| 依赖 | W62-09 |

### W62-12 全局 `PUT` 与项目编辑入口

| 项 | 内容 |
|---|---|
| 目标 | 补 `PUT /api/global/assets/{id}`（承接 5.9 FR-07 判据 3）；项目侧补前端编辑入口 |
| 改动面 | 后端 Controller/Service + 权限判定（仅管理员）+ 单测；前端编辑表单 |
| 出口判据 | ① 两 scope 都能改名称/管理 IP，保存后 DB 与界面一致；② 非管理员调用全局 PUT → 403；③ 契约快照更新 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=GlobalAssetUpdateTest test` |
| 依赖 | W62-09 |

### W62-13 端口编辑与删除

| 项 | 内容 |
|---|---|
| 目标 | 端口改/删前端入口（后端已有）；被引用时 409 点名 |
| 改动面 | 端口面板 + spec |
| 出口判据 | ① 改端口后 DB 与界面一致；② 删被链路引用的端口 → 409 点名，非 500；③ 表格下方沿用既有范式（非弹窗） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=DevicePortTest test` |
| 依赖 | W62-09 |

### W62-14 全局资产批量删

| 项 | 内容 |
|---|---|
| 目标 | 管理员批量删；被引用返 409 并点名引用项目；未引用成功 |
| 改动面 | 全局删端点（批量）+ 前端批量选择 + 影响面 |
| 出口判据 | ① 选 3 台（1 被引用）→ 2 成功 1 返 409；② 409 文案含项目名；③ 删除确认含影响面 |
| 验证命令 | `curl -s -X DELETE "$API/api/global/assets/batch" -d '{"ids":[1,2,3]}' \| jq` |
| 依赖 | W62-12 |

---

## 7. W6 机检与收口（W62-15 ~ W62-17）

### W62-15 差异矩阵机检/单测

| 项 | 内容 |
|---|---|
| 目标 | 矩阵标 `❌` 的列/动作在对应 scope 渲染结果里不得出现；新增列未更新矩阵 → 判红 |
| 改动面 | `assetWorkspaceMatrix.spec.ts`（含注入自检） |
| 出口判据 | ① project 无「引用项目数」列；② global 无「授权状态」列；③ 注入未声明列 → 判红；④ 矩阵行数 = PRD §3 行数 |
| 验证命令 | `cd frontend && npx vitest run src/config/assetWorkspaceMatrix.spec.ts` |
| 依赖 | W62-10 |

### W62-16 契约与迁移

| 项 | 内容 |
|---|---|
| 目标 | `openapi.json` 重生成（全局 PUT、成员响应扩字段、批量删） |
| 改动面 | 契约快照（WSL 内重生成） |
| 出口判据 | ① `--check` PASS；② `endpoints/paths` 读数写进测试报告；③ 零迁移或 `V11_71` 有 down |
| 验证命令 | `cd backend && python3 tools/gen_api_contract.py --check` |
| 依赖 | W62-12、W62-14 |

### W62-17 门禁全量与文档回写

| 项 | 内容 |
|---|---|
| 目标 | `GATES_RESULT FAILS=0`；六份齐全；总纲状态回写 |
| 改动面 | `docs/release/v5.12.0/*`（从 `_draft/` 迁入）、总纲交付表 |
| 出口判据 | ① 门禁 `FAILS=0`；② `check_release_docs.py v5.12.0` PASS；③ 表格机检全 PASS |
| 验证命令 | `python3 tools/check_gates.py 2>&1 \| tail -3` |
| 依赖 | W1 ~ W5 全部 |

---

## 8. 接口与数据变更清单

| 变更 | 类型 | 影响 | 处理 |
|---|---|---|---|
| `/api/case-sets/{id}/items` 响应加 `scriptPath`/`scriptName`/`bindingState` | 响应新字段 | 前端成员表 | 契约重生成 |
| `PUT /api/global/assets/{id}` | 新端点 | 全局编辑 | 契约重生成 |
| 全局资产批量删端点 | 新端点 | 管理员批量操作 | 契约重生成 |
| 成员批量排序/移出端点（若缺） | 新端点 | 批量动作条 | 契约重生成 |
| 数据库 | **预期零迁移** | 若 join 需索引则 `V11_71` | 配 `.down.sql` |

---

## 9. 风险与应对

| # | 风险 | 应对 |
|---|---|---|
| R1 | 合并资产工作台造成功能回退（两页各有独有动作） | 先写矩阵（W62-07）再写组件；W62-09 要求逐条走查清单，不靠「看起来对」 |
| R2 | 删旧组件后无法回滚 | 打 tag `v5.12.0-pre-merge`；L-62-03 登记 |
| R3 | 组件退化成「按 scope 复制整段模板」 | W62-08 出口判据 3 用 `v-if="scope"` 整块数 ≤ 矩阵差异行数做硬约束 |
| R4 | 项目侧越界（本应只看授权却看到别的项目设备） | W62-11 含「按 id 直访」用例；前端不复制过滤逻辑 |
| R5 | 成员幂等单测写成通配映射 | 注入假列名必须判红（W62-03 判据 2） |

---

## 10. 与相邻版本的边界

1. 本版**不新增采集逻辑**：`canCollect` 与采集按钮行为保持现状，缺陷登记 5.13（避免两版各写一遍）。
2. 本版复用 5.11 的 `DetailDock` 与 `CaseScriptLink`，**不写第二套**。
3. 本版**不合并后端资产端点**（权限与审计语义不同），只合前端。
4. 与评估 03 §3.3 的顺序差异已留痕：先合组件（本版）再在组件内接采集（5.13），实际只写一次。

---

## 11. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：6 波 17 个 WBS；矩阵先行、合并后补 CRUD；打 pre-merge tag 保回滚 | `PRD.md` v5.12.0 + 评估 02 §四 + 评估 03 §一~§二 |
