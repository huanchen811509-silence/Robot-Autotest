# W67-03 长列表与分类树性能读数（v5.17.0）

> 对应：`FR-v5.17-04` / `TC-v5.17-25 ~ 29`；来源缺口 G5（5.11 NFR-03/04）。
> 执行时间：2026-11-16 08:42（GMT+8）。
> **结论：BLOCKED —— 环境不可用，未取得任何读数。不写成 PASS，也不拿旧数据顶替。**

---

## 1. 实测原文

```
$ node scripts/perf-longlist.mjs --product-only --rounds 1 --seconds 1
[perf] 环境不可用：fetch failed
```

脚本自身的退出码语义（见文件头）：`0` = 两口径都有有效样本；`1` = 有口径没样本；**`2` = 环境不可用**。
本次落在 `2`，即连量测前置都没满足。

## 2. 前置条件逐项核查

| 前置 | 要求 | 实测 | 结论 |
|---|---|---|---|
| Chrome CDP | `--remote-debugging-port=9222` 可连 | `curl 127.0.0.1:9222/json/version` → `000`（无响应） | 不满足 |
| Chrome 二进制 | 可执行 | WSL 内 `google-chrome` / `chromium` / `chromium-browser` **均不存在** | 不满足 |
| Playwright / Puppeteer | 可驱动浏览器 | `frontend/node_modules` 无，全局 npm 无，`~/.cache/ms-playwright` 空 | 不满足 |
| 统一入口 `:8001` | 可用 | 可用（`/api/health` = 200） | 满足 |
| PERF 数据集 | 1000 行 / 3000 分类 | 未导入（无浏览器跑不了页面量测，先导数据也无意义） | 未做 |

> Windows 侧装有 Chrome（`C:\Program Files\Google\Chrome\Application\chrome.exe`），但本次**未用它绕路**：
> 从 WSL 驱动 Windows Chrome 需要额外的调试端口暴露与网络打通，属于改环境而不是取证；
> 且在无人值守下启 GUI 进程本身有风险。留给有浏览器环境时补测（`L-67-05`）。

## 3. 为什么这次不"想办法凑一个数"

三条硬理由，写在证据里是为了避免下一版又有人拿旧数据填这个空：

1. **口径不可替换**：判据是「1000 行数据集、每页 100 行」的真页面 `min_fps ≥ 50`。
   harness 口径（一次性渲染 1000 行）量的是浏览器扛不扛得住，**不是产品页面**。拿它顶替就是把两个口径混成一个。
2. **5.11 的旧读数已过期**：那一版之后资产页与列表组件都改过（5.12~5.16），旧 fps 不能代表当前代码。
3. **`--selfcheck` 的意义**：脚本自带「量了个不动的东西不算样本」的判据（`scrollable === true` 且 `scrolledPx` 达阈值）。
   环境不可用时连 selfcheck 都跑不了，任何手填数字都绕过了这条护栏。

## 4. 需要什么才能关账

| 需要 | 具体 | 归属 |
|---|---|---|
| 浏览器 | WSL 内可执行的 Chrome/Chromium，或 `npx playwright install chromium` | 环境 |
| 数据集 | `python3 tools/perf_seed.py --project PERF-LONGLIST`（1000 行 + 3000 分类） | 可执行 |
| 量测 | `node scripts/perf-longlist.mjs --route cases --rows 100 --dataset 1000 --rounds 3 --seconds 3` | 可执行 |
| 判据 | `min_fps ≥ 50`；分类展开 P95 ≤ 500ms；`--selfcheck` 仍判红 | 脚本自带 |

## 5. 状态

| 项 | 值 |
|---|---|
| FR-v5.17-04 | **BLOCKED**（不是"未达成"，也不是"降级"——前置不具备） |
| 台账编号 | `L-67-05`（性能读数待浏览器环境） |
| 未达成项计数 | 本版 7 条 FR 中：5 条 PASS、1 条 PASS（口径修正）、1 条 BLOCKED |
