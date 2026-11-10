# v4.2.0 证据索引（4.2 收口批：W42-00 至 W42-05）

> 每包写清「判了什么、哪些判不了、由什么补」。`NOT-RUN` 一律保留原样，不用静态检查冒充通过。

| 包 | 目录 | 结论 | 判不了（NOT-RUN） |
|---|---|---|---|
| `W42-00` SSH 目标与凭据方案准入 | [EW42-00](./EW42-00/README.md) | 代码侧准入完成（9 项） | 真实 SSH 握手、首次指纹登记、真机 10 类输出 |
| `W42-01` CI 无 DB 质量门 | [EW42-01](./EW42-01/README.md) | `1301 例 / 0 失败 / 4 跳过`，`BUILD SUCCESS` | `actionlint`、`gh run view`（本机无二进制） |
| `W42-02` SchemaIT / db-audit 对齐 | [EW42-02](./EW42-02/README.md) | SchemaIT 7/7、db-audit PASS、89 表 | — |
| `W42-03` 远程主机只读采集 | [EW42-03](./EW42-03/README.md) | 单测 18/18、契约 29 项 PASS、远程负向 2 条 400 | 真实远端采集（无目标机） |
| `W42-04` artifact 端到端下载 | [EW42-04](./EW42-04/README.md) | 静态链路正确 | `gh run download` + `sha256sum -c`（无 gh） |
| `W42-05` 横向回归 / 门禁 / ⑦ 回写 | [EW42-05](./EW42-05/README.md) | 门禁 32/32 PASS、`--selfcheck FAILS=0`；**已落号 `4.2.0`**（四副本一致、六份产物齐） | `gh run download`（无 gh） |

## 证据文件清单

| 文件 | 内容 |
|---|---|
| `EW42-01/no-db.log` | 无库全量单测完整输出 |
| `EW42-02/schema-it.log` | `mvn -o -B -Pit test -Dtest=SchemaIT` 输出（7/7） |
| `EW42-02/schema-it-devdb-refused.log` | 误用 `robot_dev` 被 `_it` 守卫拦下的原始日志（保留，不删） |
| `EW42-02/db-audit.log` | `scripts/db-audit.sh` 读数 |
| `EW42-03/unit-targeted.log` | SSH 通道 8 例 + 采集器 10 例 |
| `EW42-03/host-inventory-contract.log` | 真实 HTTP + 真库 + 真采本机，29 项 PASS |
| `EW42-03/remote-negative.json` | 远程无凭据 / 无 confirm 的 400 原始响应 |
| `EW42-05/gates.log`、`gates-selfcheck.log` | 32 步门禁与自检 |
