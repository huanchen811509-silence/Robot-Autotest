# v5.12.0 测试报告（05-TEST-REPORT）

> 流程位置：⑥ 测试执行 → 结果落定。数据源：`TEST-PLAN.md` 的 44 条 TC + `DEV-PLAN.md` 六波执行状态。
> **不新造用例**：TC 编号与 TEST-PLAN 一一对应，逐条写结论与证据形态。
> 结论口径：`PASS` = 有可复现证据；`PARTIAL` = 只覆盖部分证据形态（写明缺口）；`FAIL` = 未达成。
> **本版不把「单测绿 / 构建绿」当放行理由**；真实链路项在 §7 单列。

## 1. 读数总览

| 项 | 读数 | 证据 |
|---|---|---|
| 后端全量单测 | **2095 条 / 0 失败 / 0 错误 / 4 跳过** | `backend/target/surefire-reports/TEST-*.xml` 317 份汇总 |
| 后端 W5 新增 | **17 条**：`GlobalAssetUpdateTest` 7 / `GlobalAssetBatchDeleteTest` 5 / `DevicePortTest` 5 | `mvn -o -B -q -Dtest=... test` |
| 前端全量单测 | **143 文件 / 1139 例 0 失败**（v5.11 收口 138 / 1089） | `npx vitest run --reporter=dot` |
| 资产页回归 | **42 条全绿**（既有 31 + W5 新增 11） | `GlobalAssetsView.spec.ts` + `AssetsView.spec.ts` |
| 类型检查 | `npx vue-tsc --noEmit` **0 error** | 命令输出 |
| 语言包机检 | **5/5 PASS**；zh/en 各 3060 键，源码硬编码中文 0 处 | `tools/check_i18n.py` |
| 接口契约 | **451 endpoints / 370 paths**，`--check` PASS | `tools/gen_api_contract.py` |
| 差异矩阵机检 | **12 条 + 4 条注入反证**，行数 = PRD §3 的 16 行 | `assetWorkspaceMatrix.spec.ts` |
| 幂等机检 | **10 条 + 6 条注入反证**，列 ⊆ 详情 DTO | `caseSetIdempotency.spec.ts` |
| 全量门禁 | **FAILS=0 UNJUDGED=0**（57 步） | `docs/evidence/gates/logs/` |
| 部署自校验 | **PASS=4 FAIL=0 SKIP=0** | `scripts/deploy.sh --verify` |

## 2. 逐条结论（TC-v5.12-01 ~ 44）

| TC | 结论 | 证据 / 说明 |
|---|---|---|
| TC-v5.12-01 | PARTIAL | `CaseSetWorkspace.spec.ts` 4 条覆盖三栏 DOM 与空态；1440×900 真视口三态截图未取（无截图环境） |
| TC-v5.12-02 | PASS | 未选中集合不默认选第一个，渲染自解释空态 `data-testid="case-set-empty"`；4 条布局壳单测 |
| TC-v5.12-03 | PASS | `GlobalCaseSetsView.vue` 三栏化后平台库入口仍可达且成员只读；既有回归未减 |
| TC-v5.12-04 | PASS | `caseSetIdempotency.spec.ts` 逐字段断言成员列 key 与 `CaseSetMemberView` 一致 |
| TC-v5.12-05 | PARTIAL | 服务端分页/筛选链路保留；200+ 集合搜索 ≤300ms 无真库样本与采样，未取读数 |
| TC-v5.12-06 | PASS | `?set=<id>&detail=<itemId>` 由页面 `syncQuery()` / `restoreSelectionFromQuery()` 恢复，视图级单测覆盖 |
| TC-v5.12-07 | PASS | 6 条注入反证（假列名 / 漏登记 / 通配符 / 不存在的 DTO 字段 / 无理由操作列 / 重复列）全部判红 |
| TC-v5.12-08 | PARTIAL | 详情字段数 ≥ 列数由单测成立；「详情值 = 库值」未做真库逐条比对 |
| TC-v5.12-09 | PASS | 详情字段数 ≥ 列数断言通过；`MEMBER_DETAIL_ROWS` 与列配置同源 |
| TC-v5.12-10 | PASS | 编号/标题/分类/优先级由同一 `TestCase` DTO 下发；脚本字段与用例清单同源（见 TC-15） |
| TC-v5.12-11 | PASS | 成员行接 `CaseScriptLink`，显示文件名、悬浮完整路径、复制按钮三要素 |
| TC-v5.12-12 | PASS | 前端不做 `split('/')`；`scriptPath` 由后端 `CaseScriptView` 计算，两处字符串同源 |
| TC-v5.12-13 | PASS | `CaseScriptView.empty()` 四态：`UNBOUND`（pytest 无记录）/ `NOT_APPLICABLE`（RF 等）/ `BOUND_VERIFIED` / `BOUND_UNVERIFIED` |
| TC-v5.12-14 | PARTIAL | 成员详情脚本行含路径 / nodeId / 状态三要素并复用同一组件；与库逐字比对未取 |
| TC-v5.12-15 | PASS | **计划假设被推翻后改写判据**：未新增成员端点、未补第二份 join；`TestSetMemberQueryTest` 断言 `scripts.of(...)` 只调一次且与清单端点五字段逐字一致 |
| TC-v5.12-16 | PASS | 成员批量条显示「已选 N 条」，选中后动作可用（`data-testid="member-batch-count"`） |
| TC-v5.12-17 | PASS | `TestSetService.removeItems()` 事务内删除 + 剩余 `reorder`；`CaseSetBatchTest` 断言 `seq_no` 连续；确认走 `confirmAction` |
| TC-v5.12-18 | PASS | 批量上下移复用 `reorder`；`CaseSetBatchTest` 覆盖空列表 / 重复 id / 集合外 id / 全部移出 |
| TC-v5.12-19 | PASS | `buildMemberCsv()` 固定五列（序号/编号/标题/分类/脚本路径）、BOM、逗号引号换行转义；`memberCsv.spec.ts` 4 条 |
| TC-v5.12-20 | PASS | 未选中时批量按钮 `disabled`（非隐藏可点），视图级断言 |
| TC-v5.12-21 | PASS | 删集合确认文案含集合名与成员数；`itemCount` 已补进前端 `TestSet` 类型 |
| TC-v5.12-22 | PASS | 批量移出确认显示数量；单条移除成员也先 `confirmAction()` |
| TC-v5.12-23 | PASS | 取消确认不调用任何写接口（`CaseSetsView.spec.ts` / W5 批量删 / 端口删三处取消用例） |
| TC-v5.12-24 | PASS | `grep -rn "window.confirm" frontend/src` 命中 **0**；历史注释改 `nativeConfirm` 伪码描述 |
| TC-v5.12-25 | PARTIAL | 共享表格由**单一** `AssetWorkspace.vue` 承载（79 行）；两页仍保留独有弹窗/拓扑/租约/表单，行数未收敛（见 §6） |
| TC-v5.12-26 | PASS | `visibleTabs('project')` = 4 页签、`visibleTabs('global')` = 2 页签，均由矩阵驱动 |
| TC-v5.12-27 | PASS | 12 条矩阵单测逐行校验 16 行差异；❌ 列/动作在对应 scope 不渲染 |
| TC-v5.12-28 | PASS | `AssetWorkspace.spec.ts` 源码断言：不含 `v-if="scope` 整块分支，且必须调用 `filterColumnsByMatrix` |
| TC-v5.12-29 | PASS | 两页既有 **31 条**视图级回归逐条通过（设备表 / 端口卡片 / 链路拓扑 / 租约 / 批量条 / URL / 采集 / 改名 / 录入）；W5 后扩到 42 条 |
| TC-v5.12-30 | PASS | 前端全量 143 文件 / 1139 例，例数较 v5.11 的 1089 只增不减 |
| TC-v5.12-31 | PARTIAL | 「来源」列标注与后端授权口径成立；A 项目「2 台授权 + 私有共 4 台」的真库 HTTP 构造未取（见 §7） |
| TC-v5.12-32 | PASS | **真实 HTTP 404**「设备不存在」（owner=95 / other=126 / device=21）；后端 `AssetScopeSql` 未改、前端不复制过滤 |
| TC-v5.12-33 | PASS | 项目页新增 `source` 列：`公共资产（已授权）` / `本项目`，由 `isPlatformAsset()` 判定；W5 新增两条断言 |
| TC-v5.12-34 | PARTIAL | 解除授权接口与入口保留，既有回归覆盖；B 项目不受影响的真库对照未取 |
| TC-v5.12-35 | PASS | **真实响应**：全局 13 台，项目侧可见数 0/4/1/1 不等，全局列表不受项目授权裁剪 |
| TC-v5.12-36 | PASS | 列与动作可见性都从 `assetWorkspaceMatrix.ts` 读；`matrixProjection()` 自检 |
| TC-v5.12-37 | PASS | `validateMatrixColumns('project', ...)` 注入 `refProjects` 判红；project 无「引用项目数」列 |
| TC-v5.12-38 | PASS | `validateMatrixColumns('global', ...)` 注入 `authStatus` 判红；global 无「授权状态」列 |
| TC-v5.12-39 | PASS | 4 条注入反证：❌ 列被渲染 / 矩阵删行 / 未声明 key / 新增未声明差异列，全部判红 |
| TC-v5.12-40 | PASS | 两 scope 编辑入口就位：全局 PUT 支持 name + 可选 managementIp；项目侧复用行内表单调 `deviceApi.update`；后端 7 条 + 前端 11 条断言 |
| TC-v5.12-41 | PASS | `GlobalAssetUpdateTest` 断言非管理员 `FORBIDDEN`；`requireAdmin()` 在 `update()` 首行；真实 HTTP 见 §7 |
| TC-v5.12-42 | PASS | `DevicePortTest` 断言被引用端口 409 且点名两条链路、不调用 `deletePort`；前端 409 文案原样展示（非笼统「删除失败」） |
| TC-v5.12-43 | PASS | `GlobalAssetBatchDeleteTest` 3 台 1 被引用 → 2 成功 1 `CONFLICT` 且含两个项目名；`conflicted` 只算被引用项，`NOT_FOUND` 单独登记 |
| TC-v5.12-44 | PASS | 契约快照 **451 endpoints / 370 paths**，`gen_api_contract.py --check` PASS；`POST /api/global/assets/batch-delete` 在快照内 |

**汇总**：44 条 TC 中 **37 PASS / 7 PARTIAL / 0 FAIL**（W6 真实 HTTP 后 TC-32 / TC-35 由 PARTIAL 升为 PASS）。PARTIAL 全部是「真视口截图 / 真库比对 / 性能采样 / 真实 HTTP」证据形态缺口，逐条在 §5 与 §7 登记原因，不用推断补齐。

## 3. 门禁与开发期抓到的真缺陷（本轮）

| # | 缺陷 | 怎么抓到的 | 修复 |
|---|---|---|---|
| 1 | 计划假设「补 `LEFT JOIN`」会写出第二份同源 SQL | W1 读 5.11 代码 | 改写成 `TestSetMemberQueryTest` 钉同源，零新增 join |
| 2 | PRD 假设「全局无 PUT」 | W5 实测 `GlobalAssetController` | 扩 `managementIp`，保留 1 参 `RenameRequest` 与 3 参前端调用 |
| 3 | 被引用端口删除会落外键 500 | 读 `DeviceService#deletePort` | 新增 `linkNamesByPort()` + 409 点名，SQL 过 `TenantGuard` |
| 4 | 409 只给数量不点名项目 | 读 `GlobalAssetService#delete` | `referenceProjectNames()` 查项目名并进文案 |
| 5 | `conflicted` 会把 `NOT_FOUND` 混进冲突数 | `GlobalAssetBatchDeleteTest` 设计评审 | 独立计数，逐条结果单独看 `items` |
| 6 | 语言包 `csvHeader` 数组形态被机检拒 | `check_i18n.py` | 改嵌套对象 `csv.*` |
| 7 | TS 诊断串含中文被当硬编码文案 | `check_i18n.py` | 诊断 reason 改英文码 |
| 8 | `createApp(Component, props, slots)` 不是 Vue API | `CaseSetWorkspace.spec.ts` 挂载失败 | 改 `render: () => h(Component, props, slots)` |
| 9 | 成员复选框类名被幂等单测当成信息列 | `caseSetIdempotency.spec.ts` 抽取正则 | 改名 `member-check`，复选框不计入信息列 |
| 10 | 后端 `CaseScriptView.empty("pytest")` 应为 `UNBOUND` 而非 `NOT_APPLICABLE` | `TestSetMemberQueryTest` 首次判红 | 改断言并补四态校验 |
| 11 | `AssetWorkspace.vue` 的 `readonly unknown[]` 与 `DataTable` 严格 prop 不兼容 | `vue-tsc --noEmit` | 通用容器 props 改 `any[]` / `any`，附注释说明适配原因 |

## 4. 与 v5.11.0 的读数对比

| 项 | v5.11.0 | v5.12.0 | 变化 |
|---|---|---|---|
| 后端单测 | 1995 | 2095 | +100（W1~W5 新增 + 既有回归） |
| 前端单测 | 138 文件 / 1089 例 | 143 文件 / 1139 例 | +5 文件 / +50 例 |
| 契约 | 449 / 368 | 451 / 370 | +2 endpoints / +2 paths（批量移出、批量删） |
| 门禁步数 | 57 | 57 | 未减步 |
| 迁移 | 零迁移 | 零迁移 | `V11_71` 未追加（无需索引） |

## 5. 遗留与降级（逐条写明理由与授权口径）

| 编号 | 内容 | 理由 | 归属 |
|---|---|---|---|
| L-62-01 | RF 用例在成员表仍显示「不适用」 | 依赖 5.11 的 L-61-01（RF 无脚本关联列），不猜路径 | 5.13 评估 |
| L-62-02 | 「授权租约」页签以子组件挂载，未配置化 | 租约是项目独有只读块，矩阵化收益低 | 保留现实现 |
| L-62-03 | 两套旧资产组件删除后回滚需按 tag 恢复 | tag `v5.12.0-pre-merge` 已验证存在 | 回滚预案 |
| L-62-04 | ~~批量删只点名首个引用项目~~ **已关闭** | W5 实现逐条返回全部引用项目名 | 已解决 |
| L-62-05 | 采集按钮行为未变（绑定自动关联、验证事实落库） | 评估 03 §3.3 顺序调整，避免在旧组件里写一遍 | 5.13 |
| L-62-06 | 真视口截图 / 1000 行 fps / 3000 分类耗时未采样 | 无浏览器性能采样环境；`perf-longlist.mjs` 产品口径 `rows=0` 不可当读数 | 沿用 5.11 未达成项 |
| L-62-07 | NFR-03 行数收敛 **未达成**（2725 > 1530） | D-6 取舍：保留两页独有块换零功能回退；不冒充达成 | 5.13 前专项收敛 |
| L-62-08 | 跨项目越界 / 授权后列表的真库 HTTP 对照 | 需两项目 + 私有资产样本；以 §7 部署实测结论为准 | W6 / 5.13 |

## 6. 未达成项（不写成达成）

| 项 | 判据 | 实测 | 结论 |
|---|---|---|---|
| NFR-v5.12-03 | 资产相关页面总行数 ≤1530 | 2725（1459 + 1187 + 79） | **未达成**；理由见 §5 L-62-07，已同步回写 PRD / ASSESSMENT / DEV-PLAN |
| NFR-v5.12-04 | 1000 行滚动 fps ≥ 50 | 未取读数 | **未验证**；不以 harness 独立 HTML 冒充产品口径 |
| NFR-v5.12-06 | 每条 FR 至少一条真实界面 / 真实请求 / DB 比对 | 9 条 FR 中 4 条有真实入口与单测，3 条真库比对缺失 | **部分达成**；PARTIAL 逐条登记 |
| NFR-v5.12-01 | 零业务表变更 | 零迁移，`db-audit` 表数与上版一致 | **达成** |
| NFR-v5.12-02 | 契约同步 | 451/370 `--check` PASS | **达成** |
| NFR-v5.12-05 | 幂等机器可查且能判红 | 10 条 + 6 条注入反证 | **达成** |
| NFR-v5.12-07 | i18n 完整 | 5/5 PASS，3060 键双语齐 | **达成** |

## 7. W6 补记：部署与全量门禁（真实链路）

> 本段在 W6 部署与门禁执行后填入真实读数；**不预填结论**。

| 项 | 读数 | 证据 |
|---|---|---|
| 部署自校验 | **PASS=4 FAIL=0 SKIP=0**（jar commit = HEAD = `b0a6362`；8081/8001 均 200；运行期版本 5.7.0 一致） | `scripts/deploy.sh --verify` |
| 真实 HTTP 探针 | **PASS=22 FAIL=0 TOTAL=22** | `docs/evidence/v5.12.0/w6-http-probe.txt` |
| 跨项目按 id 直访 | **404**「设备不存在」（owner=95 / other=126 / device=21） | 探针响应体 |
| 全局 PUT 与 IP 校验 | 不存在资产返 404；CREDENTIAL 传 IP 返 400「管理 IP 只适用于设备资产」 | 探针响应体 |
| 批量删 | 空列表 400 / 未知类型 400 / 不存在 id 逐条返回 `NOT_FOUND` 且 `conflicted=0` | 探针响应体 |
| 全局资产读数 | 全局平台设备 **13** 台；各项目可见数 0/0/0/0/0/0/0/0/4/1/0/1 | 探针响应体 |
| 全量门禁 | **FAILS=0 UNJUDGED=0**（57 步） | `GATES_RESULT` + `docs/evidence/gates/logs/` |
| 发布文档机检 | **PASS**（FR=9 / TC=44 / FAIL=0 / WARN=0） | `check_release_docs.py v5.12.0` |
| 表格机检 | **PASS**（6 份 / 85 表格块 / 599 数据行） | `check_md_tables.py` |

## 8. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：44 条 TC 逐条结论、11 项开发期缺陷、4 项未达成 | W1 ~ W5 的实测输出 |
| 2026-11-15 | W6 回写：补部署 / 门禁 / 真实 HTTP 读数，关闭或保留 PARTIAL | 见 §7 补记 |
| 2026-11-15 | 真实读数落定：门禁 **FAILS=0 UNJUDGED=0**（57 步）、HTTP **PASS=22 FAIL=0 TOTAL=22**、部署 **PASS=4 FAIL=0 SKIP=0**；TC-32/35 升 PASS | `w6-http-probe.txt` + `/tmp/w512_gates.log` |

## 9. W6 门禁修复纪实（三轮，逐条留痕）

> 门禁不是「一次跑绿」：第 1 轮 FAILS=4，第 2 轮 FAILS=1，第 3 轮才 FAILS=0。
> 每一轮暴露的都是真问题或真假红，逐条登记，不用「重跑一次就好了」糊过去。

| 轮次 | 判据 | 根因 | 修复 |
|---|---|---|---|
| 1 | 02-frontend_test | 草稿 PRD 迁入正式目录后，矩阵单测仍读 `_draft/v5.12.0/PRD.md` | 机检改双路径（正式优先 + 草稿回退），矩阵行数断言不变 |
| 1 | 06-lint（eslint） | `AssetWorkspace.vue` 5 处 `any`、spec 1 处 `any`、空接口 `CaseSetMemberView` | 工作台泛型化 `generic` + `DataTableColumns` / `IconName` / `IllustrationName`；空接口改类型别名并同步幂等单测断言 |
| 1 | 15-css_vars | `CaseSetWorkspace.vue` 用了不存在的 CSS 变量 `--n-border-color`，属性会静默回落 | 改成 theme.css 真实令牌 `--border` |
| 1 | 19-evidence_integrity | 02 失败导致前端日志无有效读数（连锁） | 02 修复后自动转绿 |
| 2 | 06-lint（check_tokens） | W1/W2 遗留 4 处 px 不在白名单（此前被 check_colors 挡住未跑到） | 复用既有档位：210 改 200 / 48 改 44 / 1100 改 1080 / 96 改 100，**不扩白名单** |
| 2→3 | 06-lint（check_icons） | 后顾断言只排除冒号，把复合属性 `empty-icon` 里的 `icon` 拆成静态图标名误报 | 正则后顾扩为「冒号 / 词字符 / 连字符」；自检新增 2 类场景（共 6 类），真实静态未定义名仍判红 |

**机检自身的修复也算交付物**：`tools/check_icons.py` 的第 5 类自检专门钉住「复合属性不误报」，
第 6 类钉住「真实静态未定义名仍被拦」——防止把正则改松到失去判定力。

| 轮次 | 结果 | 说明 |
|---|---|---|
| 第 1 轮 | FAILS=4 UNJUDGED=0 | 02 / 06 / 15 / 19 |
| 第 2 轮 | FAILS=1 UNJUDGED=0 | 仅 06（check_tokens 暴露 W1/W2 遗留 px） |
| 第 3 轮 | 见 §7 | 全量 57 步读数 |

### 9.1 收口时发现的计划偏差补充

| # | 偏差 | 处理 |
|---|---|---|
| A-7 | DEV-PLAN §7 W62-17 的验证命令 `python3 tools/check_gates.py` 在仓库中不存在（全仓 `find` 零命中） | 改为读 `scripts/gates.sh` 输出的 `GATES_RESULT FAILS=0 UNJUDGED=0`；DEV-PLAN §7 与 §12.2 B-6 已回写 |
| A-8 | 归档时把 PRD 从 `_draft/` 移到正式目录，矩阵单测路径未同步 | 第 1 轮门禁 02 判红后改双路径，见 §9 轮次 1 |

> 门禁日志本体：`docs/evidence/gates/logs/` 57 份；汇总读数 `GATES_RESULT FAILS=0 UNJUDGED=0`。
> 发布文档机检：全仓 36 个版本 **FAIL 0 / WARN 0**；v5.12.0 单版 `FR=9 TC=44`。
> 表格机检：6 份 / 87 表格块 / 615 数据行 **PASS**。
