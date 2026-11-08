# UI-05 主题快捷二态与外观收口证据（v2.3 / FR-UI-16）

## 1. 问题与改法

原顶栏主题按钮是**三态循环**（system → light → dark → system）。用户在 dark 下想回「跟随系统」要点两次，
中间还会闪一次 light；而且按钮图标表示「当前态」，点击结果无法预判。

改法：快捷按钮只做**二态**，目标值取**当前解析后主题**的反面：

```ts
const themeTarget = computed<ResolvedTheme>(() =>
  theme.resolved.value === 'dark' ? 'light' : 'dark',
)
```

用 `resolved` 而不是 `mode`：`system` 模式下用户看到的可能是暗色，此时点按钮应该是「切到亮色」，
而不是「跳到 system 的下一档」——后者会让按钮看起来没反应。

`system` 没有消失，只是收进外观弹层（`AppearanceDialog.vue:28` 仍为 `['system','light','dark']`），
弹层管「全部外观选项」，快捷按钮管「最高频的那一次切换」。

## 2. 命令原文与真实输出

```bash
cd /home/evergreen/robot-autotest/frontend
npx vitest run src/components/AppHeader.spec.ts
```

```text
✓ src/components/AppHeader.spec.ts (11 tests) 229ms
Test Files  1 passed (1)
      Tests  11 passed (11)
```

其中与本包直接相关的 2 条：

| 用例 | 断言 |
|---|---|
| 二态切换 | 初始 `data-theme=light` → 点一次 `dark` → 再点一次 `light`；末态 `theme.mode !== 'system'` |
| 可读名称 | `aria-label` = `theme.switchTo` 且 mode 参数是**目标**（dark），不是当前值 |

## 3. 未改动项（避免误以为重做了主题系统）

| 项 | 状态 |
|---|---|
| 三模式（system 实时跟随 OS） | 未改，`useTheme` 的 `matchMedia` 监听保持 |
| 6 个强调色预设 | 未改（`check_accent_presets.py` PASS，见 UI-07） |
| 2 档密度 | 未改 |
| 首帧防闪（`index.html` 内联脚本） | 未改 |
| 外观弹层（主题三选 + 强调色六选 + 密度二选） | 未改，仍为唯一「全量选项」入口 |

## 4. 遗留

| 项 | 说明 |
|---|---|
| 外观弹层三选未加组件测试 | 只有源码事实（`THEME_MODES` 常量），本轮未新增该组件测试；不构成行为风险，但属证据缺口 |
| 二态按钮在 `system` 下首次点击的真实视觉 | 单测断言的是 `data-theme` 属性值，不是屏幕像素；视觉效果需门禁第 13 步截图，本轮 CDP 不可用 |
