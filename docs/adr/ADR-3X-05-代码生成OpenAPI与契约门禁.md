# ADR-3X-05 · 以代码生成 OpenAPI 与契约门禁

**状态** 已采纳 ｜ **日期** 2026-11-08 ｜ **约束范围** v3.2.0 起全部 HTTP 接口
**关联** FR-3.2-08、ADR-3X-01、`docs/evidence/v3.2.0/EW32-08/`

## 背景

后端现有 239 个端点（187 条路径，`tools/gen_api_contract.py` 实测）。靠人工维护接口文档
不现实：改一个 `@RequestParam` 名字，前端与文档同时失真，而且没有门禁会红。

Springdoc 可以运行时生成，但需要新增运行时依赖，超出 v3.2.0 批准的依赖清单（D32-1 只批了 actuator）。

## 决策

1. **构建期静态解析**：`tools/gen_api_contract.py` 扫描 `api/**/*Controller.java`，
   提取类级/方法级映射、路径变量、查询参数（含 `required`/`defaultValue`）、请求体、
   以及是否返回分页信封，生成 OpenAPI 3.0.3 骨架到 `docs/contracts/openapi.json`。
2. **快照即合同**：`--check` 与提交的快照逐字节比对，漂移即退出 1；确认变更后重新生成并随
   PR 提交，破坏性变更在评审中显式标注。
3. **字段级契约靠测试**：静态解析不承诺字段级 schema，改由
   `CoreApiContractTest`（8 个核心端点：信封/分页四字段/排序白名单/键集/错误码）与
   `ApiConventionsTest`（错误码唯一与段位、错误码→HTTP、分页字段名、ISO-8601 时间）兜底。
4. **分层用 ArchUnit 守**：在既有 R1~R5 上补 R6（禁止字段注入）、R7（`application.repository`
   不得依赖 Spring JDBC）、R8（控制器端点必须返回 `ApiResponse`/`ResponseEntity`），
   每条规则配违规 fixture 的元测试。

## 备选方案

| 方案 | 否决原因 |
|---|---|
| 引入 springdoc-openapi | 新增运行时依赖未获批准；启动期扫描对 239 端点开销不必要 |
| 手写 YAML | 与代码双写，必然漂移，且没有门禁 |
| 只做运行时 Schema 导出 | 需要起容器，CI 成本高；本版先解决「路径/参数漂移」 |

## 后果

- 正面：改端点必然触发快照 diff；参数名、必填性、返回信封在评审里可见。
- 负面：静态解析不覆盖字段级 schema、泛型展开与条件分支；这些由契约测试补位。
- 已知限制：`/healthz` 不在 `/api` 前缀下（探针约定），生成器按原样记录，不做重写。
- 证据：`EW32-08` 的 `contract-check.log`（含漂移演练：改快照 → exit 1 → 还原 → exit 0）。

## 修订记录

| 日期 | 变更 |
|---|---|
| 2026-11-08 | 首版；239 端点 / 187 路径快照，8+4 契约测试，ArchUnit R6~R8 |
