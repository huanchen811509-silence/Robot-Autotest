# v3.5.0 证据总览

| 项 | 内容 |
|---|---|
| 时点 | 2026-11-09（B0–B2） |
| 门禁 | 同批次 27 步 `FAILS=0`；`--selfcheck` `FAILS=0`（`EW35-02/README.md`） |
| 机检 | `check_release_docs.py` FAIL 0 / WARN 0；`check_md_tables.py` PASS；台账 / REG / 度量聚合器 / 黄金集评测器自检全绿 |

| 包 | 状态 | 证据 |
|---|---|---|
| EW35-01 | PASS | 基线 sha `2278fb9`，两仓 + 双远程一致，0 个 mode change |
| EW35-02 | PASS | 同批次汇总与负向自检 |
| EW35-03 | PARTIAL | Top 8 脚本 / 50 查询集 / 事件字典 / 度量字典冻结 |
| EW35-04 | PASS | 上一轮承诺判定表 + 修订记录 |
| EW35-05 | PARTIAL | 真实浏览器探针；18 处 <24×24 目标待整改 |
| EW35-06 | PARTIAL | CWV 实验室 p75 三项达阈；RUM 未采 |
| EW35-07 | PARTIAL | REG-* 11 类扩容 + 机检 + 负向自检 |
| EW35-08 | PASS | 文档回写与六份产物 |
| EW35-09 | PASS | 统一台账 + 机检 |
| EW35-10 | PASS | 看板 v1 + `metrics.json`（默认关闭、未测量语义） |

> 真人 / 真机 / 用户签字类判据一律 `BLOCKED` 并写明阻塞原因；`PARTIAL` 不得读作通过。
