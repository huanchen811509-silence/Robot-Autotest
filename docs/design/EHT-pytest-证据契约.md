# EHT pytest 证据契约（W-EHT-01 交付）

- 日期：2026-11-19
- 状态：**已按实测确认，作为 W-EHT-02/03/05/07 的实现依据**
- 上游：`docs/plan/EHT-pytest执行与报告修复-20261119/01-PRD.md`（FR-EHT-01/08/09）
- 一句话：**证据主源是 junit 的 system-out/system-err；framework.log 只作人工兜底，不拼树；计划文件单独消费。**

---

## 1. 实测基线（2026-11-19 21:16，WSL，venv `nic-fake`，pytest 9.2.0）

复现命令（与平台 `PytestNicKernelCommand` 同参数）：

```
cd "/mnt/e/workspace/自动化测试/pytest-cases"
/home/evergreen/venvs/nic-fake/bin/python -m pytest \
  --env_config config/environments/run_env_default.yaml --mode fake \
  --junitxml=/tmp/eht-verify-capture/junit.xml \
  -o junit_family=xunit2 -o junit_logging=out-err \
  --capture=fd -q tests/nic/base/test_im_fun_020.py
```

实测结果：

| 观测项 | 值 | 说明 |
|---|---|---|
| testcase | 1 | 单用例 |
| system-out | 2 | pytest 按阶段写多份 |
| system-err | 2 | 同上 |
| `[明细]` 原文出现次数 | 6 | 含 setup/call 阶段重复 |
| `[判定]` | 0 | fake/Plan-only 不打判定 |
| 用例结论 | **SKIPPED** | Plan-only 跳过功能断言（`conftest.py:420`） |
| framework.log | 有 3 条 `[明细]` | 同内容，会话级单文件 |
| fake_plan.json | 有，`connection_established=false` | 计划完整 |

**关键结论**：
1. `--capture=fd` 覆盖用例库 `addopts` 的 `-s` **成功**，日志进了 junit。→ **不需要读 framework.log 取步骤。**
2. 步骤明细可从 junit `<testcase>` 天然分组提取，**不需要从会话级日志切分**。设计者 `PytestEvidenceService:36` 反对 framework.log 拼树的理由成立且不必要。
3. fake 模式只有 `[明细]`（Plan-only 说明），无 `[判定]`，整体 SKIP。→ fake 模式的"判据"是 Plan-only 事实，不是通过/不通过。
4. **`pytest_case_step=0` 的真因不是"提取不出来"，是"提取方法没被执行回调调用"**（`PytestEvidenceService.extract()` 只在手动端点调用）。

> 修订说明：评估报告曾推断"junit 默认无 system-out → 须改读 framework.log"。**实测推翻该推断**：平台已用 `--capture=fd` 保证捕获，junit 有明细。本契约以此为准，`00/01` 文档同步修正。

---

## 2. 产物优先级与职责

| 优先级 | 产物 | 路径 | 用途 | 平台是否读 |
|---|---|---|---|---|
| 1（主） | junit.xml | `run.artifacts_dir/junit*.xml` | 步骤（`[明细]`/`[判定]`）、用例结论、failure message | 是（现有 `PytestStepExtractor.fromJunit`） |
| 2 | commands.jsonl | `<repo>/artifacts/<run_id>/cases/<node>/raw/*.commands.jsonl` | 命令证据（command/purpose/stdout 引用） | 是（现有 `commandsOf`） |
| 3 | fake_plan.json / manual_replay.md | `<repo>/artifacts/<run_id>/` | fake 计划、`connection_established`、BranchPlan | **新增（FR-EHT-08）** |
| 4（兜底） | framework.log | `<repo>/artifacts/<run_id>/framework.log` | 人工排查、junit 捕获失败时提示 | **仅当 junit 捕获为空时读取并提示，不拼步骤树** |

**禁止**：用 framework.log 按行序拼用例步骤树（并行/重试必错位）。若 junit 捕获为空，如实返回 `captured=false` + hint，不编树。

---

## 3. 用例边界规则

- **唯一边界来源 = junit `<testcase>` 的 `classname + name`**（= nodeid）。
- nodeid 生成：`classname.isEmpty() ? name : classname + "::" + name`（现有实现）。
- 目录名 sanitize：`nodeid.replaceAll("[^A-Za-z0-9_.-]+", "_")`，与框架 `Runtime.begin_case` 逐字一致（用于定位 `cases/<node>/raw`）。
- 同一 testcase 的多份 `<system-out>/<system-err>` 按**内容去重**（`LinkedHashSet`），不去模糊重复。

---

## 4. 状态映射

| 场景 | junit 证据 | 用例状态 | 步骤状态 | criteria_json |
|---|---|---|---|---|
| real 通过 | 有 `[判定]…通过` | PASS | VERDICT=PASS | 记 `[明细]` 原文 + `[判定] 通过` |
| real 失败 | 有 `[判定]…不通过（原因）` 或 `<failure>` | FAIL | VERDICT=FAIL + FAILURE 行 | 记判据 + 原因 |
| fake/Plan-only | `[明细]…Plan-only…跳过`，整体 SKIPPED | SKIP | DETAIL=SKIP | 记 Plan-only 原文，**判据态=未判定（PLAN_ONLY）**，不得记 PASS |
| 捕获为空 | 无 system-out/err | 按 junit 结论 | 空 | MISSING（hint 提示检查 --capture=fd） |
| 空执行 | 无 testcase | **空执行（默认 FAILED）** | — | — |

**铁律**：Plan-only 的"未判定" ≠ "通过"；空执行 ≠ "通过率 0%/100%"。

---

## 5. 判据提取规则

- 来源：junit `<testcase>` 的 system-out/err 中 `[明细]` 与 `[判定]` 行（去重后）。
- 一行只认首个标记（`[判定]` 优先于 `[明细]`，按出现位置）。
- 标签解析：`标签：取值`；标签含 `=`/空格/`，`/超 48 字 → 视为无标签，整行作取值。
- `不通过（a；b）` → 取值取括号内 `a；b`。
- 若同一用例既有 `[明细]` 又有 `[判定]`，两者都入库（明细在前、判定在后）。

> 修正 FR-EHT-03：**方向 A 成立且无需改用例脚本**。junit 的 `[明细]/[判定]` 即为判据载体，10 个靶子用例在 real 下自然产出判据。W-EHT-04 可跳过（除非 real 实测发现个别用例不打 `[判定]`）。

---

## 6. 命令证据规则

- 来源：`cases/<sanitize(nodeid)>/raw/*.commands.jsonl`，每行一个 JSON。
- 取字段：`command_id / command / purpose / expected_observation / stdout_file / stderr_file`。
- `stdout` 正文不入库，存相对路径 `commands/<stdout_file>`（产物会被归档搬机器，绝对路径失效）。
- raw 目录不存在 → 返回空表，不阻断步骤树入库。

---

## 7. 计划文件规则（FR-EHT-08 新增）

- 读取 `<repo>/artifacts/<run_id>/fake_plan.json`：
  - `mode`、`connection_established`、`stdout_synthesized`、`commands[]`、`alternative_branches[]`。
- 读取 `manual_replay.md` 作为人工回放附录（可只存路径）。
- 报告必须显性呈现：`connection_established=false`（fake 未连设备）、BranchPlan 的 name/condition/expected_result/commands。
- 计划缺失/空：报告标"无执行计划"，不得静默成功。

---

## 8. 失败态定义（供 W-EHT-05/06 使用）

| 态 | 触发 | 落库 |
|---|---|---|
| 空执行 | junit 无 testcase 或归一 `cases().size()=0` | run 态=FAILED（默认，待大师裁决），reason="no tests executed" |
| 证据缺失 | 有用例但 captured=false 且无命令证据 | 报告标 MISSING，不得 FINISHED |
| 全 Plan-only | 全部 SKIP 且含 Plan-only 标记 | 通过率"不适用"，报告标"未真实执行" |
| 装配失败 | 报告装配异常 | 报告顶部标"装配失败（原因）"，不得全 0 空壳 |

---

## 9. 对实现的影响（W-EHT-02/03/05/07）

| WBS | 需要改什么 | 依据 |
|---|---|---|
| W-EHT-02 | 基本无需重写提取；补"真实 junit 样本"测试 + 去重/边界回归；framework.log 降为兜底 | §1/§2/§3 |
| W-EHT-03 | **核心：执行完成回调自动调 `PytestEvidenceService.extract()`** | §1 结论 4 |
| W-EHT-05 | 加空执行硬门禁（修 `allSkipped` 空 merged 漏洞） | §8 |
| W-EHT-06 | 报告四态反映真实 verdict；装配失败显性 | §4/§8 |
| W-EHT-07 | 新增计划文件消费 | §7 |
| W-EHT-04 | **可跳过**（方向 A 经实测成立） | §5 修正 |
