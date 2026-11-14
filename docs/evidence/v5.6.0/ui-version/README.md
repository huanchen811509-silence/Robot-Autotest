# v5.6.0 顶栏模式选中态与「关于 / 版本迭代」落号取证（2026-11-14）

两项用户可见改动：**顶栏「全局 / 项目」选中态改强调色**、**落号 5.6.0 并刷新关于页与版本迭代页**。
本文只记录实测读数与复现方式，不复述推断。

## 一、选中态：改前 vs 改后

| 项 | 改前 | 改后 | 判据 |
|---|---|---|---|
| 选中项背景 | `var(--panel)`（浅色主题下与轨道几乎同色） | `var(--accent)` 实心 | computed `background-color` |
| 选中项文字 | `var(--text)` | `var(--accent-contrast)` + 字重 600 | computed `color` / `font-weight` |
| 未选中项 | 无悬停反馈 | 悬停给 `var(--panel)` 底 + 正文色 | `:hover:not(.is-active)` |
| 过渡 | 无 | `background-color` / `color` 160ms | 视觉连续性 |

选中态与未选中态的颜色对由 `--accent` / `--accent-contrast` 承担，浅深两套主题各自校准，
不引入新色值（同时避开 `check_colors.py` 的硬编码红线）。

## 二、浏览器实测（computed style，非源码推断）

| 主题 | 当前模式 | 选中项 | 背景 | 文字 | 未选中项文字 |
|---|---|---|---|---|---|
| 深色 | 项目 | 项目 | `rgb(63, 167, 255)` | `rgb(6, 19, 31)` | `rgb(139, 155, 176)` |
| 浅色 | 全局 | 全局 | `rgb(21, 103, 201)` | `rgb(255, 255, 255)` | `rgb(91, 107, 124)` |

两种模式的互斥性也实测过：切到全局后 `项目` 的 `is-active` 消失、`aria-pressed` 变 `false`，
不会出现「两个都亮」。

证据：[`raw/mode-accent-dark.json`](raw/mode-accent-dark.json)、
[`raw/mode-switch-dark.png`](raw/mode-switch-dark.png)、
[`raw/mode-switch-light.png`](raw/mode-switch-light.png)。

## 三、关于页 / 版本迭代页

| 页面 | 数据源 | 实测显示 | 证据 |
|---|---|---|---|
| 关于页 版本 | `/api/system/build-info` | `5.6.0`（来源标记「仓库 VERSION 文件」） | [`raw/about-5.6.0.png`](raw/about-5.6.0.png) |
| 关于页 最近更新 | `/CHANGELOG.md` 首条 | `v5.6.0（2026-11-14）`，列 4 条 | 同上 |
| 版本迭代页 首条 | `/CHANGELOG.md` | `v5.6.0（2026-11-14）` + 「当前版本」标记 | [`raw/changelog-5.6.0.png`](raw/changelog-5.6.0.png) |
| 版本迭代页 小节 | 同上 | 新增 5 / 变更 5 / 修复 4 / 破坏性变更 3 | [`raw/version-ui.json`](raw/version-ui.json) |

一致性探针 `scripts/about-changelog-probe.mjs` **7 判据全 PASS**（`FAILS=0`），
`--selfcheck` 抹掉「最近更新」区块后 4 条判据转红（`FAILS=4`），证明判据不是只会说绿。
读数见 [`raw/about-changelog-probe.json`](raw/about-changelog-probe.json)。

## 四、版本五处同源

| 位置 | 值 |
|---|---|
| `VERSION` | 5.6.0 |
| `build-info.properties` | 5.6.0（source=file） |
| `docs/contracts/openapi.json` `info.version` | 5.6.0 |
| `/api/_ping` / `/api/system/build-info` 运行期 | 5.6.0 |
| 入口托管 `/CHANGELOG.md` | 5.6.0 |

`python3 tools/check_version_consistency.py` 输出 `VERSION_CONSISTENCY PASS (0 项)`。

## 五、落号带来的两处连带修复

| 步骤 | 现象 | 处置 |
|---|---|---|
| 30 delivery_bundle | 旧交付包 MANIFEST 写 `5.0.0`，与 `VERSION=5.6.0` 不一致 | `scripts/package-release.sh` 重打 `robot-autotest-5.6.0-42d925b-linux-x64.tar.gz`（399 文件 / 49060373 B） |
| 13 browser_regression | `/` 被解析到 `/g/projects`，2 条断言红 | 取证性质的探针把账号 `ui_mode` 留在 `global` 且写了服务端偏好；用公开接口 `PUT /api/me/ui-mode` 改回 `project` 后复跑 `fails=0`。**不是产品缺陷**，但暴露出「探针会污染后续回归」这条纪律，见下 |

## 六、复现命令

```bash
bash scripts/build.sh                      # 构建（含 CHANGELOG → public 同步）
bash scripts/package-release.sh            # 交付包（VERSION 变了必须重打）
node scripts/about-changelog-probe.mjs     # 关于 / 版本迭代一致性
python3 tools/check_version_consistency.py # 五处同源（需 QA_E2E_PASSWORD 才能查运行期）
```

> **纪律提醒**：任何在真实浏览器里切过模式的探针，跑完必须把 `ui_mode` 改回 `project`。
> 该值既写 `localStorage` 也写服务端 `app_user.default_ui_mode`，残留会让
> `browser-regression` 的 `/` 路由断言变成假红（本轮已实际踩到一次）。
