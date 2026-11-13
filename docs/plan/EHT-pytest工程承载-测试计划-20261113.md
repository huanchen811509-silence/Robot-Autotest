# EHT pytest 工程承载与适配解耦 — 测试计划（2026-11-13）

**撰写** 学徒 ｜ **审批** 大师（2026-11-13「均按你的建议来」）
**性质** 七道流程第 5 环正式产物（P1）。**在写代码前定死「怎么算过」；本轮只交计划，未执行任何 TC。**
**上游（顺序不可倒置）** 评估报告 → 产品迭代优化 PRD（18 条 `FR-v5.5-xx`）→ 开发计划（`EHT-01~16`）→ 本计划 → 测试执行。
**流程规范** `docs/process/版本开发流程.md` §2.5：必含 L1–L7 分层、**FR → TC 映射矩阵**、证据形态、失败判据、独立裁判方案、故障注入清单；**每条 FR 至少 1 条 TC，关键需求必须有反例**；**不接受实现方自证**。§2.6：`BLOCKED` 不等于 `PASS`，必须写明原因与解除条件。

---

## 0. 结论先行

| # | 结论 |
|---|---|
| 1 | **18 条 `TC-v5.5-01`~`18`** 覆盖 PRD **18 条 FR**，无孤儿 FR（§4 矩阵逐行核对） |
| 2 | 判定只认**真实链路**：Fake 70 tests 全链路 + RF 回归 RK-1~5 + 六页真端点；单测绿／构建绿／实现方自述均不是放行理由 |
| 3 | 工作规划草案的 `TC-EHT-01`~`12` 升格为 `TC-v5.5-01`~`18`（机检正则口径），语义前 12 条不变，后 6 条为补足覆盖（见 PRD §2.2） |
| 4 | **两处演练改了做法**：重命名跟随与断链降级一律在 `/tmp` 最小副本 + 临时 `repo_path` 上做，**绝不改 `pytest-cases` 真源工作树**（§7 F3/F4）——工作规划原写法与解耦 E2「零写入」冲突，此处按硬规矩纠正 |
| 5 | 已知欠账如实带入：门禁 `FAILS=2`（13 CDP 已取消、29 host_inventory 需现场重采）；L-54-02（G-1 未授权，D-F）、L-54-03、L-54-04、L-54-05 并列保留，**不写入本轮验收** |

---

## 1. 测试基线与前置条件

| 项 | 基线值 | 复核命令／来源 |
|---|---|---|
| 代码 | `~/robot-autotest` @ `0248498`（权威副本）；本计划基线后各包提交号由 DEV-PLAN §7 回填 | `git log -1 --oneline` |
| 版本 | `VERSION=5.0.0`，**不 bump** | `cat VERSION` |
| 后端服务 | `:8081/api/_ping` 200 且登录可用（**:8001 是 node 自回显，不算数**）；构建后须重启后端再验 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/api/_ping` |
| 数据库 | `robot_dev`：迁移 `V11_55`、105 表、7 项目；`db-audit` PASS | `bash scripts/db-audit.sh` |
| 门禁 | 48 步 / `FAILS=2`；新增 49–52 待 EHT-13 | `bash scripts/gates.sh` 尾部读数 |
| 用例库 | `HEAD=915c2ca`；469 用例 / 62 脚本；Fake 模式 70 tests | `case-index.json`；`git -C <repo> rev-parse HEAD` |
| 前端 | 783 例 / 104 文件；`vue-tsc` 无输出 | `npm run test:unit`；`npx vue-tsc --noEmit` |
| 环境 | 门禁前 `set -a; . ~/.config/robot-autotest/env; set +a`；curl 加 `--noproxy '*'` | 项目硬规矩 |

**准入**：EHT-04~13 全部完成、承载工程 `health` 200「可跑」、49–52 机检已登记，方可进入 EHT-14 真实链路执行。
**准出**：18 条 TC 均有结果 + 证据路径；P0 清零或大师书面豁免；四份文档回写完成；**不回写＝版本未完成**。

---

## 2. 测试分层与覆盖范围（L1–L7）

| 层 | 范围 | 对应 TC | 裁判 |
|---|---|---|---|
| L1 单元 | `ProjectKind`／`CreateProjectRequest`／归一函数／绑定服务单测 | TC-v5.5-01 / 05 | `mvn -o -B test`；`npm run test:unit` |
| L2 接口 | `POST /api/projects`、7 个 pytest 端点、settings validate、health | TC-v5.5-01 / 02 / 03 / 04 / 05 | curl + 库查询交叉复核 |
| L3 集成 | 平台渲染 env → 绑定归一 → 命令构造 → 契约探测 → 执行 → 归档 | TC-v5.5-07 | 独立脚本读 junit／`output.xml` |
| L4 端到端 | Fake 70 tests 全链路 + 一键操作四链路 | TC-v5.5-07 / 14 | 真实运行 + 调用日志 |
| L5 界面 | 用例管理页、六页、进度与错误原因 | TC-v5.5-10 / 11 / 12 / 13 | 真端点响应 + 截图，非 mock |
| L6 回归／非功能 | RK-1~5、结构一致、性能与并发目标值 | TC-v5.5-09 / 16 | 门禁读数 + 独立压测脚本 |
| L7 流程／文档 | metadata 六项、机检自证、文档回写 | TC-v5.5-15 / 17 / 18 | `check_release_docs.py` + 大师旁路复核 |

---

## 3. 测试用例 `TC-v5.5-01` ~ `TC-v5.5-18`

> 结果列在 P6 执行后回填（PASS／FAIL／BLOCKED）。**未执行不算 PASS。**

| TC | 覆盖 FR | 层 | 前置 | 步骤与通过判据 | 失败判据 | 证据形态 |
|---|---|---|---|---|---|---|
| TC-v5.5-01 | FR-v5.5-01 / 02 / 03 | L1 L2 | EHT-05、EHT-07 完成 | `POST /api/projects` 三种入参：带 `kind=pytest-suite` → 200 且库内 `code=EHT-PYTEST-LIB`／`kind=pytest-suite`／`test_kernel=pytest`；不带 kind → `standard`；非法 kind → 400 且响应给允许值；`GET .../pytest/health` 200「可跑」；`SELECT COUNT(*) FROM project WHERE kind='pytest-suite'` = 1 | 非法 kind 静默回落；落库 kind 与入参不符；5 参旧调用方行为变化；health 仅 200 但必填未齐 | 接口响应 JSON + 库查询 JSON + `flyway_schema_history` 快照 |
| TC-v5.5-02 | FR-v5.5-03 / 04 | L2 | EHT-04 完成 | 检查 `normalizeKernel()` 只认 `robot`／`pytest` 且三份文档口径一致；旧 `FR-v5.2-01` R3 已按代码真值改写；`§13.1` 标「降级达成」并含授权人；两处修订记录存在；库中无 `pytest-eht` 作为 kind 值；`flyway_schema_history` 无 `V11_56` | 文档仍写 `pytest-nic` 为已注册内核；状态仍标「达成」且无修订记录；出现未授权改名 | 源码 grep 输出 + 文档 diff + 库查询 |
| TC-v5.5-03 | FR-v5.5-05 | L2 | 承载工程存在 | 四项 `pytest.*` 设置逐项落库；`GET/PUT settings` 可用；`settings/validate` 只校验不落库（调用前后 `project_setting` 行数不变）；逐一清空任一项 → validate 报错含缺项名且 `PytestPrecheckService` 拒跑；补全后恢复 | validate 偷偷写库；缺项仍放行；错误信息不含缺项名 | 响应体 + `project_setting` 前后快照 + 预检日志 |
| TC-v5.5-04 | FR-v5.5-06 | L2 | EHT-08 完成 | 证据 JSON 含 `rev-parse HEAD` 值（基线 `915c2ca`）与同步时间；快照**不含工作树哈希**；`git diff --ignore-cr-at-eol --stat` 仍为空；`git status --short` 与执行前一致（除行尾） | 快照记工作树哈希；用例库出现批量行尾改写或内容改动 | 证据 JSON + git 命令输出前后对比 |
| TC-v5.5-05 | FR-v5.5-07 | L2 L3 | EHT-08 完成 | `bindings/sync` 报告四类计数与 `case-index.json` 的 469／62 自洽（bound+unbound+orphan+pending 与分母一致）；`GET bindings` 非空；独立脚本查 `case_script_binding` **无重复 `nodeid`**（`<文件末段>::<函数名>`） | 计数不自洽；重复 nodeid；分母写成 470 或 61 | sync 报告 + 独立 SQL 去重查询 + `case-index.json` |
| TC-v5.5-06 | FR-v5.5-07 / 11 | L3 | F4 副本就绪 | 在 `/tmp` 最小副本把 1 个 `test_*.py` 改名，临时切 `repo_path` 指向副本后重扫 → 绑定跟到新路径、不计 orphan；还原配置后真源绑定不变；**真源 `git status` 无新增改动** | 改名后判 orphan；平台回写真源；还原失败 | 前后绑定 diff + 副本目录清单 + 真源 git status |
| TC-v5.5-07 | FR-v5.5-08 | L3 L4 | 工程可跑、Fake 计划就绪 | 平台发起 Fake 全量 **70 tests**；命令同时含 `--capture=fd`、`-o junit_logging=out-err`、`-o junit_family=xunit2`；独立脚本解析 junit／`output.xml` → verdict 全 `PLANNED`；界面与归一结果显示「通过率不适用」；结果入库可查 | 三条件缺任一；出现 PASS／FAIL 冒充；显示 0% 或 100%；结果未入库 | `output.xml` + `run.log` + 归一 JSON + 截图 |
| TC-v5.5-08 | FR-v5.5-09 | L2 L6 | EHT-09 完成 | E1 `check_kernel_decoupling.py` PASS 且平台内无脚本副本；E2 前后 `git status` 一致；E3 `bash scripts/secret-scan.sh` PASS 且证据无凭据；E4 四项配置全在 `project_setting` 无硬编码；E5 `repo_path` 临时指向不存在路径 → health 报「仓库不可达」+ 修复提示、**不抛 500**、RF 项目 1／29 仍可执行；还原 | 抛 500 或全局不可用；配置硬编码；真源被写入；还原遗漏 | 注入演练日志 + curl 状态码 + 扫描输出 |
| TC-v5.5-09 | FR-v5.5-10 | L6 | 解耦演练同时 | RK-1~5 五条逐条复验：RF 执行、拆单、报告、看板、菜单；对比演练前后同一批 RF 用例结果与菜单树 | 任一红线回退；拿「单测绿」当 RF 无回退的证据 | 前后结果对比 + `check_navigation.py` 输出 |
| TC-v5.5-10 | FR-v5.5-11 | L5 | EHT-10 完成 | 用例管理页分母显示 469；按大类／优先级／评审态筛选返回子集与独立 SQL 一致；可看单条绑定与脚本路径；批量选例可用；列表数据源为真实端点 | 分母不符；筛选结果与 SQL 不一致；mock 数据 | 截图 + 端点响应 JSON + 独立 SQL |
| TC-v5.5-11 | FR-v5.5-12 | L5 | EHT-10 完成 | 非自动化用例打开编辑 → 改字段 → 保存 → 回读一致；自动化用例不受影响；`V11_55` 迁移已应用 | 保存丢失；影响自动化用例；页面只读无编辑入口 | 截图 + 前后 DB 行快照 |
| TC-v5.5-12 | FR-v5.5-13 | L5 | EHT-11 完成 | 六页（时长基线／处置闭环／环境雷达／双内核统一视图／手工执行／分离统计）逐页打开；每页抓真实端点响应，路径存在于 openapi 且响应字段与页面展示一致；每页双语 i18n 键 + spec 齐；`check_eht_pages_live.py` PASS | 任一页 mock／静态写死；端点 404；字段对不上；缺 i18n 或 spec | 逐页截图 + 端点响应 + 网络请求清单 |
| TC-v5.5-13 | FR-v5.5-14 | L5 | EHT-11 完成 | 进度可视化读 `ExecutionMonitor` 真实通道；失败归因四类分布（产品／环境／跳过／未归类）分母口径与测试计划 §12.2 一致；每条失败有原因码 + 人话摘要 + 修复提示 | 进度为静态数；分母口径漂移；只有错误码无人话 | 截图 + 端点响应 + 归因服务输出 |
| TC-v5.5-14 | FR-v5.5-15 | L4 | EHT-12 完成 | 一键同步／一键 Fake 自证／一键执行（预检通过才提交）／命令集合一键复制，四条链路各跑通一次并留调用日志；人为让预检不过 → 提交按钮不可用且展示原因码 + 人话摘要 + 修复提示 | 预检不过仍可提交；失败无原因；命令集合复制内容与实际执行不一致 | 操作日志 + 接口调用链 + 剪贴板内容 |
| TC-v5.5-15 | FR-v5.5-16 | L7 | EHT-13 完成 | 49–52 四步各跑 `--self-test`：真注入 → **判红** → 还原 → **判绿**；`scripts/gates.sh` 的 `check_structure` 登记 41–52；原 48 步原样存在 | 注入后仍判绿（假绿灯）；只留判红不还原；删改既有步 | 门禁日志 + `--self-test` 输出 + `gates.sh` diff |
| TC-v5.5-16 | FR-v5.5-17 | L6 | EHT-13 完成 | `bash scripts/db-audit.sh` PASS 且业务表 105；openapi 重生成后端点数不减少（基线 403／334）；`npx vue-tsc --noEmit` 无输出；`npm run test:unit` 全绿；四份文档 `check_md_tables.py` PASS | 表数漂移未登记；端点减少；tsc 有报错；表格列数不一致 | 命令输出 + openapi diff |
| TC-v5.5-17 | FR-v5.5-16 / 18 | L7 | 全量门禁跑完 | `bash scripts/gates.sh` 只读尾部 `GATES_RESULT FAILS=N`；48 步段与新增 4 步段**分别登记**，不相加成「全绿」；已知 2 项 FAIL 如实保留且写明与本轮无关；四份已跟踪副产物 `git restore --` 后 `git status --short` 干净 | 用 `grep FAIL` 历史日志当本轮读数；改证据凑源码；副产物漂移被提交 | 门禁尾部输出 + `git status` + 两段登记表 |
| TC-v5.5-18 | FR-v5.5-18 | L7 | EHT-14、EHT-15 完成 | `docs/evidence/v5.5.0/eht/` 证据齐；每条 `output.xml` metadata 六项必填且无凭据；四份文档状态与实测一致、均有修订记录；无「CHANGELOG 写已完成但代码没有」；提交号 + 三处 sha 一致 | metadata 缺项或含凭据；FR 状态未落定；回写合并成一句「已更新」 | 文档 diff + metadata 抽样 + `git ls-remote` 输出 |

---

## 4. FR → TC 覆盖矩阵（逐行核对，无孤儿 FR）

| FR | 主 TC | 补充 TC | 关键反例 |
|---|---|---|---|
| FR-v5.5-01 | TC-v5.5-01 | — | F2 非法 kind |
| FR-v5.5-02 | TC-v5.5-01 | TC-v5.5-15 | F2 删 `kind` 字段 |
| FR-v5.5-03 | TC-v5.5-01 | TC-v5.5-02 | F2 带 `pytest-eht` kind |
| FR-v5.5-04 | TC-v5.5-02 | TC-v5.5-18 | F6 文档口径不改 |
| FR-v5.5-05 | TC-v5.5-03 | — | F1 四项逐一缺失 |
| FR-v5.5-06 | TC-v5.5-04 | TC-v5.5-06 | F4 工作树哈希入快照 |
| FR-v5.5-07 | TC-v5.5-05 | TC-v5.5-06 | F4 改名；F5 并发 |
| FR-v5.5-08 | TC-v5.5-07 | TC-v5.5-14 | F6 去掉 capture 条件 |
| FR-v5.5-09 | TC-v5.5-08 | TC-v5.5-04 | F3 断链 |
| FR-v5.5-10 | TC-v5.5-09 | TC-v5.5-08 | F3 演练期间 RF 执行 |
| FR-v5.5-11 | TC-v5.5-10 | TC-v5.5-06 | F5 重复 nodeid |
| FR-v5.5-12 | TC-v5.5-11 | — | F1 编辑自动化用例越界 |
| FR-v5.5-13 | TC-v5.5-12 | TC-v5.5-15 | F6 端点改 mock |
| FR-v5.5-14 | TC-v5.5-13 | TC-v5.5-14 | F6 归因分母改错 |
| FR-v5.5-15 | TC-v5.5-14 | TC-v5.5-03 | F1 预检不过仍提交 |
| FR-v5.5-16 | TC-v5.5-15 | TC-v5.5-17 | F6 四步真注入 |
| FR-v5.5-17 | TC-v5.5-16 | TC-v5.5-17 | F6 表格列数改坏 |
| FR-v5.5-18 | TC-v5.5-18 | TC-v5.5-02 | F6 文档不回写 |

**统计**：PRD 18 条 FR 全部出现在本矩阵；每条 ≥1 条主 TC；关键 FR 均有反例；`check_release_docs.py` 的 `detect_prefix` 能识别 `FR-v5.5-` 与 `TC-v5.5-` 前缀，不会误报孤儿。

---

## 5. 反例与负向验证（关键需求必须有反例）

| # | 反例 | 期望 | 关联 TC |
|---|---|---|---|
| V1 | 建工程入参带非法 kind | 400 + 允许值提示，不静默回落 standard | TC-v5.5-01 |
| V2 | 四项必填逐一缺失 | validate 报缺项名、预检拒跑 | TC-v5.5-03 |
| V3 | `repo_path` 指向不存在路径 | health 报「仓库不可达」，不 500、不阻断 RF | TC-v5.5-08 |
| V4 | 副本里脚本改名 | 绑定跟随、不计 orphan，真源零改动 | TC-v5.5-06 |
| V5 | 两个并发同步请求 | 无重复 nodeid | TC-v5.5-05 |
| V6 | Fake 执行缺 `-o junit_family=xunit2` 等任一条件 | 判 FAIL，不许用单测绿替代 | TC-v5.5-07 |
| V7 | 把某页端点改成 mock／去掉一页 i18n 键／删 `kind` 字段／证据 `scripts` 改 0 | 机检 49–52 分别判红 | TC-v5.5-15 |
| V8 | 预检不通过仍点提交 | 按钮不可用且给出原因 + 人话 + 修复提示 | TC-v5.5-14 |
| V9 | 用例库工作树出现非行尾改动 | 判 E2 违反，源码零写入不成立 | TC-v5.5-04 / 08 |
| V10 | 文档回写只写一句「已更新」 | 判 FR-v5.5-18 未达成 | TC-v5.5-18 |

---

## 6. 独立裁判方案（实现方不自证）

| # | 判定对象 | 裁判手段（不是被测系统自述） | 谁复核 |
|---|---|---|---|
| J1 | 绑定归一与分母 | 独立 Python 脚本用 `ast` 解析 `pytest-cases` 的 `test_*.py` 取函数名，与 `case_script_binding` 比对；不用平台 `sync` 报告自证 | 大师旁路抽 10 条 |
| J2 | Fake 全链路 verdict | 独立脚本直接解析 junit XML／`output.xml` 统计 verdict，与界面显示交叉 | 大师抽验 1 次 |
| J3 | 六页真端点 | 抓网络请求或服务端访问日志，核端点路径与 openapi；不看前端代码自述 | 大师抽 3 页 |
| J4 | 断链降级 | 独立 curl 复验状态码与响应体，不看业务日志 | 学徒执行、大师复核 |
| J5 | 机检 49–52 | `--self-test` 真注入判红由大师抽验 2 步，还原后判绿 | 大师 |
| J6 | 文档回写与 FR 状态 | `check_release_docs.py` + 文档 diff 逐条核；技术负责人可改判状态 | 大师 |

**硬规矩**：「单测绿／构建绿」不是放行理由；判定「对不对」的不能是被测的那个东西本身。

---

## 7. 故障注入清单（含隔离方案）

| # | 注入 | 做法 | 还原 | 判据 |
|---|---|---|---|---|
| F1 | 配置缺项／预检不过 | 逐一清空 `pytest.*` 四项；让预检失败后尝试提交 | 逐项回填 | TC-v5.5-03 / 14 |
| F2 | 非法创建入参 | 非法 kind；不带 kind；临时删 `CreateProjectRequest.kind` 字段（机检自证） | 还原字段与入参 | TC-v5.5-01 / 15 |
| F3 | 用例库断链 | **临时把 `repo_path` 指向不存在的路径**（配置层注入）；**不物理移走 `E:\workspace\自动化测试\pytest-cases` 目录** | `PUT settings` 还原原路径并复验 health | TC-v5.5-08 |
| F4 | 脚本重命名 | 在 `/tmp/eht-binding-fixture/` 复制最小脚本子集，临时 `repo_path` 指向副本，在副本里改名后重扫；**绝不动真源工作树** | 删副本 + 还原 `repo_path`，真源 `git status` 复核 | TC-v5.5-06 |
| F5 | 并发同步 | 同一工程并发发起 2 个 `bindings/sync` | 等待结束，查重复 nodeid | TC-v5.5-05 |
| F6 | 机检／文档反向注入 | 把证据 `scripts` 改 0、某页端点改 mock、去掉一页 i18n 键、删 `kind` 字段、改坏表格列数、去掉 capture 条件 | 逐项还原并复跑判绿 | TC-v5.5-15 / 16 / 18 |

**口径纠正（必须留痕）**：工作规划 §4 的 `TC-EHT-04` 写「改一个脚本文件名后重扫」、`TC-EHT-05` 写「目录不可达」，字面上会碰用例库真源，与解耦 E2「平台零写入」冲突。本计划 F3/F4 改为**配置层 + `/tmp` 副本**注入，验收判据增加「真源 `git status` 无新增改动」。工作规划 v1.0 保留原貌，此处记修订。

---

## 8. `output.xml` metadata 等价物（项目硬规矩）

| 项 | 要求 |
|---|---|
| 必填 6 项 | `CASE_ID` / `RUN_ID` / `QA_PROJECT` / `DUT` / `LINK` / `SW_BUILD` |
| 推荐 4 项 | `BASELINE` / `OPERATOR` / `STARTED_AT` / `CASE_KIND` |
| 键名 | **全大写**；`RUN_ID` 键名不可改（三方关联唯一键） |
| 值 | ≤ 40 字符；**不得写密码／凭据**（metadata 会进看板库和对外报告） |
| 目的 | 对外报告能说清「谁、何时、哪台设备、哪条链路、什么版本、哪条用例」 |
| 验收 | 每条 `output.xml` 抽样检查六项齐且无凭据明文；pytest 侧 json-report 的 `environment` 键一并落同样口径 |

---

## 9. 回归红线 RK-1 ~ RK-5

| # | 红线 | 判据 | 关联 TC |
|---|---|---|---|
| RK-1 | RF 内核执行不受影响 | 演练期间 RF 项目 1／29 正常发起并归档 | TC-v5.5-09 |
| RK-2 | 拆单逻辑不回退 | 同批用例拆单数与结果一致 | TC-v5.5-09 |
| RK-3 | 报告生成不回退 | RF 报告可生成且字段完整 | TC-v5.5-09 |
| RK-4 | 看板口径不回退 | 看板数据与库一致 | TC-v5.5-09 |
| RK-5 | 菜单树不回退 | `check_navigation.py` PASS；`PYTEST_SUITE_NAVIGATION` 4 组 11 叶不减少 | TC-v5.5-09 / 12 |

---

## 10. 门禁与读数纪律

| # | 纪律 |
|---|---|
| 1 | 只认尾部 `GATES_RESULT FAILS=N`；`docs/evidence/gates/logs/*.log` 是历史累积，`grep FAIL` 会把旧失败当本轮 |
| 2 | 原 48 步段与新增 49–52 段**分别登记 PASS/FAIL，不得相加成「全绿」** |
| 3 | 已知 `FAILS=2`：13 CDP（大师已取消，如实 FAIL）、29 host_inventory（证据漂移，需现场重采）；**不许改证据凑源码** |
| 4 | `check_colors` 失败会挡住其后 `check_tokens`，修完一项要重跑整条 `scripts/lint.sh` |
| 5 | 全量门禁会重写 4 份已跟踪副产物，收口前 `git restore --` `ai-eval.json`／`capacity-report.json`／`security-scan.json`／`dora-trend.json` |
| 6 | 新增机检只增不删，`--self-test` 必须真注入 → 判红 → 还原 → 判绿 |

---

## 11. 证据规范

| 项 | 规范 |
|---|---|
| 根目录 | `docs/evidence/v5.5.0/eht/`，原始文件放 `raw/`；每份证据写清采集命令、时间（GMT+8）、采集人 |
| 形态 | 接口响应 JSON、库查询 JSON、`output.xml`／`run.log`／junit XML、页面截图、命令输出、`--self-test` 日志、文档 diff |
| 命名 | `<TC编号>-<动作>-<时间戳>.json`，例如 `TC-v5.5-07-fake-run-20261113.json` |
| 安全 | 证据 JSON 与 metadata **不含凭据明文**；提交前 `bash scripts/secret-scan.sh` PASS |
| 可复查 | 每条 TC 在 `05-TEST-REPORT.md` 写「结果 + 证据路径」；BLOCKED 必须写原因与解除条件 |

---

## 12. 欠账登记（本轮无法测或只能部分测）

| # | 欠账 | 状态 | 解除条件 |
|---|---|---|---|
| L-EHT-01 | 18 条 TC 全部未执行 | **未执行（本轮只交计划）** | EHT-04~13 完成后的 EHT-14 |
| L-EHT-02 | 效果类指标（Top8 成功率／SUS／fps／createP95／真实端点一致性） | 未测量（沿用上游欠账） | 大师安排现场测量 |
| L-54-01 | 六张前端页未落地 | **本轮 D-G 定为全做** → 转 FR-v5.5-13，EHT-11 后关闭 | 六页真端点验收 |
| L-54-02 | G-1 AI 真实端点 | **未达成（D-F 未授权），不写入本轮验收** | 大师授权 |
| L-54-03 | 报告幂等键降级 | 并列保留，不被本轮覆盖 | 单独排期 |
| L-54-04 | host_inventory 证据漂移 | 门禁 29 步如实 FAIL，需现场重采 | 现场重采 |
| L-54-05 | CDP 已取消 | 门禁 13 步如实 FAIL | 大师决定是否重启 |
| 历史项 | 75／76 历史删除授权偏差是否恢复 | 仍待大师确认，本轮不动 | 大师拍板 |

---

## 13. 文档回写清单（第 7 道，每版本执行）

| 文档 | 回写内容 | 特别要求 |
|---|---|---|
| 评估报告 | 被实测推翻的假设逐条改（§2.3 内核码偏差、§2.4 行尾差异定性） | 保留原文并留「原值／实测值／影响」，**不悄悄改数字** |
| PRD | 18 条 FR 状态落定：达成／未达成／降级 | 降级必须写理由与**授权人** |
| 开发计划 | §7 偏差记录逐包填实际完成、提交号、偏差原因 | 未完成包标「延后」写去向，**不许从表里删掉** |
| 测试计划（本文） | 每条 TC 实测结果 + 证据路径 + 新增用例补编号 | 计划外新增用例补 `TC-v5.5-xx` 编号 |
| 实施台账 / 项目记忆 | 每包提交号／证据路径／门禁读数；`.workbuddy/memory/YYYY-MM-DD.md` 追加 | `MEMORY.md` 同步当前提交与项目数真值 |
| 流程规范 §7 | 回写记录表记一行 | 不许合并成一句「已更新」 |

Markdown 写完统一跑 `check_md_tables.py`；文档补丁**按内容匹配**，绝不用行号。

---

## 14. 修订记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.0 | 2026-11-13 | 首版：L1–L7 分层、18 条 `TC-v5.5-xx`、FR→TC 覆盖矩阵、10 项反例、J1–J6 独立裁判、F1–F6 故障注入（含 `/tmp` 副本隔离口径纠正）、metadata 六项、RK-1~5、门禁读数纪律、欠账与文档回写清单 |
