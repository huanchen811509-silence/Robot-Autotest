# EW35-03 · 效果度量最小体系

| 项 | 内容 |
|---|---|
| 目的 | 冻结可复现的效果度量口径，把「无证据」显式化为「未测量」，杜绝拍脑袋数字 |
| 环境 | WSL `~/robot-autotest`（ext4）；Python 3.12；2026-11-09 |
| 命令 | `python3 tools/ux_metrics.py --selfcheck`；`python3 tools/ux_metrics.py --aggregate` |
| 原始输出 | `ux-metrics-selfcheck.log`、`ux-metrics-aggregate.log`、`../EW35-10/metrics.json` |

## 交付物

| 文件 | 内容 | 状态 |
|---|---|---|
| `top8-script.md` | 8 条冻结任务脚本、成功判据、计时 / 步数定义 | 已冻结 |
| `search-queries-50.json` | 50 条查询集，判定列留空 | 已冻结；人工判定 `BLOCKED` |
| `event-dictionary.md` | 6 类最小事件、字段白名单、禁止采集清单、隐私评审 | 通过核对 |
| `metrics-dictionary.md` | 度量字典，基线值一律「未测量」 | 已建 |

## 结论

- **PASS**：脚本、查询集、事件字典、度量字典冻结落盘；聚合器默认关闭、无数据输出「未测量」；负向自检可实证非法字段会被拒。
- **BLOCKED**：8 名内部用户的 Top 8 实测、50 条查询集的人工逐条判定、10 人 SUS 问卷。
  阻塞原因：真人样本与评审签字资源未到位；所需条件：8–10 名内部用户、冻结数据集、独立环境、不求助的任务测试时段。
- **禁止**：不得把 `PARTIAL` / `BLOCKED` 读作通过，不得用 API p95、包体、黄金集等机器指标替代人的效果证据。

## 复现步骤

1. `cd ~/robot-autotest`
2. `python3 tools/ux_metrics.py --selfcheck`（负向自检）
3. `python3 tools/ux_metrics.py --aggregate`（默认关闭，输出「未测量」）
4. 显式开启开关并放入内部事件样本后才产生读数；每份读数必须能下钻到原始事件文件。
