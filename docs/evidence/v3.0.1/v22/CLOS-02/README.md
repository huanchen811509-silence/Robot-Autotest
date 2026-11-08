# CLOS-02 · 证据完整性机检（output.xml metadata 六必填 + 三处单测基线）

| 项 | 值 |
|---|---|
| 版本 / 波次 | v3.0.1 · v2.2 · M3 |
| 日期 | 2026-11-08 |
| 判据出处 | `docs/plan/v2.2-PRD-增补草案-待评审-20261107.md` FR-v2.2-25 / FR-v2.2-28；`docs/plan/v2.2-DEV-PLAN-增补草案-待评审-20261107.md` 第 90 行 |
| 新增文件 | `tools/check_output_metadata.py`、`tools/check_test_baseline.py`、`docs/evidence/v3.0.1/test-baseline.json` |
| 门禁接线 | `scripts/gates.sh` 新增第 19 步 `evidence_integrity` 与 `check_structure()` 断言 |

## 1. 真实执行的命令

两台检查器均为纯标准库、不读取 env 里的任何凭据，本包按最小调用执行；门禁第 19 步走同一组命令（`bash -lc` 内 cwd 为仓库根）。外层实际调用（Windows 侧，命令整体作为单个字符串交给 WSL）：

```bash
wsl.exe -d Ubuntu-24.04 bash -lc 'cd /home/evergreen/robot-autotest && python3 tools/check_output_metadata.py --self-check'
wsl.exe -d Ubuntu-24.04 bash -lc 'cd /home/evergreen/robot-autotest && python3 tools/check_output_metadata.py'
wsl.exe -d Ubuntu-24.04 bash -lc 'cd /home/evergreen/robot-autotest && python3 tools/check_test_baseline.py --self-check'
wsl.exe -d Ubuntu-24.04 bash -lc 'cd /home/evergreen/robot-autotest && python3 tools/check_test_baseline.py'
wsl.exe -d Ubuntu-24.04 bash -lc 'cd /home/evergreen/robot-autotest && bash scripts/gates.sh --selfcheck'
```

等价的 WSL 内命令（cwd 为 `/home/evergreen/robot-autotest`）：

```bash
python3 tools/check_output_metadata.py --self-check
python3 tools/check_output_metadata.py
python3 tools/check_test_baseline.py --self-check
python3 tools/check_test_baseline.py
bash scripts/gates.sh --selfcheck
```

按项目环境约定，若前置 `set -a; . /home/evergreen/.config/robot-autotest/env; set +a;` 结果相同（两脚本不引用其中变量，证据文件不落任何凭据明文）。本包**未跑全量** `bash scripts/gates.sh`，避免与并行任务争用 `target` 目录，由主代理最后统一复跑。

## 2. 真实输出

### 2.1 `python3 tools/check_output_metadata.py --self-check`

```
==============================================================================
CLOS-02 · output.xml metadata 六必填机检（FR-v2.2-25）
==============================================================================
[判据自校验] 临时目录生成 6 组 XML，逐组走 parse_metadata + validate：
  [PASS] 齐全样本（6/6） -> 判据结果 PASS（预期 PASS，判据已接受） | 0 问题
  [PASS] 缺 1 项样本（缺 SW_BUILD） -> 判据结果 FAIL（预期 FAIL，判据已拒绝） | 缺必填项: SW_BUILD
  [PASS] 值超 40 字符样本 -> 判据结果 FAIL（预期 FAIL，判据已拒绝） | 值超 40 字符: SW_BUILD (41)
  [PASS] RUN_ID 改小写别名样本 -> 判据结果 FAIL（预期 FAIL，判据已拒绝） | 缺必填项: RUN_ID; RUN_ID 键名不可改（发现别名: run_id）
  [PASS] 值含 token= 明文样本 -> 判据结果 FAIL（预期 FAIL，判据已拒绝） | 值含凭据明文特征: LINK
  [PASS] 键名含 KEY 但值无凭据样本 -> 判据结果 PASS（预期 PASS，判据已接受） | 0 问题
PASS  判据自校验：6 组正/负样本全部符合预期（缺项被拒、齐全通过、键名不误判）
```

### 2.2 `python3 tools/check_output_metadata.py`（默认流程）

```
==============================================================================
CLOS-02 · output.xml metadata 六必填机检（FR-v2.2-25）
==============================================================================
[扫描范围] docs/evidence/v3.0.1/**/output.xml + fixtures/**/output.xml
[排除清单] 以下历史冻结目录显式排除（既不计合规，也不判红）：
  - docs/evidence/v2.0/a3-rf-kernel : 29 份 | v2.0 RF 内核历史冻结证据，无 metadata、字节冻结，不得改
  - docs/evidence/v2.0/a8-report-chain : 4 份 | v2.0 报告链历史冻结证据（含 deleted-run 原始产物），无 metadata、字节冻结
  - docs/evidence/v2.1 : 1 份 | v2.1 历史示例证据（SMPL-01），无 metadata、字节冻结
  - docs/evidence/v1.0 : 3 份 | v1.0 历史证据（HRD02B / RPT05B / RPT0607B 原始产物），无 metadata、字节冻结
  - var/artifacts : 6 份 | 运行时产物，非仓库版本证据，不纳入本版判据
  以上目录一律：不计为合规、不因缺项判红、一个字节都不改。 全仓 output.xml 实测 43 份，其中排除 43 份。
[样本] 当前版本样本数 = 0
INFO  当前版本样本数 = 0：不得静默放行，强制运行判据自校验（负向实证）

[判据自校验] 临时目录生成 6 组 XML，逐组走 parse_metadata + validate：
  [PASS] 齐全样本（6/6） -> 判据结果 PASS（预期 PASS，判据已接受） | 0 问题
  [PASS] 缺 1 项样本（缺 SW_BUILD） -> 判据结果 FAIL（预期 FAIL，判据已拒绝） | 缺必填项: SW_BUILD
  [PASS] 值超 40 字符样本 -> 判据结果 FAIL（预期 FAIL，判据已拒绝） | 值超 40 字符: SW_BUILD (41)
  [PASS] RUN_ID 改小写别名样本 -> 判据结果 FAIL（预期 FAIL，判据已拒绝） | 缺必填项: RUN_ID; RUN_ID 键名不可改（发现别名: run_id）
  [PASS] 值含 token= 明文样本 -> 判据结果 FAIL（预期 FAIL，判据已拒绝） | 值含凭据明文特征: LINK
  [PASS] 键名含 KEY 但值无凭据样本 -> 判据结果 PASS（预期 PASS，判据已接受） | 0 问题
PASS  判据自校验：6 组正/负样本全部符合预期（缺项被拒、齐全通过、键名不误判）

结论 PASS：当前版本样本数 = 0（这不是合规证明）；判据自校验负向实证通过，据此放行，历史冻结证据一个字节未改、也未假装合规
```

### 2.3 `python3 tools/check_test_baseline.py --self-check`

```
==============================================================================
CLOS-02 · 三处单测回归基线机检（FR-v2.2-28）
==============================================================================
[判据自校验] 临时目录构造假日志，逐组走 parse_log + evaluate：
  [PASS] 正常三处读数 + 正常基线 -> 判据接受（预期接受） | 0 问题
  [PASS] backend failures=1 假日志 + 正常基线 -> 判据判红（预期判红） | backend: failures=1 errors=0，必须为 0
  [PASS] 正常三处读数 + 基线被下调到 1 -> 判据判红（预期判红） | backend: 基线被下调 baseline.tests=1 < floor=1016（拒绝放过倒退）
  [PASS] backend 日志乱码（解析不出） + 正常基线 -> 判据判红（预期判红） | backend: 无有效读数（日志缺失或解析失败）; backend: 解析失败 -> 未解析出 'Tests run: N, Failures: F, Errors: E'
  [PASS] 三份日志全缺失 -> 判据判红（预期判红） | frontend: 日志缺失 docs/evidence/gates/logs/02-frontend_test.log; backend: 日志缺失 docs/evidence/gates/logs/05-backend_test.log
PASS  判据自校验：5 组样本全部符合预期（正常放行、failures=1 判红、下调基线判红、乱码日志判红、日志缺失判红）
```

### 2.4 `python3 tools/check_test_baseline.py`（默认流程）

```
==============================================================================
CLOS-02 · 三处单测回归基线机检（FR-v2.2-28）
==============================================================================
[基线] docs/evidence/v3.0.1/test-baseline.json | capturedAt=2026-11-08
       floor={'frontend': 325, 'backend': 1016, 'editor': 219}（防下调）  rule=只增不减：三处当前 tests 必须 >= 各自 baseline.tests，且 failures/errors 必须均为 0；baseline.tests 不得低于 floor（防止偷偷调低基线来放过倒退）。floor 固定为本版起点读数，永不随日志上涨而上调。
  [frontend] 02-frontend_test.log -> Tests 325 passed (325) | tests=325 failures=0 errors=0 | 基线 tests=325
  [backend] 05-backend_test.log -> 汇总行 Tests run: 1016（共 148 条读数，取 N 最大者） | tests=1016 failures=0 errors=0 | 基线 tests=1016
  [editor] 11-editor_test.log -> Tests 219 passed (219) | tests=219 failures=0 errors=0 | 基线 tests=219

[判据自校验] 临时目录构造假日志，逐组走 parse_log + evaluate：
  [PASS] 正常三处读数 + 正常基线 -> 判据接受（预期接受） | 0 问题
  [PASS] backend failures=1 假日志 + 正常基线 -> 判据判红（预期判红） | backend: failures=1 errors=0，必须为 0
  [PASS] 正常三处读数 + 基线被下调到 1 -> 判据判红（预期判红） | backend: 基线被下调 baseline.tests=1 < floor=1016（拒绝放过倒退）
  [PASS] backend 日志乱码（解析不出） + 正常基线 -> 判据判红（预期判红） | backend: 无有效读数（日志缺失或解析失败）; backend: 解析失败 -> 未解析出 'Tests run: N, Failures: F, Errors: E'
  [PASS] 三份日志全缺失 -> 判据判红（预期判红） | frontend: 日志缺失 docs/evidence/gates/logs/02-frontend_test.log; backend: 日志缺失 docs/evidence/gates/logs/05-backend_test.log
PASS  判据自校验：5 组样本全部符合预期（正常放行、failures=1 判红、下调基线判红、乱码日志判红、日志缺失判红）

结论 PASS：frontend / backend / editor 三处 failures=0、errors=0，tests 均不低于基线（只增不减），判据自校验通过
```

### 2.5 `bash scripts/gates.sh --selfcheck`

```
== 13/14 负向自检 ==
[cdp] Chrome/159.0.8144.0
[app] http://localhost:8001  routes=/,/runs,/assets,/reports/templates,/nodes  mode=selfcheck

[auth] 登录成功

[selfcheck] 注入横滚后 scrollWidth=1600 viewport=1280 -> 已判红（符合预期）
SELFCHECK_13 PASS
SELFCHECK 13 PASS（注入横滚被判红）
PASS 自校验：白样本 0 命中；黑样本命中 IP/COMMAND/CREDENTIAL/ABS_PATH
SELFCHECK 14 PASS（黑样本命中、白样本 0 命中）
STRUCT_FAIL 缺少步骤 13 browser_regression
SELFCHECK 删步骤 13 PASS（删了变红）
STRUCT_FAIL 缺少步骤 14 report_sensitive
SELFCHECK 删步骤 14 PASS（删了变红）
STRUCT_FAIL 缺少步骤 19 evidence_integrity
SELFCHECK 删步骤 19 PASS（删了变红）

SELFCHECK_RESULT FAILS=0
```

## 3. 判据表

| # | 判据（出处） | 落地实现 | 真实实证 | 结果 |
|---|---|---|---|---|
| 1 | 六必填 `CASE_ID` / `RUN_ID` / `QA_PROJECT` / `DUT` / `LINK` / `SW_BUILD` 齐全（FR-v2.2-25） | `validate()` 缺项即列入 problems | 缺 SW_BUILD 样本被拒、6/6 齐全样本通过 | PASS |
| 2 | 键名全大写、`RUN_ID` 键名不可改（FR-v2.2-25） | `KEY_RE` 加 `^[A-Z][A-Z0-9_]*$`、别名检测 | `run_id` 别名样本被拒 | PASS |
| 3 | 值 ≤40 字符（FR-v2.2-25） | `len(v) > 40` 即判红 | 41 字符样本被拒 | PASS |
| 4 | 不含 `password` / `token` / `secret` / `key=` 等凭据明文，且不误判 `CRED_MASTER_KEY` 类键名 | `CRED_RE` 只作用于 item 的值、键名不参与匹配 | `token=` 值样本被拒；键名含 KEY 且值无凭据样本通过 | PASS |
| 5 | 0 份样本不得静默放行，须打印「当前版本样本数 = 0」并强制判据自校验 | 默认流程无条件执行 `self_check()` | 2.2 实测样本数 0、6 组负向实证全过 | PASS |
| 6 | 只扫当前版本 `docs/evidence/v3.0.1/**` 与 `fixtures/**`，历史冻结目录显式排除并带理由 | `INCLUDE_ROOTS` 加 `EXCLUDED_DIRS` 动态计数 | 2.2 实测排除 43 份并逐目录打印理由，不判红也不假装合规 | PASS |
| 7 | 三处 failures / errors = 0 且 tests ≥ 基线，只增不减（FR-v2.2-28） | `evaluate()` 三处逐一比对 | 2.4 实测 325 / 1016 / 219 三处全 0 且不低于基线 | PASS |
| 8 | 不许把基线偷偷调低来放过倒退 | `floor` 断言 `baseline.tests >= floor` | 基线被下调到 1 的样本被判红 | PASS |
| 9 | 日志缺失 / 解析不出明确报错 exit 非 0，不静默 PASS | `collect_records()` 收集 errs、主流程返回 1 | 2.3 乱码日志与三份日志全缺失两组均被判红 | PASS |
| 10 | `--self-check` 与默认流程都做负向实证，实证失败则 exit 非 0 | 两脚本各自 `self_check()`，main 依据其返回码退出 | 2.1 六组、2.3 五组全部符合预期；4 条命令 exit 均为 0 | PASS |
| 11 | 门禁新增第 19 步、删掉该步必须变红 | `run 19` 加 `check_structure()` 的 `^run 19 evidence_integrity ` 断言 | 2.5 打印「删步骤 19 PASS（删了变红）」、`SELFCHECK_RESULT FAILS=0` | PASS |

## 4. `test-baseline.json` 三处读数

| 来源 | 日志 | baseline.tests（起点） | floor | 当前实测 tests | failures / errors |
|---|---|---|---|---|---|
| frontend | `docs/evidence/gates/logs/02-frontend_test.log` | 325 | 325 | 325 | 0 / 0 |
| backend | `docs/evidence/gates/logs/05-backend_test.log` | 1016 | 1016 | 1016 | 0 / 0 |
| editor | `docs/evidence/gates/logs/11-editor_test.log` | 219 | 219 | 219 | 0 / 0 |

`capturedAt` = `2026-11-08`。`source` 写明三处起点读数取自 2026-11-08 02:38–02:40 全门禁当轮真实日志（frontend `Tests 325 passed (325)`、backend `Tests run: 1016, Failures: 0, Errors: 0, Skipped: 0` 加 `BUILD SUCCESS`、editor `Tests 219 passed (219)`），非外推值。后续并行工作包会令日志读数上涨（backend 1050 / frontend 330 / editor 219），但基线固定取起点值、不随之上调；判据始终是 `tests >= 起点值`。`floor` 与 `baseline.tests` 相同，写入 JSON 是为了让「偷偷调低基线」在脚本里被明确判红，而不是靠人自觉。

## 5. 门禁第 19 步的确切命令

`scripts/gates.sh` 在 18 之后新增（编号 01–18 一个未改、未重排）：

```bash
run 19 evidence_integrity "python3 tools/check_output_metadata.py && python3 tools/check_test_baseline.py"
```

`check_structure()` 新增的存在性断言：

```bash
grep -qE '^run 19 evidence_integrity ' "$file" || { echo "STRUCT_FAIL 缺少步骤 19 evidence_integrity"; bad=1; }
```

文件头注释同步更新为「本脚本共 19 步」并列入第 19 步；`--selfcheck` 分支沿用 `sed '/^run 13 /d'` / `'/^run 14 /d'` 造临时文件再调 `check_structure` 的原有逻辑，另按同样模式新增 `'/^run 19 /d'` 一组实证。第 13 步「选样 SQL 同时取 `run_id + project_id`、给浏览器脚本传 `REGRESS_REPORT_PROJECT`」的逻辑一字未动。

## 6. 边界（如实说明）

| 项 | 说明 |
|---|---|
| 当前版本样本 | `docs/evidence/v3.0.1/**` 与 `fixtures/**` 实测 0 份 output.xml。本包结论 PASS 的依据是判据负向实证通过，**不是**「0 份样本即合规」；脚本已明确打印该区别 |
| 历史冻结证据 | 全仓实测 43 份 output.xml 全部落在 `EXCLUDED_DIRS`（v2.0 a3-rf-kernel 29、v2.0 a8-report-chain 4、v2.1 1、v1.0 3、var 6）。任务书按 37 份口径描述 v2.0/a3-rf-kernel；此处以仓库 `find` 实测为准。它们字节冻结、无 metadata，既不计合规、也不判红 |
| 凭据规则 | 只扫 item 的值，不匹配键名，因此 `CRED_MASTER_KEY` 这类键名本身不会被误判；规则覆盖赋值形态明文（`password=` / `token=` / `secret=` / `key=`）与 `Bearer` 令牌，不是任意字符串黑名单 |
| 全量门禁 | 本包**未跑** `bash scripts/gates.sh`（避免与并行任务争用 `target` 目录），只跑 `--selfcheck`；第 19 步在全量门禁中的真实结论由主代理最后统一复跑后产生 |
| 日志读数 | 基线取 2026-11-08 全门禁当轮起点值；若并行任务随后把日志刷成 1050 / 330 / 219，判据 `tests >= 起点值` 仍然 PASS，不会倒退误判 |

## 7. Markdown 表格机检

写完 Markdown 后在仓库根真实执行：

```bash
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py
```

真实输出（全量默认文件集）：

```
扫描 7 份文档 / 157 个表格块 / 1252 个数据行
[PASS] 表格列数一致性：全部表格块内列数一致（转义竖线已按非分隔符处理）
```

本版三份新文档一起校验（含本 README）：

```bash
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/层S-AB完成证据索引与双线不抵扣声明-20261108.md docs/evidence/v3.0.1/v22/CLOS-01/README.md docs/evidence/v3.0.1/v22/CLOS-02/README.md
```

真实输出：

```
扫描 3 份文档 / 10 个表格块 / 68 个数据行
[PASS] 表格列数一致性：全部表格块内列数一致（转义竖线已按非分隔符处理）
```
