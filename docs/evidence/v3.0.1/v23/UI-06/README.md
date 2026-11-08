# UI-06 证据：统一细滚动条（v2.3 / FR-UI-20 / 21 / 22）

把侧栏、主区、AI 抽屉、AI 消息列表、数据网格五类滚动容器的滚动条统一为「8px 宽、轨道透明、thumb 由语义变量派生」，并在 Firefox 走 `scrollbar-width` / `scrollbar-color`；主区加 `scrollbar-gutter: stable`。

> 状态：已回填（2026-11-08，命令真实执行，原始日志见 `docs/evidence/v3.0.1/v23/UI-06/raw/`）。本文件所有读数来自真实日志。

## 1. 交付文件

| 文件 | 类型 | 说明 |
|---|---|---|
| `frontend/src/styles/main.css` | 修改 | 末尾追加 `/* v2.3 / UI-06：统一细滚动条 */` 整段（7 条规则，全部在 `@media screen` 内），未改动上文任何既有规则 |
| `docs/evidence/v3.0.1/v23/UI-06/README.md` | 新增 | 本文件 |
| `docs/evidence/v3.0.1/v23/UI-06/raw/*` | 新增 | 4 份真实命令日志（grep / 容器命中 / postcss 解析 / lint 全文） |

未改：`theme.css`、任何 `.vue`、`tools/px-allow.txt`（新增 px 只用了白名单已有的 8px 与 4px）。

## 2. 命令原文

```bash
cd /home/evergreen/robot-autotest
grep -rln "webkit-scrollbar" frontend/src | wc -l
grep -rn  "webkit-scrollbar" frontend/src | wc -l
for c in app-side app-main ai-drawer ai-history table-scroll data-table; do
  grep -rn "class=\"[^\"]*$c" frontend/src --include=*.vue
done
cd frontend && node /tmp/parse_css.js          # postcss 解析 main.css，按媒体块列规则
cd .. && python3 tools/check_colors.py
python3 tools/check_tokens.py
python3 tools/check_css_vars.py
python3 tools/check_w7_tokens.py
python3 tools/subset_font.py --check
bash scripts/lint.sh
```

## 3. 五类容器（含第 6 个横向滚动壳）与选择器对应关系

| 序号 | 容器 | 真实类名 | 类名出现处（grep） | 本段命中的选择器 |
|---|---|---|---|---|
| 1 | 侧栏 | `.app-side` | `App.vue:164` `<aside class="app-side">` | `.app-side::-webkit-scrollbar` 族 + `.app-side { scrollbar-width: thin }` |
| 2 | 主区 | `.app-main` | `App.vue:190` `<main class="app-main">` | `.app-main::-webkit-scrollbar` 族 + `scrollbar-width` + `scrollbar-gutter: stable` |
| 3 | AI 抽屉 | `.ai-drawer` | `AiPanelDrawer.vue:134` `<aside ... class="ai-drawer">` | `.ai-drawer::-webkit-scrollbar` 族 + `scrollbar-width` |
| 4 | AI 消息列表 | `.ai-history` | `AiPanelDrawer.vue:159` `class="ai-history"` | `.ai-history::-webkit-scrollbar` 族 + `scrollbar-width` |
| 5 | 数据网格 | `.data-table`（内含 naive `NDataTable`） | `DataTable.vue:133` `<div class="data-table">` | `.data-table .n-scrollbar`（改 naive 变量，见 §5） |
| 6 | 超宽表格横向滚动壳 | `.table-scroll` | `ReportDetailView.vue:588` `<div class="table-scroll">` | `.table-scroll::-webkit-scrollbar` 族 + `scrollbar-width` |

第 6 个不在需求列的五类里，但它是同一批「内容超过容器就得滚」的容器，横向滚动条同理需要窄化；一并收进同一组选择器，避免表格横向滚动条仍是 17px。

规则原文（`main.css:1153` 起，节选，完整见 `raw/postcss-scrollbar.txt`）：

```css
@media screen {
  .app-side::-webkit-scrollbar,
  .app-main::-webkit-scrollbar,
  .ai-drawer::-webkit-scrollbar,
  .ai-history::-webkit-scrollbar,
  .table-scroll::-webkit-scrollbar { width: 8px; height: 8px; }

  /* track: background: transparent */

  .app-side::-webkit-scrollbar-thumb, /* 同批 5 个 */ {
    background: var(--text-muted);
    border-radius: 4px;
  }
  /* thumb:hover: background: var(--text) */

  .app-side, .app-main, .ai-drawer, .ai-history, .table-scroll {
    scrollbar-width: thin;
    scrollbar-color: var(--text-muted) transparent;
  }

  .app-main { scrollbar-gutter: stable; }
}
```

## 4. `webkit-scrollbar` 命中读数（改造前 / 改造后）

| 口径 | 改造前 | 改造后 |
|---|---|---|
| 命中文件数（`grep -rln` 后管道 `wc -l`） | 0 | 1（仅 `frontend/src/styles/main.css`） |
| 命中行数（`grep -rn` 后管道 `wc -l`） | 0 | 23 |

改造前读数取自开工时对同一仓库、同一命令的执行结果（0 文件 / 0 行）；改造后为 `raw/grep-webkit.txt`。命中集中在一个文件是刻意的：滚动条口径只允许有一个真源，散到各个 `.vue` 里必然漂移。

## 5. 关键决策：数据网格为什么不用 `::-webkit-scrollbar`

先实测 naive-ui 的表体结构，再写选择器，结论与「想当然」相反：

- DOM 链：`.n-data-table-base-table-body > .n-scrollbar > .n-scrollbar-container`（`es/data-table/src/styles/index.cssr.mjs` 里以 `cB("data-table-base-table-body")` 声明）。
- `.n-scrollbar-container` 被库写死 `scrollbar-width: none` 且有 `&::-webkit-scrollbar, &::-webkit-scrollbar-track-piece, &::-webkit-scrollbar-thumb { width: 0; height: 0; display: none }`（`es/_internal/scrollbar/src/styles/index.cssr.mjs`）。
- 条是库自己画的：`.n-scrollbar-rail > .n-scrollbar-rail__scrollbar`，颜色/宽度来自 6 个 CSS 变量，而这 6 个变量由 `Scrollbar.mjs` 写在**元素 style 属性**上（内联声明）。

因此对表体写 `::-webkit-scrollbar` **不会生效**（原生条已被库藏起来，写了也测不到效果）。实际做法是把库的变量改为指向语义 token：

| 变量 | 值 | 作用 |
|---|---|---|
| `--n-scrollbar-color` | `var(--text-muted)` | thumb 常态 |
| `--n-scrollbar-color-hover` | `var(--text)` | thumb 悬停 |
| `--n-scrollbar-rail-color` | `transparent` | 轨道透明，与 Chrome 侧一致 |
| `--n-scrollbar-width` | `8px` | 竖向条宽 |
| `--n-scrollbar-height` | `8px` | 横向条高 |
| `--n-scrollbar-border-radius` | `4px` | 圆角 |

`!important` 是必需的：这 6 个变量由库内联写在元素上，普通作者声明压不过内联，只有 important 作者声明能覆盖。

**没做到的判据（如实记）**：这一项拿不到「`::-webkit-scrollbar` 命中表体」这种证据，因为该路径在 naive-ui 下物理上不生效；证据只有「变量已指向语义 token」+「库源码里原生条被隐藏」两段静态事实。另外 `--n-scrollbar-*` 是 naive 的内部变量名，升级 naive 若改名会**静默失效**（不报错、退回库默认 5px 灰条），目前无自动化断言兜它。

## 6. thumb 为什么是 `--text-muted` 而不是 `--border-strong`

先按需求建议值算了一遍，再用实测值改判（数值见 UI-07 证据 §4）：

| 候选 | dark 在 panel 上 | light 在 panel 上 | 非文本图形 3:1 | 采用 |
|---|---|---|---|---|
| `--border-strong` | 1.76:1 | 1.61:1 | 不达标 | 否 |
| `--text-muted` | 5.41:1 | 5.47:1 | 达标 | 是（常态） |
| `--text` | 12.97:1 | 15.68:1 | 达标 | 是（hover） |

滚动条 thumb 属「非文本图形」，按 3:1 判；`--border-strong` 差得较远，照抄建议值会让细滚动条在暗色下几乎看不见，与「统一可见的细滚动条」目标相反。

## 7. 打印媒体限定证据（FR-UI-22）

整段收在 `@media screen` 里。用 postcss 解析 `main.css` 全文件，按「规则实际所属的媒体块」统计（不是靠肉眼数花括号）：

```text
$ cd frontend && node /tmp/parse_css.js
[media=screen] .app-side::-webkit-scrollbar, .app-main::-webkit-scrollbar, ...
[media=screen] .app-side::-webkit-scrollbar-track, .app-main::-webkit-scrollbar-track, ...
[media=screen] .app-side::-webkit-scrollbar-thumb, .app-main::-webkit-scrollbar-thumb, ...
[media=screen] .app-side::-webkit-scrollbar-thumb:hover, .app-main::-webkit-scrollbar-thumb:hover, ...
[media=screen] .app-side, .app-main, .ai-drawer, .ai-history, .table-scroll
    scrollbar-width: thin
    scrollbar-color: var(--text-muted) transparent
[media=screen] .app-main
    scrollbar-gutter: stable
[media=screen] .data-table .n-scrollbar
    --n-scrollbar-color: var(--text-muted)  /* !important */
    ...
---
滚动条相关规则 = 7 | @media screen 内 = 7 | screen 外 = 0
scrollbar-gutter 位置： .app-main => stable | media= screen
```

「screen 外 = 0」即打印媒体拿不到任何一条自定义滚动条规则。打印页本身是独立路由 `/reports/:runId/print` → `ReportPrintView.vue`（`router/index.ts:93`），不继承本段任何声明。

## 8. 机检真实输出（2026-11-08 09:5x +08:00）

```text
$ python3 tools/check_colors.py
[PASS] 180 个文件无硬编码色值
exit=0

$ python3 tools/check_tokens.py
扫描 71 个文件：114 个 token 定义，424 处不同引用
[PASS] var 引用 0 悬空；px 字面量 1084 处全部在白名单内（39 个取值）
exit=0

$ python3 tools/check_css_vars.py
[OK] CSS 变量引用全部有定义（已定义 114 个）
exit=0

$ python3 tools/check_w7_tokens.py
[OK] W7 token guard: 8 grid tokens / 6 font levels / theme + density + a11y overrides
exit=0

$ bash scripts/lint.sh
... （逐条 PASS，全文见 raw/lint.txt）
[lint] PASS
LINT_EXIT=0
```

`check_tokens.py` 报「39 个取值」是白名单去空行后的实际条目数，本段只用了其中已有的 `8px` / `4px`，没有新增档位。`tools/px-allow.txt` 在开工时已是修改态（把 `32px` 移到排序位置），本次未触碰，`git diff` 显示取值集合不变，见 `raw/guards.txt` 末尾。

### 一个真实踩到的坑

第一版注释里写了「打印媒体」「桥接」「常驻」三个词，`tools/subset_font.py --check` 立刻报：

```text
[FAIL] 有 3 个字不在中文字体子集里（改了文案但没重新生成子集？）：媒桥驻
扫描到界面汉字 1106 个 … 中文字体 cmap 覆盖 1103 / 1106 字
```

该脚本扫的是 `frontend/src` 下 `.vue` / `.ts` / `.css` / `.html` 的汉字，**CSS 注释也算界面文案**。改词（打印时 / 改为指向 / 宽度固定）后复跑：

```text
扫描到界面汉字 1103 个（下限 200）
中文字体 cmap 覆盖 1103 / 1103 字
[PASS] 字体子集 1103 字 / 188907 B gzip，未超 800 KiB 预算
```

没有去重新生成字体子集来「消化」这三字——字体子集应由界面文案驱动，不该为注释扩容。

## 9. 结论与遗留

| 判据 | 状态 | 依据 |
|---|---|---|
| FR-UI-20 五类容器统一 8px 细滚动条 | 达成（第 5 类走变量改名，见 §5） | postcss 解析出 7 条规则 |
| FR-UI-21 主区 `scrollbar-gutter: stable` | 达成 | postcss：`.app-main => stable`，media=screen |
| FR-UI-22 打印媒体不应用 | 达成 | screen 外 = 0；打印页为独立路由 |
| Firefox `scrollbar-width` / `scrollbar-color` | 达成 | 同一选择器组内声明，postcss 已解析 |
| 不新增 px 档、不写字面色值 | 达成 | check_tokens / check_colors 均 PASS |

遗留（不粉饰）：

1. naive `--n-scrollbar-*` 是内部变量名，名字变则静默失效，无断言兜底（见 §5）。
2. 未做真实浏览器像素级验证（本批不跑 `npx vitest`、不启 dev server）：8px 是样式表里声明的值，未经「DevTools 量出来确认」这一步。
3. 只覆盖上述 6 个容器；页面里若新增滚动容器（如未来的表格卡片内滚区），需手动加进这组选择器。
