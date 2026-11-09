# EW35-10 · 度量看板 v1

| 项 | 内容 |
|---|---|
| 产物 | `dashboard.html`（五类：体验 / 性能 / 运行 / AI / 运维） |
| 数据字典 | `../EW35-03/metrics-dictionary.md`；事件字典 `../EW35-03/event-dictionary.md` |
| 口径 | 与门禁 24 / 26 步同源；无数据显示「未测量」而非 0；机器预算与用户体感并列不互替 |
| 下钻 | 每个数字指向 `docs/evidence/gates/logs/` 或 `docs/evidence/v3.x.0/` 原始证据 |

## 结论

- PASS：看板 v1 与数据字典落盘；五类数字均可下钻；空样本显示「未测量」。
- PARTIAL：体验、CWV、采纳率、DORA 尚无真实读数，需真人样本 / 真实交互采样 / 观察窗。
- 本看板不产生发版判定、不申请 G 门、不构成对外口径。

## 复现

```bash
cd ~/robot-autotest
python3 tools/ux_metrics.py --aggregate
# 打开 docs/evidence/v3.5.0/EW35-10/dashboard.html
```
