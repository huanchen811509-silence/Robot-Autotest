# 4.1.0 · 项目工作区与创建向导 · 证据索引

| 项 | 内容 |
|---|---|
| 批次 | `4.1.0`（`W41-01` 至 `W41-07`） |
| 需求 | `FR-v4.1-01` ～ `FR-v4.1-07` |
| 日期 | 2026-11-09 |
| 环境 | WSL Ubuntu-24.04 · MySQL 8.0 `127.0.0.1:3306`（`robot_dev`）· 后端 `:8081` · 前端 `:8001` · Chrome CDP `:9222` |
| 版本 | `VERSION` 仍 `3.11.0`（按铁律：全部实现与门禁通过、大师书面确认后才 bump） |
| 结论 | 七包全部落地；契约脚本 `43 / 9 / 16 / 18` 项 PASS，UI 探针 `10 + 3 + 15` 项 PASS，门禁 `32` 步见 `EW41-07/gates.log` |

---

## 1. 逐包索引（判了什么 / 哪些判不了）

| 包 | 需求 | 结论 | 证据目录 | 判不了（NOT-RUN 摘要） |
|---|---|---|---|---|
| `W41-01` | `FR-v4.1-01/02` | `PASS`（契约机检 + 15 项负向自证） | `EW41-01/` | 只读挂载实测 |
| `W41-02` | `FR-v4.1-01` | `RESULT PASS passes=16 fails=0` | `EW41-02/`、`EW41-02-workspace-contract.log` | 事务快照 / 项目锁并发、配额负向 |
| `W41-03` | `FR-v4.1-02` | `RESULT PASS passes=18 fails=0` | `EW41-03/`、`EW41-02-workspace-contract.log` | 写入中断、冷启动建项目、路径穿越单列 |
| `W41-04` | `FR-v4.1-03` | `RESULT PASS passes=9 fails=0` | `EW41-04/` | 配额负向、闭包告警、并发/只读、三级 sha 对照表 |
| `W41-05` | `FR-v4.1-04` | `W41_PROBE PASS`（向导 10/10 + viewer 3/3 + 模板单测 3/3） | `EW41-05/` | 网络级 0 写请求、`axe` 扫描、成员失败降级 |
| `W41-06` | `FR-v4.1-05` | `W41_PROBE PASS`（工作区 15/15） | `EW41-06/` | 隔离确认后真导入、重试转成功、`axe` 扫描、大包压测 |
| `W41-07` | 全部 | 见 `EW41-07/README.md` | `EW41-07/` | 落号与发布归收尾阶段 |

## 2. 证据文件分布

| 目录 | 关键文件 |
|---|---|
| `EW41-01/` | `README.md`、`workspace-check.log`、`workspace-selfcheck.log`、`contract-inventory.log` |
| `EW41-02/` | `README.md`、`../EW41-02-workspace-contract.log`（export 16 项） |
| `EW41-03/` | `README.md`（import 18 项，日志同上文件） |
| `EW41-04/` | `README.md`（levels 9 项） |
| `EW41-05/` | `README.md`、`wizard-probe.json`、`wizard-probe.log`、`wizard-db-readings.log`、`templates-vitest.log` |
| `EW41-06/` | `README.md`、`workspace-probe.json`、`workspace-probe.log` |
| `EW41-07/` | `README.md`、`gates.log`、`gates-selfcheck.log`、`release-docs.log`、`md-tables.log`、`i18n.log`、`version-consistency.log` |

## 3. 真链路读数（摘要，详见各包 README）

| 项 | 读数 |
|---|---|
| 导出 / 导入 / 三级 | `43` 项合并（`export 16`、`import 18`、`levels 9`）全 PASS，含篡改必拒、隔离区、权限矩阵 |
| 向导 | 5 步（viewer 4 步）、4 模板、键盘 Space 选中、回退保留输入、成员 `id=18`；提交前 `projects=11` → 提交后 `12` |
| 真库六项 | `project=85`（`W41E2E-769631` / `robot` / `project`）、`members=2`、`engineer=1`、`kernel.default=robot`、`audit.project:create=1`、`test_sets=0` |
| 工作区 UI | 三卡 / 三粒度 / 三策略；包 `qa-e2e-fixture` 75.2 KB 24 文件已签名；预检 `496` 行（`0 导入 / 494 跳过 / 2 隔离`）；任务 `20` 行、失败 9、仅失败行重试 |
| 重试入口 | 真实调用后端，负向任务返回 `INVALID_ARGUMENT (40001)`（`路径含非法段: ../x`）；「重试转成功」未演示 |

## 4. 取证缺口（沿测试计划 §3 / G-01 ～ G-10）

| 缺口 | 对 4.1 的影响 |
|---|---|
| `G-01` CDP 非持久化 | 本次探针已跑通；后续复测前先 `curl :9222/json/version`，不可用记 `NOT-RUN` |
| `G-09` 空实例 B 未就绪 | 跨实例 A→B round-trip 未执行 |
| `G-10` 签名密钥流水线未定 | 以 `keyId=server-local-v1` 本地验签为准 |
| `axe-core` 未安装 | 两包的无障碍半项以手写判据降级并显式标注 |
