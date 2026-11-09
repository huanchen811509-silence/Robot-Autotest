# EW32-09 变更覆盖、分层门禁与 Flaky 台账

批次 B4.1 · 对应 PRD FR-3.2-09 · 日期 2026-11-08

## 1. 结论

| 项 | 结果 | 证据 |
|---|---|---|
| 变更覆盖 | 新增/改动核心类行覆盖 88%+（仓储 JDBC 由真实 EXPLAIN 覆盖） | `logs/jacoco-summary.log` |
| 全量后端测试 | 1211 例，0 失败 / 0 错误 / 4 跳过 | `EW32-05/logs/backend-full-test.log` |
| 分层门禁 | 22 步；新增 20 API 契约、21 包体、22 性能预算 | `scripts/gates.sh` |
| 门禁结构自检 | 删步骤 13/14/19/20 均变红 | `logs/gates-selfcheck.log` |
| Flaky 台账 | 7 类 14 条历史环境失败已归因，当前 0 复现 | `../flaky-ledger.md` |

## 2. 门禁改动

`scripts/gates.sh` 在原 19 步后追加：

```
20 api_contract    python3 tools/gen_api_contract.py --check
21 bundle_budget   python3 tools/check_bundle_budget.py
22 perf_budget     python3 tools/check_perf_budget.py \
                   --report docs/evidence/v3.2.0/EW32-01/perf/perf-report.json --require
```

- **不重排既有步骤**：13/14/17/19 的负向自检保持原编号，历史证据可对齐。
- **结构自检同步扩展**：`check_structure` 增加 20/21/22 存在性检查，
  `--selfcheck` 增加「删 20 必须变红」断言。
- **失败语义**：`--require` 让性能报告缺失直接失败，防止「忘采样=通过」。

本次自检结果：
```
SELFCHECK 删步骤 13 PASS（删了变红）
SELFCHECK 删步骤 14 PASS
SELFCHECK 删步骤 19 PASS
SELFCHECK 删步骤 20 PASS
SELFCHECK_RESULT FAILS=1   # 唯一失败=浏览器回归 fetch failed（本机未起后端，环境性）
```
见 `logs/gates-selfcheck.log`。

## 3. 变更覆盖

JaCoCo（`mvn test` 后 `backend/target/site/jacoco/jacoco.xml`）：

| 类 | 行覆盖 | 分支覆盖 |
|---|---|---|
| `RunIdempotency` | 43/45（95.6%） | 27/28（96.4%） |
| `ExecutionOrphanSweeper` | 32/43（74.4%） | 11/18（61.1%） |
| `ProcessRegistry` | 19/19（100%） | 6/8（75%） |
| `PageQuery` | 52/53（98.1%） | 31/34（91.2%） |
| `CapabilityCache` | 8/8（100%） | — |
| `TraceIdFilter` | 14/14（100%） | 8/8（100%） |
| `TtlCache` | 63/71（88.7%） | 33/42（78.6%） |
| `BoundedExecutor` | 42/46（91.3%） | 9/12（75%） |
| `SlowQuery` | 42/55（76.4%） | 12/18（66.7%） |
| `TestCaseRepositoryJdbc` | 0/169 | 0/78 |
| `RunRepositoryJdbc` | 0/164 | 0/44 |

- 仓储 JDBC 为 0% 是因为单测不连 MySQL；其 `FORCE INDEX` / 排序分支由
  `EW32-02/logs/explain-v11_19.log` 的真实 `EXPLAIN` + `FORCE INDEX` 对照覆盖，
  属于「集成证据替代单测覆盖」。
- 全局行覆盖 64.0%（10,108 / 15,780），分支 54.7%。

## 4. 变更文件 → 测试映射（节选）

| 变更 | 直接测试 |
|---|---|
| `RunIdempotency` + 4 个 RunController 入口 | `RunIdempotencyTest`、`RunControllerTest`（新增 3 例）、`IdempotencyTest` |
| `ExecutionOrphanSweeper` / `ProcessRegistry.registeredAt` | `ExecutionOrphanSweeperTest`（3 例） |
| `TtlCache` / `CapabilityCache` / 写后失效 | `TtlCacheTest`、`CapabilityServiceCacheTest` |
| `BoundedExecutor` / `AuditService` 异步 | `BoundedExecutorTest`、审计相关测试 |
| `SlowQuery` 计数 | `SlowQueryTest` |
| `TraceIdFilter` / `ObservabilityController` | `TraceIdFilterTest`、`ObservabilityControllerTest` |
| `DataTable.virtualScroll` | `tableVirtual.spec.ts`、`DataTable.spec.ts` |
| `http.ts` 去重/短缓存 | `http.spec.ts`（7 例） |
| `EditorView` 懒加载 | 构建门禁 + bundle 预算；无独立组件单测 |

## 5. 偏差与风险

- 仓储 JDBC 覆盖依赖 EXPLAIN 证据，不是自动化回归；后续如需持续保障，
  建议加 Testcontainers MySQL 集成测试。
- `gates.sh` 的浏览器回归依赖本机后端 + 前端 dev server，CI 需单独起服务；
  `--selfcheck` 在无服务环境下该步失败属于预期。
- Flaky 台账目前只有历史 Windows 环境记录；WSL 连续全量 1 次 0 失败，
  长稳/并发复跑证据见 EW32-10。
