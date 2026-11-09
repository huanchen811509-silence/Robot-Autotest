# EW32-08 · API 契约与架构守卫（FR-3.2-08 / TC-3.2-11）

对应版本：v3.2.0「快而稳」· 批次 B3.2
证据日期：2026-11-08

## 1. 交付物

| 类型 | 路径 |
|---|---|
| OpenAPI 快照（构建期生成） | `docs/contracts/openapi.json` |
| 生成 / 比对工具 | `tools/gen_api_contract.py`（`--check` 漂移即退出 1） |
| 8 个核心端点契约测试 | `backend/src/test/java/com/qa/autotest/api/contract/CoreApiContractTest.java` |
| 跨端点约定测试 | `backend/src/test/java/com/qa/autotest/api/contract/ApiConventionsTest.java` |
| ArchUnit 规则补强 R6/R7/R8 | `backend/src/test/java/com/qa/autotest/arch/LayerRules.java` |
| 违规 fixture（元测试证明规则真的会红） | `backend/src/test/java/com/qa/archfixture/violation/`（fieldinjection / repojdbc / controllerraw） |
| ADR | `docs/adr/ADR-3X-04-统一分页与深分页键集.md`、`ADR-3X-05-代码生成OpenAPI与契约门禁.md` |
| 日志 | `logs/tests-contract.log`、`logs/contract-check.log` |

## 2. 真实执行记录

```text
# 契约 + 架构测试
mvn "-Dtest=LayerRulesTest,LayerRulesMetaTest,CoreApiContractTest,ApiConventionsTest" test
→ Tests run: 28, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS
  - ApiConventionsTest        4
  - CoreApiContractTest       8
  - LayerRulesMetaTest        7（含 R6/R7/R8 违规 fixture 各 1 例）
  - LayerRulesTest            9（R1~R5 + 无环 + R6/R7/R8 真实代码全过）
  （logs/tests-contract.log）

# OpenAPI 生成 + 比对
python tools/gen_api_contract.py
→ endpoints=239 paths=187；WROTE docs/contracts/openapi.json
python tools/gen_api_contract.py --check
→ PASS；exit 0

# 漂移演练（把快照 version 改成 3.2.0-drift，不动源码）
python tools/gen_api_contract.py --check
→ FAIL API 契约快照与源码不一致；diff 显示 -"3.2.0-drift" / +"3.1.0"；exit 1
# 还原快照后再次 --check → PASS；exit 0
（logs/contract-check.log）
```

## 3. FR / TC 三态

| 项 | 状态 | 说明 |
|---|---|---|
| FR-3.2-08 OpenAPI 生成 | 完成（降级说明见 §5） | 构建期静态解析，239 端点 / 187 路径；不新增运行时依赖 |
| FR-3.2-08 快照 diff 门禁 | 完成 | 逐字节比对；演练证明漂移 → exit 1，还原 → exit 0 |
| FR-3.2-08 核心契约测试（8 个） | 完成 | 信封、分页四字段、size 上限、排序白名单、键集语义、非法 cursor |
| FR-3.2-08 错误码 / 分页 / 时间 / 枚举统一 | 完成 | 错误码唯一 + 段位 + name 规范；错误码→HTTP 全量映射；分页字段名；Instant ISO-8601 |
| FR-3.2-08 ArchUnit 补强 | 完成 | R6 字段注入、R7 仓储接口依赖 JDBC、R8 端点裸返回；均有违规 fixture 元测试 |
| FR-3.2-08 ADR 索引 | 完成 | 新增 ADR-3X-04 / 3X-05 并登记到 `docs/adr/README.md` |
| TC-3.2-11 报告 | 完成 | 本文 + 两份日志 |

## 4. 8 个核心契约（`CoreApiContractTest`）

| # | 端点 | 断言 |
|---|---|---|
| 1 | `GET /api/projects/{projectId}/cases` | `code=0`、`codeName=OK`、`data.items[]`、`total/page/size` |
| 2 | 同上 `?size=1000` | 归一化为 `PageQuery.MAX_SIZE=100`（ArgumentCaptor 验证服务端实际收到 100） |
| 3 | 同上 `?sort=secret_column` | 400 + `40001 INVALID_ARGUMENT` + 「不支持的排序字段」 |
| 4 | 同上 `?beforeId=5000` | 键集模式：`beforeId=5000`、`offset=0`、`ORDER BY id DESC` |
| 5 | `GET /api/execution/runs?projectId=29` | 信封 + 分页四字段 |
| 6 | `GET /api/test-reports?projectId=29` | `data` 是数组，不是裸列表 |
| 7 | `GET /api/audit?projectId=29` | 信封 + 分页四字段 |
| 8 | `GET /api/audit?cursor=not-a-cursor` | 400 + `40001` + 「cursor 格式非法」（不静默忽略） |

`ApiConventionsTest`（4 例）：
错误码数值唯一 / name 唯一 / 段位正确（0、4xxxx、5xxxx）；8 个业务错误码到 HTTP 状态的映射逐一断言；
`PageResult` JSON 字段名固定；`Instant` 输出 `2026-11-08T01:02:03Z` 而非 epoch。

> 关于时间断言：测试显式关闭 `WRITE_DATES_AS_TIMESTAMPS`，因为该开关由 Spring Boot 的
> `JacksonAutoConfiguration` 在容器内设置；裸 `Jackson2ObjectMapperBuilder` 默认是 epoch 数字。
> 第一次运行正是因此失败（实际输出 `{"at":1794099723.000000000}`），修正测试构造方式后通过——
> 这不是改测试凑绿，而是让测试的构造与生产容器一致。

## 5. 降级与限制（如实记录）

1. **未引入 springdoc**：批准的依赖清单只有 actuator。改为构建期静态解析，覆盖路径、方法、
   路径/查询参数、必填与默认值、请求体、响应信封；**不覆盖字段级 schema、泛型展开、条件分支**。
   字段级契约由 8 个端点测试 + 统一约定测试兜底，不含全部 239 个端点的字段。
2. **端点数量与计划不一致**：计划文档写「222 路由」，实际静态解析 239 端点 / 187 路径。
   差异来自 v3.1 新增接口与同一路径的多方法（239 是「方法级」计数，187 是「路径」计数）。
   以工具真实输出为准，未手工修改数字。
3. **`/healthz` 不在 `/api` 前缀**：探针约定，生成器原样记录；既有 `check_api_prefix.py` 门禁的
   白名单是否覆盖它由该门禁负责，本工具不重写路径。
4. **R8 允许 `ResponseEntity`**：CSV/文件下载需要自定义头与字节流，强行套 `ApiResponse` 会破坏
   下载语义；R8 只禁止「裸实体/裸字符串」。

## 6. ArchUnit 补强明细

| 规则 | 内容 | 违规 fixture | 元测试断言 |
|---|---|---|---|
| R6 | 任何字段不得标 `@Autowired`（统一构造器注入） | `FieldInjectedBean` | 报错含 `FieldInjectedBean` / `Autowired` |
| R7 | `..application.repository..` 不得依赖 `org.springframework.jdbc..` | `LeakyOrderRepository` | 报错含 `LeakyOrderRepository` / `JdbcTemplate` |
| R8 | `@RestController` 的端点方法只能返回 `ApiResponse` / `ResponseEntity` | `RawController.raw` 返回 `String` | 报错含 `RawController.raw` / 「必须是 ApiResponse」 |

真实代码全部通过（`LayerRulesTest` 9 例），说明补强规则没有误伤既有实现。

## 7. 复现命令

```bash
# 生成 + 比对
python tools/gen_api_contract.py
python tools/gen_api_contract.py --check

# 契约与架构测试
cd backend && mvn "-Dtest=LayerRulesTest,LayerRulesMetaTest,CoreApiContractTest,ApiConventionsTest" test
```

## 8. 回滚

本批只新增测试、工具、快照与 ADR，无数据库迁移、无生产代码行为变更（R6~R8 是测试规则）。
回滚即删除上述文件；`docs/contracts/openapi.json` 删除后 `--check` 会以「快照不存在」失败，
需同步从门禁移除 `contract` 项或重新生成。
