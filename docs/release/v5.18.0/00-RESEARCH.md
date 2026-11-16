# v5.18.0 需求调研

> 流程位置：① 需求调研。上游：`docs/plan/评估报告-v5.8至v5.16-实际开发完成情况-20261116.md` §6.1 台账。

## 1. 调研方法

不采信旧报告结论，逐项读当前代码（`HEAD 876da9b`）与真实库（`robot_dev`），每条事实标证据级：

- **[已核实]**：有命令输出 / 代码行 / DB 查询结果；
- **[推断]**：由已核实事实推导，未直接验证；
- **[假设]**：待验证的前提。

## 2. 现状事实

| # | 事实 | 级别 | 证据 |
|---|---|---|---|
| 1 | 资产页三文件 1637 + 1378 + 90 = 3105 行 | [已核实] | `wc -l` |
| 2 | `ImportWizard.vue` 不存在，导入是两个两步弹窗 | [已核实] | `ls frontend/src/components/ImportWizard.vue` → 无 |
| 3 | 后端 `/import/preview` 不接受字段映射参数 | [已核实] | `GlobalAssetController.preview` 只收 `file` |
| 4 | `link` 表已有 `confidence`（MEASURED/PLANNED/INFERRED）与 `source`（LLDP/MANUAL） | [已核实] | `V11_77` 与 `TopologyTrustService` |
| 5 | 拓扑只有 `/trust`（计数桶）与 `/lldp-import`，**没有边列表接口** | [已核实] | `TopologyTrustController` 只有两个端点 |
| 6 | `topology_node` 表存在但 0 行，未被任何聚合读取 | [已核实] | `SELECT COUNT(*)` = 0；`grep topology_node` 只在迁移里 |
| 7 | `probe_pack` 只有 `HOST_LINUX` 一套；`HostInventoryCollector.COMMANDS` 恰 10 类 | [已核实] | `SELECT code FROM probe_pack`；门禁 29 断言 10 类 |
| 8 | 探针包端点 `deviceType` 带 `defaultValue=HOST_LINUX`，漏传会静默按 Linux 采 | [已核实] | `ProbePackController.commands` |
| 9 | 9 个账号里 3 个是历史临时账号、14 个项目里 8 个是临时项目，且多数未打 `is_demo` | [已核实] | `inventory-temp.md` |
| 10 | 26 张表以 RESTRICT 引用 `project(id)`；一批子表（`test_case_step` 等）没有 `project_id` | [已核实] | `information_schema.KEY_COLUMN_USAGE` |
| 11 | 环境无 Chrome/Chromium/Playwright，CDP 9222 不通 | [已核实] | `curl :9222` 无响应；`~/.cache/ms-playwright` 空 |

## 3. 由此确定的四个前提

| # | 前提 | 若不成立的影响 |
|---|---|---|
| 1 | 迁移只能追加，`V11_79` 可插数据（本次只插包与命令，不建表） | 需改表结构 → 影响面扩大 |
| 2 | 交换机/BMC 命令可由独立目录承载，不必挤进 `COMMANDS` | 门禁 29 会红 |
| 3 | 清场可用「显式清单 + 多轮解外键」完成，无需逐表手写顺序 | 会重现 1451 半删 |
| 4 | 截图类证据在无浏览器环境只能降级为组件断言 | 需先恢复 CDP，成本高 |

## 4. 结论

六项建设里，只有「导入向导」与「拓扑边接口」是**从零建**；行数收敛与探针包属于
**已有能力重组**；运维与清场属于**执行与治理**。因此本版工作量集中在 W1（向导）与 W3（拓扑）。
