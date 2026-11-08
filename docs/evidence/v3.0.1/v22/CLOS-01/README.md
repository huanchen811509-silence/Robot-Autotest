# CLOS-01 · 层 S / A / B 完成证据索引与双线不抵扣声明

| 项 | 值 |
|---|---|
| 版本 / 波次 | v3.0.1 · v2.2 · M3（CLOS-01 为 M1 前置） |
| 日期 | 2026-11-08 |
| 判据出处 | `docs/plan/v2.2-PRD-增补草案-待评审-20261107.md` FR-v2.2-26 / FR-v2.2-27；`docs/plan/v2.2-DEV-PLAN-增补草案-待评审-20261107.md` 第 24 / 89 行 |
| 交付物 | `docs/plan/层S-AB完成证据索引与双线不抵扣声明-20261108.md` |
| 机检 | `python3 tools/check_release_docs.py`（六项，脚本本身未改） |

## 1. 真实执行的命令

环境按项目约定加载 `set -a; . /home/evergreen/.config/robot-autotest/env; set +a`（只引用路径，证据文件不落任何凭据明文）。外层实际调用（命令整体作为单个字符串交给 WSL）：

```bash
wsl.exe -d Ubuntu-24.04 bash -lc 'set -a; . /home/evergreen/.config/robot-autotest/env; set +a; cd /home/evergreen/robot-autotest && python3 tools/check_release_docs.py'
wsl.exe -d Ubuntu-24.04 bash -lc 'cd /home/evergreen/robot-autotest && python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/层S-AB完成证据索引与双线不抵扣声明-20261108.md'
wsl.exe -d Ubuntu-24.04 bash -lc 'cd /home/evergreen/robot-autotest && python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py'
```

等价的 WSL 内命令（cwd 为 `/home/evergreen/robot-autotest`）：

```bash
python3 tools/check_release_docs.py
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/层S-AB完成证据索引与双线不抵扣声明-20261108.md
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py
```

## 2. 六项机检真实输出（`python3 tools/check_release_docs.py`）

该脚本检查六项：① 每版目录六份产物齐全；② PRD 每条 FR 在 TEST-PLAN 有覆盖；③ TEST-PLAN 每条 TC 在 TEST-REPORT 有结果；④ BLOCKED 写明原因；⑤ 评估报告含「不做」与「前置条件」；⑥ 评估报告有修订记录段。本包**未改** `tools/check_release_docs.py`，只消费其输出。

```
==============================================================================
版本开发流程机检（docs/process/版本开发流程.md §8）
==============================================================================

[IDLE] v0.1-foundation   FR=22 TC=60 WBS=9
   INFO  未启动流程（缺 2 项，含 00-RESEARCH / 01-ASSESSMENT），按 §9 不计为违规

[IDLE] v0.2-ledger   FR=63 TC=55 WBS=18
   INFO  未启动流程（缺 2 项，含 00-RESEARCH / 01-ASSESSMENT），按 §9 不计为违规

[IDLE] v0.3-editor   FR=27 TC=41 WBS=11
   INFO  未启动流程（缺 2 项，含 00-RESEARCH / 01-ASSESSMENT），按 §9 不计为违规

[IDLE] v0.4-execution   FR=34 TC=49 WBS=10
   INFO  未启动流程（缺 2 项，含 00-RESEARCH / 01-ASSESSMENT），按 §9 不计为违规

[IDLE] v0.5-report   FR=35 TC=66 WBS=7
   INFO  未启动流程（缺 2 项，含 00-RESEARCH / 01-ASSESSMENT），按 §9 不计为违规

[IDLE] v0.6-automation   FR=21 TC=52 WBS=4
   INFO  未启动流程（缺 2 项，含 00-RESEARCH / 01-ASSESSMENT），按 §9 不计为违规

[IDLE] v1.0-ga   FR=25 TC=43 WBS=4
   INFO  未启动流程（缺 2 项，含 00-RESEARCH / 01-ASSESSMENT），按 §9 不计为违规

[PASS] v2.0-platform   FR=61 TC=67 WBS=0

[IDLE] v2.1-platform   FR=26 TC=32 WBS=0
   INFO  未启动流程（缺 3 项，含 00-RESEARCH / 01-ASSESSMENT），按 §9 不计为违规

------------------------------------------------------------------------------
合计：9 个版本  |  FAIL 0 项  |  WARN 0 项  |  未启动 8 个
未启动的版本：正式开工前须先走 ① 调研 → ② 评估，再写 PRD。
==============================================================================
```

真实结论行：`合计：9 个版本  |  FAIL 0 项  |  WARN 0 项  |  未启动 8 个`，exit 0。8 个未启动版本按流程 §9 不计为违规，唯一走完流程的 `v2.0-platform` 为 PASS（FR=61 / TC=67）。

## 3. Markdown 表格机检

```bash
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/层S-AB完成证据索引与双线不抵扣声明-20261108.md
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py
```

单文件校验（层 S / A / B 声明文档）的真实输出：

```
扫描 1 份文档 / 4 个表格块 / 29 个数据行
[PASS] 表格列数一致性：全部表格块内列数一致（转义竖线已按非分隔符处理）
```

三份本版新文档一起校验的真实输出：

```
扫描 3 份文档 / 10 个表格块 / 68 个数据行
[PASS] 表格列数一致性：全部表格块内列数一致（转义竖线已按非分隔符处理）
```

全量默认文件集的真实输出：

```
扫描 7 份文档 / 157 个表格块 / 1252 个数据行
[PASS] 表格列数一致性：全部表格块内列数一致（转义竖线已按非分隔符处理）
```

## 4. 结论

| 判据 | 落地 | 真实结论 | 结果 |
|---|---|---|---|
| FR-v2.2-26 层 S 八项证据可点开、`check_release_docs.py` 六项 PASS | `层S-AB完成证据索引与双线不抵扣声明-20261108.md` §1 逐项 S1–S8 列出结论 / 证据路径 / 状态 | 六项机检 `FAIL 0 项`、exit 0；S2–S8 已回写、S1 如实标未恢复 | PASS |
| FR-v2.2-27 双线不抵扣书面声明、44 PARTIAL / 7 NOT-RUN 前后可对照 | 同文件 §2 层 A / B 证据索引、§3 双线不抵扣声明与三套台账对照 | 声明写明 v2.2 / 层 M 与层 N 新证据不得抵扣 v2.0 的 44 PARTIAL / 7 NOT-RUN，两套台账独立读账 | PASS |
| S1 书面豁免口径 | 同文件 §1 S1 行与 §3 豁免记录 | 未恢复、书面豁免、授权人大师、双线不抵扣，依据定案文件 §4 | 如实挂账 |
| 表格列数一致性 | `check_md_tables.py` 单文件校验与全量校验 | 单元格内无裸竖线，全量 `[PASS]` | PASS |

## 5. 边界（如实说明）

1. 本文不改 `tools/check_release_docs.py`；六项机检是**版本流程文档机检**，不是本包新写的检查器，CLOS-02 的两台纯标准库检查器在 `CLOS-02/README.md`。
2. 六项机检中 8 个 `IDLE` 版本按流程 §9 不计违规，不代表「全部已发布」；本文不判 G4 / G5 / G7 / G8，不宣布任何版本已发布。
3. S1 的 44 PARTIAL / 7 NOT-RUN 逐条复核**未收敛**，按大师书面豁免挂账为遗留缺陷；本文不把「豁免」写成「已收敛」，不把「双线不抵扣」写成「已抵扣」。
4. 全量 `bash scripts/gates.sh` 由主代理最后统一复跑；本包只引用已落盘的真实证据与已跑出的机检结论。
