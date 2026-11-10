# EW43-01 · 权限四处联动（W43-01）

**日期** 2026-11-10 ｜ **对应** `TC-v4.3-09`、`FR-v4.3-02`

## 1. 四处改动

| 位置 | 文件 | 改动 |
|---|---|---|
| 权限矩阵 | `backend/src/main/java/com/qa/autotest/domain/iam/PermissionMatrix.java` | PM 的 PROJECT 动作集 `EnumSet.of(READ, UPDATE)` → `EnumSet.of(READ, CREATE, UPDATE)` |
| 单元测试 | `backend/src/test/java/com/qa/autotest/domain/iam/PermissionMatrixTest.java` | 期望行 `R-U---` → `RCU---`；`项目经理边界()` 增「可 CREATE」断言 |
| 契约样本 | `backend/src/test/resources/authz-matrix.yaml` | `AUTHZ-PM-PROJECT-WRITE` 的 `expected` 由 `DENY` 改 `ALLOW` |
| 需求文档 | `docs/release/v4.3.0/PRD.md` §3.1 | 角色表 PM 的「创建项目」标为允许 |

## 2. 命令与读数

```bash
# WSL ~/robot-autotest
mvn -o -B test -Dtest='PermissionMatrixTest,AssetInfoServiceTest,AssetInfoCacheTest,AssetInfoControllerTest'
```

| 项 | 读数 |
|---|---|
| 结果 | `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0` |
| `PermissionMatrixTest` | 全绿，含 `项目经理边界()` 与 `RCU---` 期望行 |
| `AuthzMatrixTest` | 随门禁后端全量单测执行，`AUTHZ-PM-PROJECT-WRITE` 判 `ALLOW` |

## 3. 说明

- PM 与 ADMIN 同持 `PROJECT CREATE`；ENGINEER / VIEWER 仍无，`TC-v4.3-04` 的四角色对照以此为据。
- 未放宽任何资源的既有动作集，只补 PM 的 `CREATE` 一项。
