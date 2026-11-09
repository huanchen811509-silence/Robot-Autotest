# EW32-01 规模数据集与预算门禁 · 证据

| 项 | 值 |
|---|---|
| 版本 | v3.2.0（B3.1） |
| 日期 | 2026-11-08 |
| 对应 FR | FR-3.2-01（性能预算与规模基线） |
| 对应 TC | TC-3.2-01（预算采样与门禁越线） |
| 结论 | **交付完成（本机冒烟）**；规模压测与浏览器项按 D32-5 留待 CI/EW32-06/EW32-10 |

## 1. 交付物

| 文件 | 作用 |
|---|---|
| `tools/perf_dataset.py` | 固定种子规模数据集生成器（small / standard / large，CSV + manifest + 只读校验） |
| `tools/perf-budget.json` | 四类预算单一事实源（API p95/p99、慢查询、前端双阈值、长列表 fps、运行链） |
| `tools/perf_sample.py` | 只读接口采样器，产出 `perf/perf-report.json` |
| `tools/check_perf_budget.py` | 预算门禁：越线即红；缺报告默认 SKIP、CI 用 `--require` 升级为失败 |
| `tools/check_bundle_budget.py`（扩展） | 新增主入口 ≤300 KiB gzip、单路由块 ≤150 KiB gzip 两条判据 |
| `docs/evidence/v3.2.0/EW32-01/perf/perf-report.json` | 真实采样报告（本机冒烟，60 次/场景） |
| `docs/evidence/v3.2.0/EW32-01/perf/dataset-manifest-standard.json` | 标准数据集指纹（种子/行数/字节/sha256/contentHash） |
| `docs/evidence/v3.2.0/EW32-01/logs/` | 生成、校验、自检、门禁、采样原始日志 |

数据集本体在 `artifacts/perf-dataset-*/`（`.gitignore` 已忽略 `artifacts/`），只把 manifest 入仓，避免 67 MB 日志进版本库。

## 2. 执行记录（真实输出摘要）

### 2.1 数据集可复现

```text
small-a contentHash=798726ea5b23bac3dc0aeb1e678ab5a54078c21943d6392b0c8aedd66e4a0e51
small-b contentHash=798726ea5b23bac3dc0aeb1e678ab5a54078c21943d6392b0c8aedd66e4a0e51
verdict=REPRODUCIBLE
```

标准规模（`--scale standard`，种子 20261108）：

| 文件 | 行数 | 字节 | sha256（前 16） |
|---|---|---|---|
| cases.csv | 10,000 | 906,433 | `272bdb09a2d646e6` |
| runs.csv | 100,000 | 6,809,006 | `dcad01309ac75add` |
| logs/log-000..009.csv | 100,000 × 10 | 66,948,060（合计） | 见 manifest |
| contentHash | — | — | `59824208438adcd769ca17b368062bd684a81825e356fb1454dd7cbf1f5cbdc7` |

耗时 24.44s（Windows 本机，Python 3.13，流式写 + 每文件 sha256）。
`--verify` 复核 12 个文件全部一致（`logs/dataset-verify.log`）。

### 2.2 预算门禁自检（能判红）

`logs/budget-selftest.log` 覆盖 7 类：健康、p95 越线、p99 越线、场景缺失、长列表低帧率、
日志吞吐不足、相对回退 +40%。全部按预期 PASS/FAIL。

### 2.3 前端双阈值（真实产物）

`logs/bundle-dist.log`：

- 主入口闭包 gzip **184,919 B** ≤ 307,200 B（300 KiB），含 3 个文件（index + vue + 其余静态依赖）。
- **1 项违规**：`assets/EditorView-D1KtI0aa.js` gzip **247,191 B** > 153,600 B（150 KiB）。

该违规是真实发现，不是脚本误报：EditorView 静态 `import` 整个编辑器包（`editor.js` 单独 gzip 94 KiB），
路由块因此膨胀到 247 KiB。修复归入 **EW32-06（路由拆分/按需加载）**，本包只负责「预算与门禁先立起来」。

### 2.4 接口采样（本机冒烟）

环境：`http://127.0.0.1:8001`，项目 29，3 轮 × 20 次/场景（预热 2 轮），数据集 manifest 为 standard。

| 场景 | p50 | p95 | p99 | 预算 p95/p99 | 结论 |
|---|---|---|---|---|---|
| cases_list | 23.83 | 31.12 | 31.42 | 150 / 300 | 达标 |
| runs_list | 25.45 | 42.30 | 84.27 | 150 / 300 | 达标 |
| reports_list | 17.24 | 19.62 | 19.96 | 150 / 300 | 达标 |
| audit_list | 15.97 | 20.06 | 21.25 | 150 / 300 | 达标 |

`logs/budget-run.log` → `PERF_RESULT PASS`。

**重要口径**：数据库里仍是既有小数据，**没有**灌入 1 万用例/10 万运行。因此本表只能证明
「预算门禁可执行、当前小数据不越线」，**不能**当作规模基线结论。规模基线必须在 CI 固定机
（D32-5）把 CSV 灌库后重跑，原始报告另存 `perf/perf-report-ci.json`。

## 3. FR / TC 三态

| 项 | 状态 | 说明 |
|---|---|---|
| FR-3.2-01 数据集与预算 | 达成 | 生成器、四类预算、门禁、自检、真实报告齐备 |
| TC-3.2-01 预算采样与门禁越线 | 达成（本机） | 自检证明 7 类越线可判红；真实冒烟报告 PASS |
| TC-3.2-01 规模 p95（1 万用例/10 万运行） | BLOCKED | 需 CI 固定机灌库后采样（D32-5） |
| 前端主入口预算 | 达成 | 184,919 B ≤ 300 KiB |
| 前端单路由块预算 | **未达成** | EditorView 247,191 B；转 EW32-06 修复 |
| 长列表 ≥50fps | 未执行 | 浏览器项，归 EW32-06 |
| 运行链 p95 / 日志吞吐 | 未执行 | 写接口压测，归 EW32-10 |

## 4. 偏差与决策记录

1. **采样器实现**：规划原文写「JUnit 压测采样」，落地为独立 Python 采样器（`tools/perf_sample.py`）。
   理由：采样对象是运行中的 HTTP 服务，无需 Spring 上下文；Windows/WSL 同一脚本；报告与门禁同源。
   JUnit 方案不再保留，避免两套采样口径。
2. **数据集落库**：本机未灌库（避免污染开发库、且数据集体积 67 MB+）。EV32-01 只保证「数据可复现」，
   规模读数交 CI。灌库脚本与命令写在 `tools/perf_dataset.py` 的 manifest 与本文 §2.4 口径里。
3. **门禁缺报告口径**：默认 SKIP（本机无后端不红），CI 加 `--require` 防「忘跑采样」假绿。
4. **路由块预算现状红**：保留红并归 EW32-06，不通过「例外名单」把 247 KiB 洗成达标。

## 5. 复现命令

```bash
# 数据集（Windows / WSL 均可）
python tools/perf_dataset.py --scale small  --out artifacts/perf-dataset-a
python tools/perf_dataset.py --scale small  --out artifacts/perf-dataset-b   # 比对 contentHash
python tools/perf_dataset.py --verify --out artifacts/perf-dataset-a
python tools/perf_dataset.py --scale standard --out artifacts/perf-dataset-standard

# 门禁自检
python tools/check_perf_budget.py --self-test
python tools/check_bundle_budget.py --self-test

# 采样 + 预算判定（需要 8001 与 QA_E2E_PASSWORD；WSL 下读 ~/.config/robot-autotest/env）
python3 tools/perf_sample.py --local --dataset-manifest artifacts/perf-dataset-standard/manifest.json
python tools/check_perf_budget.py
```

## 6. 回滚

本包只新增工具与预算文件，未改运行时与数据库，回滚等于删除新增文件；
`check_bundle_budget.py` 的扩展可用 git 单文件还原，不影响既有 5 条判据。
