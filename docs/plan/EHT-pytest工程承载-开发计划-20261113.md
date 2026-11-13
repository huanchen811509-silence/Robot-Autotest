# EHT pytest 工程承载与适配解耦 — 开发计划（2026-11-13）

**撰写** 学徒 ｜ **审批** 大师（2026-11-13「均按你的建议来」）
**性质** 七道流程第 4 环正式产物（P1），由《工作规划 v1.0》升格。**本轮只交文档；EHT-04 及以后未开工。**
**上游（顺序不可倒置）** 评估报告 → **产品迭代优化 PRD**（`FR-v5.5-01`~`18`）→ 本计划 → 测试计划。**无 PRD 不开工编码。**
**流程规范** `docs/process/版本开发流程.md` §2.4：WBS 必含编号＋体量＋依赖＋**验收命令**＋AI 自主度；无验收命令的包视为未定义、不得认领；**超过两个包并行即违规**。

---

## 0. 结论先行

| # | 结论 |
|---|---|
| 1 | **EHT-01 ~ EHT-16** 共 16 包；本轮（P1）完成 **EHT-01 / 02 / 03**（决策记录 + PRD + 两份计划），其余 13 包未启 |
| 2 | **EHT-06 标记「不适用」**：D-A=A，kind 不改名，不追加 `V11_56`、不做全链路改名。这是决策取消，不是遗漏；按流程 §2.7 保留在表里不删 |
| 3 | 门禁 **48 步原样保留 + 只增 49–52**；每步 `--self-test` 必须真注入 → 判红 → 还原 → 判绿 |
| 4 | 顺序：先修口径（EHT-04）→ 补创建入口（EHT-05）→ 建实体（EHT-07）→ 绑定（EHT-08）→ 解耦演练（EHT-09）→ 前端接入（EHT-10~12）→ 门禁（EHT-13）→ 真实链路（EHT-14）→ **文档回写（EHT-15）** → 收口（EHT-16） |
| 5 | `VERSION` 仍 **5.0.0**，不 bump、不判 G 门、不对外称 GA；FR 前缀 `v5.5` 是文档编号 |

---

## 1. 计划基线（真值，不套旧数）

| 项 | 值 | 复核命令／来源 |
|---|---|---|
| 权威副本 | WSL `~/robot-autotest` @ `0248498`，工作树干净 | `git log -1 --oneline`；`git status --short` |
| `VERSION` | `5.0.0` | `cat VERSION` |
| 门禁 | 48 步 / `FAILS=2`（13 CDP 已取消、29 host_inventory 需现场重采） | `bash scripts/gates.sh` 尾部 `GATES_RESULT FAILS=N` |
| 后端／前端测试 | 后端 1717 例（4 skipped）；前端 783 例 / 104 文件；`vue-tsc` 无输出 | 上一轮收口记录 |
| 数据库 | 迁移 `V11_55`、业务表 105、`db-audit` PASS；项目 7 个（1/29/77/80/88/92/93） | `bash scripts/db-audit.sh`；`SELECT id,code,kind FROM project` |
| openapi | 403 endpoints / 334 paths | 上一轮重生成结果 |
| 用例库 | `pytest-cases` `HEAD=915c2ca`；469 用例 / 62 脚本；162 文件 M 为纯 CRLF 假脏 | `git -C <repo> rev-parse HEAD`；`git diff --ignore-cr-at-eol --stat` |
| 承载实体 | `kind=pytest-suite` 项目 0、`pytest.*` 配置 0、绑定 0 行 | 评估 §2.1 |

**命令纪律**：一律在 WSL 权威副本执行；门禁前 `set -a; . ~/.config/robot-autotest/env; set +a`；curl 加 `--noproxy '*'`；复杂脚本写 `/tmp/*.sh` 再 `bash /tmp/*.sh`，**不在 `bash -lc '...'` 里写 `$VAR`／`$()`／反引号**（外层 Git Bash 会提前展开，退出码恒 0）；Windows 侧写出的 `.sh` 先 `tr -d '\r'`。

---

## 2. 工程策略

| # | 策略 |
|---|---|
| S1 | **一次改动 = 一个可评审的包**。六张前端页每页独立提交，不攒大包；任何时刻并行包 ≤ 2 |
| S2 | **先口径、后实体、再界面**。`EHT-04` 未做完不建工程，避免「界面标 pytest、落库跑 RF」 |
| S3 | **真实链路才算完成**。单测绿／构建绿不是放行理由；Fake 70 tests 全链路是每个后端包的收口验收 |
| S4 | **只增不删**：既有 48 步门禁、既有端点、既有 FR 编号一律不动；新增机检 49–52 追加 |
| S5 | **不写用例库真源**。重命名／断链演练在 `/tmp` 最小副本 + 临时 `repo_path` 上做，演练后还原 |
| S6 | **构建后必须重启后端**。`mvn`／`build.sh` 重写 jar 会让旧进程懒加载报 `NoClassDefFoundError: logback ThrowableProxy`，表现为登录 30 s 超时而 `/api/_ping` 仍 200；重启后复验登录 |
| S7 | **每跑全量门禁会重写 4 份已跟踪副产物**，收口前 `git restore --` `ai-eval.json`／`capacity-report.json`／`security-scan.json`／`dora-trend.json` |

**AI 自主度**（沿用既有开发计划口径）：`A` 可自主实现并自测 ／ `B` 实现后须人工复核判据 ／ `C` 须现场或大师执行。发版、破坏性变更、对外口径、判 G 门恒为 `C`，学徒不得自主。

---

## 3. WBS：`EHT-01` ~ `EHT-16`

> 「验收命令」列是本计划的硬门槛；命令不可执行或不可判定的包不得认领。新增脚本统一标「新建」。

| 包 | 交付物 | 依赖 | 验收命令 | 自主度 | 机检增量 | 状态 |
|---|---|---|---|---|---|---|
| **EHT-01** | D-A~D-G 决策记录（含授权人与生效时点） | — | `python3 -c "import pathlib; t=pathlib.Path('docs/plan/EHT-pytest工程承载-产品迭代优化PRD-20261113.md').read_text(encoding='utf-8'); assert all(k in t for k in ['D-A','D-B','D-C','D-D','D-E','D-F','D-G','大师'])"` | A | — | **完成（本轮）**：落 PRD §2.1 |
| **EHT-02** | 产品迭代优化 PRD（18 条 FR + 判据 + NFR + 退出标准） | EHT-01 | `python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/EHT-pytest工程承载-产品迭代优化PRD-20261113.md` | A | `check_release_docs.py` 编号正则 | **完成（本轮）** |
| **EHT-03** | 正式 DEV-PLAN（本文）+ TEST-PLAN | EHT-02 | `python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/EHT-pytest工程承载-开发计划-20261113.md docs/plan/EHT-pytest工程承载-测试计划-20261113.md` | A | — | **完成（本轮）** |
| **EHT-04** | 口径偏差回写：旧评估 §4.1、旧 PRD `FR-v5.2-01` R3、`§13.1` 状态 | EHT-01（D-C 授权） | `python3 tools/check_release_docs.py`；`grep -n '降级达成' docs/plan/v5.1-5.4-pytest用例库工程化-产品PRD-20261112.md`；`grep -n 'normalizeKernel' backend/src/main/java/com/qa/autotest/application/iam/ProjectService.java` 与文档逐句比对；三份文档均留修订记录行 | **B**（改既有 FR 状态须人工复核） | `check_release_docs.py` 复跑 | 未启 |
| **EHT-05** | 创建入口：`CreateProjectRequest` 加 `kind`，HTTP 接 6 参 `create` | EHT-03 | `mvn -o -B -Dtest=ProjectKindTest,ProjectControllerTest test`；`curl -s --noproxy '*' -X POST ... -d '{"kind":"pytest-suite",...}'` → 200 且落库 kind 正确；不带 kind → `standard`；非法 kind → 400；`python3 tools/check_project_kind_create.py`（新建）PASS 且 `--self-test` 判红 | A | **新增 49** `check_project_kind_create.py`（真注入删 `kind` 字段判红） | 未启 |
| **EHT-06** | 命名包（**仅 D-A=B**：`V11_56` + 全链路改名） | EHT-05 | **不执行**。D-A=A，验收栏留空；保留本行不删，按流程 §2.7 标「不适用（决策取消）」 | C | — | **不适用** |
| **EHT-07** | 承载工程落地（走接口、留删前式取证 JSON） | EHT-05 | `POST /api/projects` 建 `code=EHT-PYTEST-LIB`／`kind=pytest-suite`／`testKernel=pytest`；`PUT /api/projects/{id}/pytest/settings` 填 4 项；`GET /api/projects/{id}/pytest/health` → 200 且「可跑」；`SELECT id,code,kind,test_kernel FROM project` 复核；证据落 `docs/evidence/v5.5.0/eht/` | **B**（首次向 `robot_dev` 写业务数据，人工复核取证 JSON） | — | 未启 |
| **EHT-08** | 绑定同步与真源 HEAD 快照 | EHT-07 | `POST .../pytest/bindings/sync` → 四类计数与 `case-index.json` 的 469／62 自洽；`GET .../bindings` 非空且无重复 `nodeid`；`git -C '/mnt/e/workspace/自动化测试/pytest-cases' rev-parse HEAD` 写入快照证据；`python3 tools/check_eht_binding_evidence.py`（新建）PASS | A | **新增 50** `check_eht_binding_evidence.py`（真注入把 `scripts` 改 0 判红） | 未启 |
| **EHT-09** | 解耦五条 E1–E5 注入演练 | EHT-08 | `bash scripts/secret-scan.sh` PASS；`python3 tools/check_kernel_decoupling.py` PASS；临时把 `repo_path` 指向不存在路径 → `health` 报「仓库不可达」不 500；执行前后 `git -C <repo> status --short` 一致（除行尾）；RF 项目 1／29 照常执行；还原配置 | A | 既有解耦步复跑 | 未启 |
| **EHT-10** | 用例管理页（469 分母／筛选／批量／绑定详情／非自动化入口） | EHT-03 | `npm run test:unit`；`npx vue-tsc --noEmit`（无输出）；`python3 tools/check_eht_case_ui.py`（新建）PASS；页面截图 + 真实列表端点响应留证 | A | **新增 51** `check_eht_case_ui.py`（真注入去掉一页 i18n 键判红） | 未启 |
| **EHT-11** | 可视化六页（L-54-01）+ 执行进度／失败原因，全接真端点 | EHT-10 | 六页逐页 `python3 tools/check_eht_pages_live.py`（新建）PASS；`python3 tools/check_nav_kind.py --self-test`；每页双语 i18n 键 + spec；`npm run test:unit` 全绿；逐页端点响应证据 | **B**（真端点一致性须人工抽验） | `check_nav_kind.py` 叶子基线重刷；**新增 52** `check_eht_pages_live.py`（真注入把端点改 mock 判红） | 未启 |
| **EHT-12** | 便捷操作聚合（一键同步／Fake 自证／执行预检／命令复制） | EHT-11 | 四条链路各跑通一次并留接口调用日志；预检不过时提交按钮不可用且展示原因码 + 人话摘要 + 修复提示；命令集合机检步复跑 | A | 既有 41 命令集合步复跑 | 未启 |
| **EHT-13** | 门禁增量与全量回归 | EHT-05~12 | `bash scripts/gates.sh` 只读尾部 `GATES_RESULT FAILS=N`；48 步原样保留 + 49–52 新增；两段分别登记；`bash scripts/db-audit.sh` PASS；openapi 重生成后比对漂移；`git restore --` 四份副产物 | A | 49–52 全部登记到 `check_structure`（41–48 → 41–52） | 未启 |
| **EHT-14** | 真实链路测试执行（流程 6） | EHT-13 | Fake 模式全量 70 tests → 全 `PLANNED`、通过率「不适用」；RF 回归 RK-1~5；六页真端点一致性；`mvn -o -B test` 与 `npm run test:unit` 全绿；`output.xml` metadata 六项齐；证据落 `docs/evidence/v5.5.0/eht/` | **B**（关键项人旁路抽验） | — | 未启 |
| **EHT-15** | **文档回写（流程 7）** | EHT-14 | 评估报告／PRD／DEV-PLAN／TEST-PLAN 四份逐项回写 + 修订记录；实施台账 + `.workbuddy/memory/` 同步；`check_md_tables.py` 全量复跑；`docs/process/版本开发流程.md` §7 记一行 | A | `check_md_tables.py` 全量复跑 | 未启 |
| **EHT-16** | 收口材料交大师 | EHT-15 | `git rev-parse HEAD`；`git ls-remote origin main`；`git ls-remote github main` → **三处 sha 一致**；E 盘 `git fetch ~/robot-autotest main && git merge --ff-only FETCH_HEAD` + `git update-ref`；附门禁读数与遗留清单 | **C**（不判 G、不发版、不 bump） | — | 未启 |

**体量参考**（`[估]` 非实测）：EHT-04／05 各 1 个提交；EHT-07／08 各 1 个提交 + 取证；EHT-10 1 页；EHT-11 六页各 1 提交；EHT-13／14／15 各 1 提交。**不把估算写进验收判据。**

---

## 4. 推进顺序与依赖图

| 阶段 | 包 | 前置 | 可并行（≤2） | 出门判据 |
|---|---|---|---|---|
| **P0 决策** | EHT-01 | — | — | D-A~D-G 落盘，学徒不再自行假设 |
| **P1 三文档** | EHT-02 → EHT-03 | EHT-01 | EHT-02 与 EHT-03 不并行（PRD 先行） | PRD 18 条 FR 可判定；两份计划 `check_md_tables` PASS |
| **P2 口径与实体** | EHT-04 → EHT-05 → EHT-07 → EHT-08 | EHT-03 | EHT-04 完成后 EHT-05 可与文档复核并行 | health 200「可跑」、绑定有数、HEAD 有快照 |
| **P3 解耦与管理** | EHT-09 ／ EHT-10 | EHT-08 | **允许 EHT-09 与 EHT-10 并行（仅此一对）** | 断链降级不崩、源码零写入、469 分母可查 |
| **P4 可视化** | EHT-11 | EHT-10 | — | 六页真端点一致，非 mock |
| **P5 便捷** | EHT-12 | EHT-11 | — | 四链路留痕，失败有原因码 |
| **P6 测试与回写** | EHT-13 → EHT-14 → EHT-15 → EHT-16 | EHT-05~12 | 不并行 | 门禁读数 + 测试结果 + 四份文档回写；**不回写＝版本未完成** |

**关键路径**：EHT-01 → 02 → 03 → 04 → 05 → 07 → 08 → 09 → 11 → 12 → 13 → 14 → 15 → 16。`EHT-10` 可在 P3 与 `EHT-09` 并行，`EHT-06` 已从关键路径移除。

---

## 5. 门禁增量 49–52（只增不删）

| 步 | 脚本（新建） | 真注入判红 | 还原判绿 | 归属包 |
|---|---|---|---|---|
| 49 | `check_project_kind_create.py` | 删掉 `CreateProjectRequest` 的 `kind` 字段 → 必须 FAIL | 还原字段 → PASS | EHT-05 |
| 50 | `check_eht_binding_evidence.py` | 把证据里 `scripts` 改成 0 → 必须 FAIL | 还原 → PASS | EHT-08 |
| 51 | `check_eht_case_ui.py` | 去掉一页 i18n 键 → 必须 FAIL | 还原 → PASS | EHT-10 |
| 52 | `check_eht_pages_live.py` | 把某页端点改成 mock → 必须 FAIL | 还原 → PASS | EHT-11 |

**读数纪律**：`docs/evidence/gates/logs/*.log` 是历史累积，禁止 `grep FAIL` 当本轮读数；只看尾部 `GATES_RESULT FAILS=N`。原 48 步段与新增 4 步段**分别登记 PASS/FAIL，不得相加成「全绿」**。`db/schema-contract.json` 的 `expectedTables` 仅在新表出现时同步改并重跑 `db-audit`。

---

## 6. 降级预案

| 触发 | 降级动作 | 必须写明 | 授权 |
|---|---|---|---|
| D-A 改为 B（大师后续改主意） | 重启 EHT-06，先改名后建实体，拆成独立提交；追加 `V11_56` 改列注释、全链路字面替换、5 份文档口径回写、机检基线重刷 | 理由 + 授权人 + 旧值 `pytest-suite` 在库 0 行／源码 0 处残留 | 大师 |
| 六页无法全做 | 逐页标「延后」并写去向，**不许从 WBS 表删掉**；PRD 对应 FR 状态改「未达成／降级」 | 理由 + 授权人 | 大师 |
| G-1 仍未授权 | L-54-02 保持「未达成」，不写入验收判据 | 解除条件 | 大师 |
| host_inventory 现场无法重采 | 门禁 29 步如实 FAIL，**不许改证据凑源码** | 原因 + 现场重采计划 | 大师 |
| Fake 70 tests 链路跑不通 | 判 P0 未达成，不得发版；不得用单测绿／构建绿替代 | 失败归属（代码／用例／环境） | 大师 |

---

## 7. 偏差记录（实施期逐包填写，不预先填）

| 包 | 计划 | 实际 | 偏差原因 | 提交号／证据 |
|---|---|---|---|---|
| EHT-01 | 决策记录落 `docs/plan/` | 落 PRD §2.1，7 项全有授权人与时点 | 与 PRD 合并交付，未单开文件（减少一份漂移文档） | 本轮提交号由 EHT-16 回填 |
| EHT-02 | `FR-EHT-xx` | 改为 `FR-v5.5-01`~`18` | `check_release_docs.py` 的 `detect_prefix` 只认 `FR-v[\d.]+-\d+`；已在 PRD §2.2 留对照与修订记录 | 同上 |
| EHT-03 | `TC-EHT-01`~`12` | 改为 `TC-v5.5-01`~`18` | 同上；且 18 条 FR 需一一覆盖 | 同上 |
| EHT-06 | 条件包（D-A=B 才启） | 标记「不适用」，未执行 | D-A 定为 A，kind 不改名 | 同上 |
| EHT-04 ~ 05 / 07 ~ 16 | 待实施 | 未启 | — | 实施期逐包填 |

**已登记的已知偏差（非本轮）**：旧 PRD `FR-v5.2-01` R3 与代码不符、`PRD §13.1` 状态标「达成」依据不足 —— D-C 已授权，归 EHT-04 订正。

---

## 8. 风险预判（工程侧）

| # | 风险 | 预判与处置 |
|---|---|---|
| R1 | 构建覆盖运行中的 jar → 登录 30 s 超时但 `_ping` 200 | 见 S6；构建后 `systemctl --user restart robot-autotest-backend`，复验登录而非只看 `_ping` |
| R2 | 跨层 shell 变量被外层 Git Bash 提前展开，真实退出码恒 0 | 复杂脚本写 `/tmp/*.sh` 再执行；外部 CLI 一律 `< /dev/null` |
| R3 | Windows 侧写 WSL 脚本带 CRLF → `$'\r': command not found` | `tr -d '\r'` 后再跑；`.md` 写盘后 `assert '\ufffd' not in text` |
| R4 | 已应用迁移不可改、乱序追加 validation failed | D-A=A 不追加 `V11_56`；若将来追加须顺延新号，取最新版本用 `ORDER BY installed_rank DESC LIMIT 1` |
| R5 | `check_colors` 失败挡住其后的 `check_tokens` | 修完一项重跑整条 `scripts/lint.sh` |
| R6 | 六页联动 i18n／菜单叶子基线／spec | 每页独立提交，每页 `--self-test` |
| R7 | 重命名跟随演练写用例库真源，违反 E2 | `/tmp` 最小副本 + 临时 `repo_path`，演练后还原；详见 TEST-PLAN §7 |
| R8 | 全量门禁重写 4 份已跟踪副产物 | 收口前 `git restore --` |
| R9 | 「接口形状已按页面产出」是文档自述 | EHT-11 真端点联调，不接受 mock |

---

## 9. 授权闸门（学徒不得自主）

| # | 事项 | 归属 |
|---|---|---|
| G1 | 改既有 FR 状态（`FR-v5.2-01` R3 / `§13.1`） | D-C 已授权，但复核判据归人工（EHT-04 自主度 B） |
| G2 | 首次向 `robot_dev` 写业务数据（建承载工程） | 人工复核取证 JSON（EHT-07 自主度 B） |
| G3 | 六页真端点一致性判定 | 人工旁路抽验（EHT-11 自主度 B） |
| G4 | AI 真实端点 G-1 授权 | **未授权**，保持 L-54-02「未达成」 |
| G5 | bump 版本、判 G 门、发版、对外口径、CHANGELOG 对外小节 | **大师**，本计划不含 |
| G6 | 75／76 历史删除授权偏差是否恢复 | 仍待大师确认，本轮不动 |

---

## 10. 文档回写清单（P6 必做，逐项做）

| 文档 | 回写内容 |
|---|---|
| 评估报告 `EHT-pytest工程承载-评估报告-20261113.md` | 被实测推翻的假设逐条改（尤其 §2.3 内核码偏差、§2.4 行尾差异定性），留「原值／实测值／影响」，不悄悄改数字 |
| PRD（本系列） | 18 条 FR 状态落定：达成／未达成／降级；降级写理由 + 授权人 |
| 开发计划（本文） | §7 偏差记录逐包填实际完成与提交号；未完成包标「延后」+ 去向，不删行 |
| 测试计划 | TC 实测结果、遗留缺陷 `L-EHT-xx`，与既有 L-54-01~05 并列不覆盖 |
| 实施台账 | 每包提交号／证据路径／门禁读数 |
| 项目记忆 | `.workbuddy/memory/YYYY-MM-DD.md` 追加；`MEMORY.md` 更新当前提交与项目数真值 |
| 流程规范 | `docs/process/版本开发流程.md` §7 回写记录表记一行 |

Markdown 写完统一跑 `check_md_tables.py`；文档补丁**按内容匹配**，绝不用行号。P6 若按流程规范升格 `docs/release/v5.5.0/`，需落齐六份产物并复跑 `python3 tools/check_release_docs.py`（FR/TC 前缀已按该脚本正则统一，见 PRD §2.2）。

---

## 11. 修订记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.0 | 2026-11-13 | 首版：由《工作规划 v1.0》升格；EHT-01~16 WBS 含验收命令与自主度、依赖图与并行纪律、门禁 49–52、降级预案、偏差记录、授权闸门、文档回写清单；EHT-06 标不适用；FR/TC 编号按机检正则修订 |
