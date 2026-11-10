# v4.9.2 开发计划（收尾）

## 目标

把 4.4.0–4.9.0 的能力收敛为可发布 GA：门禁全绿、证据齐备、版本四处一致、关于页与版本迭代可见。

## 任务

| 编号 | 任务 | 产出 |
| --- | --- | --- |
| T49-16 | 全量单测复跑并修复回归 | 品牌 / 顶栏 / 账户菜单 / 审计 / 脱敏五处断言与新契约对齐 |
| T49-17 | 错误码 + traceId | `ApiError.traceId`、错误展示追加追踪号 |
| T49-18 | i18n 全量校验 | `tools/check_i18n.py` 0 缺失 |
| T49-21 | 迁移 down→up 演练 | 演练脚本 + 日志（环境受限时 NOT-RUN） |
| T49-22 | 版本四处一致 | `VERSION`、`build-info.properties`、OpenAPI、CHANGELOG |
| T49-23 | 发布六件套 | 4.4.0–4.9.0 与本版目录 |
| T49-24 | 关于 / 版本迭代 | 动态构建信息 + `<!-- current -->` 迁移 |

## 门禁顺序

1. `mvn -q -DskipTests compile`
2. `npx vue-tsc --noEmit`
3. `mvn test`（排除 `*IT`）
4. `npx vitest run`
5. `python3 tools/check_i18n.py`
6. `python3 tools/check_version_consistency.py --static`

## 完成定义

- 上述六步全部通过（除真库与 DUT 类明确标注 NOT-RUN）；
- 发布包 MANIFEST 与工作区文件一一对应；
- 人工核对清单逐条可勾选。
