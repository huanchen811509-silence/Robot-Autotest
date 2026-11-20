# EHT pytest 用例库新导入脚本 — 平台适配评估（2026-11-20）

**环节** 第 ② 环（评估报告）：只回答「平台能不能吃下这批脚本、哪里吃不下、代价多大」，不含 FR 编号、不含 WBS、不含 TC。
**范围** 用例库 `pytest-cases@7ee6877`（319 文件 / +42274 行）新导入的脚本与相关 AW 改动，对照平台 `robot-autotest@b327d18`。
**上游** `docs/plan/EHT-pytest工程承载-产品迭代优化PRD-20261113.md`（N3 解耦口径）、`docs/plan/交接文档-扫描边界收口-20261120.md`。
**取证时点** 2026-11-20 23:40 ~ 23:55（GMT+8）。

---

## 0. 结论先行

**能吃下，且不需要为这批文件改扫描/绑定/执行三层代码。** 71 个正式脚本与平台契约 100% 对齐：编号解析、nodeid、单函数、0 重复、0 孤儿。

但有 6 处「只吃下半截」，按影响排序：

| 序 | 问题 | 影响面 | 判定 |
|---|---|---|---|
| 1 | **评审目录自带的安全门（`conftest`）不会跟着脚本一起迁入** | 24 个脚本带 `destructive` / `requires_reboot` / `environment_gated` | 必须在迁入前处置，否则 real 模式会真刷固件、真重启 |
| 2 | 16 条新用例被服务端兜底成 `P2`，而非台账口径的「待评审 NULL」 | 16 条 | 口径不一致，会让覆盖率分母失真 |
| 3 | 已绑定的 71 条用例里 **41 条仍 `pending`** | 58% 绑定 | 一旦打开 `execution.require-approved`，一半以上跑不了 |
| 4 | 平台不解析 `[归属]` 行 | 22 个双端脚本的证据缺 host 归属 | 证据可读性缺口，不影响执行 |
| 5 | `CRITERIA_JSON` 只覆盖 1/71 | 判据链 | 结构化判据基本没有，只能靠日志文本 |
| 6 | `pytest.repo_path` 指向 `/mnt/e`（9P） | 全部 real 执行 | 既有结论：9P 构建/读文件慢约 35 倍 |

**最重要的一句**：R1 是「现在跑没事、将来一定出事」的类型——这 24 个脚本现在被目录排除在外，安全门与脚本在同一个目录里，所以看着没问题；等评审通过把它们搬进特性目录时，**脚本走了、门留下了**。

---

## 1. 取证方法与可复现

| 手段 | 命令 / 位置 | 用途 |
|---|---|---|
| 用例库形态统计 | `python3 /tmp/scan_cases.py`（按目录、编号来源、marker、parametrize 统计） | 得到 218/71/146 的分层 |
| **平台规则复刻** | `python3 /tmp/replica_scan.py`：按 `PytestScriptScanner` 的 `SKIP_DIRS` / `CASE_CODE` / `FILE_CODE` / `TEST_FUNC` 重写一遍 | 复刻结果 **71** 与平台 `/pytest/health` 的 `scriptsScanned=71` 一致，互为校验 |
| 编号一致性 | `python3 /tmp/code_consistency.py` | 文档串编号 vs 文件名降级编号，0 处不一致 |
| 台账读数 | `mysql robot_dev`（只读） | 485 用例 / 71 绑定 / 优先级与评审态分布 |
| 平台契约 | 读码：`PytestScriptScanner`、`PytestNicKernelCommand`、`PytestResultNormalizer`、`PytestStepExtractor`、`PytestCriteriaExtractor`、`PytestBindingSyncService` | 判定「平台认什么」 |

复刻扫描这件事值得单独说明：**先复刻、再对账**。只有复刻出的 71 与平台实测 71 相等，后面按目录/编号做的分类才可信；否则统计只是我自己的猜测。

---

## 2. 事实清单（全部为实测，可复现）

### 2.1 规模分层

| 分层 | 脚本数 | 平台口径 |
|---|---|---|
| 用例库 `tests/` 下 `test_*.py` 合计 | 218 | — |
| 正式目录（`base`/`ethernet`/`nic_data_integrity`/`rdma`/`performance`/`virtualization`/`mixed`/`hardware`） | 71 | **参与扫描与绑定** |
| `pending_review/`（含 batch1~5 子目录） | 54 | 内置跳过 |
| `awaiting_review/`（含 batch_fun_pxe、batch_fun_tunicadm、reboot_only） | 92 | 内置跳过 |
| `tests/test_config_and_plan.py`（框架自测，9 个 test 函数、无编号） | 1 | 无编号 → 不是用例 |

`tests/nic/new_scripts/` 目录**存在但为空**（只剩 `__pycache__`）。它不是评审目录，将来若把未定稿脚本丢进去，会被当成正式用例扫到（见 R7）。

### 2.2 编号与命名

| 项 | 实测 |
|---|---|
| 71 个正式脚本的编号来源 | **71 / 71 来自文档串 `用例编号:`**，文件名降级路径在正式目录一次都没用上 |
| 文件名降级是否保留前导零 | **保留**（`PytestScriptScanner` 用 `group(3)` 原样拼接），故 `test_im_fun_014.py` → `IM-FUN-014`，与台账口径一致 |
| 文档串编号 vs 文件名编号不一致 | **0 处**（先按去零误判 67 处，读码确认保留前导零后归零——这是我自己的口径错误，不是仓库问题） |
| 同编号多文件 | 2 组（`IM-NET-007`、`IM-NET-057`），均为「正式目录一份 + 评审目录一份」，内置排除后归零 |
| 文件名不匹配 `test_<域>_<数字>.py` | 2 个：`test_im_cer_008.py`（`cer` 域，规则能认）、`test_config_and_plan.py`（框架自测，应忽略） |

### 2.3 内容形态

| 项 | 实测 | 对平台的含义 |
|---|---|---|
| `@pytest.mark.parametrize` | **0 个脚本** | 「一文件 = 一用例 = 一绑定」的口径成立，不需要参数化拆分 |
| 同文件多个 `def test_*` | **0 个**（框架自测那 9 个已被「无编号」拦掉） | nodeid 取第一个函数即可，无歧义 |
| marker 覆盖（71 内） | `nic` 69、`performance` 5、`rdma` 2 | 都是已注册 marker，`pyproject.toml` 里齐全，不会有 `PytestUnknownMarkWarning` |
| 高风险 marker（71 内） | **0**（无 `destructive` / `requires_reboot` / `environment_gated`） | 当前 real 执行没有「跑着跑着机器失联」的风险 |
| 高风险 marker（146 评审脚本内） | **24 个脚本**（`awaiting_review` 里 24，`pending_review` 里 0） | 迁入后才暴露，见 R1 |
| `[明细]` 覆盖 | **71 / 71** | 步骤证据有来源，`PytestStepExtractor` 不会空手 |
| `CRITERIA_JSON` 覆盖 | **1 / 71**（仅 `base/test_im_fun_014.py`） | 结构化判据几乎为零 |
| 双端脚本（`bind_hosts`） | **22 / 71** | 这 22 个会打 `[归属]` 行，平台不解析（R4） |

### 2.4 台账与绑定

| 读数 | 值 |
|---|---|
| 用例总数（项目 95，未删） | **485** |
| `pytest` 内核绑定 | **71** |
| 孤儿绑定（绑定指向已删/不存在用例） | **0** |
| 优先级分布 | P0 231 / `NULL` 178 / P2 49 / P1 27 |
| 16 条新用例（`IM-NIC-002/004/008/012~018`、`IM-FUN-079~084`） | id `32648~32663` 全部存在，**`priority` 全为 P2**，`review_status` 全为 `pending` |
| 已绑定用例的评审态 | `approved` 30 / `pending` 41 |
| 绑定脚本目录分布 | `base` 24 / `ethernet` 24 / `nic_data_integrity` 10 / `rdma` 5 / `performance` 4 / `virtualization` 2 / `hardware` 1 / `mixed` 1 |

> 16 条新用例里，10 条（`IM-NIC-*`）脚本在正式目录 `nic_data_integrity/` → 已绑定；
> 6 条（`IM-FUN-079~084`）脚本在 `awaiting_review/batch_fun_tunicadm/` → **台账有、绑定无**。
> 这不是缺陷，是「脚本还没过评审」的正确状态，但汇报时必须说清，否则会被读成「平台漏绑 6 条」。

### 2.5 运行配置（项目 95 实测）

| 键 | 值 | 备注 |
|---|---|---|
| `pytest.repo_path` | `/mnt/e/workspace/自动化测试/pytest-cases` | **9P 挂载**，real 执行读文件慢（R6） |
| `pytest.python_bin` | `/home/evergreen/venvs/nic-fake/bin/python` | 独立 venv，非系统 python |
| `pytest.mode_default` | `fake` | 安全默认，符合预期 |
| `pytest.env_config` | `config/environments/run_env_lab.yaml` | 执行期会被渲染产物覆盖（`PytestNicKernelCommand` 传绝对路径） |
| `pytest.inventory_path` | `config/inventory/inventory_lab.yaml` | 与门禁 37 判的同一份文件 |

### 2.6 用例库工作区状态

`git status` 显示 **283 个文件「已修改」**，但 `git diff --ignore-cr-at-eol` 为**空**——
全部是行尾符（Windows 检出把 LF 改成 CRLF），**不是内容改动**。抽样 71 个正式脚本，61 个工作区字节与 git blob 不一致，同一原因。

---

## 3. 平台侧契约（读码定位，不是推测）

| 维度 | 平台实现 | 判据 |
|---|---|---|
| 扫描边界 | `PytestScriptScanner.SKIP_DIRS` = 7 个产物目录 + `pending_review` + `awaiting_review`，按**路径段名**匹配 | 目录名精确命中即整棵跳过 |
| 编号解析 | 先认文件头 8KB 内 `用例编号[:：] X-Y-Z`，再退 `^test_([a-z]+)_([a-z]+)_(\d+)\.py$` | 两者都无 → 不算用例 |
| nodeid | `relativePath::第一个 test 函数名` | 同文件多函数会漏其余函数（本批不受影响） |
| 执行命令 | `python -m pytest --env_config <渲染产物> --mode <fake/real> --junitxml=... -o junit_family=xunit2 -o junit_logging=out-err --capture=fd -q <nodeid...>` | 不使用 `--json-report`（自研库不认） |
| 结果归一 | `PytestResultNormalizer`：junit `<skipped>` → `CaseStatus.SKIP`；全 SKIP 单独成状态 | 环境门槛跳过不会被算成失败 |
| 步骤证据 | `PytestStepExtractor` 只认两种前缀：`[明细]`、`[判定]` | **`[归属]` 不在识别范围内** |
| 判据证据 | `PytestCriteriaExtractor` 取 `CRITERIA_JSON` 哨兵块，junit `system-out` 为主通道 | 无哨兵则回落到日志文本 |
| 重命名跟随 | 按 `script_sha256` 对齐上一轮绑定 | 仅对「编号不在台账里」的脚本生效 |

---

## 4. 适配矩阵

| 维度 | 现状 | 判定 | 依据 |
|---|---|---|---|
| 目录扫描 | 71 正式 / 146 评审被跳过 | **兼容** | 复刻 = 平台 = 71 |
| 编号解析 | 71/71 文档串命中，0 不一致 | **兼容** | §2.2 |
| 一带一绑定 | 0 参数化、0 多函数 | **兼容** | §2.3 |
| marker 注册 | 全部已在 `pyproject.toml` 注册 | **兼容** | 仓库 ini |
| 环境门槛跳过 | `environment_gated` 的 hook 在 `awaiting_review/conftest.py` | **需补**（迁入即失效） | R1 |
| 破坏性/重启类用例 | 平台 0 处识别 `destructive` / `requires_reboot` | **需决策**（是否在平台侧也加一道闸） | R1 |
| 台账优先级口径 | 新建用例走服务端兜底 `P2` | **需补**（要么改兜底，要么准入时显式传） | R2 |
| 评审态门禁 | `execution.require-approved` 已存在，默认关 | **需决策**（开则拦 41/71） | R3 |
| 双端归属 | 平台不解析 `[归属]` | **需补**（证据可读性） | R4 |
| 结构化判据 | 1/71 发 `CRITERIA_JSON` | **观察**（用例侧质量问题，非平台缺陷） | R5 |
| 执行性能 | `repo_path` 在 9P | **需决策**（是否复制到 ext4 再跑） | R6 |

---

## 5. 风险清单（按影响排序）

### R1 · 评审目录的安全门不会跟着脚本迁入（最高）

**事实**：`tests/nic/awaiting_review/conftest.py` 里有一个 **autouse fixture** `awaiting_review_guards`（`runtime.mode == REAL` 才生效）——

- `requires_reboot` → 直接 `pytest.skip("仅允许 --mode fake …禁止 real 执行（避免被测机失联）")`；
- `environment_gated` → 默认 skip，只有 `NIC_TRY_GATED=1` 且不在保护名单（`IM-NET-018/019/082/085`、`IM-RDM-011`、`IM-STB-008`）时才放行。

**关键**：`tests/conftest.py` 与 `tests/nic/conftest.py` 里**没有**这两个标记的任何处理（已 grep 确认）。
也就是说这道门是**目录级 fixture**，作用域就是 `awaiting_review/`。

**风险**：评审通过后把 `test_im_fun_011.py` 之类搬进特性目录，钩子仍留在 `awaiting_review/`。
此时脚本在正式目录、`requires_reboot` 成了**装饰性标记**，`--mode real` 会真的刷固件/重启，被测机失联。

**规模**：24 个脚本带这三类标记（`destructive`+`requires_reboot` 组合 13 个、`environment_gated` 7 个、`longrun`/`stability` 若干）。

**结论**：平台侧不需要改代码，但**迁入动作必须先走「把钩子提到 `tests/nic/conftest.py`」这一步**，否则迁移即事故。

### R2 · 16 条新用例的优先级被兜底成 P2

**事实**：`POST /api/projects/95/cases` 传 `priority=null` 会被服务端兜底成 `P2`；16 条实测全为 `P2`、`review_status=pending`。
**影响**：覆盖率分母的「待评审」口径按 `priority IS NULL` 判（实测 178 条 `NULL`），这 16 条落到「非待评审」，
`boundExcludingPending` 的分母被抬高 16，覆盖率被压低约 0.5 个百分点——数字不大，但口径不一致会让对外口径说不圆。

### R3 · 41/71 绑定用例仍是 `pending`

**事实**：已绑定用例 `approved` 30 / `pending` 41。
**影响**：`execution.require-approved=true` 下会拦掉 58% 的绑定。当前开关默认关，所以「能跑」；
但这条开关的存在意义正是拦住未评审用例，现在等于失效状态，需要明确「是故意放宽，还是评审没跟上」。

### R4 · `[归属]` 行不进入证据

**事实**：22/71 是双端脚本，`StepReporter.bind_hosts()` 每步打一行 `[归属]`；平台只解析 `[明细]`/`[判定]`。
**影响**：双端用例的「这一步是 sender 还是 receiver」在平台证据里丢失。不是算错，是**看不出来**；
排查时只能翻原始日志。改动量很小（`PytestStepExtractor` 加第三种前缀），但要定它落在哪一栏。

### R5 · `CRITERIA_JSON` 覆盖 1/71

**事实**：只有 `base/test_im_fun_014.py` 发结构化判据（该条是 `IM-FUN-014` 的 CRITERIA_JSON 试点，见用例库 `744350c`）。
**影响**：平台判据链在其余 70 条上只能回落到「日志文本」，判据不能机器比对。
这属**用例侧质量**问题，平台侧无缺陷；评估结论是「不阻塞」，但对外不能宣称「判据已结构化」。

### R6 · 9P 上的 real 执行

**事实**：`pytest.repo_path=/mnt/e/...`。
**影响**：既有结论（9P 构建/读文件慢约 35 倍）在 real 全量跑时会直接体现为用例间等待变长。
本次**未复测**该倍率，只确认配置仍指向 9P。

### R7 · `new_scripts/` 是空目录，但不是评审目录

**事实**：`tests/nic/new_scripts/` 只剩 `__pycache__`，平台不排除它。
**影响**：将来若把未定稿脚本丢在这里，会被当正式用例扫到并绑定——和这次 `pending_review` 的事故同一形状。
**处置**：要么删掉，要么加进 `pytest.scan_exclude`（配置即可，不用发版）。

### R8 · 行尾符导致 sha 与 git blob 不一致（低）

**事实**：61/71 工作区文件字节与 git blob 不同（纯 CRLF）。
**影响**：`script_sha256` 记的是工作区版本。编号匹配优先于 sha，所以当前无影响；
只有「文件改名且编号不在台账」时才会用到 sha，那时跨机器换行符差异会导致跟随失败。
**结论**：记录在案，不建议现在动（动行尾符会让 71 条绑定的 sha 全变一次）。

---

## 6. 建议动作（只给方向，不排期、不编号）

| 优先级 | 动作 | 归属 | 理由 |
|---|---|---|---|
| 必须（迁入前） | 把 `requires_reboot` / `environment_gated` 的 real 门禁钩子从 `awaiting_review/conftest.py` 提到 `tests/nic/conftest.py` | 用例库侧（属 N3：平台不改写用例） | R1，防真机失联 |
| 必须（对外口径前） | 明确 16 条新用例的优先级口径：改回 `NULL`（待评审）或在准入时显式定级 | 大师裁定 | R2 |
| 建议 | 平台证据树加 `[归属]` 一栏（或把归属并入步骤行） | 平台侧 | R4，改动小、证据可读性提升明显 |
| 建议 | `new_scripts/` 删除或进 `scan_exclude` | 用例库侧 + 配置 | R7，零成本 |
| 需决策 | `execution.require-approved` 是否打开；开之前先把 41 条 pending 过评审 | 大师 | R3 |
| 需决策 | real 全量执行是否把 repo 复制到 ext4 | 大师 | R6，与「9P 禁构建」同源 |
| 观察 | `CRITERIA_JSON` 在用例侧的推广节奏 | 用例库侧 | R5，非平台缺陷 |

---

## 7. 未核实与推断（明确标注，不当结论用）

1. **9P 慢 35 倍**：取自既有结论，本次未复测。
2. **24 个高风险脚本迁入后的真实行为**：未实跑（它们现在被排除，且 real 模式需要真机）。R1 的结论来自读 `conftest.py` 的钩子代码，不是跑出来的。
3. **覆盖率被压低约 0.5 个百分点**：按 16/307 粗算，未用平台 `/pytest/health` 复算。
4. **`[归属]` 缺失对排查的实际影响**：未做「有归属 vs 无归属」的对照排查实验，属定性判断。
5. 用例库 283 个「已修改」文件是否会在某次 `git add -A` 里被误提交（CRLF 全量改写）：**未验证**，但这是 E 盘工作区的一个现实风险。

---

*本文只到第 ② 环。要不要做、做成什么样，见后续 PRD；怎么排期见开发计划。本文不含任何 FR/WBS/TC 编号。*
