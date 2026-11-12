# pytest 链路 Fake 自证（v5.1.0 · W51-15）

> 执行时间：2026-11-12 21:43–21:45（GMT+8）
> 执行人：学徒
> 目的：在不碰真实设备的前提下，用**用例库自己的代码**跑通「渲染 → 执行 → 产物 → 步骤抽取」，
> 证明平台侧的命令装配与解析契约成立。**不是**取代 W51-13/14 的真实样板（那两步需 G-1 授权）。

## 一、结论先说

| 结论 | 证据 |
|---|---|
| `--junitxml` 可用，无需 `--json-report`（用例库 requirements 只有 pytest/paramiko/PyYAML） | 2 条用例真实执行并产出 junit |
| 步骤明细**必须**同时开三项开关才进 junit：`--capture=fd` + `-o junit_logging=out-err` + `-o junit_family=xunit2` | 只开第一项时 junit 里 0 条明细；三项全开后 4 条 |
| pytest 会为同一用例的 setup/call 两个阶段各写一份内容**逐字相同**的 `<system-err>`，必须按内容去重 | 不去重 6 条 → 去重 4 条 |
| 框架的 `artifacts/<run_id>/cases/<sanitized nodeid>/raw` 目录确实按 `run_env.run_id` 落位，平台可确定定位 | 两个用例目录名与 Java 侧 `sanitize()` 逐个字符一致 |
| Fake（Plan-only）模式不产生命令证据（`commands.jsonl` 为空） | raw 目录存在但无 `*.commands.jsonl` |

## 二、命令原文

```bash
cd ~/nic-fake-cases
~/venvs/nic-fake/bin/python -m pytest \
  tests/nic/base/test_im_fun_020.py tests/nic/ethernet/test_im_net_002.py \
  --env_config /home/evergreen/nic-fake-run/run_env.yaml \
  --mode fake \
  --junitxml=/home/evergreen/nic-fake-run/junit-e2e.xml \
  -o junit_family=xunit2 -o junit_logging=out-err --capture=fd -q
```

输出：`2 skipped in 0.05s`（Plan-only 下用例在 `StepReporter.finalize` 主动 skip，属预期）。

## 三、三个实测踩出来的坑

### 3.1 `junit_logging` 默认是 `no`，不是 `log`

`_pytest/junitxml.py` 里 `parser.addini("junit_logging", ..., default="no")`。
连 `--help` 都只写可选值、不写默认值，很容易以为「捕获了就会写进 junit」。

实测对照：

| argv | junit 里 `[明细]` 条数 | framework.log 里条数 |
|---|---|---|
| `--capture=fd` | 0 | 3 |
| `--capture=fd` + `-o junit_logging=out-err` | 6（含重复） | 3 |

只看 junit 会得出「用例没写步骤」的错误结论——这正是「没有证据」与「证据没落到位」的区别。

### 3.2 取 `out-err` 而不是 `all`

`all` 会把 `caplog` 也拼进 `<system-out>`；框架的 `logging.StreamHandler()` 同时被 pytest 的
logging 插件捕获，于是同一条明细既进 `caplog` 又进 `capstderr`，**每条重复两遍**。
`out-err` 只取 stdout/stderr，一条一份。

### 3.3 阶段级重复

一个在 call 阶段被 skip 的用例产生两个阶段报告，两份 `<system-err>` 内容逐字相同：
不去重就是「3 条明细变 6 条」，看起来像用例写了两遍，极难定位。
`PytestStepExtractor.capturedOf()` 按**完全相同**的文本块去重（不做模糊去重，真的写两次仍保留两条）。

## 四、产物与位置

| 产物 | 路径 | 说明 |
|---|---|---|
| junit（去重前原始件） | `docs/evidence/v5.1.0/pytest/fake-junit-e2e.xml` | 归档样例，2 用例 |
| 框架会话日志 | `~/nic-fake-cases/artifacts/RUN-20261112214500-fake00/framework.log` | 含 3 条 `[明细]`（未归档，含本机路径） |
| 框架假计划 | `.../fake_plan.json` | Plan-only 记录 |
| 用例命令目录 | `.../cases/<sanitized nodeid>/raw/` | Fake 下为空 |

样例里只含用例库自带的示例地址（`10.202.54.x`，`config/inventory/inventory_lab.yaml` 注释已写明
「示例数据仅用于展示配置结构」），**无任何口令**：凭据在渲染产物里只写 `${ENV:PYTEST_SSH_USER/PASSWORD}`。

## 五、未覆盖（如实登记）

| 项 | 原因 | 归属 |
|---|---|---|
| real 模式真实执行（SSH 连接、真实命令回显、`commands.jsonl` 非空） | G-1 真实端点授权归大师，学徒不自行启用 | W51-14 / TC-v5.1-14 |
| 真实设备上的判定通过/不通过（非 Plan-only 的 `[判定]` 行） | 同上 | W51-14 |
| 进度实时通道（stdout 流） | 平台改为 `--capture=fd` 后控制台不再实时输出，监视改 tail `framework.log` | W53-01 |

## 六、复现步骤

1. `cp -a <用例库> ~/nic-fake-cases`（必须在 ext4 上跑，9P 上慢 35 倍）
2. 建 venv：`python3 -m venv ~/venvs/nic-fake && ~/venvs/nic-fake/bin/pip install pytest PyYAML paramiko`
3. 造 run_env：复制 `config/environments/run_env_default.yaml`，改三处——
   `inventory_file: "inventory.yaml"`、`fake_plan_file` 写绝对路径、`run_id` 写死（目录名由它决定）
4. 跑第二节的命令
5. `python3 /tmp/count_steps.py junit-e2e.xml` 统计去重后步骤数
