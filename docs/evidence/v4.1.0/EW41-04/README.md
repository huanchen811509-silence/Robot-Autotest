# EW41-04 · 三级传递与配额（FR-v4.1-03）

| 项 | 内容 |
|---|---|
| 工作包 | `W41-04` 三级传递与配额 |
| 日期 | 2026-11-09 |
| 环境 | 后端 `:8081`（4.2.0 包）· 真库 `robot_dev` · 项目 29 |
| 结论 | `RESULT PASS passes=9 fails=0`（PROJECT→DIR→FILE 三级，含越界负向与恢复） |

---

## 1. 真实读数（`bash scripts/workspace-contract.sh levels`）

| 组 | 断言 | 读数 |
|---|---|---|
| L1 | DIR 粒度校验 | 缺 `rootPath` → 400；`rootPath` 传文件路径 → 400 |
| L2 | 路径安全 | 绝对路径 → 400；`..` 越界 → 400；非法粒度 → 400 |
| L3 | DIR 导出 | `granularity=dir`、`rootPath=files`；只带 `files/`（用例 0、源文件 14） |
| L4 | FILE 导出 | 单文件 `guard.robot`，manifest 只带该文件 |
| L5 | 恢复全量 | 重新导出 PROJECT 全量包 200（三级切换后可逆） |

证据：`workspace-contract-levels.log`（9 项 PASS）。另有 `../EW41-02-workspace-contract.log` 记录 `all` 模式 43 项合并读数。

## 2. 实现口径

- 粒度枚举：`project / dir / file`；`dir` 必须给 `rootPath` 且不得是文件、绝对路径或含 `..`；
- 包目录名仍为项目 code；`project.json.granularity` 与 `rootPath` 如实记录，manifest 只覆盖传递范围内的文件；
- 包结构与签名校验与全量包同一套（`tools/check_workspace_bundle.py`）。

## 3. 判不了的部分（NOT-RUN）

| # | 项 | 原因 | 补齐 |
|---|---|---|---|
| 1 | 配额负向样本 | 未构造超配额包触发拒绝码 | 需临时调小配额 + 大文件样本 |
| 2 | 依赖闭包告警 | 未构造「用例引用未随包传递资产」的样本 | 需缺资产的最小包 + 告警断言 |
| 3 | 并发锁与只读文件系统日志 | 未做并发导出/导入与只读挂载实测 | 需两路并发 + `chmod -w` 目录样本；只读分支代码已判存在（EW41-01） |
| 4 | 三级 sha256 逐项对照表 | 契约脚本校验了包可机检通过，但未输出三级之间逐文件的 sha256 差异表 | 可用 `check_workspace_bundle.py --bundle` 对三级包分别存档 |
