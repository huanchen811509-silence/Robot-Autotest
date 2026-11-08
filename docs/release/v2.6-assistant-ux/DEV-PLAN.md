# v2.6 工作助手体验 · ④ DEV-PLAN

**文档编号** RA-REL-2026-026-D ｜ **版本** v2.6-assistant-ux ｜ **批次** B14（待落号） ｜ **门禁** G13（未判）
**日期** 2026-11-08 ｜ **状态** 5 包全部完成并自测；**不进 130 包 WBS 分母**

---

## 1. 包与交付

| 包 | 内容 | 主要文件 | 验收命令 | 状态 |
|---|---|---|---|---|
| V26-01 | 宽度真接布局：CSS 变量 + `clampWidth` + 持久化 | `styles/theme.css`、`styles/main.css`、`App.vue`、`useAiDrawer.ts` | `npx vitest run src/composables/useAiDrawer.spec.ts` | 完成 |
| V26-02 | 拖拽手柄（指针 + 键盘 + 档位按钮三路径） | `components/AiPanelDrawer.vue` | `npx vitest run src/components/AiPanelDrawer.spec.ts` | 完成 |
| V26-03 | 后端 agent 清单接口（非管理员）+ 按次覆盖 | `AiAssistantConfigService`、`AiPanelService`、`AiPanelController` | `mvn -Dtest=AiAssistantConfigServiceTest,AiChatRequestBindingTest test` | 完成 |
| V26-04 | 抽屉下拉 + 回落明示 + 偏好持久化 | `AiPanelDrawer.vue`、`useAiDrawer.ts`、`api/aiPanel.ts`、locales | 同上 + 真实 HTTP | 完成 |
| V26-05 | 输入输出放大 + 文档回写 | `AiPanelDrawer.vue`、`docs/release/v2.6-assistant-ux/` | `bash scripts/gates.sh` | 完成 |
| V26-06 | 退出意图标记：退出后不被记住的密码自动登回（v1.2 补） | `useLogout.ts`、`LoginView.vue`、两份 spec | `npx vitest run src/views/LoginView.spec.ts src/composables/useLogout.spec.ts` | 完成 |

## 2. 实施顺序（为什么是这个顺序）

1. **先让宽度「真」起来**（V26-01）：把布局接到 CSS 变量是拖拽能生效的前提；
   反过来说，先做拖拽再接变量，会出现「拖了但页面不动」——正是 v2.5 之前那个假功能的复现路径。
2. **再做交互**（V26-02）：手柄、键盘、按钮都只负责「算出一个目标宽度并 emit」，
   钳制只在 `clampWidth` 一处，避免三条路径各写一份边界。
3. **后端先行**（V26-03）：清单接口与覆盖参数不依赖前端；先做完才能用真实 HTTP 证明「选择生效」。
4. **前端接上**（V26-04）：下拉、回落提示、偏好持久化。
5. **改版式**（V26-05）：最后动布局尺寸，避免前面每改一次测试都要重排。

## 3. 关键实现点

| 点 | 做法 | 为什么 |
|---|---|---|
| 宽度单一真源 | `--ai-drawer-w` 在 `theme.css :root` 声明默认值，`App.vue` 内联覆盖 | CSS 里必须有声明，否则机检判悬空；也保证 JS 未跑时有可用宽度 |
| 钳制集中 | `clampWidth()` 导出，四条路径共用 | 边界只写一次，不会「拖拽能到 800、按钮只能到 520」 |
| 拖拽监听挂 window | `pointerdown` 后挂 `pointermove` / `pointerup`，松手移除 | 鼠标很快移出手柄，挂手柄上会丢事件 |
| 清单不含凭据 | 只回 `key/name/models/defaultModel/modelFlag/injectable/configured` | 抽屉面向所有能访问项目的人，罗列凭据形态本身就是攻击面 |
| 回落而非报错 | 选中不可用 → 用首选，响应标 `cliFallback` | 本地偏好会过期；报错会让抽屉彻底不可用 |
| 换 agent 清模型 | `setAgent()` 内部调 `setModel('')` | 跨家的模型名基本不存在，留着必然报错 |
| 输入框高度 | `resizeInput()` 按 `scrollHeight` 长高，封顶 240 | 长提示词要看得见上文，又不能把历史区挤没 |

## 4. 质量门读数

| 门 | 读数 |
|---|---|
| 后端 `AiAssistantConfigServiceTest` | **13 tests / 0 failure**（原 7 + 新增 6） |
| 后端 `AiChatRequestBindingTest` | **2 tests / 0 failure** |
| 前端 `AiPanelDrawer.spec` | **16 tests / 0 failure**（原 6 + 新增 10） |
| 前端 `useAiDrawer.spec` | **7 tests / 0 failure**（原 3 + 新增 4） |
| 前端全量 | **55 files / 393 tests** |
| 后端全量 | **1088 tests / 0 failure** |
| 编辑器 | 24 files / 219 tests |
| `vue-tsc` | 0 error |
| `check_tokens` | 1122 处 px 全在白名单（39 个取值） |
| `check_css_vars` | 115 个定义 / 0 悬空 |
| `check_colors` | 185 文件 / 0 硬编码色 |
| `check_i18n` | 124 文件 / 0 硬编码中文 / 键集相等 |

## 5. 偏差

| 编号 | 偏差 | 原因与处置 |
|---|---|---|
| D-1 | 原打算把宽度定义在 `main.css` | `check_css_vars` 判 `var(--ai-drawer-w)` 悬空：内联样式设的变量不算「定义」。改到 `theme.css :root` 声明默认值 |
| D-2 | 键盘 Home/End 原用组件内 `setWidth` | 只改 composable 状态、不 emit，父组件的 `width` prop 不变 → 布局不动。改为统一 emit |
| D-3 | 拖拽初版把监听挂在手柄上 | 鼠标移出手柄即丢事件；改挂 window 并在 `pointerup` 清理 |
| D-4 | 验收时发现对话一律 DEGRADED | 本机 `FEATURE_AI_REAL_CLI` 从未开启（先于本轮）。以 systemd drop-in 临时打开，**不改产品默认值** |
| D-5 | 非管理员 200 无法取证 | 本机已知三个账号都是 admin；只做代码审阅 + 管理员 200，如实记 C5 未满足 |

## 修订记录

| 版本 | 日期 | 修订内容 | 授权 |
|---|---|---|---|
| v1.0 | 2026-11-08 | 首版：5 包 + 实施顺序 + 质量门读数 | 大师本轮 3 条指令 |
| v1.1 | 2026-11-08 | ⑦ 回写：补 D-3..D-5 三条实测偏差 | 同上 |
