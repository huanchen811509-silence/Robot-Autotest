# EW42-01 CI 无 DB 质量门（FR-v4.2-01）

> 结论：**通过**。在清空全部 `DB_*` 与 `CRED_MASTER_KEY` 的环境里跑后端非 IT 全量单测，
> `Tests run: 1301, Failures: 0, Errors: 0, Skipped: 4`、`BUILD SUCCESS`。
> 这证的是「CI 里没有 MySQL 也能红/绿」，不是「有库时更强」——有库的门在 `EW42-02`。

## 1. 命令与读数

```bash
env -u DB_HOST -u DB_PORT -u DB_NAME -u DB_USERNAME -u DB_PASSWORD -u CRED_MASTER_KEY \
  mvn -o -B test
```

| 读数 | 值 |
|---|---|
| 用例总数 | 1301（含 FR-v4.2-03 新增 8 例） |
| Failures / Errors | 0 / 0 |
| Skipped | 4（`RunLogWebSocketE2ETest` 等按 `@EnabledIfEnvironmentVariable(named="DB_PASSWORD")` 自动跳过） |
| 构建 | `BUILD SUCCESS` |
| 日志 | [no-db.log](./no-db.log) |

## 2. 为什么这样判

1. **只清 `DB_PORT` 不够**：第一次尝试时环境里仍有 `DB_PASSWORD` 与 `DB_PORT=1`，
   `RunLogWebSocketE2ETest` 会真的去连库并报 4 个错误。无库判据必须是「六个变量全不设」。
2. **不硬上**：没有为了变绿去改测试或加 `@Disabled`；跳过项本来就带条件注解，
   有库时自动参与，无库时自动退出。
3. **`delivery.yml` 已接线**：第 71 行 `Backend unit tests (no DB)`、第 77 行
   `Frontend unit tests (no DB, no CDP)` 已在构建前跑；不改门禁强度。

## 3. 哪些判不了（NOT-RUN）

| 项 | 为什么 | 由什么补 |
|---|---|---|
| `actionlint .github/workflows/*.yml` | 本机无 `actionlint`（`command -v actionlint` 为空），且离线环境不新增依赖 | 有 `actionlint` 的环境补跑；不拿 YAML 能解析冒充通过 |
| `gh run view <id>` 与缓存/时长读数 | 无 `gh`、无授权 | 与 `EW42-04` 一并补 |

## 4. 与 W41-07 的关系

`scripts/gates.sh` 第 5 步就是本条命令（32 步门禁里唯一跑全量单测的一步）。
本包不新增门禁步，只证明它在无库环境成立并把读数落盘。
