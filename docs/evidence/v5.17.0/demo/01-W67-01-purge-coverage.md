# W67-01 演示对象 purge 覆盖取证（v5.17.0）

> 对应：`FR-v5.17-03` / `TC-v5.17-21 ~ 24`；来源缺口 G4（5.10 FR-11 演示对象 purge 覆盖）。
> 取证时间：2026-11-16 08:04 ~ 08:22（GMT+8）。
> 环境：WSL `~/robot-autotest`，HEAD `876da9b`，库 `robot_dev@127.0.0.1:3306`，后端 `:8081`（`/api/_ping` = 200）。

---

## 1. 结论

`purge-demo.sh` 的**标记链路本身是通的**（`is_demo=1` 能被正确识别与清除），
但取证同时查出 **4 处真实缺陷**，其中 2 处会让脚本在真实数据上**删不动或删一半**。四处均已修复并复验。

| 判据 | 结果 | 证据 |
|---|---|---|
| 1 清单含 13 个对象（逐类计数） | **PASS** | `purge-manifest.json`：test_set 2 + test_case 10 + test_report 1 = 13 |
| 2 dry-run 前后逐表计数一致（零写入） | **PASS** | `counts-before-dryrun.txt` vs `counts-after-dryrun.txt`，diff 为空 |
| 3 真实项目不出现 | **PASS** | manifest 的 `project` 数组仅含 3 个 `is_demo=1` 项目，无 `PLATFORM-LIB` / `ED202-COMMON` / `demo` / `qa-e2e-*` |
| 4 `--apply` 后 `is_demo=1` 回 0 | **PASS**（需先清空内容，见 D3） | `purge-apply.log`：`project 0` / `app_user 0` |

---

## 2. 造的演示对象

| 类型 | 数量 | 说明 |
|---|---|---|
| `project`（`is_demo=1`） | 1 | `DEMO-W67-01`（id 128） |
| `test_set` | 2 | `DEMO-W67-S1` / `S2`，`kind='case_set'` |
| `test_case` | 10 | `DEMO-W67-C01 ~ C10` |
| `run` | 1 | `RUN-W67-01-DEMO` |
| `test_report` | 1 | 关联该 run |

13 = 2 用例集 + 10 用例 + 1 报告，与原计划一致（run 不计入 13）。

---

## 3. 四处缺陷（取证的真实产出）

### D-67-01-1 脚本漏统计 `test_set`（已修）

原脚本只统计 `test_case` / `run` / `test_result` / `test_report`，**没有 `test_set`**。
因此「2 集 + 10 例 + 1 报告」的 13 个对象里，**2 个用例集完全不在清单里**，判据 1 无法成立。

修法：统计段加 `demo_sets`，输出段加一行 `test_set`。默认行为仅多一行，不改变任何判定。

### D-67-01-2 只有计数、没有对象清单（已修）

原脚本只打印「各类多少行」，**不打印是哪些对象**。这意味着「清单可人工复核」这条判据
在脚本层面根本落不了地——人看到的是一串数字，无法逐条核对哪条该删、哪条不该删。

修法：新增 `--json`，输出 `totals` + `objects`（逐条 `id` / `code` / `name` / `kind`），只读，
即使同时给 `--apply` 也先输出清单再退出。`--json` 输出已用 `json.load` 解析验证（`PARSE OK`）。

### D-67-01-3 有从属数据时 `--apply` 直接 1451 失败，且留下半删状态（已修，最严重）

实测原文：

```
ERROR 1451 (23000) at line 1: Cannot delete or update a parent row: a foreign key
constraint fails (`robot_dev`.`run`, CONSTRAINT `fk_run_project`
FOREIGN KEY (`project_id`) REFERENCES `project` (`id`))
```

外键普查（`information_schema`）：**26 张表以 `project(id)` 为父键，其中只有 `project_asset`
与 `project_group_role` 是 `ON DELETE CASCADE`**，其余 24 张（`run` / `test_case` / `test_set` /
`device` / `credential` / `test_scenario` / `topology_node` / `env_profile` / `execution_plan` …）
全是 RESTRICT。

后果比"报错"更糟：脚本的删除顺序是「先关系、后主行」，`project_member*` / `project_group_role` /
`user_group_member` 三条已在前面执行成功，`project` 才失败退出 —— 库进入**半删状态**，重跑也回不到原样。

> 这说明 5.9 当初只验了 dry-run，`--apply` 从未在"有内容的演示项目"上跑过。

修法：`--apply` 前置门禁。逐表数一遍 22 张 `project_id` 表 + `test_result` / `test_report`（经 `run`），
有任何一行就**拒绝执行**（`exit 4`），打印逐表条数与正确做法，绝不再进入半删路径。

### D-67-01-4 「走项目删除接口」这条路对有内容的项目也走不通（已改指引）

原脚本注释指引：「项目下的用例/执行/报告请通过项目删除接口处理（那里有级联与审计）」。
实测 `DELETE /api/projects/128`（`qa-e2e-admin` 登录，Bearer 有效）：

```
http=409
{"code":40900,"codeName":"CONFLICT",
 "message":"项目下仍有运行 1 条、用例 10 条、报告 1 条、用例集 2 条，只能停用（归档），不能彻底删除"}
```

接口是 fail-closed 的：**有内容只能归档，不能删**。所以「调接口」不是清空内容的替代方案——
必须先逐类清掉用例 / 报告 / 执行，接口才可能删得动。原注释会把人引到死路，已改为准确指引。

---

## 4. 正确删除顺序（本次实测可用）

| 步 | 动作 | 表 |
|---|---|---|
| 1 | 删报告 | `test_report` |
| 2 | 删结果 | `test_result` |
| 3 | 删执行 | `run` |
| 4 | 删用例 | `test_case` |
| 5 | 删用例集 | `test_set` |
| 6 | 再跑 `purge-demo.sh --apply` | 仅剩空项目，门禁放行 |

按此顺序执行后，`is_demo=1` 的 `project` 与 `app_user` 均归 0。

---

## 5. 收尾后库状态（造数完全可逆）

| 表 | 造数前 | 收尾后 | 差 |
|---|---|---|---|
| `app_user` | 9 | 9 | 0 |
| `project` | 16 | 14 | −2（`TEST-V59-OWNER` / `TEST-V59-GROUP` 两个 `is_demo=1` 空项目按设计清除） |
| `project_member` | 17 | 17 | 0 |
| `test_set` | 47 | 47 | 0 |
| `test_case` | 12491 | 12491 | 0 |
| `run` | 546 | 546 | 0 |
| `test_result` | 725 | 725 | 0 |
| `test_report` | 3 | 3 | 0 |

保留对象零变化：`demo`（project 1）**238** 条用例、`demo-pytest`（project 80）**12** 条用例，前后逐字一致。

> `TEST-V59-OWNER` / `TEST-V59-GROUP` 本就在 5.18 FR-07 的候选清理清单里，本次随 `is_demo=1` 一并清除，
> 不构成额外删除；其 5.9 证据已在 `docs/evidence/v5.9.0/` 留档。

---

## 6. 代码改动

| 文件 | 改动 |
|---|---|
| `scripts/ops/purge-demo.sh` | 加 `test_set` 统计；加 `--json` 逐条清单；加从属数据前置门禁（`exit 4`）；修正「走接口」指引 |
| `tools/check_demo_data.py` | 未改（复跑 `PASS`，`--self-test` 仍判红） |

机检复核：`python3 tools/check_demo_data.py` → `[PASS] 演示数据治理：标记位齐备、默认 dry-run、删除只认 is_demo = 1`。

---

## 7. 复跑命令

```bash
set -a; . ~/.config/robot-autotest/env; set +a
cd ~/robot-autotest

bash scripts/ops/purge-demo.sh            # 人读盘点（dry-run）
bash scripts/ops/purge-demo.sh --json     # 逐条清单
PURGE_DEMO_CONFIRM=YES bash scripts/ops/purge-demo.sh --apply   # 有从属数据时 exit 4 拒绝
python3 tools/check_demo_data.py && python3 tools/check_demo_data.py --self-test
```

---

## 8. 对 5.18 清场的影响（必须同步）

| 影响 | 说明 |
|---|---|
| 清场不能只靠脚本 | 10 个候选项目里 8 个有内容（`PRJ-RF-*` 各 20 例、`UIAUTO-*` 35~43 例、`PERF-LONGLIST` 10000 例），必须**先清内容再删项目** |
| 需要"按外键顺序清内容"的能力 | 5.18 的 `purge-temp.sh` 必须内置顺序（§4），否则同样 1451 |
| 备份是硬前提 | 本次已用 `scripts/ops/backup.sh --apply` 备份（`robot_dev-20261116-080338.sql.gz`，2.5MB，sha256 `54d182ac…`，`BACKUP PASS` / `MANIFEST PASS`） |
| 接口路线要写清 | 「归档」与「彻底删除」是两回事，对外的清理口径不能混用 |
