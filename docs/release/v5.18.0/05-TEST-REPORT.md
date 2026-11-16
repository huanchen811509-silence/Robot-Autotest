# v5.18.0 测试报告

> 流程位置：⑥ 测试报告（实测回写）。
> 上游：`TEST-PLAN.md`（42 TC）、`PRD.md`（7 FR / 8 NFR）。
> 执行环境：`robot_dev`（真实库）+ 后端 `:8081`（新 jar）+ 前端 vitest；无 Chrome/CDP。

---

## 1. 总览

| 项 | 读数 |
|---|---|
| TC 总数 | 42 |
| PASS | 40 |
| 明示降级 | 1（TC-14 截图 → 组件断言） |
| BLOCKED | 1（TC-29 真机采集无样本） |
| 失败 | 0 |
| 前端用例 | 1232 passed / 0 failed |
| 后端单测 | `ProbeCommandCatalogTest` 8 条 + 既有全绿 |
| 门禁 | 见 §5（步骤 59 新增） |

**放行判断**：P0（FR-07 清场）7 条判据全部达成；P1 中 FR-01/02/03/04/06 达成，
FR-02 判据 6 与 FR-03 判据 1 的截图部分按 L-68-01 明示降级；P2（FR-05）判据 5 记 BLOCKED。
**G 门未判，本报告不主张 GA。**

---

## 2. 逐条结果

### FR-v5.18-01 导入五步向导（TC-01 ~ 08）

| TC | 结果 | 证据 |
|---|---|---|
| TC-v5.18-01 | PASS | `ImportWizard.spec.ts` 步态用例：初始步 1 / 选文件后预检 / 步 2 / 可回退 |
| TC-v5.18-02 | PASS | 映射页按目标字段排样例；`设备名称→portName` 后标签变「端口名」，值 `sw1 / sw2 / sw3` |
| TC-v5.18-03 | PASS | HTTP `w6801-http-verify.log`：`toCreate=1 toUpdate=1 conflicts=1` 与文件内容逐数对应 |
| TC-v5.18-04 | PASS | 取消只 emit `close`，`commit` 调用 0 次；HTTP 侧业务表前后计数一致（`ZERO_WRITE=PASS`） |
| TC-v5.18-05 | PASS | 不传 `mapping` 时 `assetPreview.mock.calls[0][1] === undefined`；HTTP 侧 200 且走表头自动识别 |
| TC-v5.18-06 | PASS | `check_navigation.py`：全局 6 组 / 20 叶，项目 7 组 / 28 叶（与 5.17 一致） |
| TC-v5.18-07 | PASS | 结果页 `iw-open-batches` 触发 `openBatches` 事件；资产侧批次面板可取数 |
| TC-v5.18-08 | PASS | `git show 876da9b:frontend/src/components/ImportWizard.vue` 不存在 |

### FR-v5.18-02 行数收敛（TC-09 ~ 14）

| TC | 结果 | 证据 |
|---|---|---|
| TC-v5.18-09 | PASS | `wc -l`：684 + 789 + 90 = **1563** ≤ 2200 |
| TC-v5.18-10 | PASS | 最大单文件 789 ≤ 800 |
| TC-v5.18-11 | PASS | 资产回归 22 + 20 = **42** 条，与改造前 `it()` 计数逐一对齐 |
| TC-v5.18-12 | PASS | `assetWorkspaceMatrix.spec.ts` 12 条全绿 |
| TC-v5.18-13 | PASS | 新增 `AssetTopologyBlock.spec.ts`（5）+ `AssetImportBlock.spec.ts`（5），含空态 |
| TC-v5.18-14 | 明示降级（L-68-01） | 九项功能面无自动化回退；「走查截图」因无 Chrome/CDP 降级为组件断言，未拍图 |

### FR-v5.18-03 拓扑图例（TC-15 ~ 19）

| TC | 结果 | 证据 |
|---|---|---|
| TC-v5.18-15 | PASS | `topologyLegend.spec.ts`：四类图例齐全，计数 2/1/1/1 与接口逐数一致 |
| TC-v5.18-16 | PASS | 仅实测后 `edgeCount=2` = `kindCounts.MEASURED`；HTTP 侧 `MEASURED_CONSISTENT=PASS` |
| TC-v5.18-17 | PASS | `KIND_SUM_EQ_TOTAL=PASS`；四类之和 = `total + overridden`（`PASS`） |
| TC-v5.18-18 | PASS | 勾选后 URL `?topo=measured`；按该 URL 重开仍只剩 2 条（可复现） |
| TC-v5.18-19 | PASS | 无数据时 `topology-edges-empty` 可见，非空白 |

### FR-v5.18-04 规划源与去重（TC-20 ~ 24）

| TC | 结果 | 证据 |
|---|---|---|
| TC-v5.18-20 | PASS | 造 2 个规划节点（1 个带 `ref_device_id`、1 个不带）→ `plannedNodes=2`、`withRefDevice=1` |
| TC-v5.18-21 | PASS | 造两对同对端（含 A/B 反序）→ 每对只出 1 条且 `kind=MEASURED`，`overridden=2` |
| TC-v5.18-22 | PASS | 清理后项目 1 只剩 2 条 `PLANNED` 边，`kindCounts.PLANNED=2` |
| TC-v5.18-23 | PASS | `topology_node` 真实写入 2 条，`edges` 出 1 条父子边（`source=TOPO_NODE`） |
| TC-v5.18-24 | PASS | 同批邻居连导两次：`imported=1` → `imported=0`（幂等） |

### FR-v5.18-05 探针包（TC-25 ~ 29）

| TC | 结果 | 证据 |
|---|---|---|
| TC-v5.18-25 | PASS | 三套包命令全部以 `export LC_ALL=C;` 开头且 ⊆ 代码常量（`ProbeCommandCatalog`） |
| TC-v5.18-26 | PASS | 不传 `deviceType` → 400，文案点名三个支持类型 |
| TC-v5.18-27 | PASS | `deviceType=SWITCH_X` → 400，文案含该值 |
| TC-v5.18-28 | PASS | `required` 按类独立：Linux 6/10、交换机 2/4、BMC 1/4 |
| TC-v5.18-29 | BLOCKED（无真机样本） | 交换机 / BMC 无真机可采；本版只交付建包与白名单一致性，真机采集未执行（L-68-02） |

### FR-v5.18-06 运维真机动作（TC-30 ~ 35）

| TC | 结果 | 证据 |
|---|---|---|
| TC-v5.18-30 | PASS | `BACKUP_HASH=PASS`，`MANIFEST PASS items=3` |
| TC-v5.18-31 | PASS | `backup-drill` 10 表逐一 `OK`，`DRILL_RESULT PASS`，`robot_it` 已还原 |
| TC-v5.18-32 | PASS | 四阶段 A/B/C/D 全 PASS，`UPDATE_DRILL_RESULT FAILS=0` |
| TC-v5.18-33 | PASS | `PROD_UNCHANGED=PASS`（9/14/12491/47/546/725/3/21/8 前后一致） |
| TC-v5.18-34 | PASS（口径修正） | `ops_task` 6 → 7 行，`params_json` 含 `targetDb=robot_it`。**本版 schema 无 `dry_run` 列**，故判据改为「落行 + 参数可核」，见 §4 |
| TC-v5.18-35 | PASS | `POST /ops/restore-drill?projectId=1` 传 `robot_dev` → 400 点名；传 `robot_it` → 200 |

### FR-v5.18-07 清场（TC-36 ~ 42，P0）

| TC | 结果 | 证据 |
|---|---|---|
| TC-v5.18-36 | PASS | `inventory-temp.md`：逐对象「信号 + 理由」，账号 9 行 / 项目 14 行，三类分组齐全 |
| TC-v5.18-37 | PASS | 执行前重新备份 `robot_dev-20261116-093856.sql.gz`，hash 比对通过 |
| TC-v5.18-38 | PASS | `DRYRUN_ZERO_WRITE=PASS`（9 张表逐表计数一致） |
| TC-v5.18-39 | PASS | `--apply` 后候选残留全 0（8 项目 + 3 账号） |
| TC-v5.18-40 | PASS | `KEEP_UNCHANGED=PASS`；`demo` 238 / `demo-pytest` 12 用例数逐字不变 |
| TC-v5.18-41 | PASS | `check_temp_objects.py --self-test`：注入未打标临时项目 → 判红 → 还原 → 判绿 |
| TC-v5.18-42 | PASS | 严格口径孤儿行 **0**（`project_id` 非空但项目不存在）；32 条 `project_id IS NULL` 为平台级用例集，合法 |

---

## 3. 关键读数

| 项 | 清场前 | 清场后 |
|---|---|---|
| 账号 | 9 | **6** |
| 项目 | 14 | **6** |
| 用例 | 12491 | **2267** |
| 用例集 | 47 | 39 |
| 设备 / 凭证 | 21 / 8 | 21 / 8（未动） |

行数：`AssetsView 684` / `GlobalAssetsView 789` / `AssetWorkspace 90`（合计 1563）。

---

## 4. 口径变更与偏差（须与 PRD 对照阅读）

| # | 原口径 | 实际 | 处理 |
|---|---|---|---|
| C-4-1 | 清场候选 10 项目 | **8 项目** | `TEST-V59-OWNER/GROUP` 两个 `is_demo=1` 项目已在 v5.17.0 W67-01 清掉，本版不重复登记 |
| C-4-2 | 引用面为 0 才可删 | 区分「运行依赖」与「夹具/造数引用」 | `qa-ui`（2 处）、`PRJ-RF-202611-001`（1 处注释示例）、`PERF-LONGLIST`（`perf_seed.py` 默认名）的引用均不读取该行存在，故仍进候选；清单里两列并列供复核 |
| C-4-3 | TC-34「`dryRun=0`」 | `ops_task` 无 `dry_run` 列 | 改判「落行 + `params_json` 含 targetDb」，dry-run 语义由 `DrillView.dryRun` 承担 |
| C-4-4 | W68-10「命令取自 `COMMANDS`」 | `COMMANDS` 受门禁 29「恰好 10 类」约束 | 新增 `ProbeCommandCatalog`：Linux 直接引用 `COMMANDS`，交换机/BMC 各自成表；「唯一真源」上移到目录层 |

---

## 5. 门禁

| 步 | 结果 |
|---|---|
| 全部 59 步 | 见 `docs/evidence/v5.18.0/w6/80-gates.log` |
| 新增 59 `temp_objects` | `python3 tools/check_temp_objects.py && --self-test` |
| 结构自检 | 已加 `run 59 temp_objects` 断言 |

---

## 6. 遗留

| 编号 | 内容 |
|---|---|
| L-68-01 | 行数第二台阶 1530 未承诺（D-2 第一台阶 2200 已达成） |
| L-68-02 | 交换机 / BMC 真机采集无样本，长期只能到「建包 + 白名单一致」 |
| L-68-03 | `demo` / `demo-pytest` 已在清单标注「产品样例，非临时」 |
| L-68-04 | `AssetLeaseBlock` / `AssetCollectBlock` 未单独抽组件：租约块模板仅 15 行、采集块与 `CredentialPicker`/`CollectHistoryPanel` 强耦合，抽组件的 props 转发成本大于结构收益；两者逻辑已分别在 `assetColumns` / `useAssetCollection` 中独立 |
