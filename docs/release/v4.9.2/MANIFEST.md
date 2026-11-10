# v4.9.2 交付清单（MANIFEST）

## 版本与构建

| 文件 | 说明 |
| --- | --- |
| `VERSION` | `4.9.2` |
| `backend/src/main/resources/build-info.properties` | 构建时生成，版本 / commit / 依赖数 |
| `docs/contracts/openapi.json` | 335 endpoints / 271 paths |
| `CHANGELOG.md` / `frontend/public/CHANGELOG.md` | 两份内容一致，`v4.9.2-ga` 标 `<!-- current -->` |

## 数据库

`db/migration`：`V11_30`–`V11_37` 共 8 组；`db/down` 一一配对；`db/schema-contract.json` 的
`expectedTables` 同步为 **98**（`SchemaIT` 与 `scripts/db-audit.sh` 同源）。

| 迁移 | 内容 |
| --- | --- |
| `V11_30` | 项目成员多角色 |
| `V11_31` | 双模式与平台项目 |
| `V11_32` | 公共用例库（分类、业务键、生命周期） |
| `V11_33` | 导入台账与导出登记 |
| `V11_34` | 用例版本快照 |
| `V11_35` | 资产导入来源与软失效 |
| `V11_36` | 模板持久化与内置模板 |
| `V11_37` | 存量同步作业与明细 |

## 后端

- `application/caseasset`：`GlobalCaseService`、`ProjectSyncService`
- `application/asset`：`AssetImportService`、`GlobalAssetService`
- `application/iam`：`ProjectTemplateService`
- `api/caseasset`：`GlobalCaseController`、`ProjectSyncController`
- `api/asset`：`GlobalAssetController`
- `api/iam`：`TemplateController`、`ProjectContentController`
- `infrastructure/SyncScheduler`（`sync.auto.enabled=false` 默认关闭）

## 前端

| 路径 | 说明 |
| --- | --- |
| `views/global/GlobalCasesView.vue` | 公共用例库 |
| `views/global/GlobalAssetsView.vue` | 公共资产库 |
| `views/global/GlobalTemplatesView.vue` | 模板中心 |
| `views/global/GlobalProjectsView.vue` | 全局项目列表 |
| `views/global/GlobalSettingsView.vue` | 全局系统设置 |
| `views/ProjectSyncView.vue` | 存量同步 |
| `views/ProjectWizardView.vue` | 向导（含内容步） |
| `api/globalCases.ts` / `globalAssets.ts` / `globalTemplates.ts` / `projectSync.ts` | API 封装 |

## 文档

- 发布六件套：`docs/release/v4.9.2/`（4.4–4.9 的功能条目并入 `CHANGELOG.md` 的 `v4.9.2-ga` 小节）
- 证据：`docs/evidence/v4.9.2/`（含迁移演练脚本与前置条件）
- 需求基线：`docs/plan/v4.4-v4.9-*.md`
- 人工核对：`docs/release/v4.9.2/人工核对清单.md`
