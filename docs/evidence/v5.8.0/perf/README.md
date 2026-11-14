# v5.8.0 性能基线证据（W58-09 / W58-10）

> 对应 FR-v5.8-06、TC-v5.8-21 ~ TC-v5.8-25。采集时间 2026-11-14（GMT+8）。
> 结论：**产品口径 `minFps 60.1 ≥ 50` → PASS**；独立 10000 行口径 `20.4` → 不达标，
> 它是补充基线，不参与判定（理由见 §3）。

---

## 1. 文件清单

| 文件 | 内容 | 谁写的 |
|---|---|---|
| `dataset-manifest.json` | 数据集 manifest：规模 / 种子 / 每文件行数与 sha256 / `contentHash` | `tools/perf_dataset.py` |
| `seed-report.json` | 导入前后 DB 读数对比 + 4 条隔离判据 + 压测工程 id/code | `tools/perf_seed.py` |
| `longlist-raw.json` | 原始量测：每轮 fps / 帧数 / 最差帧 / 累计滚动像素 / 环境 | `scripts/perf-longlist.mjs` |
| `perf_longlist.json` | 判据结论：`status` / `min_fps` / 双口径摘要 / 理由 | `tools/perf_longlist.py` |
| `perf-report.json` | 喂 `check_perf_budget` 的最小报告（`longList.fps` / `rows`） | `tools/perf_longlist.py` |

## 2. 复跑三步

```bash
set -a; . ~/.config/robot-autotest/env; set +a      # DB_* 与 QA_E2E_PASSWORD

# ① 数据集（固定种子，两次生成 contentHash 必须一致）
python3 tools/perf_dataset.py --scale standard --out /tmp/perf-ds

# ② 隔离导入（只写 PERF- 工程，先清后写，幂等）
python3 tools/perf_seed.py --dataset /tmp/perf-ds

# ③ 量测 + 判定（工程 id 每次 purge 后会变，按 code 查）
PID=$(mysql ... -e "SELECT id FROM project WHERE code='PERF-LONGLIST'")   # 本次 = 124
node scripts/perf-longlist.mjs --project "$PID" --rounds 5 --seconds 3 \
     --out docs/evidence/v5.8.0/perf/longlist-raw.json
python3 tools/perf_longlist.py
```

前置：Chrome 带 `--remote-debugging-port=9222`（WSL 用 Windows Chrome，`curl 127.0.0.1:9222/json/version` 可自检）；
入口 `:8001`。清理：`python3 tools/perf_seed.py --purge`。

## 3. 为什么是两个口径（偏差 D-58-08，大师 2026-11-14 拍板）

预算里的场景是「10000 行滚动 minFps ≥ 50」，但产品把每页硬顶在 **100 行**
（`PageQuery.MAX_SIZE = 100`，前端档位 10/20/50/100，全站没有任何列表取数超 100）。
「10000 行同屏」在产品形态下**到不了**，硬报一个 `rows=10000` 的产品读数就是伪造。

| 口径 | 量的是什么 | rows | minFps | 判定 |
|---|---|---|---|---|
| product | `PERF-LONGLIST` 10000 条数据集 + 每页 100 行的真页面（`/cases`） | 100 | **60.1** | **PASS**（决定 `status`） |
| harness | 一次性 HTML 渲染 10000 行，非产品页面 | 10000 | 20.4 | FAIL（补充基线，不参与判定） |

harness 那条数字的用途只有一个：如果哪天要做虚拟滚动/取消分页，先知道 10000 行同屏
在纯静态表格上只有 20~28fps（三次独立量测：23.8 / 24.2 / 20.4）。这是遗留 L-58-06 的输入。

## 4. 这套读数凭什么算数

| 质疑 | 处置 |
|---|---|
| 「数据是你自己造的，量它有什么意义」 | 数据集固定种子可复现：两次独立生成 `contentHash` 相同，且与 **v3.2.0 历史数据集逐字节一致**（`59824208438adcd7…`，见 `docs/evidence/v3.2.0/EW32-01/perf/perf-report.json` 的 `contentHash`） |
| 「100 行可能来自别的工程」 | 页面必须能看见 `PERF-C\d{6}` 用例编号，否则产品口径整轮作废（`not-perf-project`） |
| 「rAF 空转也是 60fps」 | 每轮记录 `scrollable` 与 `scrolledPx`；`scrollable=false` 或 `scrolledPx ≤ 1000` 的轮次不计样本，样本不足 3 轮**不产出结论**（退出 2，不写 PASS 也不写 FAIL） |
| 「造数据污染了既有统计」 | `seed-report.json` 有前后对比：既有工程 13→13、既有用例 2491→2491 不变；压测数据全在 `PERF-LONGLIST` 下，`--purge` 可一键清除 |
| 「采集脚本自己说自己合格」 | 判定在 `tools/perf_longlist.py`，有 `--selfcheck`（0 轮 / 少样本 / 空转轮次 / 不达标 4 类反例必须判红），并已进门禁第 22 步 |

## 5. 环境

Linux 5.15.167.4 WSL2 × HeadlessChrome 159（Windows 侧，经 localhost 转发），视口 1440×900。
**不是有头模式**：帧率上限受 vsync 约束（~60fps 封顶），读数用于「有没有掉到 50 以下」，
不用于比较两台机器的绝对性能。
