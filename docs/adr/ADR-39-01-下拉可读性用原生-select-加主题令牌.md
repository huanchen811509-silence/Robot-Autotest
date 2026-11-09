# ADR-39-01 · 下拉可读性用原生 select + 主题令牌，不引入自定义下拉组件

| 项 | 内容 |
|---|---|
| 状态 | 已采纳 |
| 日期 | 2026-11-09 |
| 约束范围 | v3.9.0 起全站表单控件 |
| 依据 | RA-PRD-39-001 §3 ADR-39-01；门禁第 27 步对比度 AA |

## 背景

用户报「暗色主题下所有下拉都是白底浅字，选项根本看不清」。实测（`docs/evidence/v3.9.0/EW39-01/dark-select-probe-before.json`）4 页 10 个 `select` 全部 `flagged`，`background-color` 为 `rgba(0,0,0,0)`（透明）→ 浏览器按 UA 默认白底渲染，而 `color` 继承了深色主题的浅灰。

## 决策

保留原生 `<select>`，用主题令牌显式给控件与 `option` 上不透明底色、前景色，并声明 `color-scheme`。
**明确不做**：不引入第三方下拉组件、不自己用 `div` 重写下拉。

## 后果与护栏

- 正面：焦点顺序、键盘操作、移动端原生选择器、无障碍语义零改动；改动面只有 CSS 令牌。
- 负面：原生 `option` 的样式能力有上限（部分平台只认 `background-color`/`color`），无法做分组图标等定制。
- 护栏：`scripts/dark-select-probe.mjs` 对每个 `select` 与首个 `option` 采样 computed style，要求底色不透明且对比度 ≥ 4.5；`--selfcheck` 注入白底浅字必须判红，防止「一直绿」。
- 复核条件：出现必须自定义选项渲染（多选标签、远程搜索）的业务需求时，重新评估组件化。

## 验证方式

`node scripts/dark-select-probe.mjs`（暗色 27 个控件 `flagged=0`）、`PROBE_THEME=light` 亮色回归、`--selfcheck` 判红。证据见 `docs/evidence/v3.9.0/EW39-01/`。

## 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-11-09 | 首版，随 v3.9.0 收口入库 |
