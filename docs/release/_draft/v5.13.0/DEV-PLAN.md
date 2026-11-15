# v5.13.0 开发计划（DEV-PLAN）：资产采集闭环 —— 绑定是事实，采集读事实

> 流程位置：④ 开发计划。**无开发计划不许开工编码**。
> 上游：`PRD.md`（10 FR + 8 NFR）、`03-资产与采集闭环评估.md` §一~§二、v5.12.0 的 `AssetWorkspace`。
> 本文件给 WBS 编号、改动面与**可复制的验收命令**；不给「大约几天」，只给依赖关系与可独立验收的边界。

---

## 1. 波次总览

| 波次 | 名称 | WBS | 可独立验收 | 与其他波关系 |
|---|---|---|---|---|
| W1 | 读得到绑定 | W63-01 ~ W63-02 | 是 | 必须最先（后面三波都读画像） |
| W2 | 采集写验证事实 | W63-03 ~ W63-04 | 是 | 依赖 W1 |
| W3 | 采集读绑定 | W63-05 ~ W63-06 | 是 | 依赖 W2（要 `source=COLLECT` 才能证明用了哪条凭证） |
| W4 | 手工关联 | W63-07 ~ W63-08 | 是 | 依赖 W1；与 W3 可并行 |
| W5 | 展示面 | W63-09 ~ W63-12 | 是 | 依赖 W1（画像）、W2（验证事实） |
| W6 | 入口与一致性 | W63-13 ~ W63-16 | 是 | 依赖 W3（回退链）、W5（三列） |
| W7 | 收口 | W63-17 ~ W63-18 | 否（最终波） | 依赖 W1 ~ W6 全部出口判据 |

**并行纪律**：W1 是硬前置；W2 → W3 串行（没有验证事实就证明不了「用了哪条凭证」）；
W4 与 W3 改动面不重叠可并行；W6 必须在 W3/W5 之后（按钮状态依赖画像与回退链）。

**两条不可降级的防线**（PRD §10 特别说明）：
1. **自动填空只填空，不覆盖非空**（W63-06）——已绑 A 用 B 采集成功，绑定仍须是 A；
2. **两者都未绑 → 400 点名**（W63-05）——不许静默用「第一个凭证」兜底。

**本版预期零迁移**：降级预案的物化表才追加 `V11_72`。

---

## 2. W1 读得到绑定（W63-01 ~ W63-02）

### W63-01 项目侧设备实体补绑定列

| 项 | 内容 |
|---|---|
| 目标 | `domain/asset/Device.java:15-35` 与 `DeviceRepositoryJdbc.java:25-31` 的列清单补 `management_credential_id` / `bmc_credential_id` |
| 改动面 | 实体 2 字段 + getter；仓库列清单与 `mapRow`；单测 |
| 出口判据 | ① 项目侧设备详情响应含两字段；② 全局与项目同一设备返回的绑定 id 相同；③ 列清单含两列（代码检查） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=DeviceRepositoryTest test` |
| 依赖 | — |

### W63-02 采集画像聚合

| 项 | 内容 |
|---|---|
| 目标 | `DeviceCollectProfile`：绑定凭证（含掩码名）、最近验证、最近采集、`usable`、`profileState`（READY/PARTIAL/BLOCKED + 原因枚举） |
| 改动面 | 新增 `DeviceCollectProfileRepository`（查询期聚合）+ DTO + `GET /api/assets/collect-profiles`（或设备列表内联）；单测 |
| 出口判据 | ① 三态各一台设备返回不同状态；② `BLOCKED` 原因与实际缺失项一致；③ `lastVerifiedAt` 取最新一条（同刻取 id 大）；④ 500 台 ≤800ms；⑤ 不新增业务表 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=DeviceCollectProfileTest test` |
| 依赖 | W63-01 |

---

## 3. W2 采集写验证事实（W63-03 ~ W63-04）

### W63-03 `collect()` 写 `credential_verification`

| 项 | 内容 |
|---|---|
| 目标 | `GlobalAssetService.collect()` 在成功路径调用 `credentialVerification.record(...)`，`source=COLLECT`；`/verify` 写 `source=VERIFY` |
| 改动面 | `GlobalAssetService`（1 处调用 + 事务边界）、`recordFromCollect` 从死代码转主路径、单测 |
| 出口判据 | ① 采集前 N 行 → 成功后 N+1 且 `result=OK`、`credential_id` = 实际使用凭证；② `source` 可区分两来源；③ 在 v5.12 收口 commit 上回放判红（行数不变） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=CollectVerificationTest test` |
| 依赖 | W63-02 |

### W63-04 失败也写 + 原因分类

| 项 | 内容 |
|---|---|
| 目标 | 失败路径同样写一行，`result=FAIL` + `failure_kind`（认证失败 / 连接超时 / 命令不支持 / 其他） |
| 改动面 | 异常分支映射到四类 + 单测（四类各一条） |
| 出口判据 | ① 错误密码采集 → 写 FAIL 且 `failure_kind` 非空；② 四类各有单测；③ 失败不写成功事实（不产生假 READY） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=CollectFailureTest test` |
| 依赖 | W63-03 |

---

## 4. W3 采集读绑定（W63-05 ~ W63-06）

### W63-05 凭证回退链与 400 点名

| 项 | 内容 |
|---|---|
| 目标 | `credentialId == null` 时按「有 `management_ip` → 管理面绑定；否则 BMC 绑定」回退；两者都空 → 400 点名「未绑定凭证」+ 设备标识；显式传参优先 |
| 改动面 | `GlobalAssetService.collect()` 入口（`:528-538` 附近）、错误码与文案、单测 |
| 出口判据 | ① 不传参用绑定凭证且验证事实 `credential_id` 一致；② 只绑 BMC 的行为与文案一致；③ 都未绑 → 400 且信息含设备标识；④ 显式传参优先；⑤ `confirmed=false` 仍 400（安全闸不回退） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=CollectCredentialFallbackTest test` |
| 依赖 | W63-04 |

### W63-06 自动填空（仅 NULL）

| 项 | 内容 |
|---|---|
| 目标 | 采集成功且用的是管理面凭证且 `management_credential_id IS NULL` → 回填；非空**不动**；失败不填空 |
| 改动面 | 与手工关联共用同一 Service 方法（`bindCredential(..., onlyIfNull=true)`）+ 单测 |
| 出口判据 | ① 未绑设备首采成功自动填；② 已绑 A 用 B 采集 → 仍为 A（**核心判据**）；③ 失败不填空；④ 界面有「首次采集会自动记住凭证」说明 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=AutoBindTest test` |
| 依赖 | W63-05 |

---

## 5. W4 手工关联（W63-07 ~ W63-08）

### W63-07 绑定写入口

| 项 | 内容 |
|---|---|
| 目标 | 设备行/详情「关联凭证」：选管理面 + BMC 凭证，保存即永久写库；落审计 |
| 改动面 | 后端两套路径（项目侧 `PUT /api/projects/{id}/assets/{deviceId}/credentials`、全局侧 `PUT /api/global/assets/{deviceId}/credentials`，与自动填空共用 Service）、前端表单、审计写入 |
| 出口判据 | ① 刷新/换账号/重启后端绑定仍在；② 项目侧越权凭证 id → 403/400 点名；③ 审计行含 who/when/device/credential |
| 验证命令 | `curl -s -X PUT "$API/api/projects/1/assets/9/credentials" -d '{"managementCredentialId":3}' -H "Authorization: Bearer $TOKEN" \| jq` |
| 依赖 | W63-01 |

### W63-08 解绑与状态回落

| 项 | 内容 |
|---|---|
| 目标 | 清空绑定 → 字段 NULL，画像状态回落 `BLOCKED` |
| 改动面 | 同一 Service 的 `clearBinding` + 单测 |
| 出口判据 | ① 解绑后两列为 NULL；② 画像立即变 `BLOCKED`（不滞后）；③ 与 5.10 向导写入互不冲突（同字段，后写覆盖） |
| 验证命令 | `cd backend && mvn -o -B -Dtest=ClearBindingTest test` |
| 依赖 | W63-07 |

---

## 6. W5 展示面（W63-09 ~ W63-12）

### W63-09 设备列表三列

| 项 | 内容 |
|---|---|
| 目标 | `AssetWorkspace` 设备列表新增「凭证」「采集」「状态」三列；两 scope 一致；「未关联」为灰标签且可点击直达关联 |
| 改动面 | `assetWorkspaceMatrix.ts`（列声明，注意 5.12 矩阵须同步）+ 设备列渲染 + spec |
| 出口判据 | ① 三列值与画像逐字段一致（抽 5 台）；② 「未关联」非空白且可点；③ 两 scope 都有三列 |
| 验证命令 | `cd frontend && npx vitest run -t 设备三列` |
| 依赖 | W63-02 |

### W63-10 详情采集历史与两点对比

| 项 | 内容 |
|---|---|
| 目标 | 设备详情「采集历史」（时间/凭证/结果/项数）+「两次对比」（新增 N / 变化 M / 消失 K，变化项显「旧值 → 新值」）；失败记录也入历史 |
| 改动面 | `DeviceDetailPanel`（或等价）+ 后端历史查询端点（若缺）+ spec |
| 出口判据 | ① 两次采集 → 2 条历史各带凭证名；② 对比数字与逐项 diff 一致；③ 无历史显示空态；④ 失败记录带原因 |
| 验证命令 | `cd frontend && npx vitest run -t 采集历史` |
| 依赖 | W63-04 |

### W63-11 凭据侧「关联设备」弹层补验证列

| 项 | 内容 |
|---|---|
| 目标 | 既有弹层加「最近验证 / 失败原因」列；双向对数一致 |
| 改动面 | 凭据页弹层 + spec |
| 出口判据 | ① 设备→凭证 与 凭证→设备 两个方向关联对数相同；② 无验证记录显示「从未验证」 |
| 验证命令 | `cd frontend && npx vitest run -t 关联设备` |
| 依赖 | W63-02 |

### W63-12 删除 `id=0` 假凭据

| 项 | 内容 |
|---|---|
| 目标 | 删 `api/assetDetail.ts:192` 的合成逻辑；详情只显示真实绑定 |
| 改动面 | `api/assetDetail.ts` + spec |
| 出口判据 | ① 代码检查无合成 `id:0`；② 无绑定设备详情显示「未关联」而不是假凭据行 |
| 验证命令 | `cd frontend && npx vitest run -t 假凭据` |
| 依赖 | W63-09 |

---

## 7. W6 入口与一致性（W63-13 ~ W63-16）

### W63-13 两个采集入口与批量

| 项 | 内容 |
|---|---|
| 目标 | 主按钮「采集」（用绑定）+ 次按钮「采集（指定凭证）」（可勾「设为默认」）+ 批量采集 |
| 改动面 | 资产页按钮区、`CredentialPicker` 复用、批量端点（已存在 `POST /assets/credentials/{credId}/collect`）、spec |
| 出口判据 | ① 未绑定时主按钮 `disabled` 且 `title` 说明缺什么；② 勾「设为默认」→ 绑定更新；③ 批量 3 台 → 3 条验证事实；④ 部分失败逐台给原因；⑤ 批量只对已关联设备可用 |
| 验证命令 | `cd frontend && npx vitest run -t 采集按钮` |
| 依赖 | W63-05 |

### W63-14 `canCollect` 判定对齐后端

| 项 | 内容 |
|---|---|
| 目标 | 前端判定改为「有管理 IP 且有凭证」；只绑 BMC 的设备按钮禁用并说明「缺管理 IP」 |
| 改动面 | `GlobalAssetsView.vue:222-224` 等价逻辑（已迁到 `AssetWorkspace`） |
| 出口判据 | ① 只绑 BMC 无 IP → 按钮禁用且文案点名「缺管理 IP」；② 与服务端 400 条件一一对应（单测枚举四种组合） |
| 验证命令 | `cd frontend && npx vitest run -t canCollect` |
| 依赖 | W63-09 |

### W63-15 项目侧采集 scope 修正

| 项 | 内容 |
|---|---|
| 目标 | 采集端点按 `AssetScopeSql` 可见性判定，不再限定 `owner_scope='PLATFORM'`；凭证须本项目可见 |
| 改动面 | `GlobalAssetService:528-538` 的可见性条件、跨项目凭证校验、越权用例 |
| 出口判据 | ① 项目授权来的平台设备在项目内可采（200 + 事实写入）；② 项目私有设备可采（原 404 消失）；③ 跨项目凭证 403/400 点名 |
| 验证命令 | `cd backend && mvn -o -B -Dtest=CollectScopeTest test` |
| 依赖 | W63-05 |

### W63-16 `referenceCount` 接到删除影响面

| 项 | 内容 |
|---|---|
| 目标 | 删凭证前显示「被 N 台设备绑定、M 次使用」，数字来自既有 `referenceCount` |
| 改动面 | 凭证删除确认弹窗 + `api/globalAssets.ts:149-151` 接线 |
| 出口判据 | ① 页面数字 = 接口数字；② 有引用时删除被拒并点名；③ 确认弹窗含不可恢复提示 |
| 验证命令 | `curl -s "$API/api/global-assets/credentials/3/reference-count" \| jq` |
| 依赖 | W63-11 |

---

## 8. W7 收口（W63-17 ~ W63-18）

### W63-17 契约与掩码

| 项 | 内容 |
|---|---|
| 目标 | `openapi.json` 重生成；`probe_oneshot_secret` 六处仍 PASS（新聚合不得泄明文） |
| 改动面 | 契约快照（WSL 内重生成）；掩码检查 |
| 出口判据 | ① `--check` PASS；② 六处掩码 PASS；③ 画像响应逐字段检查无 `secret` 字段 |
| 验证命令 | `cd backend && python3 tools/gen_api_contract.py --check`；`python3 tools/check_secrets.py 2>&1 \| tail -3` |
| 依赖 | W63-05、W63-07 |

### W63-18 门禁全量与文档回写

| 项 | 内容 |
|---|---|
| 目标 | `GATES_RESULT FAILS=0`；六份齐全；总纲状态回写 |
| 改动面 | `docs/release/v5.13.0/*`（从 `_draft/` 迁入）、总纲交付表 |
| 出口判据 | ① 门禁 `FAILS=0`；② `check_release_docs.py v5.13.0` PASS；③ 表格机检全 PASS |
| 验证命令 | `python3 tools/check_gates.py 2>&1 \| tail -3` |
| 依赖 | W1 ~ W6 全部 |

---

## 9. 接口与数据变更清单

| 变更 | 类型 | 影响 | 处理 |
|---|---|---|---|
| `GET /api/assets/collect-profiles` | 新端点 | 列表三列与画像 | 契约重生成 |
| 设备详情响应加两个绑定字段 | 响应新字段 | 项目侧可见绑定 | 契约重生成 |
| `PUT .../assets/{deviceId}/credentials` | 新端点 | 手工关联 | 契约重生成 |
| 采集端点可见性放宽 | 权限语义变更 | 项目侧可采 | 需补越权用例（NFR-06） |
| 采集失败新增 `failure_kind` | 响应/落库字段 | 历史展示 | 契约重生成 |
| 数据库 | **预期零迁移** | 降级才追加 `V11_72` | 配 `.down.sql` |

---

## 10. 风险与应对

| # | 风险 | 应对 |
|---|---|---|
| R1 | 自动填空误改非空绑定，覆盖人工配置 | W63-06 判据 2 为不可协商项；SQL 带 `AND management_credential_id IS NULL` 条件 |
| R2 | 采集端点放宽后越权（跨项目采别人设备） | W63-15 用 `AssetScopeSql` 同一可见性判定；跨项目/跨凭证用例各一条 |
| R3 | 画像聚合拖慢列表（500 台） | 查询期聚合先做，`≤800ms` 不达标才上物化表（L-63-01），不许靠「状态滞后」掩盖 |
| R4 | 失败也写事实导致状态误判 | `result=FAIL` 不参与 `usable` 计算；仅 `OK` 且未过期才算已验证 |
| R5 | 新增聚合泄露明文凭证 | W63-17 六处掩码检查 + 画像字段逐个过 |

---

## 11. 与相邻版本的边界

1. 本版在 5.12 的 `AssetWorkspace` 里加列与动作，**不再新起第二套资产页**。
2. 本版**不做**快照对象化/探针包/拓扑（5.15）、**不做**报告导出采集结果（5.14）。
3. 「已验证可采集」在本版是**画像状态**，不是执行前闸门；闸门读快照，属 5.15。

---

## 12. 修订记录

| 日期 | 修订 | 依据 |
|---|---|---|
| 2026-11-15 | 首版：7 波 18 个 WBS；W1 读绑定先行；「只填空不覆盖」单列一 WBS | `PRD.md` v5.13.0 + 评估 03 §一~§二 |
