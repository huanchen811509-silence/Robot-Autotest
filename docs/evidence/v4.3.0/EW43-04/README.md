# EW43-04 · 资产表达式派生与两级缓存（W43-04）

**日期** 2026-11-10 ｜ **对应** `TC-v4.3-13`~`18`、`25`、`FR-v4.3-03`、`FR-v4.3-04`

## 1. 改动

| 位置 | 文件 | 改动 |
|---|---|---|
| 派生结果 | `backend/.../application/asset/AssetInfoResponse.java` | 从 `api/asset/dto` **上移**到 `application` 层（分层规则 R4 禁止 `application → api`） |
| 服务 | `backend/.../application/asset/AssetInfoService.java` | 连接明细（15 字段）、拓扑（设备→网卡→接口三级 + `stats`）、`includeDisabled` 过滤、别名组装 |
| 缓存 | 同上 | 两级：片段键 `link:{id}:{contentHash}`、项目键 `project:{projectId}:{指纹}`；单飞 + 单飞内二次检查；别名**不进**缓存键 |
| 配置 | `backend/.../application/asset/AssetInfoProperties.java` | `ASSET_INFO_CACHE_ENABLED`（默认 `true`）、`ASSET_INFO_CACHE_TTL_SECONDS`（默认 `43200`）、`ASSET_INFO_CACHE_MAX_PROJECTS`（默认 `512`） |

## 2. 命令与读数

```bash
# WSL ~/robot-autotest
mvn -o -B test -Dtest='AssetInfoServiceTest,AssetInfoCacheTest,AssetInfoControllerTest'
```

| 项 | 读数 |
|---|---|
| 三个测试类 | `AssetInfoServiceTest` 7 例、`AssetInfoCacheTest` 8 例、`AssetInfoControllerTest` 4 例，**19 例全绿** |
| 单飞 | 并发 10 请求只重算 1 次、返回同一份结果（用例以 `force=false` 触发，与单飞语义一致） |
| 排序 | 同设备按 `nic_index` 升序、`nic_name` 字典序；连接行按 `linkId` 稳定 |

## 3. 说明

- 「网卡」按用户纠正**只作端口的两个属性**，未建网卡实体表、未建焊接/线缆字段。
- 复用窗口**默认 12 小时（43200s）可配**，不是 300s；资产变更走事件 `AssetChangedEvent` → `AssetInfoInvalidator` 立即失效。
- **缺口**：真库 `device_port=0`、无 `P-BIG` 数据集，条数一致性（`TC-v4.3-15`）与两项目别名对照（`TC-v4.3-18`）缺现场证据。
