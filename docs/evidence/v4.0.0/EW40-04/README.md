# EW40-04 · 折叠态留白（FR-v4.0-04）

| 项 | 内容 |
|---|---|
| 工作包 | `W40-04` 折叠态留白微调 |
| 需求 | `FR-v4.0-04`（窄轨 56→64、内容右内边距 20→28、四档视口与缩放、折叠按钮命中自身） |
| 日期 | 2026-11-09 |
| 环境 | WSL Ubuntu-24.04 · Chromium HeadlessChrome/157.0.8103.38（CDP `:9222`）· 前端 `:8001`（`vite build` 产物）· 真库 |
| 结论 | `PASS`：布局判据 12 场景 / 56 项检查 `FAILS=0`，负向注入 `FAILS=3` 必红 |

---

## 1. 判据与读数

| 判据 | 命令 | 真实读数 |
|---|---|---|
| 四档视口 + 125%/150% 缩放右留白与横滚 | `node scripts/viewport-layout-probe.mjs`（`layout.log`） | `LAYOUT_PROBE cases=12 checks=56 FAILS=0`；1024/1280/1440/1920 与 1440@125%(1152)、1440@150%(960)、1920@125%(1536)、1920@150%(1280) 全部 `paddingRight=28px`、`gap≥28`、`docScrollW=innerWidth` |
| 折叠窄轨 64 与按钮命中 | 同上（折叠态四档） | `sideW=64`、`toggleHit=true`；`[collapse] before=false after=true rail=64` |
| 折叠按钮自身命中不复发 | `node scripts/side-toggle-probe.mjs`（`side-toggle.log`） | 7 项 `[PASS]`，`SIDE_TOGGLE_PROBE collapsedBefore=false collapsedAfter=true restored=true FAILS=0` |
| 判据本身有效（负向自证） | `node scripts/viewport-layout-probe.mjs --selfcheck` | 注入 `padding-right:4px` + `grid 56px` 后 `FAILS=3`（右内边距、右留白、窄轨三项变红） |

## 2. 实现口径

| 项 | 落地 |
|---|---|
| 令牌 | `--rail-w-collapsed: 64px`、内容右内边距走统一间距令牌；`main.css` 4 处 grid + 760px 断点合计 5 处同步 |
| 探针 | `scripts/viewport-layout-probe.mjs`（版本化，支持 `--selfcheck`、`LAYOUT_PROBE_OUT` 落 JSON，只读不写库） |
| 缩放建模 | 浏览器缩放在 CDP 中等价于 CSS 视口变窄，故 1440@125% 取 1152、1440@150% 取 960、1920@125% 取 1536、1920@150% 取 1280 |

## 3. 证据文件

| 文件 | 内容 |
|---|---|
| `layout.log` / `layout.json` | 12 场景读数与逐项判定 |
| `layout-selfcheck.log` / `layout-selfcheck.json` | 负向注入后的必红读数 |
| `side-toggle.log` / `side-toggle.json` | 折叠/还原与图标、文字可见性 7 项 |

## 4. 判不了的部分（不冒充通过）

| # | 项 | 原因 | 补齐 |
|---|---|---|---|
| 1 | 真实鼠标 125%/150% 浏览器缩放（`Ctrl +`） | 无人工交互环境，用等效 CSS 视口建模 | 人工核对时抽查一档即可 |
| 2 | Firefox 视口抽查 | 本机仅有 Chromium | 环境具备时补 |
