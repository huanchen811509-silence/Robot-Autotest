# EW42-02 SchemaIT / db-audit 口径对齐（FR-v4.2-02）

> 结论：**通过**。`SchemaIT` 7/7 PASS，`db-audit` PASS，两者都从同一份
> `db/schema-contract.json` 读常量；不存在「IT 一套数、审计脚本另一套数」。

## 1. 判了什么

| 项 | 读数 | 依据 |
|---|---|---|
| 业务表数 | 89（期望 89） | [db-audit.log](./db-audit.log) |
| 缺审计四列的库表 | 0 | 同上 |
| append-only 且有 `created_at` | 8 | 同上（另有 3 张用业务时间锚点，见下） |
| 主键 / 时间类型 / 字符集 | 0 / 0 / 0 违规 | 同上 |
| SchemaIT 用例 | `Tests run: 7, Failures: 0, Errors: 0`，46.54 s | [schema-it.log](./schema-it.log) |

## 2. 同源常量怎么落

`db/schema-contract.json` 一份文件同时被两端读：

- `tools/schema_contract.py` → `scripts/db-audit.sh` 的第 29 步判据；
- `backend/src/test/java/com/qa/autotest/db/SchemaIT.java` → 直读同一 JSON。

新增的 `appendOnlyTimeColumns` 解决了一个真实冲突：`alert_event` / `archived_run` /
`release_record` 三张 append-only 表**本来就没有 `created_at`**，时间锚点是
`detected_at` / `archived_at` / `deployed_at`。原判据要求「append-only 必须有 created_at」，
在真库上必然判红；现在契约明确「created_at 或业务时间锚点二选一」，且两者互斥：

```json
"createdAtRequired": [ ...8 张... ],
"appendOnlyTimeColumns": {
  "alert_event": "detected_at",
  "archived_run": "archived_at",
  "release_record": "deployed_at"
}
```

`tools/schema_contract.py` 会拒绝：锚点表不在 append-only 清单里、锚点列为空、
或同一张表既声明 `created_at` 又声明锚点。

## 3. 怎么跑（别踩的两个坑）

```bash
# SchemaIT 必须 -Pit：默认 excludedGroups=it，只写 -Dgroups=it 会被抵消成 0 个用例而仍 BUILD SUCCESS
DB_NAME=robot_it mvn -o -B -Pit test -Dtest=SchemaIT

# db-audit 打开发库
bash scripts/db-audit.sh
```

1. **`-Dgroups=it` 的假通过**：计划里写的 `mvn -o -B test -Dgroups=it` 会跑 0 个用例。
   已改为 `-Pit`，并写进 `SchemaIT` 类注释。
2. **`DB_NAME` 必须先覆盖成 `robot_it`**：`~/.config/robot-autotest/env` 里是 `robot_dev`，
   第一次跑被 SchemaIT 的 `_it` 后缀守卫拦下（日志：[schema-it-devdb-refused.log](./schema-it-devdb-refused.log)）。
   这不是测试失败，是守卫按设计生效——**没有**为了让绿去放宽守卫。

## 4. 负向自证

`db/schema-contract.json` 改坏两处（表数改 88、append-only 去掉一张）后
`tools/schema_contract.py` 均判红；恢复后 PASS。负向结果记在
`../EW42-05/README.md` 的门禁条目里，不单独留存临时文件。
