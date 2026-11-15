# v5.13.0 方案评估（01-ASSESSMENT）：绑定是事实，采集读事实

> 流程位置：② 评估报告。**无本报告不许开 PRD**（已遵守：PRD、DEV-PLAN、TEST-PLAN 与本文同版收口）。
> 依据：`00-RESEARCH.md` R-1 ~ R-13、`docs/plan/重构评估-20261115/03-资产与采集闭环评估.md` §一~§二。
> 本文只回答「做不做、怎么做、代价是什么、哪些假设被实测推翻」。
> 证据分级：**[已核实]** = 读过代码 / 跑过命令；**[推断]** = 由事实推出未实测；**[假设]** = 待验证前提。

## 1. 逐需求评估（FR-v5.13-01 ~ 10）

| FR | 内容 | 可行性 | 代价 | 结论 |
|---|---|---|---|---|
| FR-v5.13-01 | 采集画像聚合与 `profileState` | 高（三张事实源已在库） | 1 个只读聚合仓储 + DTO + 端点，零迁移 | **做**；查询期聚合，不写物化表、不引入状态滞后 |
| FR-v5.13-02 | 采集成功/失败都写验证事实 | **高（R-1 修正）** | 复用 V11_64，来源用 `detail` 前缀而非新列 | **做**；成功才回填 `device_check`，避免「错密码也算已验证」 |
| FR-v5.13-03 | 采集未传凭证时用绑定（管理面 → BMC 回退） | 高 | `collect()` 回退链 + 两个不可降级判据 | **做**；两者都未绑必须 400 点名，不许静默兜底 |
| FR-v5.13-04 | 自动填空（仅 NULL，不覆盖非空） | 高 | 与手工绑定共用一份 `SQL_FILL_IF_NULL` | **做且不可降级**；已绑 A 用 B 采成功，绑定仍须是 A |
| FR-v5.13-05 | 手工关联并永久保存 | 高 | 1 个写入口 Service + 3 个端点 + 审计 | **做**；`null=不动 / 0=清空 / >0=绑定`，沿用 5.10 向导语义 |
| FR-v5.13-06 | 列表三列 + 关系视图 | 中（UI 截图环境缺失） | 两页共用 `useCollectProfiles()` + 三列配置 | **做**；截图判据降级为源码机检 + DOM 断言（与 5.12 同口径） |
| FR-v5.13-07 | 采集结果持久化可视化（历史 + 两次对比 + 标注凭证） | 中 | 只读摘要端点 + diff 纯函数 + 面板 | **部分达成**：历史与对比做了，**凭证名未标注**（L-63-05 未达成） |
| FR-v5.13-08 | 两个采集入口 + 批量采集 | 中 | 主/次按钮 + 设为默认 + 复用既有批量端点 | **部分达成**：两个入口做了，**批量真跑未取证**（TC-38/39 PARTIAL） |
| FR-v5.13-09 | 三处一致性缺陷（`canCollect` / 项目侧 404 / 引用数） | 高（R-6/R-7 已定位） | 纯函数修正 + scope 放宽 + 删除影响面 | **做**；项目侧采集由真实 HTTP 200 验证 |
| FR-v5.13-10 | 项目侧读得到绑定字段 | 高（R-2） | 实体补 2 字段 + 仓储 SELECT / `mapRow` | **做**；保留 14/19/20 参旧构造器，零调用方改动 |

## 2. 关键取舍（D-1 ~ D-7）

| # | 取舍 | 理由 | 复核点 |
|---|---|---|---|
| D-1 | **零迁移**：来源用 `detail` 前缀，`result` 沿用 `SUCCESS/FAILED` | R-1：表里没有 `source` 列，加列要动迁移与回填；前缀可查且不动结构 | `CollectVerificationTest` 7 条；真实 DB `LIKE 'source=COLLECT%'` |
| D-2 | 画像**查询期聚合**，不建 `device_collect_profile` 物化表 | R-3/R-4：绑定列与验证表都在，缺的是写入链；物化表会引入状态滞后 | `DeviceCollectProfileRepositoryJdbcTest` 4 条 |
| D-3 | 可见性公式**只写一份**：`AssetScopeSql.DEVICE_BODY`，平台态与项目态各拼一个 WHERE | R-7/R-8：平台项目自身没有 DEVICE 引用，混用会让全局画像恒空 | `DeviceCollectProfilePlatformScopeTest` 4 条；真实 HTTP `count=13` |
| D-4 | 自动填空与手工绑定**共用同一份 SQL** | 两份「仅当 NULL」逻辑迟早漂移；采集路径不另写第二份 | `AutoBindTest` 4 条；TC-17 真实 DB 前后比对 |
| D-5 | 失败也写事实，但**只有成功回填 `device_check`** | 否则错误密码会伪装成 READY，执行前环境闸门会被骗 | `CollectVerificationTest`；真实 FAILED 行不回填 |
| D-6 | 异常原文**绝不进 `detail`**，只写分类码 | 异常正文可能夹带口令 / 私钥 / 命令回显 | `collectFailureKind(RuntimeException)`；六处掩码机检 |
| D-7 | 采集历史**只回提列摘要**，不回 `spec_json` | `spec_json` 是命令回显，属对外可见面 | 真实 HTTP 键集合 `collectedAt,deviceId,id,nicCount,roceMaxGbps,rocePorts,status` |

## 3. 风险与缓解

| # | 风险 | 缓解 | 落点 |
|---|---|---|---|
| R1 | 自动填空误改非空绑定，覆盖人工配置 | 不可协商：SQL 带 `AND management_credential_id IS NULL`，TC-17 必须真实请求 + DB 比对 | TC-17 / `AutoBindTest` |
| R2 | 采集端点放宽 scope 后越权 | `ProjectAssetRepository` 接口判定，避免 application 反向依赖 infrastructure 成环；跨项目 / 跨凭证用例各一条 | `CollectScopeTest` 6 条；TC-22 / 44 |
| R3 | 画像聚合拖慢列表（500 台） | 先查询期聚合；≤800ms 未采样就如实登记，不靠「状态滞后」掩盖 | TC-04 PARTIAL / L-63-01 |
| R4 | 失败也写事实导致状态误判 | `FAILED` 不参与 `usable`；仅 `SUCCESS` 回填 `device_check` | `CollectWritesFactTest` |
| R5 | 新增聚合泄露明文凭证 | 六处掩码机检 + 画像 19 字段逐字段核对 | `probe_oneshot_secret.py` 6/6；真实 HTTP 断言 |
| R6 | **平台态画像恒空**（W7 真实探针新发现） | `DEVICE_WHERE_PLATFORM` + 4 条回归单测；平台态才放宽，项目态不受影响 | 提交 `7333338`；探针 `count=13` |
| R7 | 采集 FAILED 状态被当成功 | `recordCollectFact` 显式判 `FAILED`，并补兜底 `failure_kind` | `CollectWritesFactTest`；真实 `REJECTED` 行 |

## 4. 前置条件（缺一不可）

| # | 前置条件 | 证据 / 判定 |
|---|---|---|
| P-1 | 5.12 已交付 `AssetWorkspace` 两 scope 合一与 `AssetScopeSql` 可见性真源 | commit `687df14`；R-7/R-8 |
| P-2 | V11_45 绑定列、V11_64 验证表、`device_host_spec` 采集记录三张事实源均已在库 | `SHOW COLUMNS`；R-2/R-3/R-13 |
| P-3 | WSL `~/robot-autotest` 为权威副本，构建 / 测试 / 门禁不在 9P（`/mnt/*`）上跑 | 项目硬规矩；`mvn` / `vitest` / `gates.sh` 均在 WSL 内 |
| P-4 | 门禁前加载 `~/.config/robot-autotest/env`，且 jar 与 HEAD 一致 | `deploy.sh --verify` 四项判据；运行期 `7333338` |
| P-5 | 两条不可降级防线必须拿到真实请求 + DB 前后比对，不接受单测代替 | `w7-http-probe.txt` TC-13 / TC-17 |
| P-6 | 发布六份产物齐全、49 条 TC 编号连续无补号且逐条有结论 | `check_release_docs.py v5.13.0` |
| P-7 | 发版 / 破坏性变更 / 对外口径由大师拍板，学徒不擅自 bump 版本号 | 项目流程纪律；本版只做版本内收口 |

## 5. 本版不做的事（范围外，逐条写明）

| 不做 | 理由 |
|---|---|
| 环境快照对象化 / 探针包 / 拓扑分级 | 属 5.15（R17） |
| 资产工作台形态调整 | 5.12 已完成，本版只在唯一组件里加三列共享列，不改 16 行差异矩阵 |
| 凭证轮换 / 过期提醒 / 自动清理失效凭证 | 涉及删除数据与新需求，需单独评估 |
| 把「已验证」用于执行前环境闸门 | 属 5.15（读快照而非读验证事实） |
| 采集结果导出为对外报告 | 属 5.14 测试报告的证据引用 |
| 新增数据库迁移（`V11_72` 物化表降级预案） | R-1/R-3：三张事实源已够用，**实测零迁移** |
| 为压指标造 500 台样本或伪造错误密码结果 | 无采样环境就如实标 PARTIAL，不许用推断补齐 |
| 版本 bump / 正式 tag / push / 更新公司群晖与 GitHub | 统一留到 5.16 后收口，由大师拍板 |

## 6. 被实测推翻的假设（⑦ 回写，逐条留痕）

| # | 原假设（评估 03 / PRD / DEV-PLAN / TEST-PLAN） | 实测结论 | 已改位置 |
|---|---|---|---|
| A-1 | `credential_verification` 有 `source` 列，采集写 `source=COLLECT` | R-1：**无该列**；枚举是 `SUCCESS/FAILED` | PRD §8、DEV-PLAN §9；改用 `detail` 前缀，零迁移 |
| A-2 | 验证结果枚举 `OK/FAIL` | R-1：真实枚举 `SUCCESS/FAILED`；采集状态另有 `PARTIAL`（不失败即算可采） | `CredentialVerificationService`；测试报告 §3 |
| A-3 | 画像复用 `AssetScopeSql.DEVICE_WHERE` 即可覆盖全局资产 | R-7/R-8：平台项目自身无 DEVICE 引用 → **全局画像恒空**，首次真实探针 `count=0` | 提交 `7333338`；`AssetScopeSql` / 仓储 / Controller；`DeviceCollectProfilePlatformScopeTest` |
| A-4 | 采集失败一定会抛异常，异常分类即可填 `failure_kind` | R-10：采集器落 `status=FAILED` 时是 **HTTP 200**，旧逻辑 `failure_kind` 为空 | `recordCollectFact` + `FAILURE_UNCLASSIFIED`；测试报告 §3 |
| A-5 | W63-18 验证命令 `python3 tools/check_gates.py` | 全仓无此文件；门禁判据由 `scripts/gates.sh` 输出 `GATES_RESULT` | DEV-PLAN §8 W63-18（本版回写） |
| A-6 | 「无 DUT 窗口」导致采集类 TC 只能标未执行 | R-9：本机 `127.0.0.1` 通道是真实链路，两轮探针据此跑通 13 条采集类断言 | 测试报告 §6；TEST-PLAN §5 口径补充 |
| A-7 | 采集历史「标注凭证」随历史端点一并完成 | `CollectHistoryRow` 只读摘要**无凭证名字段**，FR-07 该判据未达成 | 测试报告 §5 `L-63-05`；不静默改判据 |
| A-8 | 500 台画像聚合 ≤800ms 可视为随实现达成 | U-1：未采样、未计时 | TC-04 PARTIAL；`L-63-01` 保持未闭合 |

## 7. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：10 FR 可行性 + D-1~D-7 + 7 条风险 + 7 条前置条件 + 范围外清单 | 评估 03 §一~§二 + `00-RESEARCH.md` R-1 ~ R-13 |
| 2026-11-15 | **W7 回写**：按真实探针逐条改掉 A-1 ~ A-8 八条过期假设，不静默改数字；新增 R6/R7 两条风险 | commit `7850f34` / `7333338`；`w7-http-probe.txt` 两轮输出 |
