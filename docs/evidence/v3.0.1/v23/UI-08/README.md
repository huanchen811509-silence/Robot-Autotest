# UI-08 收口与证据索引（v2.3 / 界面与主题）

本批 8 包的收口文件：把 UI-01..07 的证据串成一条线，给出**当轮真实读数**与**没做到的判据**。

> 状态：**部分收口**。证据齐全、单测与静态守门全过，但全量门禁 `GATES_RESULT FAILS=1`（第 13 步浏览器回归无法完成）。
> 本文件不含占位、不含「待复跑」；没跑的就是没跑，写在 §7。

## 1. 证据索引

| 包 | README | 主要判据命令 | 关键读数 | 结论 |
|---|---|---|---|---|
| UI-01 菜单 IA 重构 | `../UI-01/README.md` | `npx vitest run src/config/navigation.spec.ts src/components/AppNav.spec.ts` | 2 files / **38 tests** 全绿 | 达成 |
| UI-02 卡牌聚合端点 | `../UI-02/README.md` | `bash UI-02/raw/collect-http.sh` | 7d/77 → **200 / 8 项 / 13 字段**；90d → **400** | 达成 |
| UI-03 工作台卡牌页 | `../UI-03/README.md` | `npx vitest run src/views/WorkbenchView.spec.ts src/components/ProjectCard.spec.ts` | 2 files / **11 tests** 全绿 | 达成（截图未出） |
| UI-04 顶栏排版/排序/美工 | `../UI-04/README.md` | `npx vitest run src/components/AppHeader.spec.ts` | 1 file / **11 tests** 全绿 | 达成（像素读数未验） |
| UI-05 主题快捷二态 | `../UI-05/README.md` | 同 UI-04（二态翻转断言在该 spec） | 三次点击内不出现 `system` | 达成 |
| UI-06 滚动条统一 | `../UI-06/README.md` | `grep -rc webkit-scrollbar frontend/src` | 改前 **0** 文件 → 改后 `main.css` 内 **23 处** | 达成（截图未出） |
| UI-07 对比度实测 | `../UI-07/README.md` | `python3 tools/check_contrast.py` | **14 组 / 280 项 / FAIL 0** | 达成 |
| UI-08 收口与证据 | 本文件 | `bash scripts/gates.sh` | **19 步 18 PASS / 1 FAIL** | **部分收口** |

## 2. 全量门禁（当轮）

本轮 **2026-11-08 10:18** 完整重跑一次，输出已覆盖 `docs/evidence/gates/logs/00-full-run.log`。
该文件此前是 08:55（CDP 可用时）的旧结果，**不能**当本批证据。下表用时取自当轮 stdout。

```bash
cd /home/evergreen/robot-autotest
bash scripts/gates.sh
```

| # | 步骤 | 用时 | 结果 | 说明 |
|---|---|---|---|---|
| 01 | subset_font | 5s | PASS | 中文字体子集重生成后无缺字 |
| 02 | frontend_test | 22s | PASS | `Test Files 53 passed (53)` / `Tests 370 passed (370)` |
| 03 | type_check | 14s | PASS | `vue-tsc --noEmit` 0 error |
| 04 | frontend_build | 16s | PASS | `vite build` 成功 |
| 05 | backend_test | 60s | PASS | `Tests run: 1061, Failures: 0, Errors: 0, Skipped: 0` |
| 06 | lint | 19s | PASS | `[lint] PASS` |
| 07 | release_docs | 0s | PASS | `9 个版本 / FAIL 0 项 / WARN 0 项` |
| 08 | wbs_consistency | 0s | PASS | `130 个包 / 未开始 0 / 进行中 0 / 待验 0` |
| 09 | md_tables | 0s | PASS | 表格列数一致性通过 |
| 10 | diff_check | 0s | PASS | 无空白错误 |
| 11 | editor_test | 6s | PASS | `Test Files 24 passed (24)` / `Tests 219 passed (219)` |
| 12 | db_audit | 1s | PASS | `robot@127.0.0.1:3306/robot_dev` |
| **13** | **browser_regression** | 0s | **FAIL** | `FAIL 浏览器回归无法完成: fetch failed`（Chrome CDP `:9222` 无监听） |
| 14 | report_sensitive | 0s | PASS | `SKIP 无 EXTERNAL 样本，改跑扫描器自校验` |
| 15 | css_vars | 0s | PASS | `已定义 114 个`，引用全部有定义 |
| 16 | report_consistency | 1s | PASS | 四格式一致性 |
| 17 | perf_baseline | 3s | PASS | `PERF_FIXTURE run=RUN-20261107151146-000000` |
| 18 | structure_selfcheck | — | PASS | 自检：删步骤 13 / 14 / 19 均能判红（该步不打印用时） |
| 19 | evidence_integrity | 1s | PASS | metadata 六必填 + 三处单测基线只增不减 + AI 契约机检 |

```text
FAIL  13-browser_regression (0s) -> docs/evidence/gates/logs/13-browser_regression.log
FAIL 浏览器回归无法完成: fetch failed
GATES_RESULT FAILS=1
```

**18 / 19 步 PASS，`FAILS=1`。** 唯一失败项是第 13 步，根因是 Chrome CDP `:9222` 无监听——该后台进程已被大师取消，
并按指示**未重启**。第 13 步日志：`docs/evidence/gates/logs/13-browser_regression.log`（内容为上面两行）。

## 3. 定向测试（本批 5 个新/改 spec）

```bash
cd /home/evergreen/robot-autotest/frontend
npx vitest run src/config/navigation.spec.ts src/components/AppNav.spec.ts \
  src/components/AppHeader.spec.ts src/components/ProjectCard.spec.ts \
  src/views/WorkbenchView.spec.ts
```

```text
Test Files  5 passed (5)
     Tests  60 passed (60)
```

原始件：`raw/ui-all-targeted.log`。

## 4. 三处单测基线（只增不减）

| 处 | 当轮读数 | 门槛 | 判定 |
|---|---|---|---|
| 后端 | `Tests run: 1061` | ≥ 1050 | 增 |
| 前端 | `Tests 370 passed` / `Test Files 53` | ≥ 330 | 增 |
| 编辑器 | `Tests 219 passed` / `Test Files 24` | ≥ 219 | 持平（未动该包） |

## 5. 负向验证（红/绿对照）

```bash
cd /home/evergreen/robot-autotest
# 逐条：备份 → 注入单点缺陷 → 跑判据（必须判红）→ 还原 → 复跑（必须回绿）
python3 docs/evidence/v3.0.1/v23/UI-08/raw/negative-checks.py
```

脚本本身入库（`raw/negative-checks.py`），不是「跑完就没了」的一次性命令。

| 编号 | 注入的缺陷 | 结果 |
|---|---|---|
| N-01 | `NAVIGATION.reverse()` | 一级项顺序断言判红 → 还原回绿 |
| N-02 | 非成员账号直达项目 77 | 6 个请求全部 **403**（真实 HTTP） |
| N-04 | `main.css` 写 `#888` | `check_colors` 判红并定位 1227 行 → 还原回绿 |
| N-06 | 主题按钮退化为单态 | `AppHeader.spec.ts:161` 判红 → 还原回绿 |
| N-07 | 卡牌页多发一次请求 | `WorkbenchView.spec.ts:156` 判红 → 还原回绿 |
| N-09 | 给合并组加回组级 `capability` | viewer 角色矩阵判红 → 还原回绿 |
| N-10 | 加回 `reports` 一级项 | 一级项数 7→8 判红 → 还原回绿 |
| N-11 | `/test-reports` 入口改道 | 叶子计数 1→0 判红 → 还原回绿 |
| N-12 | 让 `section` 参与选中判定 | 带/不带 section 结果不等判红 → 还原回绿 |

N-02 的复跑命令（真实 HTTP，需 `set -a; . ~/.config/robot-autotest/env`）：

```bash
# 用 qa-e2e-engineer / qa-e2e-nobody 登录后，直达项目 77 的三个端点，期望全部 403
for p in /api/projects/77 /api/projects/77/overview /api/projects/77/cases; do
  curl -s --noproxy '*' -o /dev/null -w "$p -> %{http_code}\n" "$BASE$p" -H "Authorization: Bearer $TK"
done
```

```text
NEG_RESULT ALL_RED_AS_EXPECTED
```

原始件：`raw/negative-checks.log`、`raw/n02-cross-project.log`、`raw/tc08-set-compare.log`。

**没做的三条**：N-03（26px 高度差）与 N-05（去掉 `scrollbar-gutter`）在本环境**没有自动判据**——jsdom 无布局、第 13 步不可用；N-08 以审计形式通过（`check_contrast.py` 本轮 `git diff` 为空，阈值未动，14 组 280 项 FAIL 0）。

## 6. 原始件清单（`raw/`）

| 文件 | 内容 |
|---|---|
| `ui01-nav.log` | 导航/组件 spec 全量输出 |
| `ui-all-targeted.log` | 5 个定向 spec 合计 60 tests |
| `frontend-full.log` | 前端全量 370 tests |
| `vue-tsc.log` | 类型检查 0 error |
| `lint.log` | `[lint] PASS` 全段 |
| `negative-checks.log` | 8 条负向注入的红/绿对照 |
| `n02-cross-project.log` | 跨项目直达 403 与卡牌可见集合 |
| `tc08-set-compare.log` | `/api/projects` 与 `/api/projects/cards` id 集合比对 |

## 7. 没做到的判据（如实）

| 项 | 状态 | 原因与补救 |
|---|---|---|
| 28px 像素读数（TC-UI-14 / V-UI-06 像素项） | **未验** | jsdom 无布局，`getBoundingClientRect()` 恒 0；需真实浏览器 |
| 1440 无横向滚动（TC-UI-20 / V-UI-09） | **未验** | 判据在第 13 步里，本轮 `fetch failed` |
| 侧栏点「对外报告」可达（TC-UI-31 / V-UI-11） | **未验** | 同上，缺真实点击 |
| 逐页走查亮/暗 × 1440/1280（TC-UI-26） | **未做** | 同上 |
| 滚动条/空态/错误态截图（TC-UI-13/19） | **未出** | 同上 |
| 首帧不闪（TC-UI-25） | **未验** | 需连续取帧 |
| 全量门禁 `FAILS=0`（V-UI-10） | **未达成** | 第 13 步需 Chrome CDP `:9222`；该后台进程已被大师取消，**按指示未重启** |

**补救路径（一项动作即可闭环）**：恢复 CDP `:9222` 后跑一次 `bash scripts/gates.sh`，上述前六项与环境就位后一并补齐；在此之前不得声称 V-UI-06 像素判据、V-UI-09、V-UI-10 达成。

## 8. 复跑命令（可原样执行）

```bash
# 1) 静态守门
cd /home/evergreen/robot-autotest && bash scripts/lint.sh

# 2) 前端定向 + 全量
cd frontend && npx vitest run src/config/navigation.spec.ts src/components/AppNav.spec.ts \
  src/components/AppHeader.spec.ts src/components/ProjectCard.spec.ts src/views/WorkbenchView.spec.ts
npx vitest run

# 3) 类型检查
npx vue-tsc --noEmit

# 4) 后端
cd ../backend && mvn -o -B test

# 5) 真实 HTTP（需服务在 :8001 且已 set -a; . ~/.config/robot-autotest/env）
cd .. && bash docs/evidence/v3.0.1/v23/UI-02/raw/collect-http.sh

# 6) 全量门禁（需 Chrome CDP :9222）
bash scripts/gates.sh
```
