# EHT pytest 工程承载与适配解耦 — 产品迭代优化 PRD（2026-11-13）

**撰写** 学徒 ｜ **审批** 大师（2026-11-13 19:00 GMT+8「均按你的建议来」，D-A ~ D-G 全项采纳）
**性质** 七道流程第 3 环正式产物（P1）。**只定义做什么与怎么判定；本轮不编码、不建项目、不改数据、不 bump 版本。**
**上游** `docs/plan/EHT-pytest工程承载-评估报告-20261113.md`（调研＋评估，v1.0）、`docs/plan/EHT-pytest工程承载-工作规划-20261113.md`（WBS 草案，v1.0）、`docs/plan/v5.1-5.4-pytest用例库工程化-产品PRD-20261112.md`（既有 22 条 FR）
**基线** `~/robot-autotest` @ `0248498`；`VERSION=5.0.0`；门禁 **48 步 / FAILS=2**（13 CDP 已取消、29 host_inventory 需现场重采）；业务表 **105**；openapi **403 endpoints / 334 paths**；`robot_dev` 项目 **7 个**；`pytest-cases` 真源 `HEAD=915c2ca`。
**流程规范** `docs/process/版本开发流程.md` §2.3（PRD 必含范围／不做、FR＋判据＋WBS、非功能、数据接口影响、退出标准）、§2.7（回写逐项做，不许合并成一句）。

---

## 0. 结论先行

| # | 结论 |
|---|---|
| 1 | 本 PRD 把评估报告的 6 条需求 N1–N6 与 3 处阻塞点，翻译成 **18 条 `FR-v5.5-xx`**，每条都能直接写成测试用例 |
| 2 | D-A~D-G 全部按学徒建议落定（§2）：kind 保留 `pytest-suite`，EHT 身份放项目 `code/name`；**不新增 `V11_56` 改名迁移**；`EHT-06` 命名包标记「不适用」 |
| 3 | 范围重心是「建实体 + 补创建入口 + 前端接入 + 真实链路跑通」，**不重写后端**：后端 7 端点、解耦/归一/预检/覆盖率/基线/归因服务等已备，缺的是承载工程与 6 张前端页 |
| 4 | 编号按机检正则修订：草案的 `FR-EHT-xx` / `TC-EHT-xx` → 正式 `FR-v5.5-xx` / `TC-v5.5-xx`；WBS `EHT-01~16` 不变（§2.2） |
| 5 | 版本号 **5.0.0 不动**，FR 前缀里的 `v5.5` 是文档编号不是发版号；发版区间建议 5.5.0~5.5.x（X<15），**G 门与发版归大师** |

---

## 1. 范围

### 1.1 做（对应评估 §1 的 N1–N6）

| 编号 | 需求 | 本 PRD 的落点 |
|---|---|---|
| N1 | 软件里有一个项目承载 `E:\workspace\自动化测试\pytest-cases` | FR-v5.5-01 / 02 / 05 / 06 |
| N2 | 适配：平台能执行用例库并归档结果，RF 链路不受影响 | FR-v5.5-08 / 10 |
| N3 | 解耦：git 是用例真源，平台只引用不改写 | FR-v5.5-06 / 07 / 09 |
| N4 | 测试用例管理：分母、筛选、绑定、非自动化编辑 | FR-v5.5-11 / 12 |
| N5 | 可视化：六页 + 执行进度 + 错误原因 | FR-v5.5-13 / 14 |
| N6 | 便捷操作：一键同步／自证／执行／复制，失败有人话提示 | FR-v5.5-14 / 15 |

### 1.2 明确「不做」

| # | 不做 | 理由 |
|---|---|---|
| 1 | 把公司缩写写进 `kind` 枚举（D-A=B）或新增第二个 kind（D-A=C） | kind 是界面与执行链分派维度，不是归属维度；评估 §3.3 |
| 2 | 注册 `pytest-eht` 第二内核 SPI | 当前只有一个执行器；D-C 定为「先沿用 `pytest` + 配置区分」，真有第二执行器再启 |
| 3 | 一个工程绑多个仓库、平台自动建项目 | 评估 §4 边界；`project:create` 仍需人确认 |
| 4 | 复制／改写 `pytest-cases` 源码、用 `robot_file` 当真源 | 解耦 E1/E2；git 是唯一真源 |
| 5 | 物理 SQL 插项目 | 绕过审计与两道删除闸；一律走 `POST /api/projects` |
| 6 | 批量改写用例库 CRLF 行尾 | 162 文件 M 是纯行尾假脏（`git diff --ignore-cr-at-eol --stat` 为空）；D-D 定为只固定快照口径 |
| 7 | 改 RF 内核、改 `KernelSelector` 既有路由、减少 openapi 端点 | RK-1 回归红线 |
| 8 | AI 归因建议端到端验收（G-1）、bump 版本、对外称 GA、判 G 门 | D-F 未授权；发版／对外口径归大师，AI 不勾 M 项 |

### 1.3 与 v5.1–v5.4 既有 FR 的关系

| 既有项 | 处置 |
|---|---|
| 22 条 `FR-v5.1-xx` ~ `FR-v5.4-xx` | **不重写、不改编号**；本 PRD 是增量补「承载实体与界面接入」 |
| `FR-v5.2-01` R3「`pytest-suite` 默认内核 `pytest-nic`」 | 评估 §2.3 已核实与代码不符（`normalizeKernel` 只认 `robot` / `pytest`，`KernelRegistry` 只有两个 SPI）。D-C **授权回写**，动作排 `EHT-04`；本轮不动旧文件（§2.1 注） |
| `PRD §13.1` 该行「达成」 | 按 D-C 回写为「降级达成（R3 未落地，理由：内核码未注册；授权人：大师）」，与回写同批完成 |
| L-54-01 六张前端页 | D-G 定为**本轮全做**，逐页分包逐页验收 → FR-v5.5-13 |
| L-54-02（G-1 AI 端点）／L-54-03（报告幂等键）／L-54-04（host_inventory）／L-54-05（CDP） | **并列保留、不被本 PRD 覆盖或关闭**；L-54-02 按 D-F 不写入本轮验收 |

---

## 2. 决策记录（`EHT-01` 交付物）

### 2.1 D-A ~ D-G 落定

| 决策 | 取值 | 依据 | 授权人 | 生效 |
|---|---|---|---|---|
| **D-A** kind 命名 | **A**：保留 `pytest-suite`；EHT 放项目实体 | 评估 §3.4；避免公司缩写污染平台枚举，且改名不解决「第二执行器」问题 | 大师 | 2026-11-13 19:00 |
| **D-B** 项目身份 | `code=EHT-PYTEST-LIB`，`name=「EHT pytest 用例库工程」` | 评估 §4 第 7 项；身份在 code／名称／标签层 | 大师 | 同上 |
| **D-C** 内核码与口径回写 | 先沿用 `test_kernel=pytest`，**不注册第二 SPI**；**授权回写** `FR-v5.2-01` R3 与 `§13.1` 状态 | 评估 §2.3 / §5.3；文档与代码必须同源 | 大师 | 同上 |
| **D-D** 真源口径 | 绑工作区路径 `/mnt/e/workspace/自动化测试/pytest-cases` + 快照记 `git rev-parse HEAD`（当前 `915c2ca`）；CRLF 只固定口径、不批量改写 | 评估 §2.4；绑定快照禁止记工作树哈希 | 大师 | 同上 |
| **D-E** 版本 | 文档编号前缀 `v5.5`，发版区间 5.5.0~5.5.x（X<15）；**当前 5.0.0 不动** | 工作规划 §1；发版归大师 | 大师 | 同上 |
| **D-F** G-1 授权 | **未授权**：L-54-02 保持「未达成」，不写入本轮验收判据 | 评估 §8；不承诺无法端到端测的能力 | 大师 | 同上 |
| **D-G** 六页 | **全做**，按页分包、逐页验收；每页独立提交 | 对应 N5「可视化都要实现」 | 大师 | 同上 |

**注（必须留痕）**：D-C 已授权，但**本轮（P1）只交三份文档，未改任何既有文档**。回写动作排在 `EHT-04`，与代码真值复核在同一提交内完成，避免「文档先改、代码未跟」形成新一次口径偏差。这不是遗漏，也不是学徒擅自改状态。

### 2.2 编号口径修订（草案 → 正式）

| 对象 | 工作规划草案 | 本 PRD／TEST-PLAN 正式值 | 修订理由 |
|---|---|---|---|
| 需求编号 | `FR-EHT-01` ~ `FR-EHT-xx` | **`FR-v5.5-01` ~ `FR-v5.5-18`** | `tools/check_release_docs.py` 的 `detect_prefix` 只匹配 `FR-v[\d.]+-\d+`；且仓库既有 12 个版本均用 `FR-vX.Y-xx` |
| 用例编号 | `TC-EHT-01` ~ `TC-EHT-12` | **`TC-v5.5-01` ~ `TC-v5.5-18`** | 同上（`TC` 前缀同正则）；并为 18 条 FR 补足一一覆盖 |
| WBS 编号 | `EHT-01` ~ `EHT-16` | **不变** | 机检不扫 WBS，且开发计划与台账已按此编号 |

**编号对照（草案 → 正式）**：`FR-EHT-01`→`FR-v5.5-01`，…，`FR-EHT-xx`→`FR-v5.5-xx`；`TC-EHT-01`~`12`→`TC-v5.5-01`~`12` 语义不变，`TC-v5.5-13`~`18` 为覆盖新增 FR 而补。**工作规划 v1.0 保留原貌不改**，此处留修订记录。

---

## 3. 现状事实（每条标来源，区分已核实与目标）

| 事实 | 值 `[已核实]` | 来源 |
|---|---|---|
| `kind=pytest-suite` 项目数 | **0** | `SELECT COUNT(*) FROM project WHERE kind='pytest-suite'` |
| `project_setting` 中 `pytest.*` | **0**（全表仅 4 条 `kernel.default`） | `SELECT * FROM project_setting` |
| `case_script_binding` / `pytest_case_step` | **0 / 0** | 两表 `COUNT(*)` |
| 唯一 `test_kernel=pytest` 项目 | 80 `demo-pytest`（standard，12 用例，双内核演示），无 `repo_path` | 库查询 |
| 用例分母／脚本数 | **469 条 / 62 脚本**（`scripts_with_declared_code=61`） | `docs/evidence/v5.1.0/pytest/case-index.json` |
| HTTP 创建入口 | `CreateProjectRequest(code,name,description,visibility,testKernel)` **无 `kind`**；6 参 `create(...,kind)` 在 `backend/src` 零调用方 | `ProjectController.java:30-32`、`ProjectService.java:100-105` |
| 前端 pytest 专属页 | **仅 1 张** `PytestWorkbenchView.vue`（594 行） | `frontend/src/views/` |
| 后端就绪端点／服务 | `PytestProjectController` 7 端点；`application/kernel/pytest/` 一整套服务 | 源码 |
| 工程菜单树 | `PYTEST_SUITE_NAVIGATION` 4 组 11 叶 | `frontend/src/config/navigation.ts:484+` |

`[推断／未联调]` 六页所需后端接口形状「已按页面产出」仅见于测试计划自述，**未经真实端点联调**；FR-v5.5-13 必须做真端点验收，不得以单测 mock 代替。

---

## 4. 功能需求 `FR-v5.5-01` ~ `FR-v5.5-18`

> 状态列初始值统一为「**未实施（待测）**」；P6 测试执行后按流程 §2.7 回写为 达成／未达成／降级（降级须写理由与授权人）。优先级 P0 = 阻塞发版，P1 = 本轮必交，P2 = 可延后但须登记。

| FR | 用户动作 | 验收判据（可观察／可复核） | WBS | TC | 优先级 | 状态 |
|---|---|---|---|---|---|---|
| FR-v5.5-01 | 在软件里打开一个承载 `pytest-cases` 的工程 | `robot_dev.project` 恰有 1 行 `code=EHT-PYTEST-LIB` 且 `kind=pytest-suite`、`test_kernel=pytest`；`GET /api/projects/{id}/pytest/health` 返回 200 且结论为「可跑」 | EHT-07 | TC-v5.5-01 | P0 | 未实施（待测） |
| FR-v5.5-02 | 通过接口／界面新建一个 pytest 工程 | `POST /api/projects` 带 `kind=pytest-suite` → 200 且落库 kind 正确；不带 kind → 仍 `standard`（向后兼容）；非法 kind → **400 且不静默回落**；`mvn -o -B -Dtest=ProjectKindTest,ProjectControllerTest test` 绿 | EHT-05 | TC-v5.5-01 | P0 | 未实施（待测） |
| FR-v5.5-03 | 在界面识别该工程属于 EHT | 工程列表／详情显示 `code=EHT-PYTEST-LIB` 与名称「EHT pytest 用例库工程」；库中 `kind` 值仍为 `pytest-suite`；**全仓无 `pytest-eht` 作为 kind 值**；`flyway_schema_history` 无 `V11_56` | EHT-07 | TC-v5.5-01 / TC-v5.5-02 | P1 | 未实施（待测） |
| FR-v5.5-04 | 查看默认内核说明时不被误导 | `ProjectService.normalizeKernel()` 与三份文档口径一致（只认 `robot`／`pytest`）；旧 `FR-v5.2-01` R3 已按代码真值改写并在 `PRD §13.1` 标「降级达成（授权人：大师）」；两处均有修订记录行 | EHT-04 | TC-v5.5-02 | P1 | 未实施（待测） |
| FR-v5.5-05 | 配置工程运行参数 | `pytest.repo_path` / `python_bin` / `env_config` / `inventory_path` 四项落 `project_setting`；`GET/PUT /api/projects/{id}/pytest/settings` 可用；`settings/validate` **只校验不落库**；缺任一项时 `PytestPrecheckService` 拒跑并返回缺项名 | EHT-07 | TC-v5.5-03 | P0 | 未实施（待测） |
| FR-v5.5-06 | 查看用例库真源版本 | 绑定快照证据含 `git -C <repo> rev-parse HEAD` 值（基线 `915c2ca`）与同步时间；快照**不含工作树哈希**；`pytest-cases` 工作树零批量改写（`git diff --ignore-cr-at-eol --stat` 仍为空） | EHT-08 | TC-v5.5-04 | P1 | 未实施（待测） |
| FR-v5.5-07 | 一键同步用例与脚本绑定 | `POST .../pytest/bindings/sync` 后四类计数（bound／unbound／orphan／pending）与 `case-index.json` 的 469／62 自洽；`GET .../bindings` 非空；无重复归一 `nodeid`（`<文件末段>::<函数名>`）；脚本重命名演练后绑定跟到新路径且不计 orphan | EHT-08 | TC-v5.5-05 / TC-v5.5-06 | P0 | 未实施（待测） |
| FR-v5.5-08 | 从平台发起一次用例执行并看结果 | Fake 模式全量 **70 tests** 经平台通道执行 → junit 归一 → 结果入库，verdict 全 `PLANNED`，通过率显示「不适用」；命令同时含 `--capture=fd`、`-o junit_logging=out-err`、`-o junit_family=xunit2` 三条件 | EHT-14 | TC-v5.5-07 | P0 | 未实施（待测） |
| FR-v5.5-09 | 用例库临时不可用时仍能打开软件 | E1 git 真源且平台无脚本副本；E2 执行前后用例库 `git status` 一致（除行尾）；E3 `bash scripts/secret-scan.sh` PASS；E4 路径／解释器／inventory／run_env 全在 `project_setting` 无硬编码；E5 `repo_path` 不可达时 `health` 报「仓库不可达」+ 修复提示、**不抛 500**、不阻断 RF 项目 | EHT-09 | TC-v5.5-08 | P0 | 未实施（待测） |
| FR-v5.5-10 | 既有 RF 功能照常使用 | RK-1~RK-5 五条回归红线全部不回退：RF 执行／拆单／报告／看板／菜单；RF 项目 1、29 在解耦演练期间可正常执行 | EHT-14 | TC-v5.5-09 | P0 | 未实施（待测） |
| FR-v5.5-11 | 在用例管理页查、筛、选、看绑定 | 列表分母 469 可查；按大类／优先级／评审态筛选返回正确子集；可看单条绑定与脚本路径；批量选例可用；重命名跟随结果可见；响应为真实端点数据 | EHT-10 | TC-v5.5-10 | P1 | 未实施（待测） |
| FR-v5.5-12 | 编辑非自动化用例 | `ManualExecutionService` + `V11_55` 对应页面有可用编辑入口，保存后回读一致，且不影响自动化用例 | EHT-10 | TC-v5.5-11 | P1 | 未实施（待测） |
| FR-v5.5-13 | 打开六张可视化页 | 时长基线／处置闭环／环境雷达／双内核统一视图／手工执行／分离统计 **六页各有菜单入口**，且每页数据源为**真实端点响应**（非 mock），逐页留有端点响应证据；每页双语 i18n 键与 spec 齐 | EHT-11 | TC-v5.5-12 | P1 | 未实施（待测） |
| FR-v5.5-14 | 看执行进度与失败原因 | 进度可视化读 `ExecutionMonitor` 真实通道；失败归因分布读 `FailureBreakdown`；每条失败有**原因码 + 人话摘要 + 修复提示**；四类分布（产品／环境／跳过／未归类）分母口径与 `§12.2` 一致 | EHT-11 | TC-v5.5-13 | P1 | 未实施（待测） |
| FR-v5.5-15 | 用一键操作完成常用动作 | 一键同步绑定／一键 Fake 自证／一键发起执行（**预检通过才提交**）／命令集合一键复制，四条链路各跑通一次并留调用日志；预检不过时提交按钮不可用且展示原因 | EHT-12 | TC-v5.5-14 | P1 | 未实施（待测） |
| FR-v5.5-16 | 跑门禁时新增检查能真判红 | 门禁在 **48 步原样保留**基础上只增 **49–52** 四步；每步 `--self-test` 真注入 → 判红 → 还原 → 判绿；`scripts/gates.sh` 的 `check_structure` 登记 41–52 | EHT-13 | TC-v5.5-15 / TC-v5.5-17 | P0 | 未实施（待测） |
| FR-v5.5-17 | 结构不改坏 | `bash scripts/db-audit.sh` PASS 且业务表仍 105（新表才允许同步改 `expectedTables`）；openapi 重生成后端点数**不减少**；`npx vue-tsc --noEmit` 无输出；`npm run test:unit` 全绿；`check_md_tables.py` 对四份文档 PASS | EHT-13 / EHT-15 | TC-v5.5-16 | P0 | 未实施（待测） |
| FR-v5.5-18 | 版本收口时可追溯 | `docs/evidence/v5.5.0/` 证据齐；每条 `output.xml` 带 metadata 六项必填；评估报告／本 PRD／DEV-PLAN／TEST-PLAN 四份均有实测回写与修订记录；提交号 + 三处 sha 一致；**未测或未过不称交付** | EHT-14 / EHT-15 / EHT-16 | TC-v5.5-18 | P0 | 未实施（待测） |

### 4.1 关键判据细化

| FR | 细化 |
|---|---|
| FR-v5.5-01 | 「可跑」指 health 响应四项必填齐、`repo_path` 可达、预检不拒；仅 200 不算可跑。建工程必须走接口并留删前式取证 JSON，**禁止物理 SQL** |
| FR-v5.5-02 | 非法 kind 必须 400 且响应体给出允许值；静默回落 `standard` 视为 FAIL。5 参旧调用方（`from-template`）行为不得变化 |
| FR-v5.5-04 | 回写是**订正口径**不是改代码：不注册 `pytest-nic`／`pytest-eht` 内核码；`KernelRegistry` 仍为两个 SPI；状态改判须写「原值／实测值／影响」 |
| FR-v5.5-08 | 「通过率不适用」必须是界面与归一结果一致显示，不得用 0% 或 100% 冒充；三条件缺任一即判 FAIL |
| FR-v5.5-13 | 「真实端点」判据：抓浏览器网络请求或服务端访问日志，端点路径与 openapi 中存在且响应字段与页面展示一致；只截 UI 不算 |

---

## 5. 非功能需求

> `[目标]`＝本 PRD 设定的验收目标，**不是实测值**；实测值一律在 P6 的 `05-TEST-REPORT.md` 回填，不得提前写成「已达成」。

| # | 类别 | 指标与判据 | 证据 |
|---|---|---|---|
| NFR-1 | 性能 `[目标]` | 一次全量绑定同步（469 用例／62 脚本）耗时 ≤ 60 s；工程 health 响应 ≤ 2 s（P95，连续 20 次） | 计时日志 |
| NFR-2 | 并发 `[目标]` | 同一工程 2 个并发同步请求不产生重复 `nodeid`；`ScaleGuard` 节点上限与证据配额提示仍生效 | 并发脚本 + 库查询 |
| NFR-3 | 安全 | 凭据零明文：配置用 `${ENV:XXX}`／`credential` 表；`bash scripts/secret-scan.sh` PASS；metadata 与证据 JSON 不含密码 | 扫描输出 |
| NFR-4 | 兼容 | RF 内核链路 RK-1~5 不回退；openapi 端点数不减少；`db-audit` 与 `flyway_schema_history` 一致 | 门禁读数 |
| NFR-5 | 可观测 | 每次执行／同步／预检都有 `runId` 与原因码；失败原因可定位到具体配置项或命令 | 日志样本 |
| NFR-6 | 可维护 | 新增机检 49–52 只增不删且各自 `--self-test` 可真判红；六页各自独立提交 | 门禁日志 + git log |
| NFR-7 | 降级 | 用例库不可达、预检缺项、AI 端点未授权三种情况下，功能**报原因不崩**，且 RF 项目不受影响 | 注入演练日志 |

---

## 6. 数据模型与接口影响

| 对象 | 类型 | 影响 | 迁移／兼容 |
|---|---|---|---|
| `CreateProjectRequest` | 入参扩展 | 增加可选 `kind`；旧 5 字段请求行为不变 | 无迁移；非法值 400 |
| `ProjectService.create` | 调用链 | 由 5 参改接 6 参（带 kind）；`syncKernelSetting` 语义不变 | 无迁移 |
| `project` | 数据新增 | 新增 1 行 `EHT-PYTEST-LIB`／`pytest-suite`／`pytest` | 无迁移；`kind VARCHAR(16)` 已够 |
| `project_setting` | 数据新增 | `pytest.repo_path`／`python_bin`／`env_config`／`inventory_path` + 真源 HEAD 快照项 | 无迁移（键值表） |
| `case_script_binding` / `pytest_case_step` | 数据从 0 → 有数 | 由 `bindings/sync` 与执行归档写入 | 表已存在，无迁移 |
| `flyway_schema_history` | **不变** | D-A=A，**不追加 `V11_56`**；当前最高仍是 `V11_55` | 已应用迁移不可改 |
| `KernelRegistry` / `KernelSelector` | **不变** | D-C 不注册第二 SPI，`normalizeKernel` 仍只认 `robot`／`pytest` | 无 |
| 既有 7 个 pytest 端点 | **不变** | 只被调用，不改形状；openapi 重生成后比对漂移 | 403 endpoints 基线 |
| 新增机检 49–52 | 门禁 | `scripts/gates.sh` 登记 41–52；`check_structure` 基线重刷 | 只增不删 |

---

## 7. 版本退出标准

| # | 判据 | 口径 |
|---|---|---|
| E1 | 18 条 FR 全部有 TC 结果且状态已回写 | 无「未执行」项；BLOCKED 必须写原因与解除条件，不等于 PASS |
| E2 | P0 项清零或经大师书面豁免 | FR-v5.5-01/02/05/07/08/09/10/16/17/18 |
| E3 | 真实链路跑通 | Fake 70 tests 全链路 + RF 回归 RK-1~5，不接受「单测绿／构建绿」当放行理由 |
| E4 | 门禁读数如实 | 只读尾部 `GATES_RESULT FAILS=N`；48 步段与新增 4 步段**分别登记 PASS/FAIL，不相加成「全绿」**；已知 2 项 FAIL 如实保留 |
| E5 | 结构一致 | `db-audit` PASS、openapi 无漂移、四份文档 `check_md_tables.py` PASS、四份已跟踪副产物 `git restore --` |
| E6 | 文档回写完成 | 四份文档 + 台账 + 项目记忆；不回写＝版本未完成 |
| E7 | 收口材料 | 提交号 + 三处 sha 一致（`git ls-remote` 复核）+ 门禁读数 + 遗留清单 |
| E8 | **不在本 PRD 内** | 判 G 门、bump 版本、对外称 GA —— 一律归大师 |

---

## 8. 验收状态初始登记（供流程 7 回写）

| 状态 | FR 范围 | 判定依据 | 授权人 | 复核人 |
|---|---|---|---|---|
| 未实施（待测） | FR-v5.5-01 ~ FR-v5.5-18（全部 18 条） | 本轮 P1 只交付文档，无源码／数据／配置改动 | — | — |
| 不适用（决策取消） | 原 `EHT-06` 命名包 | D-A=A，kind 不改名，无需 `V11_56` 与全链路改名 | 大师 | — |

**回写纪律**：降级必须写「理由 + 授权人」；未完成的 FR 不许从表里删掉；被实测推翻的假设保留原文并留「原值／实测值／影响」，**不悄悄改数字**。

---

## 9. 风险与降级预案

| # | 风险 | 缓解 | 若降级的授权 |
|---|---|---|---|
| R1 | 改名与建实体混在一次改动 | D-A=A 已消除；`EHT-06` 标不适用 | 大师 |
| R2 | CRLF 假脏导致绑定哈希漂移 | 快照只记 `rev-parse HEAD`；同步前固定行尾口径 | 大师 |
| R3 | 六页联动 i18n／菜单叶子基线／spec | 每页独立提交，每页复跑 `check_nav_kind.py --self-test` | 大师 |
| R4 | G-1 未授权 | L-54-02 如实保持「未达成」，不写入验收 | 大师 |
| R5 | 门禁已知 FAILS=2 | 与本轮无关的 FAIL 如实保留，**不许改证据凑源码**（29 host_inventory 需现场重采） | 大师 |
| R6 | 全量门禁重写 4 份已跟踪副产物 | 判门前 `git restore --` | 大师 |
| R7 | 「接口形状已按页面产出」只是文档自述 | `EHT-11` 强制真端点联调，不接受 mock 顶替 | 大师 |
| R8 | 重命名跟随演练会写用例库，违反 E2 | 在 `/tmp` 最小副本演练并临时切换 `repo_path`，**绝不动真源工作树**；详见 TEST-PLAN §7 | 大师 |

---

## 10. 修订记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.0 | 2026-11-13 | 首版：D-A~D-G 决策记录、18 条 `FR-v5.5-xx` 与可判定判据、NFR 7 项、数据与接口影响、退出标准 E1–E8、状态初始登记、编号口径修订说明 |
