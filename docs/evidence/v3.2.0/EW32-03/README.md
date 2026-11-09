# EW32-03 · 读缓存（FR-3.2-03 / D32-3）

对应版本：v3.2.0「快而稳」· 批次 B3.3
证据日期：2026-11-08

## 1. 交付物

| 类型 | 路径 |
|---|---|
| 进程内 TTL 缓存 | `backend/.../common/cache/TtlCache.java` |
| 缓存配置（开关/TTL/容量） | `backend/.../common/cache/CacheProperties.java`（`app.cache.*`） |
| 能力索引缓存 | `backend/.../application/caseasset/CapabilityCache.java` |
| 读路径接入 | `CapabilityService.list`（先鉴权，后读缓存） |
| 写路径失效 | `TestCaseService`（create/update/delete/batch）、`CaseImportService`（import） |
| 单测 | `TtlCacheTest`（8 例）、`CapabilityServiceCacheTest`（4 例） |
| 日志 | `logs/tests-cache.log`、`logs/backend-full-test.log` |

## 2. 真实执行记录

```text
mvn "-Dtest=TtlCacheTest,CapabilityServiceCacheTest" test
→ Tests run: 12, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS（logs/tests-cache.log）
```

## 3. FR / TC 三态

| 项 | 状态 | 说明 |
|---|---|---|
| FR-3.2-03 读缓存 | 完成（范围见 §5） | 能力索引按 projectId 缓存，TTL 30s、上限 512 |
| FR-3.2-03 键含 projectId | 完成 | key 就是 projectId；测试证明 29/30 不串数据 |
| FR-3.2-03 写路径失效 | 完成 | 新建/修改/删除/批量/导入成功后立即 `invalidate(projectId)`；测试证明失效后第一次读回源 |
| FR-3.2-03 命中率与容量 | 完成 | `hitRate/size/maxEntries/evictions` 从 `/api/admin/observability/summary` 可读 |
| FR-3.2-03 关缓存等价 | 完成 | `app.cache.enabled=false` 时每次回源，结果与开启时一致（测试对比两个仓库 mock 的调用次数） |
| TC-3.2-05 缓存单测 | 完成 | 8 例覆盖命中/过期/容量/失效/开关/null/非法配置 |
| TC-3.2-05 写后一致 | 完成 | `invalidationRefetches` + 权限先于缓存 |

## 4. 设计要点

1. **先鉴权再读缓存**：`CapabilityService.list` 先 `access.bindProject` + `access.require`，再查缓存；
   否则缓存命中会绕过租户/权限校验。测试 `permissionCheckedBeforeCache` 在无权限时验证
   仓库方法一次都没被调用。
2. **只缓存纯聚合**：能力索引是「按 capability 计数」的只读聚合，失效成本低、缓存价值高。
   没有缓存详情/列表这类需要精细失效的数据，避免「写一个字段清半个缓存」。
3. **键即租户边界**：`Map<Long, ...>` 的 key 是 projectId，不拼用户/角色；权限变化不影响数据本身。
4. **过期不续期**：TTL 从写入时刻算，命中不延长；避免热点 key 永不失效。

## 5. 偏差与未执行项

1. **只接了一个读路径**：计划写「总览与参考数据缓存」。实测 `cases_list` p95 31ms、`runs_list` 42ms、
   `reports_list` 19.6ms、`audit_list` 20ms（EW32-01），都在 150ms 预算内；再给它们加缓存只会
   引入失效复杂度，故只缓存最重的能力聚合。若 B4.1 容量压测显示总览成为热点，再按同一套
   `TtlCache` 接入。
2. **进程内缓存，不跨实例**：单实例部署；多实例一致性不在本版范围（ADR-3X-04 已记录）。
3. **容量淘汰近似 FIFO**：缓存值可重算，淘汰顺序不影响正确性；未实现严格 LRU。
4. **未做缓存预热**：冷启动第一次请求回源，属预期。

## 6. 复现命令

```bash
cd backend
mvn "-Dtest=TtlCacheTest,CapabilityServiceCacheTest" test
# 关缓存等价：由 CapabilityServiceCacheTest#disabledMatchesEnabled 覆盖（构造 enabled=false 的缓存）
```

## 7. 回滚

- 运行时关闭：`APP_CACHE_ENABLED=false`（无需改代码，行为等价于无缓存）。
- 代码回滚：删除 `CapabilityCache` 注入与 `TtlCache` 调用；`CapabilityService` 已有
  2 参构造器，直接回退到 `cases.capabilities(projectId)` 即可。
