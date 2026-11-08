# v2.3 界面与主题 · ④ 开发计划

**文档编号** RA-REL-2026-023-D ｜ **版本** v2.3-ui-theme ｜ **批次** B11 ｜ **门禁** G10
**日期** 2026-11-08 ｜ **状态** 8 包全落地并自测；**G10 未判**
**形态** 指针式：逐包定义与偏差不改写，真源见 §2

---

## 1. 交付结构（8 包）

| 包 | 交付物 |
|---|---|
| UI-01 菜单 IA | `config/navigation.ts`（7 组 / `exclude` / `section` / `isSectionBreak`）、`components/AppNav.vue` |
| UI-02 卡牌端点 | `ProjectCard*` 五个后端文件，**一条 SQL** 聚合五表；`GET /api/projects/cards` |
| UI-03 工作台总览 | `api/projectCards.ts`、`components/ProjectCard.vue`、`views/WorkbenchView.vue`；`/workbench` 成默认落地页 |
| UI-04 顶栏 | `components/AppHeader.vue`（四段 + 3 分隔线 + 28px 栅格 + 独立项目选择器 + 登出二次确认） |
| UI-05 主题二态 | `AppHeader` 取 `theme.resolved` 反面；`system` 收进 `AppearanceDialog` |
| UI-06 滚动条 | `styles/main.css` 5 类容器 8px 细条 + Firefox 三属性 + `scrollbar-gutter` + `@media screen` |
| UI-07 对比度 | 只调派生色、不动阈值；`check_contrast` 14 组 280 项 FAIL 0 |
| UI-08 收口 | `docs/evidence/v3.0.1/v23/UI-01..08/` |

## 2. 真源指针

| 内容 | 真源 |
|---|---|
| 逐包定义（验收命令 / 判据锚点） | `docs/plan/v2.3-DEV-PLAN-增补草案-待评审-20261108.md` v1.2 §2 |
| 8 道质量门实测 | 同上 §7.2 |
| 偏差清单 D-1..D-12 | 同上 §7.3 |
| 收口证据索引 | `docs/evidence/v3.0.1/v23/UI-08/README.md` |

## 3. 实测读数

| 项 | 读数 |
|---|---|
| 前端全量 | `53 files / 370 tests` |
| 本批 5 个定向 spec | `60 tests` |
| 后端 | `Tests run: 1062` |
| 编辑器 | `24 files / 219 tests` |
| 类型检查 / lint | `vue-tsc` 0 error；`[lint] PASS` |
| 全量门禁 | 19 步 **18 PASS / `FAILS=1`**（第 13 步浏览器回归 `fetch failed`） |

## 4. 偏差要点（全量见 §7.3）

| 编号 | 偏差 |
|---|---|
| D-4 | `window=30d` 与 `7d` 读数完全相同（样本全在 7 天内），窗口参数只能靠 `90d → 400` 证伪验证 |
| D-5 | `myRole` 原样透传库值，同批数据里 `admin` 与 `ADMIN` 并存，未做归一 |
| D-6 | 计划里的 `ProjectSwitcher.vue` / `UserMenu.vue` 两个组件未建，收进 `AppHeader` 单组件 |
| D-7 | 计划外新增 `AppNav.vue`（为让形态 / 展开策略 / 分区线可组件测试） |
| D-9 | 浏览器判据（像素读数 / 无横滚 / 点击可达 / 走查）**未验**，CDP 不可用且按指示未重启 |
| D-10 | UI-08 的验收命令写的是 `FAILS=0`，实际 `FAILS=1`，故 UI-08 判为部分完成 |

## 5. 明确不做

不 bump 版本、不建 tag、不改 WBS 分母、不判 G10；不把未验的像素判据写成通过。
