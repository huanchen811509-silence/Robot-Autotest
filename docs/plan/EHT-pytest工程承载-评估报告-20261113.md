# EHT pytest 工程承载与适配解耦 — 评估报告（2026-11-13）

**撰写** 学徒 → 交大师拍板 ｜ **性质** 七道流程第 1–2 环产物（调研 + 评估），**本文只评估，不建项目、不写代码、不改数据、不 bump 版本**。
**数据时点** 2026-11-13 18:20 GMT+8（`robot_dev` 实时查询 + `~/robot-autotest` @ `ac92543` 源码/文档 + `E:\workspace\自动化测试\pytest-cases` 工作树）。
**上游文档** `docs/plan/pytest用例库适配-评估与优化报告-20261112.md`（v2.0）、`docs/plan/v5.1-5.4-pytest用例库工程化-产品PRD-20261112.md`。本文只补「实体落地」与「命名」两处新事实，不推翻上游结论。

---

## 0. 结论先行

| # | 问题 | 结论 |
|---|---|---|
| 1 | 现在有没有项目承载 pytest-cases | **没有**。7 个项目里 0 个 `kind=pytest-suite`、0 条 `pytest.*` 配置、0 行绑定、0 行 `pytest_case_step`。适配代码齐备，**承载实体缺失** |
| 2 | 能不能马上建一个 | **不能，HTTP 层没有入口**。`POST /api/projects` 的请求体不含 `kind`，6 参 `ProjectService.create(..., kind)` 无任何 HTTP 调用方（§2.2）。这是第一个要补的包 |
| 3 | 用 `pytest-eht` 做 kind 行不行 | **技术上可行，语义上不建议**（§3）。`kind` 是「工程类型」维度，不是公司归属、也不是内核方言维度；EHT 身份应放项目 code/显示名/标签，「区分标准 pytest」应走 `test_kernel`（或将来扩内核码） |
| 4 | 适配/解耦/用例管理/可视化/便捷操作 | 后端能力大部分已备（7 端点 + `application/kernel/pytest/` 一整套服务 + 5 步机检），**缺的是承载实体、创建入口、6 张前端页、真实链路跑通**。详见 §6 差距矩阵 |
| 5 | 本轮边界 | 只交评估 + 工作规划；PRD / DEV-PLAN / TEST-PLAN 正式化与编码须等 D 决策项拍板（§8） |

**一句话**：这是「最后一公里」问题，不是重新造轮子——但最后一公里里有两处**文档与代码不符**必须先修口径（§2.3、§2.4），否则建出来的工程会「界面标 pytest、实际跑 RF」。

---

## 1. 需求还原与可判定拆解

大师原话拆成 6 条，每条给出可判定的验收判据（写不出判据就不算需求）：

| # | 需求 | 判定口径（可复核） | 证据形态 |
|---|---|---|---|
| N1 | 软件里要有一个项目承载用例库 | `robot_dev.project` 存在 1 行 `kind=pytest-suite`，且其 `project_setting` 有可执行必填 4 项；`GET /api/projects/{id}/pytest/health` 200 且「可跑」 | 接口响应 + 库查询 JSON |
| N2 | 适配 | Fake 模式全量 70 tests 经平台通道执行 → junit 归一 → 结果入库，全 `PLANNED`，通过率显示「不适用」；RF 链路不受影响 | `run.log` / `output.xml` / 归一结果 JSON |
| N3 | 解耦 | 平台**不写**用例库源码；用例库为 git 真源，平台只存路径/绑定/快照；`check_kernel_decoupling.py` PASS；断掉用例库目录后工程健康检查报「仓库不可达」而非崩溃 | 机检输出 + 注入演练录屏/日志 |
| N4 | 测试用例管理 | 469 条分母可查、可按大类/优先级/评审态筛选、可看单条绑定与脚本路径、重命名跟随、非自动化用例可编辑 | UI 截图 + 列表接口响应 |
| N5 | 可视化 | 覆盖率双口径、绑定健康度、时长基线 P90/P99、失败归因分布、执行进度、双内核统一视图均有页面入口且读真实端点 | UI 截图 + 端点响应 |
| N6 | 便捷操作 | 一键同步绑定 / 一键 Fake 自证 / 一键发起执行（含预检）/ 错误原因人话展示 / 命令集合一键复制 / 批量选用例 | UI 操作录像或逐步截图 + 接口调用日志 |

`[推断]` N4–N6 的「都要实现」按 v5.x 已定 FR 口径理解，不额外发明新概念；若大师指的是另一套界面，需在 D-G 拍板时点明。

---

## 2. 事实清单（每条标来源；区分已核实与推断）

### 2.1 承载实体现状 `[已核实]`

| 检查 | 结果 | 来源 |
|---|---|---|
| 项目总数 / kind 取值 | 7 个（1/29/77/80/88/92/93）；`DISTINCT kind = standard, platform` | `SELECT id,code,kind FROM project` |
| `kind=pytest-suite` 项目 | **0** | `SELECT COUNT(*) FROM project WHERE kind='pytest-suite'` |
| `pytest.*` 项目配置 | **0**（`project_setting` 全表仅 4 条 `kernel.default`） | `SELECT * FROM project_setting` |
| 用例↔脚本绑定 / 步骤证据 | `case_script_binding = 0`、`pytest_case_step = 0` | 两表 `COUNT(*)` |
| 唯一 pytest 内核项目 | `80 demo-pytest`（standard，12 用例，v2.7 双内核演示），未配 `repo_path` | 库查询 + PRD |
| 昨天删的 89/90 | `PRJ-PYT-202611-001/002`，`__opt_ft_*` 模板取证空壳，非承载工程 | `docs/plan/夹具项目盘点与删除评估-20261108.md` §7 |

`robot_it` 库仅 1 个 `PLATFORM-LIB`，无 pytest 工程。

### 2.2 阻塞点 A：HTTP 层建不出 pytest 工程 `[已核实]`

- `ProjectController.CreateProjectRequest` = `(code, name, description, visibility, testKernel)`，**没有 `kind`**（`ProjectController.java:30-32`）。
- `POST /api/projects` 调 5 参 `projects.create(...)`，5 参重载固定回落 `"standard"`（`ProjectService.java:100-102`）。
- `POST /api/projects/from-template` 最终也调 5 参 create（`ProjectTemplateService.java:440`）。
- 6 参 `create(..., String kind)`（`ProjectService.java:105`）**在 `backend/src` 内没有任何调用方**，只在枚举与单测里被间接验证。

→ 结论：**不补 API 就无法通过界面/接口建出 `pytest-suite` 工程**；只能物理 SQL 插，而那违反「不做物理 SQL」口径且绕过审计。

### 2.3 阻塞点 B：`pytest-nic` 内核码不存在，FR 状态口径偏差 `[已核实]`

| 位置 | 写的 | 实际代码 |
|---|---|---|
| 评估报告 §4.1 / PRD FR-v5.2-01 R3 | `kind=pytest-suite` 的项目级默认内核 = **`pytest-nic`**（KernelSelector 第一优先级） | `ProjectService.normalizeKernel()` 只认 `robot` / `pytest`，其余抛「不支持的测试内核（仅 robot / pytest）」（`:170-178`）；`KernelRegistry` 只收集到 `RobotKernel("robot")` 与 `PytestKernel("pytest")` 两个 SPI |
| PRD §13.1 FR-v5.2-01 状态 | **达成** | 依据只有 `V11_50 + check_navigation.py`（菜单树 4 组 11 叶），**未覆盖 R3** |
| `ProjectService.create()` | — | 内核由显式 `testKernel` 入参决定并 `syncKernelSetting(kernel.default=...)`，**kind 不参与内核推默认** |

→ 这是「文档与代码不符」，按项目硬规矩必须当场指出、不能悄悄带过。两条修法见 §5.2 / WBS 包 EHT-05：要么实现内核码，要么把 FR R3 改写成「显式 `testKernel=pytest`」并回写三份文档。**学徒不擅自改 FR 状态，交大师定（D-C）。**

### 2.4 阻塞点 C：用例库 git 工作树的「脏」是假象 `[已核实]`

`git -C /mnt/e/workspace/自动化测试/pytest-cases status --short` 显示 **162 个文件 M**，`git diff --stat` 为 `17067 insertions / 17067 deletions`；但 `git diff --ignore-cr-at-eol --stat` **为空**，`--numstat` 每个文件增删行数完全相等 → **纯 CRLF/LF 行尾差异，内容零改动**。`HEAD = 915c2ca`，`core.autocrlf` 未设置。

→ 不能把「162 文件已改」写成用例库不稳定；但按「git 为用例真源」契约，**绑定快照必须记 `rev-parse HEAD` 而不是工作树哈希**，且执行前应先固定行尾口径（否则 Windows 侧任一次编辑器保存都会让绑定脚本哈希漂移）。这是 N3 解耦里必须落地的一条。

### 2.5 已备能力 `[已核实]`

| 层 | 已有 | 来源 |
|---|---|---|
| 后端接口 | `PytestProjectController` 7 端点：`health` / `bindings` / `bindings/sync` / `settings` GET+PUT / `settings/validate` / `runs/{runId}/evidence` | `PytestProjectController.java` |
| 后端服务 | 配置必填 4 项 `repo_path` / `python_bin` / `env_config` / `inventory_path`；`PytestPrecheckService` 缺项拒跑；`PytestBindingSyncService` 扫盘建绑定 + 重命名跟随；`PytestCoverage` 双口径；`PytestHealthService`；`PytestEvidenceService` | `application/kernel/pytest/` |
| 工程视图 | `PYTEST_SUITE_NAVIGATION` 4 组 11 叶（工作台 / 用例库 / 执行 / 报告），刻意不含资产、环境、维护入口 | `frontend/src/config/navigation.ts:484+` |
| 前端页面 | **仅 1 张** pytest 专属页 `PytestWorkbenchView.vue`（594 行：健康、双口径覆盖率、绑定表、同步、设置、证据抽取） | `frontend/src/views/` |
| 数据分母 | `case-index.json`：`cases=469`、`scripts=62`、`scripts_with_declared_code=61` | `docs/evidence/v5.1.0/pytest/case-index.json` |
| 机检 | 门禁 48 步，含 `check_pytest_env_render.py` / `check_pytest_binding.py` / `check_kernel_decoupling.py` / `check_nav_kind.py` / `check_progress_channel.py` 等；`db-audit` 105 表 PASS | `scripts/gates.sh` |
| 遗留 | **L-54-01 六张前端页未落地**（时长基线 / 处置闭环 / 环境雷达 / 双内核视图 / 手工执行 / 分离统计）；L-54-02 G-1 AI 端点未授权；L-54-03 报告幂等键降级 | 测试计划 §12.2 |

`[推断]` 后端接口形状已按六页所需产出（测试计划 §12.2 自述），因此六页属「接入」而非「重写后端」；但**未经真实端点联调验证**，不能当作已核实结论，WBS 里必须留联调包。

### 2.6 用例库侧 `[已核实]`

`E:\workspace\自动化测试\pytest-cases` 根含 `config/`（inventory_lab.yaml / run_env_lab.yaml / fake_plan.yaml / device_specs.yaml）、`src/nic_platform/`、`tests/nic/`（`test_*.py` 62 个）、`docs/`；WSL 视角路径 `/mnt/e/workspace/自动化测试/pytest-cases`。工具脚本 `tools/gen_pytest_case_index.py` 与 `tools/check_pytest_env_render.py` 已把该路径写成默认根，但产物是**门禁证据 JSON**，不是项目绑定。

---

## 3. 命名专项：`kind=pytest-eht` 可否

### 3.1 大师这句话里的三层意思，先拆开

| 想区分什么 | 现在的维度 | 该不该用 kind |
|---|---|---|
| ① RF 项目 vs pytest 项目 | `project.test_kernel`（`robot`/`pytest`）+ `project.kind`（`standard`/`platform`/`pytest-suite`） | 已经够了，不用动 |
| ② EHT 自研 pytest 框架 vs 通用标准 pytest | **内核码**：`KernelRegistry` 现在只有 `pytest` 一个 SPI，没有第二执行器 | **该扩 `test_kernel` / 注册第二个 SPI**，不是改 kind |
| ③ 这个用例库是 EHT 公司的 | 项目 `code` / `name` / 标签 / 可选 `vendor` 字段 | **不该**把公司缩写固化进平台类型枚举 |

`pytest-eht` 把 ② 和 ③ 混进了「工程类型」这一个字段。kind 的语义在 `ProjectKind.java` 里写得很清楚：**界面与执行链如何分派**（菜单树、必填校验、看板口径），不是「谁家的框架」。

### 3.2 技术可行性 `[已核实]`

| 项 | 结论 |
|---|---|
| 列宽 | `kind VARCHAR(16)`，`pytest-eht` 9 字符够放；`V11_50` 明确「只改注释不改列定义」 |
| 迁移 | **V11_50 已应用、不可改（checksum）**；改名只能追加新迁移（当前最高 V11_55 → 顺延 **V11_56**）改列注释 |
| 字面引用 | 全仓 `pytest-suite` **12 文件 / 45 处**：`ProjectKind.java` 2、`ProjectKindTest.java` 5、`navigation.ts` 3、`navigation.spec.ts` 3、`App.vue` 1、`V11_50` 2、评估/PRD/开发/测试/台账 5 份文档 29 处 |
| 常量名 | 另需动 `tools/check_navigation.py` 的 `PYTEST_SUITE_NAVIGATION` 数组提取名、`tools/check_nav_kind.py` 基线口径、`ProjectKind.isPytestSuite()` 方法名、前端 `ProjectKind` 联合类型；i18n 键（`menu.pytestWorkbench` 等）可保留 |
| 门禁 | 菜单叶子基线、`check_nav_kind` 期望值、`check_release_docs` 的 FR 编号引用需同步重刷；每步 `--self-test` 要真注入判红再还原 |

→ **能改，成本是 1 个独立交付包（1 迁移 + 约 14 个源码/机检文件 + 5 份文档口径回写 + 单测重跑），不是做不到。**

### 3.3 三个方案比选

| 方案 | 做法 | 好处 | 代价 / 风险 | 意见 |
|---|---|---|---|---|
| **A（推荐）** | kind 保留通用名 `pytest-suite`；项目 `code=EHT-PYTEST-LIB`、显示名「EHT pytest 用例库工程」；需要区分执行器时扩 `test_kernel`（如 `pytest-eht` SPI） | 平台通用语义不被公司缩写污染；EHT 身份在「项目」这一层清晰可见；未来第二个产品线直接再建一个工程即可 | 界面上类型标签仍是「pytest 用例库工程」，EHT 身份靠名称/编码体现 | **建议采纳** |
| B | kind 直接改名 `pytest-eht`（大师提议） | 类型值一眼看出归属；与「区分标准 pytest」的直觉一致 | 把公司缩写写死在平台枚举；将来接非 EHT 用例库要么再改一次名、要么再造一个 kind；迁移/机检/文档/证据口径全量回写；且**仍然没解决 ②**（同一个 kind 下还是只有一个 `pytest` 内核） | 可行但不推荐；若大师坚持，学徒照办并单列 EHT-R 包 |
| C | 保留 `pytest-suite`，**新增**第二个 kind `pytest-eht` | 未来真有两类工程、菜单与口径确实不同时，语义最干净 | 现在只有 1 个用例库，多一个 kind 等于多一套菜单/口径要维护，属于提前设计 | 留作未来选项，本轮不启 |
| D | 不建 kind，用 standard 项目 + `kernel.default=pytest`（复用 80） | 零改动 | 必填校验、覆盖率分母、工程视图、拒跑保护全部丢失，退回「塞进某个开关」的老路 | **明确否决**（上游评估 §4.2 已列五条理由） |

### 3.4 命名结论

**建议 A**：`kind` 用平台通用名，**EHT 放在项目实体上**（code / 名称 / 标签）；「区分标准 pytest」若指执行器，用 `test_kernel`（需注册第二个 SPI，见 D-C）；若只是归属，用项目 code 就够。

**若大师仍定 B**，学徒按 B 执行，但会把「改名」与「承载工程落地」拆成两个独立 WBS 包，先改名再建工程，避免一次改动里混着「枚举变更 + 数据新增 + 前端六页」三类风险；且改名包的验收判据必须包含「旧值 `pytest-suite` 在库里 0 行、源码 0 处残留、机检基线重刷」。

---

## 4. 承载项目的设计（沿用上游 §4.3，补 2 条）

| # | 承载物 | 载体 | 本轮补充 |
|---|---|---|---|
| 1 | 仓库绑定 | `project_setting.pytest.repo_path` / `repo_url` / `branch` | **引用，不是副本**；路径用 WSL 视角 `/mnt/e/workspace/自动化测试/pytest-cases` |
| 2 | 运行时 | `pytest.python_bin` / `mode_default` | 安全默认 `fake`；换解释器后健康检查复验 |
| 3 | 资源池 | `project_asset` + `execution_selection` 快照 | 顺序即语义，冻结留哈希 |
| 4 | 用例分母 | `test_case`（469 条口径） | `source_sheet='详细测试流程'`、评审态归属规则沿用 H11 |
| 5 | 执行基线 | `case_duration_baseline`（V11_54 已建） | 冷启动用分类中位数 |
| 6 | 报告策略 | `report.*` 设置 + `ReportAutoGenerator` | 幂等键降级 L-54-03 如实带入 |
| **7（新）** | **项目身份** | `project.code` / `name` / `project_label` | EHT 归属放这里，不进 kind |
| **8（新）** | **真源快照** | 新增只读记录（建议 `pytest.repo_head` 配置项或证据 JSON） | 记 `git rev-parse HEAD`（当前 `915c2ca`）与同步时间；**工作树行尾差异不进快照** |

**不做的边界**：一个工程绑多个仓库（N1）、自动建项目（N2，`project:create` 仍需人确认）、复用 `robot_file` 当真源（N3）、平台写用例库源码（D2）、物理 SQL 建项目。

---

## 5. 适配与解耦评估

### 5.1 适配链路（真实链路判据，不认「单测绿」）

平台渲染 env → 绑定归一（`<文件末段>::<函数名>`）→ 命令构造 → 契约探测 → 执行（`-o junit_family=xunit2` + `--capture=fd` + `-o junit_logging=out-err` 三条件）→ 结果归一 → 归档。回归红线 **RK-1~RK-5**：RF 内核不受影响为第一条。Fake 模式（`--mode fake`，70 tests 全 PLANNED）是无需现场设备即可跑通全链路的唯一现有手段，应作为每个 WBS 包的收口验收。

### 5.2 解耦五条硬约束

| # | 约束 | 判据 |
|---|---|---|
| E1 | git 是唯一用例真源，平台只引用 | 用例库目录只读；`check_kernel_decoupling.py` PASS；平台内无脚本副本 |
| E2 | 不写用例库源码 | 同步/绑定/执行全过程对 pytest-cases 工作树零写入；执行前后 `git status` 快照一致（除行尾） |
| E3 | 凭据零明文 | `${ENV:XXX}` / `credential` 表；`secret-scan.sh` PASS |
| E4 | 配置与代码分离 | 路径/解释器/inventory/run_env 全在 `project_setting`，不硬编码；`PytestSettingsService.validate` 可只校验不落库 |
| E5 | 断链可降级不可崩 | 用例库目录不可达时 `health` 报「仓库不可达」并给修复提示，不抛 500、不阻断 RF 项目 |

### 5.3 一个必须先修的口径

§2.3 的 `pytest-nic` 偏差若不修，新建工程时会出现「界面说默认内核是 pytest-nic，落库却是显式传的 pytest/robot」。**建议把 FR-v5.2-01 R3 与评估报告 §4.1 表格一并回写为代码真值，并把 PRD §13.1 该行状态改判为「降级达成（R3 未落地，理由：内核码未注册）」**——这属于文档回写，须大师授权（D-C），学徒不擅自改 FR 状态。

---

## 6. 用例管理 / 可视化 / 便捷操作差距矩阵

| 能力 | 后端 | 前端 | 缺口归属 |
|---|---|---|---|
| 工程创建与配置 | 6 参 create 存在但无 HTTP 入口；settings 6 端点齐 | 工作台页有设置表单 | **EHT-04 / EHT-06** |
| 用例列表、筛选、评审态 | 通用 `/cases` 接口齐；`case-index.json` 469 条 | 通用用例页可复用，但**未按 pytest 工程分母/评审态定制** | **EHT-09** |
| 绑定与重命名跟随 | `PytestBindingSyncService` 齐（0 行数据） | 工作台页绑定表 + 一键同步齐 | **EHT-07**（跑数据） |
| 非自动化用例编辑 | `ManualExecutionService` + V11_55 齐 | **页面未落地** | L-54-01 → **EHT-10** |
| 覆盖率双口径 / 健康度 | `PytestCoverage` / `PytestHealthService` 齐 | 工作台页已展示 | 已备，联调验证 |
| 时长基线 / 归因分布 / 环境雷达 / 双内核视图 / 手工执行 / 分离统计 | 6 套接口齐并有单测 + 机检 | **六页全缺（L-54-01）** | **EHT-10** |
| 执行进度可视化、错误原因展示 | `ExecutionMonitor` + `FailureBreakdown` 齐 | 无专门页 | **EHT-10 / EHT-11** |
| 一键 Fake 自证 / 一键执行 / 批量选例 / 命令集合复制 | 预检、命令集合、报告生成齐 | 无聚合入口 | **EHT-11** |
| 真实端点联调 | 端点 403 个（openapi） | 六页没接真端点 | **EHT-12 / EHT-13** |

→ **本轮工作量重心在「建实体 + 补创建入口 + 前端接入」，不在重写后端**；但「后端接口形状已按页面产出」只是文档自述，未经联调，必须留验证包。

---

## 7. 风险与不做的边界

| 风险 | 影响 | 缓解 |
|---|---|---|
| R1 改名与建工程混在一个改动里 | 枚举、数据、六页三类风险叠一起，出问题难定位 | 拆包；先口径后建实体 |
| R2 用例库 CRLF 工作树导致脚本哈希漂移 | 绑定每次同步都判「重命名」 | 快照只记 HEAD；同步前固定行尾口径 |
| R3 六页联动 i18n / 菜单叶子基线 / spec | 每加一页都要重刷 `check_nav_kind` 期望值 | 每页独立提交 + `--self-test` 真注入判红还原 |
| R4 G-1 未授权 | AI 归因建议（L-54-02）无法端到端验收 | 本轮不承诺，按「未达成」如实登记 |
| R5 门禁 48 步已知 `FAILS=2`（13 CDP 已取消、29 host_inventory 需现场重采） | 不能把「新增步全绿」说成「整体全绿」 | 读数只看尾部 `GATES_RESULT FAILS=N`；两段不得相加 |
| R6 每跑全量门禁会重写 4 份已跟踪副产物 | 收口时误提交漂移 | 判门前 `git restore --` 四份 |

**不做**：不复制用例库源码进平台、不改 RF 内核、不物理 SQL 建项目、不自动建项目、不擅自 bump 5.0.0、不对外称 GA、AI 不勾 M 项 / 不判 G 门。

---

## 8. 待大师拍板的决策项

| # | 决策 | 学徒建议 |
|---|---|---|
| **D-A** | kind 命名走 A / B / C | **A**（保留 `pytest-suite`，EHT 放项目实体）；B 照办但拆包 |
| **D-B** | 项目 code 与显示名 | `code=EHT-PYTEST-LIB`，name「EHT pytest 用例库工程」（可改） |
| **D-C** | 是否注册第二个内核码 `pytest-eht` SPI，还是先沿用 `pytest` + 配置区分；以及是否授权回写 FR-v5.2-01 R3 状态 | 先沿用 `pytest`，**授权回写口径偏差**；第二 SPI 等真有第二执行器再启 |
| **D-D** | 用例库真源口径：绑工作区路径 vs 绑固定 commit；是否先处理 CRLF 工作树 | 绑工作区路径 + 记 `rev-parse HEAD` 快照；CRLF 只固定口径不批量改写 |
| **D-E** | 版本区间与是否 bump | 建议 5.5.x（X<15）承载；**当前 5.0.0 不动**，发版归大师 |
| **D-F** | G-1 AI 真实端点是否授权 | 未授权则 L-54-02 保持「未达成」，不写入本轮验收 |
| **D-G** | L-54-01 六页是否本轮全做 | 建议全做（对应「可视化都要实现」），但按页分包、逐页验收 |

---

## 9. 流程状态（七道流程对齐）

| 环节 | 状态 | 产物 |
|---|---|---|
| 1 调研 | **完成** | 本文 §2 事实清单（已核实 / 推断分标，逐条标来源） |
| 2 评估 | **完成** | 本文（含 §3 命名专项、§8 决策项） |
| 3 PRD | 未启 | 待 D-A~D-G 拍板后出 `FR-EHT-xx` 正式 PRD |
| 4 开发计划 | 未启 | 本轮只交《工作规划》（WBS 草案），正式 DEV-PLAN 待 PRD |
| 5 测试计划 | 未启 | 本轮只交纲要（TC 编号 + 证据形态） |
| 6 测试执行 | 未启 | 未测不许发版；不接受实现方自证 |
| 7 文档回写 | 未启 | 四份文档 + 台账 + 项目记忆，测试完必做 |

**本轮未建项目、未改数据、未改源码、未 bump 版本。** 库状态与上一份删除评估 §7 一致（7 个项目）。

---

## 修订记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.0 | 2026-11-13 | 首版：承载实体现状、三处阻塞点（API 无 kind / `pytest-nic` 口径偏差 / CRLF 假脏）、`pytest-eht` 命名三方案比选、差距矩阵、D-A~D-G 决策项 |
