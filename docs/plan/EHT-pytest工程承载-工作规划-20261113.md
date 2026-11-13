# EHT pytest 工程承载与适配解耦 — 工作规划（2026-11-13）

**撰写** 学徒 → 交大师拍板 ｜ **性质** 七道流程第 4–5 环的**规划草案**（WBS + 测试纲要），**不是执行授权、不是正式 DEV-PLAN / TEST-PLAN**。
**依赖** 《EHT pytest 工程承载与适配解耦 — 评估报告（2026-11-13）》及其中 **D-A ~ D-G 决策项**；无评估报告不许开 PRD，无 PRD 不许把本规划升格为正式计划，无正式计划不许开工编码。
**基线** `~/robot-autotest` @ `ac92543`；门禁 **48 步 / FAILS=2**（13 CDP 已取消、29 host_inventory 需现场重采）；业务表 105；`db-audit` PASS；库内 7 个项目。
**版本口径** 建议放在 **5.5.0 ~ 5.5.x（X<15）** 承载；**当前 VERSION 仍 5.0.0，不 bump、不发版、不对外称 GA**，发版与 G 门归大师。

---

## 1. 阶段划分（七道流程对齐，顺序不可跳）

| 阶段 | 环节 | 内容 | 出门判据 |
|---|---|---|---|
| **P0** | 决策 | D-A ~ D-G 拍板；冻结命名、项目 code、内核码口径、真源口径、版本区间 | 决策记录落盘，学徒不再自行假设 |
| **P1** | 流程 3–5 | 产品 PRD（`FR-EHT-xx` + 验收判据）→ 正式 DEV-PLAN → 正式 TEST-PLAN | 三份文档评审通过；每条 FR 可判定 |
| **P2** | 口径与实体 | 修 `pytest-nic` 偏差 → 补创建入口 → 建承载工程 → 填必填配置 → 绑定同步 | 工程 health 200、绑定四类计数有数、真源 HEAD 有快照 |
| **P3** | 解耦与用例管理 | E1–E5 注入演练；用例管理页（筛选/评审态/批量/绑定详情/非自动化入口） | 断链降级不崩、源码零写入、469 分母可查 |
| **P4** | 可视化 | L-54-01 六页 + 执行进度 / 错误原因展示，全部接真实端点 | 每页有真端点响应证据，非 mock |
| **P5** | 便捷操作 | 一键同步 / 一键 Fake 自证 / 一键执行预检 / 批量选例 / 命令复制 | 端到端操作留痕，失败有原因码与人话提示 |
| **P6** | 流程 6–7 | 真实链路测试执行 → 全量门禁 → **文档回写** | 测试结果 + 证据文件齐；四份文档同步更新；不回写＝版本未完成 |

**并行约束**：P2 的「改名包」（仅 D-A=B 时）必须先于「建工程包」完成；P3–P5 的前端页每页独立提交，不攒大包；P6 的门禁必须最后跑，且跑完 `git restore --` 四份已跟踪副产物。

---

## 2. WBS 与验收命令

> 命令均在 **WSL 权威副本 `~/robot-autotest`** 下跑；门禁前 `set -a; . ~/.config/robot-autotest/env; set +a`；curl 加 `--noproxy '*'`。
> 新增机检一律 **只增不删**，且 `--self-test` 必须真注入 → 判红 → 还原。

| 包 | 交付物 | 验收判据 / 命令 | 依赖 | 机检增量 |
|---|---|---|---|---|
| **EHT-01** | D-A~D-G 决策记录（含命名结论与授权人） | 决策文档落 `docs/plan/`，每项有明确取值 | — | — |
| **EHT-02** | 产品 PRD（`FR-EHT-01`~`FR-EHT-xx`） | 每条 FR 有验收判据；`python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py <PRD>` PASS | EHT-01 | `check_release_docs.py` 认全编号 |
| **EHT-03** | 正式 DEV-PLAN + TEST-PLAN | WBS 编号与本文对齐；TC 编号 + 证据形态齐；`check_md_tables.py` PASS | EHT-02 | — |
| **EHT-04** | 口径偏差回写（评估报告 §4.1 / PRD FR-v5.2-01 R3 / §13.1 状态） | `ProjectService.normalizeKernel()` 与文档一致；三份文档口径同源；留修订记录 | EHT-01（D-C 授权） | `check_release_docs.py` 复跑 |
| **EHT-05** | 创建入口：`CreateProjectRequest` 加 `kind`，接 6 参 `create` | `POST /api/projects` 带 `kind=pytest-suite` → 200 且落库 kind 正确；不带 kind 仍为 standard；非法 kind 400 不静默回落；`mvn -o -B -Dtest=ProjectKindTest,ProjectControllerTest test` 绿 | EHT-03 | **新增 49**：`check_project_kind_create.py`（真注入删 kind 判红） |
| **EHT-06** | 命名包（**仅 D-A=B**）：追加 V11_56 改列注释 + 全链路改名 | 库里旧值 0 行；源码 `pytest-suite` 字面 0 处；`ProjectKindTest` / `navigation.spec.ts` / `check_nav_kind.py --self-test` 全绿；5 份文档口径回写 | EHT-05 | 44 / `check_navigation.py` 基线重刷 |
| **EHT-07** | 承载工程落地（走接口，留 JSON 取证） | `POST /api/projects` 建 `code=EHT-PYTEST-LIB`、`kind=pytest-suite`、`testKernel=pytest`；`PUT /api/projects/{id}/pytest/settings` 填 4 项必填；`GET .../pytest/health` 200 且「可跑」；删前式取证 JSON 落 `docs/evidence/v5.5.0/` | EHT-05（+EHT-06 若 B） | — |
| **EHT-08** | 绑定同步与真源快照 | `POST .../pytest/bindings/sync` → 四类计数（bound / unbound / orphan / pending）与 `case-index.json` 的 469 / 62 对得上；`GET .../bindings` 非空；`git -C /mnt/e/workspace/自动化测试/pytest-cases rev-parse HEAD` 写入快照证据 | EHT-07 | **新增 50**：`check_eht_binding_evidence.py` |
| **EHT-09** | 解耦五条注入演练 E1–E5 | 用例库目录改名（不可达）→ health 报「仓库不可达」不抛 500；执行前后 `git -C ... status --short` 一致（除行尾）；`bash scripts/secret-scan.sh` PASS；`python3 tools/check_kernel_decoupling.py` PASS；RF 项目 1/29 照常执行 | EHT-08 | 既有解耦步复跑 |
| **EHT-10** | 用例管理页 | 469 分母列表、大类/优先级/评审态筛选、批量选例、绑定详情、重命名跟随可见、非自动化用例编辑入口；`npm run test:unit` + `npx vue-tsc --noEmit` 无输出 | EHT-03 | **新增 51**：`check_eht_case_ui.py` |
| **EHT-11** | 可视化六页（L-54-01）+ 进度 / 错误原因 | 时长基线 / 处置闭环 / 环境雷达 / 双内核视图 / 手工执行 / 分离统计 六页各接真端点；执行进度与失败归因有 UI；每页双语 i18n 键 + spec；`check_nav_kind.py` 叶子基线重刷 | EHT-10 | 44 基线重刷；**新增 52**：`check_eht_pages_live.py` |
| **EHT-12** | 便捷操作聚合 | 一键同步 / 一键 Fake 自证 / 一键执行（预检通过才提交）/ 命令集合一键复制；失败时展示原因码 + 人话摘要 + 修复提示 | EHT-11 | 41 命令集合步复跑 |
| **EHT-13** | 门禁增量与全量回归 | `bash scripts/gates.sh` → 只读尾部 `GATES_RESULT FAILS=N`；**48 步原样保留 + 新增 4 步**；每段分别登记 PASS/FAIL，不得相加成「全绿」；`bash scripts/db-audit.sh` PASS；openapi 重生成后比对漂移 | EHT-05~12 | 49–52 |
| **EHT-14** | 真实链路测试执行（流程 6） | Fake 模式全量 70 tests → 全 `PLANNED`、通过率「不适用」；RF 回归 RK-1~5；六页真端点一致性；`mvn -o -B test`、`npm run test:unit` 全绿；证据落 `docs/evidence/v5.5.0/` | EHT-13 | — |
| **EHT-15** | 文档回写（流程 7） | 评估报告：被实测推翻的假设逐条改并留修订记录；PRD：验收状态落定；DEV-PLAN：实际完成与偏差；TEST-PLAN：实测结果与遗留缺陷；实施台账 + 项目记忆同步 | EHT-14 | `check_md_tables.py` 全量复跑 |
| **EHT-16** | 收口材料交大师 | 提交号 + 三处 sha 一致（`git ls-remote` 复核）+ 门禁读数 + 遗留清单；**不判 G 门、不发版** | EHT-15 | — |

**判据口径**：`FAILS=N` 只看尾部；`docs/evidence/gates/logs/*.log` 是历史累积，禁止 `grep FAIL` 当本轮读数；全量门禁会重写 `ai-eval.json` / `capacity-report.json` / `security-scan.json` / `dora-trend.json` 四份，收口前 `git restore --`。

---

## 3. 机检门禁增量（只增不删）

| 步 | 脚本 | 判红注入 | 还原方式 |
|---|---|---|---|
| 49 | `check_project_kind_create.py` | 删掉 `CreateProjectRequest` 的 `kind` 字段 → 必须 FAIL | 还原字段 → PASS |
| 50 | `check_eht_binding_evidence.py` | 把证据里 `scripts` 改成 0 → 必须 FAIL | 还原 → PASS |
| 51 | `check_eht_case_ui.py` | 去掉一页 i18n 键 → 必须 FAIL | 还原 → PASS |
| 52 | `check_eht_pages_live.py` | 把某页端点改成 mock → 必须 FAIL | 还原 → PASS |

四步都进 `scripts/gates.sh` 的 `check_structure` 登记（41–48 → 41–52），`db/schema-contract.json` 的 expectedTables 若因新表变化则同步改并重跑 `db-audit`。

---

## 4. 测试计划纲要（正式 TEST-PLAN 待 P1）

| TC | 覆盖 | 判据 | 证据形态 |
|---|---|---|---|
| TC-EHT-01 | 建工程 | kind 落库为 `pytest-suite`（或 D-A 定值）、默认内核正确、非法 kind 400 | 接口响应 + 库查询 JSON |
| TC-EHT-02 | 必填校验 | 缺 `repo_path` / `python_bin` / `env_config` / `inventory_path` 任一项，`settings/validate` 报错且执行预检拒跑 | 响应体 + 预检日志 |
| TC-EHT-03 | 绑定同步 | 469 分母 / 62 脚本四类计数自洽（bound + unbound == 全部）；无重复 nodeid | `sync` 报告 + `bindings` 响应 |
| TC-EHT-04 | 重命名跟随 | 改一个脚本文件名后重扫，绑定跟到新路径、不算 orphan | 前后绑定 diff |
| TC-EHT-05 | 解耦降级 | 目录不可达 → health 报原因不 500；源码零写入；凭据零明文；RF 项目不受影响 | 注入演练日志 + `secret-scan` 输出 |
| TC-EHT-06 | 适配全链路 | Fake 70 tests → junit 归一 → 结果入库，全 `PLANNED`，通过率「不适用」 | `output.xml` / `run.log` / 归一 JSON |
| TC-EHT-07 | 回归红线 RK-1~5 | RF 用例、拆单、报告、看板、菜单 5 条不回退 | 回归前后对比 + 门禁读数 |
| TC-EHT-08 | 六页真端点 | 每页数据源为真实端点，非 mock；响应字段与页面展示一致 | 逐页截图 + 端点响应 |
| TC-EHT-09 | 便捷操作 | 一键同步 / 自证 / 执行 / 复制四条链路各跑通一次，失败有原因码 | 操作日志 + 接口调用链 |
| TC-EHT-10 | 机检自证 | 49–52 步 `--self-test` 真注入判红、还原判绿 | 门禁日志 |
| TC-EHT-11 | 结构一致 | `db-audit` PASS、openapi 无漂移、`check_md_tables` PASS | 命令输出 |
| TC-EHT-12 | 文档回写 | 四份文档状态与实测一致，无「CHANGELOG 写已完成但代码没有」 | 文档 diff + 修订记录 |

**硬性约定**：所有原始 `output.xml` 带 metadata（CASE_ID / RUN_ID / QA_PROJECT / DUT / LINK / SW_BUILD 六项必填，`RUN_ID` 键名不可改，不放凭据）；未测或未过不许发版；**不接受实现方自证**。

---

## 5. 文档回写清单（P6 必做）

| 文档 | 回写内容 |
|---|---|
| 评估报告（本文配套） | 被实测推翻的假设逐条改（尤其 §2.3 内核码偏差、§2.4 行尾差异定性），留修订记录，不悄悄改数字 |
| 产品 PRD | `FR-EHT-xx` 状态落定（达成 / 未达成 / 降级，降级写理由与授权人） |
| 开发计划 | 实际完成情况、WBS 偏差原因（对照本文 EHT-01~16） |
| 测试计划 | TC 实测结果 + 遗留缺陷（L-EHT-xx），与 §12.2 旧遗留并列不覆盖 |
| 实施台账 | 每包提交号 / 证据路径 / 门禁读数 |
| 项目记忆 | `.workbuddy/memory/YYYY-MM-DD.md` 追加；`MEMORY.md` 更新当前提交与项目数真值 |

Markdown 写完统一跑 `check_md_tables.py`；文档补丁**按内容匹配**，绝不用行号。

---

## 6. 风险、遗留与不做

| # | 项 | 处置 |
|---|---|---|
| R1 | D-A 选 B 时的改名与建工程耦合 | 强制拆 EHT-06 / EHT-07 两包，先改名后建实体 |
| R2 | 用例库 162 文件 CRLF 假脏 | 快照只记 `rev-parse HEAD`（当前 `915c2ca`）；不批量改写文件 |
| R3 | 六页联动 i18n / 菜单叶子基线 / spec | 每页独立提交，`check_nav_kind.py --self-test` 每页复跑 |
| R4 | G-1 AI 端点未授权 | L-54-02 保持「未达成」，不写入验收判据 |
| R5 | 门禁已知 2 项 FAIL | 与本轮无关的 FAIL 如实保留，**不许改证据凑源码**（尤其 29 host_inventory） |
| R6 | 全量门禁重写 4 份已跟踪副产物 | 判门前 `git restore --` 四份 |
| R7 | 前端「接口形状已按页面产出」是文档自述 | EHT-11 必须做真端点联调，不能以单测 mock 代替 |

**不做**：不写用例库源码、不复制脚本进平台、不改 RF 内核、不物理 SQL 建项目、不自动建项目、不擅自 bump 5.0.0、不对外称 GA、AI 不勾 M 项 / 不判 G 门。

---

## 7. 里程碑与判门

| 里程碑 | 完成标志 | 谁拍板 |
|---|---|---|
| M0 决策冻结 | D-A~D-G 落盘 | 大师 |
| M1 三份文档齐 | PRD / DEV-PLAN / TEST-PLAN 评审通过 | 大师 |
| M2 实体可跑 | 工程 health 200 + 绑定有数 + Fake 自证通过 | 学徒交付、大师复核 |
| M3 界面齐 | 六页 + 管理 + 便捷操作真端点联调完成 | 学徒交付、大师复核 |
| M4 测试与回写 | 门禁读数 + 测试结果 + 四份文档回写 | 学徒交付、大师复核 |
| **G 门 / 发版** | **不在本规划内** | **大师** |

双远程 `git push origin main` + `git push github main`，判据＝三处 sha 一致；E 盘走本地文件通道同步（E 侧 `git fetch ~/robot-autotest main && git merge --ff-only FETCH_HEAD`）。

---

## 修订记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.0 | 2026-11-13 | 首版：P0–P6 七阶段、EHT-01~16 WBS 与验收命令、49–52 门禁增量、TC-EHT-01~12 纲要、文档回写清单、风险与不判门声明 |
