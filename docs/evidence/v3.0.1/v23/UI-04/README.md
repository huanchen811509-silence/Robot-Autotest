# UI-04 顶栏排版 / 排序 / 美工证据（v2.3 / FR-UI-15..19）

把顶栏从「10 个元素等距平铺、主题双入口、登出裸奔」改成「**四段排序 + 3 条分隔线 + 28px 栅格 + 独立造型 + 登出二次确认**」。
唯一移除的入口是「关于」（与侧栏「系统设置 > 关于」重复）。

> 状态：已回填（2026-11-08，命令真实执行）。原始日志见 `docs/evidence/v3.0.1/v23/UI-08/raw/`。

## 1. 改了什么

| 文件 | 类型 | 说明 |
|---|---|---|
| `frontend/src/components/AppHeader.vue` | 新增 | 顶栏组件：四段（上下文 / 工具 / 偏好 / 账户）+ 3 条 `.app-sep`；项目选择器独立造型；主题二态；登出 danger + 二次确认 |
| `frontend/src/components/AppHeader.spec.ts` | 新增 | 11 条：四段顺序、分隔线 3 条、无「关于」、无侧栏折叠、Tab 顺序、控件计数、项目选择器三态、主题二态、登出确认两路 |
| `frontend/src/App.vue` | 修改 | 顶栏换成 `<AppHeader>`；侧栏头部新增 `.app-side-head` 承载折叠按钮；移除 `AboutDialog` 与主题切换逻辑（已迁入 AppHeader） |
| `frontend/src/styles/main.css` | 修改 | 追加 `/* v2.3 / UI-04 */` 段：`.app-seg` / `.app-sep` / `.app-project` / `.app-icon-btn` / `.app-user` / `.app-side-head` / `.app-nav-sep` |
| `frontend/src/locales/{zh-CN,en-US}.ts` | 修改 | 新增 `app.logoutConfirm` `app.localeSwitch` `app.langZh` `app.langEn` `projectPicker.choose` |

## 2. 排版规格（写进 CSS 的那一份）

| 项 | 规格 | 为什么 |
|---|---|---|
| 控件高度 | 全部 `28px` | 旧值：`.btn` 27px、`.app-lang` 23px，同一行差 4px |
| 图标按钮 | `28 × 28`，图标 16px 居中，圆角 6px | 旧样式靠 `padding: 6px 14px` 撑，实测宽度约 44px |
| 段内间距 | `6px` | — |
| 段间间距 | `14px` + 1px 分隔线 ×3 | 旧值统一 `gap: 10px`，读不出亲疏关系 |
| 项目选择器 | 独立块（面板底 + `--border-strong` 边 + `dashboard` 图标 + 名称 + 6px 状态点），最大宽 140px，超长省略 | 旧模板写 `class="app-project app-lang"` 借用语言下拉样式，`.app-project` **在样式文件里 0 处定义** |
| 未选项目 | `border-style: dashed` + 次要文字 | 「这里还空着」与「普通下拉」要能区分 |
| 账户段 | 名字 13px + 角色徽标（11px、`--border` 边） | 角色是次要信息，不该和名字同级 |

## 3. 命令原文与真实输出

```bash
cd /home/evergreen/robot-autotest/frontend
npx vitest run src/components/AppHeader.spec.ts
```

```text
✓ src/components/AppHeader.spec.ts (11 tests) 229ms
Test Files  1 passed (1)
      Tests  11 passed (11)
```

| 判据 | 断言 / 证据 |
|---|---|
| FR-UI-15（28px 栅格） | **结构部分**：`.app-icon-btn` 固定 28×28、四类控件同为 `height: 28px`（CSS 契约，见上表）。**像素部分见 §5 未验** |
| FR-UI-16（主题二态） | 连点两次，`data-theme` 在 light → dark → light 翻转，且 `theme.mode` 不是 `system` |
| FR-UI-17（登出） | 按钮含 `danger`；点击后确认层出现且**未**发出 logout；确认后才发出一次 |
| FR-UI-18（四段排序） | `[data-seg]` 顺序 = context / tools / prefs / account；`.app-sep` 恰好 3 条；顶栏内无「关于」、无「切换侧栏」 |
| FR-UI-19（项目选择器） | `.app-project` 存在且**不含** `app-lang`；有项目显示名称与点亮状态点；无项目 `is-empty` 且 select 值为空 |

控件计数（结构契约，防止以后有人再往顶栏塞按钮）：

| 场景 | 可交互元素 | 序列 |
|---|---|---|
| AI 启用 | 7 | select, button, button, button, button, select, button |
| AI 未启用 | 6 | 同上少 1（AI 项整项不渲染） |

## 4. 顺带修掉的两处形态缺陷

| 缺陷 | 处理 |
|---|---|
| 「关于」是顶栏常驻按钮，但侧栏「系统设置 > 关于」已有同一入口 | 移除顶栏按钮（`AboutDialog` 组件保留，由 `/about` 页用） |
| 侧栏折叠按钮夹在顶栏「项目选择」和「AI」之间 | 移到侧栏头部 `.app-side-head`；折叠后品牌区隐藏、按钮居中保留（否则折叠后无法展开） |

## 5. 没做到的判据（如实）

| 项 | 状态 | 原因 |
|---|---|---|
| 真实像素读数（4 类控件高度两两差 ≤ 1px 且 = 28；图标按钮 `offsetWidth === offsetHeight === 28`） | **未验** | jsdom 无布局，`getBoundingClientRect()` 恒为 0；本应放在门禁第 13 步的真实 Chromium 里量，但本轮 Chrome CDP `:9222` 后台进程被取消且按指示未重启。**这是本批唯一没有机器读数的 P0 判据**，已在测试计划记为遗留 |
| 外观弹层仍提供 `system` 三选 | 源码事实，未加测试 | `AppearanceDialog.vue:28` 的 `THEME_MODES = ['system','light','dark']` 未改动；本批未新增该组件的测试 |
