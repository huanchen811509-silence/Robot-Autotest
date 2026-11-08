# EVID-04 · 看板四态枚举 + 原始流弹窗 / JSON 下载

| 项 | 值 |
|---|---|
| 版本 / 波次 | v3.0.1 · v2.2 · M3 |
| 日期 | 2026-11-08 |
| 判据出处 | v2.2 DEV-PLAN EVID-04（看板枚举 + 弹窗 / 下载） |
| 改动文件 | `frontend/src/views/CaseBoardView.vue`、`caseBoard.ts`、`CaseBoardView.spec.ts`、`locales/zh-CN.ts`、`locales/en-US.ts` |
| 新增证据 | `docs/evidence/v3.0.1/v22/EVID-04/README.md`（本文件） |
| 未改动 | `backend/`、`scripts/gates.sh`、`scripts/db-audit.sh`、`docs/plan/v2.2-*`、`docs/release/`、`db/` |

## 1. 四态枚举的 TypeScript 定义

`frontend/src/views/caseBoard.ts`：

```ts
export const CURRENT_STATUSES = ['passed', 'failed', 'untested', 'missing_evidence'] as const
export type CurrentStatus = (typeof CURRENT_STATUSES)[number]
export type BoardStatus = CurrentStatus
```

`CaseBoardRow.current_status` 的类型是 `BoardStatus | null`；`historical_status` 保留 `string | null`，仅用于兼容外部导入的历史结论，不参与当前轮四态。裸 `export type BoardStatus = string` 已删除，spec 用 `expect(logicSource).not.toContain('export type BoardStatus = string')` 锁死。

## 2. 真实执行的命令

全部命令在 WSL 权威副本 `/home/evergreen/robot-autotest` 真实执行；外层调用形式为 `wsl.exe -d Ubuntu-24.04 bash -lc '...'`，下面列的是等价的 WSL 内命令。

```bash
cd frontend && npx vitest run src/views/CaseBoardView.spec.ts
bash scripts/lint.sh
python3 tools/check_i18n.py
python3 tools/check_tables.py
python3 tools/check_tokens.py
python3 tools/check_css_vars.py
cd frontend && npx vue-tsc --noEmit
cd frontend && npx vitest run
git diff --check
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py
```

`lint.sh` 输出量大，为取尾部结论另用包装命令 `bash scripts/lint.sh > /tmp/lint-evid04.log 2>&1 && echo LINT_EXIT_0 || echo LINT_EXIT_NONZERO; tail -50 /tmp/lint-evid04.log` 重跑一次（脚本本体未改，`LINT_EXIT_0` 即脚本退出码 0）。

## 3. 真实输出摘要

定向单测（2026-11-08 03:39:39 +08:00）：

```
 RUN  v3.2.7 /home/evergreen/robot-autotest/frontend
 ✓ src/views/CaseBoardView.spec.ts (8 tests) 6ms
 Test Files  1 passed (1)
      Tests  8 passed (8)
```

全量前端单测（03:40:56 +08:00，门禁第 02 步）：

```
 Test Files  49 passed (49)
      Tests  330 passed (330)
```

`bash scripts/lint.sh`（门禁第 06 步）：

```
LINT_EXIT_0
[lint]   - tools/check_tables.py（数据网格 ↔ DataTable 壳 / 原生 table 白名单）
[PASS] 表格规范：41 处数据网格全部走壳；原生 <table> 仅出现在白名单 3 个文件
[lint]   - tools/check_brand.py（favicon 可辨 / 品牌区挂载 / 版本号归位）
[PASS] 品牌：favicon 可辨 / 侧栏与登录页均有标记 / 版本号只在关于（取自 /_ping）
[lint] PASS
```

中间省略 check_colors / check_contrast / check_states / check_w7_tokens / check_icons / check_api_prefix / check_wbs_consistency 等步骤的 PASS 行；末行为 `[lint] PASS`，退出码 0。

`python3 tools/check_i18n.py`：

```
[1/5] 语言包解析：zh-CN 1169 键 / en-US 1169 键
[2/5] 键集相等：通过
[3/5] 源码扫描：116 个文件，硬编码中文 0 处
[4/5] 同值漏译与键引用：通过
[5/5] 受控词表：27 个值逐个核对，全部有键
[RESULT] 通过：键集相等 / 无硬编码中文 / 无同值漏译 / 键引用完整 / 枚举全覆盖
```

`python3 tools/check_tables.py`：

```
扫描 64 个 .vue；DataTable 壳实例 41 处 / 29 个文件
[PASS] 表格规范：41 处数据网格全部走壳；原生 <table> 仅出现在白名单 3 个文件
```

`python3 tools/check_tokens.py`：

```
扫描 67 个文件：108 个 token 定义，386 处不同引用
[PASS] var 引用 0 悬空；px 字面量 1034 处全部在白名单内（39 个取值）
```

`python3 tools/check_css_vars.py`（门禁第 15 步）：

```
[OK] CSS 变量引用全部有定义（已定义 108 个）
```

`cd frontend && npx vue-tsc --noEmit`（门禁第 03 步）：退出码 0，stdout / stderr 均为空。
`git diff --check`（门禁第 10 步）：退出码 0，包装输出 `GIT_DIFF_CHECK_OK`。

## 4. 判据逐条命中

| 判据 | 命中它的断言 | 要点 | 结果 |
|---|---|---|---|
| 1 四态枚举 | `当前轮状态收口为 4 个枚举值，且四态各有样本` | `CURRENT_STATUSES` 恰好 4 值、`as const`、无裸 string；四行样本各占一态 | PASS |
| 2 双计数保持 | `历史通过数不写进当前列（EVID-04 新增锁死断言）` + MENU-03 三用例 | 历史通过 2 不进当前列；`currentPass=1`、`currentUntested=1`；MENU-03 三用例描述 / 期望未改 | PASS |
| 3 原始流弹窗 | `弹窗原始流五要素与后端 EvidenceLogWriter 口径一致` + 源码探针 | `[node-a]`、`#步骤2说明`、`$ sh -c`、stdout / stderr、`exit_code=3`；`data-testid="evidence-dialog"` 与 `evidence-log` | PASS |
| 4 JSON 下载 | `有证据可下载真实 JSON；无证据返回 null，下载不可用` + 源码探针 | Blob + `URL.createObjectURL` + `anchor.download`；无证据返回 `null`、`disabled`、`t('caseBoard.evidence.missing')` | PASS |
| 5 spec 覆盖 1–4 | 8 条用例（MENU-03 三条 + EVID-04 五条） | 四态样本、弹窗内容、下载可用性、双计数隔离、无证据态全部有断言 | PASS |

## 5. 结论

EVID-04 五条判据全部被真实单测与静态守门命中：定向 8/8、全量 330/330、`lint.sh` PASS、i18n / tables / tokens / css_vars 四脚本全 PASS、`vue-tsc` 与 `git diff --check` 退出码 0。两套语言包键数由 1158 → 1169 且保持相等；数据网格仍走 `DataTable` 壳、无原生 `<table>`；新增 px 全部复用白名单档位；源码硬编码中文 0 处。页面行集初始仍为空（不编造业务数据），证据列、弹窗、下载逻辑只对真实 `evidence` 生效，无证据时显示「证据缺失」且下载按钮禁用，不用占位数据假装可下载。

## 6. 边界（如实说明）

- 前端未安装 `@vue/test-utils`，`CaseBoardView.spec.ts` 沿用 MENU-03 的「纯函数 + 路由 + 源码字符串断言」范式，不挂载组件；弹窗 DOM、`Blob` / `<a download>` 的运行时行为由源码探针 + 纯函数（`renderEvidenceLog` / `buildEvidenceJson` / `hasEvidence`）覆盖，未做真实浏览器点击回归。
- 为让 `current_status` 收口为四态枚举并同时通过门禁第 03 步 `vue-tsc --noEmit`（`tsconfig.json` 的 `include` 含 `src/**/*.spec.ts`），MENU-03 第 2 条用例夹具里两个中文 `'未测试'` 替换为语义等价的 `'untested'`；用例描述、断言、期望值（含 `currentUntested=3`、`currentPass=0`、历史通过 >0）一字未改，仍全绿。历史列的中文 `'通过'` / `'失败'` 原样保留，由 `summarizeBoard` 的兼容词表判定。
- 弹窗原始流里的 `#步骤N说明` 行由 `t('caseBoard.evidence.stepLine', { no, desc })` 生成，中文模板存于语言包（zh / en 各一份），与后端 `EvidenceLogWriter.write` 的字节口径一致；`caseBoard.ts` 不含任何中文字面量。
- 未改 `backend/`、`scripts/gates.sh`、`scripts/db-audit.sh`、`docs/plan/v2.2-*`、`docs/release/`、`db/`；未 `git commit`、未 bump 版本。全门禁 19 步已于 2026-11-08 08:19 整体复跑，19 步全 PASS、`GATES_RESULT FAILS=0`（含第 13 步浏览器回归，真实 Chrome 9222 + :8001），日志 `docs/evidence/gates/logs/00-full-run.log`；本包前端相关的 02 / 03 / 06 / 09 / 10 / 15 步读数与整轮一致。

## 7. Markdown 表格机检

写完 Markdown 后在仓库根真实执行：

```bash
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/evidence/v3.0.1/v22/EVID-04/README.md
```

默认文件集真实输出：

```
扫描 7 份文档 / 157 个表格块 / 1252 个数据行
[PASS] 表格列数一致性：全部表格块内列数一致（转义竖线已按非分隔符处理）
```

本 README 单独传参复跑的真实输出：

```
扫描 1 份文档 / 3 个表格块 / 22 个数据行
[PASS] 表格列数一致性：全部表格块内列数一致（转义竖线已按非分隔符处理）
```

结论：默认文件集与本 README 的表格列数一致性均 PASS。

## 8. 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 本包定向 spec | CaseBoardView.spec.ts 8 tests 全过（同批 Test Files 6 passed、Tests 47 passed、`VITEST_RC=0`） | docs/evidence/v3.0.1/v22/raw-20261108/frontend-targeted.log |
| 全量前端单测 | Test Files 49 passed、Tests 330 passed（门禁第 02 步） | docs/evidence/gates/logs/02-frontend_test.log |
| 类型检查 | `npx vue-tsc --noEmit` 退出码 0，stdout 与 stderr 均为空（门禁第 03 步） | docs/evidence/gates/logs/03-type_check.log |
| 表格与令牌机检 | `check_tables.py` 41 处数据网格全部走壳；`check_tokens.py` var 引用 0 悬空；`check_css_vars.py` 已定义 108 个 | docs/evidence/gates/logs/06-lint.log 与 15-css_vars.log |
| i18n 机检 | zh-CN 1169 键 / en-US 1169 键，键集相等，硬编码中文 0 处 | docs/evidence/gates/logs/06-lint.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |
| 三处单测 | backend 1050、frontend 330、editor 219，failures=errors=0；基线 floor 1016 / 325 / 219（只增不减） | docs/evidence/gates/logs/19-evidence_integrity.log |
| 表格机检 | 默认文件集与本 README 单文件传参均 PASS（见 §7） | 本轮 `python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py` 实跑输出 |
