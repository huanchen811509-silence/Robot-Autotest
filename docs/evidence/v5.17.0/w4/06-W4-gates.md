# W67-12 / W67-13 契约、造数自清与门禁（v5.17.0）

> 对应：`TC-v5.17-37` 及 NFR-02/03/08。执行时间：2026-11-16 08:50 ~ 08:56（GMT+8）。

---

## 1. 契约

```
$ python3 tools/gen_api_contract.py --check
endpoints=484 paths=401
PASS API 契约快照与控制器源码一致
```

**本版零迁移**（最新仍 `V11_78`），契约也无需重生成——该工具记录的是**端点/路径**，
不记录请求体字段，所以 `ContentRequest.scenarioIds` 的增量不体现在快照里。

> 这是一条容易误判的事实：加字段不等于改契约。判据以 `--check` 的实际行为为准，不按"应该要改"去动文件。

---

## 2. 造数自清（NFR-08）

| 造数来源 | 对象 | 清理后残留 |
|---|---|---|
| W67-01 演示项目 | 1 项目 + 2 集 + 10 例 + 1 run + 1 报告 | 0 |
| W67-02 三样本 | 1 项目 + 3 run + 6 test_result + 1 节点 | 0 |
| W67-06 kind 校验 | 1 项目 + 1 场景 | 0 |
| W67-07 快照 | 1 项目 + 5 用例 | 0 |
| W67-08 凭证探针 | 2 验证记录 + 2 采集记录 | 0 |
| W67-10 压测 | 1 项目 + 500 台设备 | 0 |

收尾后逐表回到基线：

| 表 | 基线 | 收尾后 |
|---|---|---|
| `app_user` | 9 | 9 |
| `project` | 14 | 14 |
| `test_set` | 47 | 47 |
| `test_case` | 12491 | 12491 |
| `run` | 546 | 546 |
| `test_result` | 725 | 725 |
| `test_report` | 3 | 3 |
| `device` | 21 | 21 |
| `execution_node` | 9 | 9 |

（`project` 从 16 降到 14，是 W67-01 按设计清掉两个 `is_demo=1` 的空项目，见该证据 §5。）

---

## 3. 门禁

```
$ bash scripts/gates.sh
...
  FAIL  13-browser_regression (0s) -> docs/evidence/gates/logs/13-browser_regression.log

GATES_RESULT FAILS=1 UNJUDGED=0
```

### 3.1 唯一失败项：环境，不是代码

```
[13] 对比/趋势深检 run=RUN-20261109133212-b70001 project=80
FAIL 浏览器回归无法完成: fetch failed
```

| 事实 | 值 |
|---|---|
| 历史状态 | `docs/evidence/v5.16.0/w8-gates-release.log` 里该步 **PASS (45s)** |
| 当前 CDP | `curl 127.0.0.1:9222/json/version` → 无响应 |
| WSL 内浏览器 | `google-chrome` / `chromium` / `chromium-browser` 均不存在；无 playwright |
| 本次是否改了浏览器相关代码 | **没有**（改动集中在模板来源、场景入参、凭证名掩码、spec） |

结论：**环境退化导致的门禁红**，登记为 `L-67-07`，不写成"通过"。

### 3.2 前两轮的 3 个红都是本次引入，已修

| 步 | 原因 | 修法 |
|---|---|---|
| 03 type_check | 4 处 TS 错误 | 卡片字段改名后残留 `tpl.testKernel`（3 处）；`credentialText` 未使用；两处 `ContentSelection` 缺 `scenarioIds` |
| 06 lint | 同上一处未使用变量 | 在面板里真正用它显示「当前绑定凭证」，而不是删掉 prop |

修完复跑：**FAILS=3 → FAILS=1**。

---

## 4. 复跑命令

```bash
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest
python3 tools/gen_api_contract.py --check
bash scripts/gates.sh 2>&1 | grep -E "^  FAIL|GATES_RESULT"
```
