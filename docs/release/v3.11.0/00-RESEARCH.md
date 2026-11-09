# v3.11.0 需求调研（RA-RES-311）

> 目的：动手前把「这 8 个页面现在到底是什么、后端有什么真数据」量出来。
> 标注口径：`[已核实]` = 有命令输出 / 源码读数 / 数据库读数；`[推断]` = 由已核实事实推导，未直接观测；`[假设]` = 无法验证、按经验取值。

## 1. 8 个占位页的实际状态

| # | 结论 | 标注 | 依据 |
|---|---|---|---|
| 1 | `views/environment/` 5 页、`views/maintenance/` 3 页共 8 个 `.vue`，每个 55~61 行 | [已核实] | `wc -l frontend/src/views/{environment,maintenance}/*.vue` |
| 2 | 8 页的 `rows` 全部是 `ref<...[]>([])`，模板里只有一张 `DataTable`，行集恒空 | [已核实] | 逐个读 8 个文件的 `<script setup>` |
| 3 | 8 页源码注释均写明「只读视图 / 不发起任何写请求 / 行集恒为空」 | [已核实] | 同上 |
| 4 | `environment/pages.spec.ts` 与 `maintenance/pages.spec.ts` 只断言路由、源码里没有写动词、含「0 条」「只读」 | [已核实] | 两份 spec 的 4+5 条用例 |
| 5 | 因此旧版「只读」是**没有数据源**换来的，不是设计约束；接上真实接口后必须重新取证 | [推断] | 由 #2/#3/#4 推导：恒空数组不可能产生写请求 |
| 6 | 8 条路由都已注册，导航分组各含 5 / 3 个二级项 | [已核实] | `router.resolve()` 与 `NAVIGATION`（两份 spec 已绿） |

## 2. 后端已有的真实数据来源

| # | 结论 | 标注 | 依据 |
|---|---|---|---|
| 7 | `EnvController` 已有 `GET /projects/{id}/snapshots?name=`、`GET /latest?name=`、`POST /snapshots`、`POST /snapshots/diff` | [已核实] | `backend/.../api/asset/EnvController.java` |
| 8 | 查快照列表**必须**传环境名，但没有任何接口告诉前端「有哪些环境名」 | [已核实] | `EnvProfileService.list()` 对空 name 抛 400；仓库接口只有 `listSnapshots` / `latestSnapshot` |
| 9 | 因此需要补一个纯 GET 的环境清单接口，否则总览/准备/拓扑只能让用户手敲名字 | [推断] | 由 #8 推导；本次落 `GET /snapshots/profiles` |
| 10 | `env_profile` 在 V1 基线带唯一键 `uk_env_profile_project_name (project_id, name)` | [已核实] | `V1__baseline.sql:244` + `SHOW INDEX FROM env_profile` |
| 11 | 该唯一键使「同一环境两份快照」不可能：`POST /snapshots` 同名第二次返回 500 | [已核实] | `scripts/env-fixtures.sh` 首跑实测：第 1 份 200、第 2 份 `INTERNAL_ERROR` |
| 12 | 于是 `diff(before, after)` 要求的「同一环境前后两份快照」在该约束下永远无法成立，判据②不可能过 | [推断] | 由 #10 + #11 推导；`list(name)` 最多回 1 行 |
| 13 | `audit_log` 已有 14 类真实 action，`case.batch.undo.*` 是撤销/恢复动作，当前库里 0 条 | [已核实] | `SELECT action, COUNT(*) FROM audit_log GROUP BY action`；`BatchUndoService:103` |
| 14 | `run.retry_of_run_id` 与 `run_attempt.retried` 也是恢复线索，但当前库里同为 0 条 | [已核实] | `SELECT COUNT(*) FROM run WHERE retry_of_run_id IS NOT NULL` = 0 |
| 15 | 所以「恢复记录」页面只能显示真实空态，不能拿别的 run 凑数 | [推断] | 由 #13/#14 推导；判据要的是「来自真实数据」不是「必须有行」 |
| 16 | `GET /api/audit` 支持 `projectId/action/page/size`，`GET /api/search?types=run` 只查历史 run | [已核实] | `AuditController.java` / `frontend/src/api/search.ts` |
| 17 | `GET /system/settings` 与 `GET /projects/{id}/settings` 返回 `Record<string,string>` | [已核实] | `frontend/src/api/settings.ts` |

## 3. 主机清单（EW311-01）与页面的关系

| # | 结论 | 标注 | 依据 |
|---|---|---|---|
| 18 | `GET /api/asset-host-inventory/commands` 返回 10 类只读命令；`POST /collect` 为显式采集 | [已核实] | `scripts/host-inventory-contract.sh`：`RESULT PASS fails=0` |
| 19 | 列表接口不返回 `spec_json`，详情才给；摘要列含 `nicCount/rocePorts/roceMaxGbps` | [已核实] | 同上判据⑥ |
| 20 | 本机（WSL Ubuntu 24.04）没有 `nvidia-smi`，GPU 项带原因「未采集到」，`status=PARTIAL` 且 9/10 项有值 | [已核实] | `EW311-01/host-inventory.json` |
| 21 | 这个「缺一项」的样本正好能验证失败项语义，不需要制造异常 | [推断] | 由 #20 推导 |
| 22 | 采集记录会被契约脚本清掉，页面取证前需要重新采一次并留行 | [已核实] | `host-inventory-contract.sh` 的 `trap cleanup`；`SELECT COUNT(*) FROM device_host_spec` = 0 |

## 4. 影响验收的环境事实

| # | 结论 | 标注 | 依据 |
|---|---|---|---|
| 23 | `:8001` 是 `serve-entry.mjs` 托管 `frontend/dist`，改前端必须 `vite build` 才看得见 | [已核实] | `scripts/serve-entry.mjs:14/23` |
| 24 | systemd 跑 `backend/target/*.jar`，改后端必须重建 jar + `systemctl --user restart` | [已核实] | v2.7 起的项目硬规矩；本次两处修复首跑未生效就是因为只改了 E 盘文档工作区 |
| 25 | `http.ts` 对 GET 有短时请求缓存：同参重复搜索不发网络请求 | [已核实] | `env-pages-probe.mjs` 首跑「只读找回检索」判红，改关键字后才有 `GET /search` |
| 26 | naive-ui 的 `NSelect` 在 happy-dom 下不渲染选项，页面筛选一律用原生 `<select>` | [假设] | 沿用 v3.10.0 调研 #21 的结论；本版未再复验虚拟列表行为 |

## 5. 调研结论

1. **8 页的问题不是「差一点数据」，是从来没接过数据**：接真实接口后，旧 spec 的「恒空」断言必须整体重写（#2~#5）。
2. **补一个环境清单 GET 就能让 5 页都有真实入口**（#8/#9），但快照历史被 V1 唯一键卡死，必须补迁移（#10~#12）。
3. **恢复类数据当前为空是真值**，页面要如实显示 0 条，探针验「打到 /api/audit」而不是「有行」（#13~#15）。
4. **只读口径必须靠运行时网络取证**：源码级「没有 POST」判不出第三方组件或缓存层发出的写（#5、#25），因此新增 `env-pages-probe.mjs` 录请求方法。
