# W58-13 判据链端到端取证（FR-v5.8-07）

本目录回答一个问题：**「报告详情页给出的结论」是不是真的等于「那一行用例的判据结论」**。

答案是**是**，但成立的前提有三个，缺一个就只剩一句口号：

1. 提取器能从产物里拿到判据（pytest 9.x 不写 junit `<system-out>`，只认 json-report 的 `tests[].*.stdout`）；
2. 解析器能在一大段日志里稳定切出判据 JSON（改用 `CRITERIA_JSON` 哨兵行 + 花括号配对，不再「首个 `{` 到最后一个 `}`」）；
3. 界面不再另起一套口径（速览卡原来按 `failed == 0` 推「全部通过」，本波次改掉）。

第 3 条是这一轮的实质发现：**代码对、数据对，界面上却有两处结论互相打架**。

## 一、两个样本

| 样本 | RUN_ID | 行数 | 判据直方图 | 期望结论 | 实测结论 |
|---|---|---|---|---|---|
| 正向（真机） | `EHT-REAL-1794671083499` | 1 | `PASS: 1` | `PASS` | `PASS` |
| 反向（判据全缺失） | `EHT-FAKE-1794661209912` | 70 | `MISSING: 70` | `UNDETERMINED` | `UNDETERMINED` |

正向样本来自 gpu01/gpu02 真机执行（`IM-FUN-014`），三条判据都是**实测值**：

| metric | op | threshold | actual | passed |
|---|---|---|---|---|
| `link_speed` | `==` | `400000Mb/s` | `400000Mb/s` | true |
| `mtu` | `>=` | `1500` | `9000` | true |
| `pci_vendor_device` | `==` | `15b3:1021` | `15b3:1021` | true |

反向样本是用来**证伪**的：70 条用例里 5 条 `verdict=PASS`，但判据一条都没声明（全 `MISSING`）。
按旧口径（失败数 0 → 通过）这里会被读成「通过」；现在两处都写「未判定」，并说明「不按失败数推断」。

反向样本的通过率只有 7.1%、失败 4 条 / 跳过 61 条，所以它同时说明另一件事：
**结论不是通过率的翻译**——同一屏上「未判定」与「通过率 7.1%」可以并存，二者回答的不是同一个问题。

## 二、文件清单

| 文件 | 内容 |
|---|---|
| `raw/row-verdict.json` | 正向样本的 `test_result` 行快照（`criteria_verdict` / `criteria_json` / 直方图 / 期望结论） |
| `raw/row-verdict-unspecified.json` | 反向样本的行快照（70 行直方图 + 前 20 行明细） |
| `raw/report-detail.json` | `GET /api/reports/{runId}?projectId=95` 原始回执（正向） |
| `raw/report-detail-unspecified.json` | 同上（反向） |
| `raw/ui-measure.json` | 页面实测：结论标签、卡片文本、速览卡文本、14 项断言结果（正向） |
| `raw/ui-measure-unspecified.json` | 同上（反向） |
| `raw/ui-report-conclusion-1440.png` | 报告详情页截图（正向，1440×1000） |
| `raw/ui-report-conclusion-unspecified-1440.png` | 报告详情页截图（反向） |

## 三、复跑

```bash
# 前置：Chrome 带 --remote-debugging-port=9222；入口 :8001 已托管最新 frontend/dist；
#       后端 :8081 在跑；QA_E2E_PASSWORD 在 ~/.config/robot-autotest/env
export PATH="/home/evergreen/.hermes/node/bin:$PATH"
cd ~/robot-autotest

# 1) 行快照（真值来自 DB，不由页面反推）
bash /mnt/e/workspace/自动化测试/.workbuddy/tmp/w13_row6.sh EHT-REAL-1794671083499 row-verdict.json

# 2) 正向取证
node scripts/w13-criteria-ui.mjs \
  --run EHT-REAL-1794671083499 --project 95 \
  --row-verdict docs/evidence/v5.8.0/criteria/raw/row-verdict.json \
  --out docs/evidence/v5.8.0/criteria/raw

# 3) 反向取证
node scripts/w13-criteria-ui.mjs \
  --run EHT-FAKE-1794661209912 --project 95 \
  --row-verdict docs/evidence/v5.8.0/criteria/raw/row-verdict-unspecified.json \
  --out docs/evidence/v5.8.0/criteria/raw --tag unspecified
```

退出码：`0` = 全部断言通过；`1` = 有断言失败；`2` = 前置缺失（口令 / 参数）。

## 四、凭什么算数

1. **真值不来自页面**。`row-verdict.json` 直接查 `test_result` 与 `run` 两张表，页面只用来「看它有没有说对」。
   若拿页面的值去比页面的值，这条判据等于自证。
2. **三方比对，不是两方**。`test_result.criteria_verdict`（行）、`criteriaConclusion`（接口聚合）、
   页面标签（渲染）必须三者相等；任何一环退化成「按失败数推断」，三方立刻分叉。
3. **反向样本必须判「未判定」**。只有正向样本的取证，证明不了「不会误报通过」——
   这正是 FR-v5.8-01 要消除的那个口径。
4. **断言 `ui-no-counted-verdict`**：正反两页都要求页面文本里**不出现**「全部通过」。
   这条断言在修复前是红的（速览卡原文案），修复后转绿。
5. **`ui-no-js-error`**：采集期间 `Runtime.exceptionThrown` 必须为 0。截图漂亮但控制台报错的页面不算通过。

## 五、环境

| 项 | 值 |
|---|---|
| 平台版本 | 5.7.0（`/api/_ping`；v5.8.0 未落号，归大师判） |
| 入口 | `http://localhost:8001`（`scripts/serve-entry.mjs`，托管 `frontend/dist`） |
| 后端 | `http://127.0.0.1:8081` |
| 浏览器 | Chrome headless，CDP `127.0.0.1:9222`，视口 1440×1000 |
| 账号 | `qa-e2e-admin`（`qa-e2e-engineer` 亦可），项目 95 `EHT-PYTEST-LIB` |
| 采集时间 | 2026-11-14T15:59Z ~ 16:01Z（GMT+8 23:59 ~ 次日 00:01） |

## 六、一个必须记住的坑

**改了 `.vue` 不重建 `frontend/dist`，浏览器看到的还是旧产物。**
本轮第一次取证时结论卡片整张不渲染，`grep report-conclusion frontend/dist/assets/*.js` 无结果，
`dist/index.html` 时间戳比源码早一小时——这类「源码对、界面不对」的假象只有重建后才会消失。
取证脚本里的 `ui-conclusion-mounted` 断言就是用来把这种情况挡下来的，不许为了「跑绿」去删它。
