# EW43-08 · 全量回归、门禁、版本 4.3.0 与交付包（W43-08）

**日期** 2026-11-10 ｜ **对应** `TC-v4.3-36`~`41`

## 1. 版本与「关于 / 版本迭代」

| 项 | 读数 |
|---|---|
| `VERSION` | `4.3.0` |
| `backend/src/main/resources/build-info.properties` | `app.version=4.3.0`、`commit=9fb2358`、`deps=23`（`python3 scripts/gen_build_info.py` 重生成） |
| 根 `CHANGELOG.md` | 顶部新增 `## v4.3.0-project-create-asset-info（2026-11-10） <!-- current -->`，四小节锚点齐（added / changed / fixed / breaking）；`v4.2.0` 标题的 `<!-- current -->` 已摘除 |
| `frontend/public/CHANGELOG.md` | 与根文件同源拷贝，两处小节逐字一致 |
| 关于页 | 运行期读 `/api/system/build-info`，重启后显示 `4.3.0`（不写死） |
| 运行期 | `systemctl --user restart robot-autotest-backend` → `/actuator/health=UP`，`/api/system/build-info=4.3.0` |

## 2. 构建命令与读数

```bash
# WSL ~/robot-autotest
python3 scripts/gen_build_info.py
(cd backend && mvn -o -B -DskipTests package)
(cd packages/editor && npm run build)
(cd frontend && npm run build)
bash scripts/package-release.sh
```

| 步骤 | 读数 |
|---|---|
| build-info | `version=4.3.0 (file) commit=9fb2358 deps=23` |
| 后端打包 | `BACKEND_EXIT=0` |
| 编辑器构建 | `EDITOR_EXIT=0` |
| 前端构建 | `FRONTEND_EXIT=0` |
| 交付包 | `PACKAGE PASS name=robot-autotest-4.3.0-9fb2358-linux-x64 files=293`，`tarball 48,267,736 B`，`sha256=e08c88e3e1eaa8b341052d2c253872dbc478116ecde37da1cfe5e4edfe703e66` |
| 包内 MANIFEST | `version=4.3.0`，与 `VERSION` 一致 |

## 3. 门禁 32 步

```bash
bash scripts/gates.sh
bash scripts/gates.sh --selfcheck
```

| 结果 | 步骤 |
|---|---|
| PASS（31 步） | 01–12、14–17、18、19–32 |
| **FAIL（1 步）** | **13-browser_regression**：`fetch failed`，`CDP :9222` 不可用（`EW43-00` 已记改动前即红） |
| 合计 | `GATES_RESULT FAILS=1` |

**本版修掉的基线红**：06-lint（`changelog.ts` 的 `no-control-regex`、6 处 px 未登记档位、字体子集缺 3 字已重生成）。
**结构自检**：`--selfcheck` 的 `STRUCT_FAIL 缺少步骤 N` 与「删步骤 N PASS」是判据自证的**预期输出**；
其中 `SELFCHECK_RESULT FAILS=1` 指第 13 步在骨架里同样删了即红，属预期，不是新缺陷。

## 4. 前端 / 后端读数

| 项 | 读数 |
|---|---|
| 前端全量 | **596 例通过**（门禁 02；较基线 592 增 4 例：`ProjectCard` 停用/删除入口） |
| 类型检查 | 0 错误（门禁 03） |
| 前端构建 | 成功（门禁 04） |
| 后端全量 | `BUILD SUCCESS`，**1323 例 0 失败**（门禁 05） |
| 后端定向 | `LayerRulesTest` / `AssetInfo*` / `AuthorizationBoundaryTest` / `PermissionMatrixTest` / `ProjectServiceDeleteTest` 共 **78 例 0 失败** |
| 证据完整性 | 05 日志含 `BUILD SUCCESS`；前端 596 ≥ 325、后端 1323 ≥ 1016、editor ≥ 219（门禁 19 PASS） |

## 4.1 项目生命周期实机验证（`TC-v4.3-40`~`41`）

夹具：临时项目 `code=tmp-lifecycle-161655`（id=86），验证后已彻底删除；该时点（16:16）库内 6 个项目。
其后的 75/76 清理见 §4.2。

| 动作 | 读数 |
|---|---|
| 建项目 | `POST /api/projects` → `id=86` |
| 造业务数据 | `POST /api/projects/86/views` → `id=1` |
| 非空删除 | `DELETE /api/projects/86` → **409**「项目下仍有保存视图 1 条，只能停用（归档），不能彻底删除」 |
| 停用 | `PUT /api/projects/86 {enabled:false}` → `enabled=false`，列表读回同步 |
| 启用 | `PUT /api/projects/86 {enabled:true}` → `enabled=true` |
| 清视图后删除 | `DELETE /api/projects/86` → **200** |
| 删除后查询 | `GET /api/projects/86` → **404**「项目不存在」 |
| 审计 | `audit_log` id=1711013：`action=project:delete`、`summary=before={code=tmp-lifecycle-161655; name=生命周期校验临时项目}; deleted=true` |
| 库回读 | `project` 表 6 行（该时点）：`1 / 29 / 75 / 76 / 77 / 80` |

> `busyCounts` 用 `information_schema` 动态发现所有带 `project_id` 的表，排除
> `project_setting / project_label / project_member`（删除时一并清）与 `audit_log / slo_daily`（合规保留），
> 避免「漏登记 → 硬删成功 → 留孤儿行」。

## 4.2 历史临时项目清理（75 / 76）

A8 报告链路夹具 `75`、`76` 按同一套前置校验先清业务数据、再走 API 删除：

| 动作 | 读数 |
|---|---|
| 清业务数据（单事务） | `keyword_result=8`、`test_result=8`、`run_artifact=12`、`execution_queue=6`、`resource_lease=0`、`run=4`（id 16526/16527/16529/16530）、`test_report=2`、`robot_file_version=6`、`robot_file=6`、`export_record=2`、`resource_lease_history=6`、`execution_job=6` → `COMMIT` |
| 删除 | `DELETE /api/projects/75` → **200**；`DELETE /api/projects/76` → **200** |
| 删除后查询 | `GET /api/projects/75`、`/76` → **404**「项目不存在」 |
| 审计 | `audit_log` id=1711038（75）、1711039（76）：`action=project:delete` |
| 库回读 | `project` 表 5 行：`1 demo / 29 qa-e2e-fixture / 77 ED202-COMMON / 80 demo-pytest / 87 P001` |

> 87（`P001`「测试001」）为人工在平台界面新建（2026-11-10 16:44，`status=0` 停用态）的项目，无业务数据，**保留不删**。

## 5. 说明

- 交付包与 `.sha256`、`.manifest.json` 已回写到工作副本 `releases/`，保证下次同步后第 30 步仍能实证。
- **缺口**：第 13 步需可连通的 CDP 端点；本机 WSL 无浏览器、Windows Chrome 与 WSL 双向不通，
  故 `TC-v4.3-37` 记 `PARTIAL`，不写 `FAILS=0`。
