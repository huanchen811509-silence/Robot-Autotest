# UI-03 证据（v2.3 / v3.0.1）

实现工作台项目总览卡牌页：`/workbench` 一次请求拉全部项目卡牌，支持本地搜索 / 排序，四态齐全，单卡动作「进入」与「设为当前」语义分离。

> 状态：已回填（2026-11-08，命令真实执行，原始日志见 `docs/evidence/v3.0.1/v23/UI-03/raw/`）。本文件所有读数均来自真实日志，无占位、无编造。

## 交付文件

| 文件 | 类型 | 说明 |
|---|---|---|
| `frontend/src/api/projectCards.ts` | 新增 | `ProjectCard` 类型（可空项用 `null` 联合）+ `projectCardsApi.list(window, current)` |
| `frontend/src/components/ProjectCard.vue` | 新增 | 单卡三层字段；进入 = setProject + push('/overview')；设为当前 = 只 setProject |
| `frontend/src/views/WorkbenchView.vue` | 新增 | 页头 / 工具条 / 四态 / 卡牌栅格；`onMounted` 只调一次接口 |
| `frontend/src/views/WorkbenchView.spec.ts` | 新增 | 5 条：渲染 N 张、搜索过滤、两种排序、空态、错误态 + 只请求一次 |
| `frontend/src/components/ProjectCard.spec.ts` | 新增 | 6 条：三层字段、null 显示「—」、进入、设为当前、当前标记、停用态 |
| `frontend/src/locales/zh-CN.ts` | 修改 | 新增 `workbench.enabled: '启用'` |
| `frontend/src/locales/en-US.ts` | 修改 | 新增 `workbench.enabled: 'Enabled'`（与中文等量、不同值） |

## 命令原文

```bash
cd frontend && npx vitest run src/views/WorkbenchView.spec.ts src/components/ProjectCard.spec.ts
cd frontend && npx vue-tsc --noEmit
python3 tools/check_i18n.py
python3 tools/check_tables.py
python3 tools/check_tokens.py
python3 tools/check_colors.py
bash scripts/lint.sh
```

## 真实输出（2026-11-08 09:48 +08:00）

### 定向 spec

```
 ✓ src/components/ProjectCard.spec.ts (6 tests) 107ms
 ✓ src/views/WorkbenchView.spec.ts (5 tests) 144ms

 Test Files  2 passed (2)
      Tests  11 passed (11)
   Start at  09:48:38
   Duration  2.41s
```

`VITEST_RC=0`，原始日志 `raw/vitest.log`。

### 类型检查

```
$ npx vue-tsc --noEmit
（输出为空，日志文件 0 字节）
VUE_TSC_RC=0
```

原始日志 `raw/vue-tsc.log`。

### 四个静态守门

```
check_i18n : [1/5] 语言包解析：zh-CN 1206 键 / en-US 1206 键
             [3/5] 源码扫描：120 个文件，硬编码中文 0 处
             [RESULT] 通过：键集相等 / 无硬编码中文 / 无同值漏译 / 键引用完整 / 枚举全覆盖
check_tables: [PASS] 表格规范：41 处数据网格全部走壳；原生 <table> 仅出现在白名单 3 个文件
check_tokens: [PASS] var 引用 0 悬空；px 字面量 1078 处全部在白名单内（40 个取值）
check_colors: [PASS] 177 个文件无硬编码色值
```

四条退出码均为 0，原始日志 `raw/check_i18n.log` `raw/check_tables.log` `raw/check_tokens.log` `raw/check_colors.log`。

### lint

```
[secret-scan] PASS（无命中，双向自检已过）
[lint] frontend: npm run lint      （ESLint 退出码 0）
[lint] backend: mvn -B -q checkstyle:check
[lint] backend: mvn -B -DskipTests compile
[PASS] 字体子集 1103 字 / 188907 B gzip，未超 800 KiB 预算
[PASS] 品牌：favicon 可辨 / 侧栏与登录页均有标记 / 版本号只在关于（取自 /_ping）
[lint] PASS
```

`LINT_RC=0`，原始日志 `raw/lint.log`。

## 判据逐条

| 判据 | 落地位置 | 命中它的证据 | 结果 |
|---|---|---|---|
| 三层字段齐全（必有 / 概要 / 细节） | `ProjectCard.vue` | `ProjectCard.spec.ts` 三层字段用例断言 813 / 3 / 97.0% / 0 / 2 / 私有 / 管理员 | PASS |
| 空值容错：null 显示「—」不显示 0 | `ProjectCard.vue` + `utils/format.ts` | `passRateBp` / `lastRunAt` / `myRole` 三项为 null 时逐项断言 `—`，并断言通过率不是 `0.0%` | PASS |
| 「进入」= setProject + push('/overview') | `ProjectCard.enter()` | 点击后断言 `projectId === 7` 且 `push` 收到 `/overview` | PASS |
| 「设为当前」= 只 setProject，不跳转 | `ProjectCard.setCurrent()` | 点击后断言 `projectId === 7` 且 `push` 调用次数为 0 | PASS |
| 当前卡 `aria-current="true"` 且按钮禁用 | `ProjectCard.vue` 模板 | 断言根节点 `aria-current` 为 `true`、`card-set-current` 的 `disabled` 为 `true`、文案含「当前项目」 | PASS |
| 停用项目显示「已停用」 | `ProjectCard.vue` | 断言文案含「已停用」且不含「启用」 | PASS |
| 一次请求（FR-UI-14） | `WorkbenchView.load()` | 断言 `list` 调用 1 次、参数 `('7d', 1)`；搜索 / 排序后仍为 1 次 | PASS |
| 搜索按名称 / 代码本地过滤 | `WorkbenchView.filtered` | 输入 `beta` 后卡牌 3 → 1，计数变「共 1 个项目」 | PASS |
| 两种排序首项不同 | `WorkbenchView.filtered` | 默认最近活跃首项 Zeta 项目；切名称排序首项 Alpha 项目 | PASS |
| 空数组显示空态 | `WorkbenchView.vue` | 断言 `workbench-empty` 存在且卡牌数为 0 | PASS |
| 失败不显示假数据 | `WorkbenchView.vue` | reject 后断言错误块存在、卡牌数 0、`project-cards` 容器不存在 | PASS |
| 栅格与色值合规 | `WorkbenchView.vue` / `ProjectCard.vue` | `check_tokens` / `check_colors` 全 PASS；栅格用 `minmax(340px, 1fr)`，颜色只引用 `var(--…)` | PASS |
| 无原生 `<table>` | 两个新组件 | `check_tables` PASS，新文件不在白名单内 | PASS |

## 附带改动（跨任务，需评审）

| 改动 | 原因 | 读数 |
|---|---|---|
| `tools/px-allow.txt` 增加 `130px` | 该值来自 `main.css` 的 `.app-project-select { max-width: 130px }`（UI-04 顶栏改造，本任务无权修改 `main.css`），`check_tokens` 因此 FAIL；按脚本提示执行 `--write-px-allow` | diff 仅新增 `130px`（另把游离的 `32px` 归位），复核 PASS |
| 重新生成中文字体子集 `frontend/src/assets/fonts/noto-sans-sc-ui.woff2` | v2.3 新文案（含 `sortRecent` 的「活跃」等）有 8 个字不在旧子集里，`subset_font.py --check` 报 FAIL 并阻断 lint | 1103 字全覆盖；gzip 154316 B；仅一个 woff2 变化（+1336 B），预算 800 KiB 未触及 |

## 边界与未覆盖项（如实登记）

- 仓库**没有安装 `@vue/test-utils`**（`frontend/package.json` 无此依赖），两个 spec 沿用同目录既有范式：`createApp(Component, props) + pinia + i18n + happy-dom` 真实挂载并派发原生事件，未新增依赖。
- 后端 `GET /api/projects/cards` 由另一人并行实现，本包只按已定稿契约编码，**未做真实联调**（前端单测里接口被 mock）。
- 卡片内的「进入」在组件内完成 `setProject + push`，因此点击「进入」后当前项目标记不留在本页（会直接跳走），这是有意的语义差异。
- 未实现分页 / 虚拟滚动：项目数量级为「几十」时 `auto-fill` 栅格足够，契约也未提供分页字段。
- 前端**全量**单测当前为 `Test Files 1 failed | 50 passed (51)`、`Tests 8 failed | 333 passed (341)`，8 条失败全部落在 `src/config/navigation.spec.ts`（断言一级恰好 8 组，实际 `navigation.ts` 已是 v2.3 的 7 组）。该文件与本包无关且属禁止修改范围，已单独反馈；本包两条定向 spec 全过。
