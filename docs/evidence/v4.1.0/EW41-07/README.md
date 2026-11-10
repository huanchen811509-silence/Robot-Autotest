# EW41-07 · 横向回归 / 全量门禁 / ⑦ 回写（4.1 收口）

> 包：`W41-07`；覆盖 4.1 全部 FR；不含落号与发版（当时 `VERSION` 仍为 `3.11.0`，按 `DEV-PLAN §5` 须大师书面确认后才 bump）。
> **补记（2026-11-10）**：大师已书面确认，4.0 / 4.1 / 4.2 三批**一次性落号 `4.2.0`**（不单独立 `4.1.0` 版本目录），
> 发版产物见 `docs/release/v4.2.0/`。

## 1. 结论

| 项 | 结果 | 证据 |
|---|---|---|
| 全量门禁 32 步 | `GATES_RESULT FAILS=0`（32/32 PASS） | `gates.log` |
| 门禁自检 | `SELFCHECK_EXIT=0`，`SELFCHECK_RESULT FAILS=0`；含第 13 步「注入横滚被判红」与第 31/32 步删步必变红 | `gates-selfcheck.log` |
| 发布文档机检 | `22 个版本 / FAIL 0 / WARN 0 / 未启动 11`（未启动=只写了 `05-TEST-REPORT` 的目录，按 INFO 计） | `release-docs.log` |
| 文档表格机检 | `7 份文档 / 159 个表格块 / 1282 个数据行`，列数一致 PASS | `md-tables.log` |
| 版本四副本 | 收口当时 `VERSION=3.11.0`、build-info、入口 CHANGELOG、`openapi.json` 一致，`VERSION_CONSISTENCY PASS (0 项)`；落号后复议为 `4.2.0`（见 `docs/release/v4.2.0/05-TEST-REPORT.md` §5） | `version-consistency.log` |
| i18n 与对比度 | `14 组（主题 × 强调色）共 280 项` 对比度全部达标，键集中英相等 | `i18n.log` |

## 2. 复现命令

```bash
bash scripts/gates.sh
bash scripts/gates.sh --selfcheck     # 必须在登录 shell 下调用：selfcheck 第 13 步直调 node
python3 tools/check_release_docs.py
python3 docs/evidence/v1.0/policy-20261106/check_md_tables.py docs/plan/v4.x-*.md
python3 tools/check_version_consistency.py
```

## 3. 本轮修掉的门禁阻断

| 步 | 首次失败 | 处置 |
|---|---|---|
| `06-lint` | `views/WorkspaceView.vue 出现原生 <table> 3 处` | 把三张原生表改为 `DataTable` 壳（`bundleColumns` / `reportItemColumns` / `jobColumns`），未扩白名单 |

## 4. `NOT-RUN`（如实保留）

| 项 | 原因 |
|---|---|
| `docs/release/v4.1.0/` 六份产物 | **不单独建**：2026-11-10 大师确认三批一次落号，4.0/4.1/4.2 的产物统一放 `docs/release/v4.2.0/`；不建 `v4.1.0` 目录不是缺项 |
| `VERSION` bump 至 `4.1.0` | 原 `DEV-PLAN §5` 须大师书面确认；**已确认并执行，落号为 `4.2.0`**（非 `4.1.0`） |
