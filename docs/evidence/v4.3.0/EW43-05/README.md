# EW43-05 · 接口、失效与契约（W43-05）

**日期** 2026-11-10 ｜ **对应** `TC-v4.3-19`~`25`、`TC-v4.3-32`、`FR-v4.3-05`、`FR-v4.3-06`

## 1. 改动

| 位置 | 文件 | 改动 |
|---|---|---|
| 接口 | `backend/.../api/asset/AssetInfoController.java` | `GET /api/projects/{id}/asset-info`、`POST /api/projects/{id}/asset-info/refresh` |
| 审计 | 同上 | 刷新写审计 `asset-info.refresh`（含 scope、force） |
| 失效 | `backend/.../application/asset/AssetChangedEvent.java`、`AssetInfoInvalidator.java` | 资产变更（设备改 IP / 链路改速率 / 解除引用）即清相关缓存键 |
| 契约 | `docs/contracts/openapi.json` | `python3 tools/gen_api_contract.py` 重生成 |

## 2. 命令与读数

```bash
# WSL ~/robot-autotest
python3 tools/gen_api_contract.py --check
python3 tools/check_version_consistency.py
```

| 项 | 读数 |
|---|---|
| OpenAPI 快照 | **293 endpoints / 234 paths**，`info.version=4.3.0` |
| `--check` | 不漂移（门禁第 20 步 PASS） |
| 版本一致性 | 静态四处一致（VERSION / build-info / CHANGELOG 两份 / openapi），门禁第 23 步 PASS |

## 3. 说明

- `refresh` 需 `asset:update`：VIEWER / ENGINEER 返回 `403`，ADMIN / PM 通过（`AssetInfoControllerTest` 覆盖）。
- **缺口**：真实审计表查询与「引用前后两次 GET」的真 HTTP 流程未走；负向注入（`TC-v4.3-33`~`35`）未执行。
