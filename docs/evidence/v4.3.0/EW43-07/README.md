# EW43-07 · 平台资产引用与解除引用（W43-07，P1）

**日期** 2026-11-10 ｜ **对应** `TC-v4.3-30`、`FR-v4.3-08`

## 1. 改动

| 位置 | 文件 | 改动 |
|---|---|---|
| 页面 | `frontend/src/views/assets/AssetsView.vue` | 平台共享资产行新增「引用」「解除引用」；端口表单增 `nicName` / `nicIndex` 两列 |
| API 封装 | `frontend/src/api/asset.ts` | `shareApi.reference`、`shareApi.unreference` |
| 后端 | `backend/.../api/asset/*` | 引用写 `project_asset`；解除引用后触发缓存失效 |
| 词条 | 语言包 | 引用 / 解除引用 / 无权限提示 / 确认文案中英同步 |

## 2. 命令与读数

| 项 | 读数 |
|---|---|
| 引用后可见 | 写库即改变项目指纹 → 下次 `GET asset-info` 必然重算（`AssetInfoServiceTest` 覆盖） |
| 解除引用后 | 资产不可见 + 缓存失效（`AssetInfoInvalidator` 监听） |
| 无权限 | 提示且不清空表单（前端逻辑；`asset:update` 不足时后端 403） |

## 3. 说明

- P1 已实装，但**完整浏览器流程（引用 → 页面自动可用 → 解除引用 → 空态）未走**，`TC-v4.3-30` 记 `PARTIAL`。
- 该项属 P1：即便未测也不阻断 P0 收口，但**不得**据此称「引用链路已验证」。
