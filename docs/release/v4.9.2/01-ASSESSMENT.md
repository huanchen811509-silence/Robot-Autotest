# v4.9.2 影响评估

## 1. 前置条件

- 预发或生产 MariaDB 可连接，已备份目标库；
- 部署环境提供 `DB_PASSWORD`（本仓库不保存）；
- Flyway 允许执行 `V11_30`–`V11_37`；
- 前端构建使用 Node 20，后端 Java 21；
- 发布窗口内不允许并行执行其他 DDL。

## 2. 影响面

| 层 | 变更 |
| --- | --- |
| 数据库 | 新增 `case_category`、`import_item`、`export_record`、`case_version`、`project_template_item`、`project_template_asset`、`sync_job`、`sync_job_item`；`test_case` / `device` / `credential` / `import_batch` / `project_template` 增列与索引 |
| 后端 | 新增 `GlobalCaseService`、`GlobalAssetService`、`AssetImportService`、`ProjectTemplateService`、`ProjectSyncService` 及对应控制器；`SyncScheduler` 默认关闭 |
| 前端 | 新增 `/g/cases`、`/g/assets`、`/g/templates`、`/sync` 页面与 API 封装；向导增加内容步；账户菜单收敛帮助入口；错误展示追加 traceId |
| 导航 | 全局模式独立导航；项目导航「维护与恢复」新增「存量同步」 |

## 3. 兼容性

- 新增列均可空或带默认值（`safety_level` 缺省 `LX`、`lifecycle_status` 缺省 `active`、`version_no` 缺省 `1`）。
- 存量用例在 `V11_32` 中按 `case_kind` / `case_code` 回填，不需要人工改数据。
- 旧接口路径未改动；新增路径均为增量。

## 4. 风险与措施

| 风险 | 措施 |
| --- | --- |
| 权限并集放大越权面 | 后端 `AccessControlService.require` 二次校验，前端只负责隐藏入口 |
| 导入重复污染公共库 | 文件 sha256 / 行 external_id / content_sha 三层幂等 + 预览回放 |
| 同步覆盖丢本地修改 | 冲突默认 `KEEP_LOCAL`；覆盖前写 `before_json`，支持整单回滚 |
| 资产删除导致引用悬空 | 删除前查 `project_asset` 引用数，>0 返回 409 |
| 凭据明文落库 | 只存 `secret_ref` 与掩码，导入只生成 `mask()` |

## 5. 回退方案

1. 应用回退到上一 tag；
2. 倒序执行 `db/down/V11_37` → `V11_30` 回滚脚本；
3. 演练脚本见 [migration-drill-V11_30-37.sh](file:///E:/workspace/自动化测试/Robot-Autotest/docs/evidence/v4.9.2/migration-drill-V11_30-37.sh)。

## 6. 结论与不做的事

**做**：六块能力的 GA 收敛、门禁、证据、版本一致、关于 / 版本迭代展示。

**不做**：
- 不新增业务范围，不重构既有执行 / 报告引擎；
- 不做跨实例公共库复制；
- 不引入新的权限模型，沿用 `PermissionMatrix` 并集；
- 不伪造 DUT、负载或缺失样本的测试结果；
- 不自动执行存量同步的覆盖动作。

## 7. 证据分级

| 结论 | 等级 | 依据 |
| --- | --- | --- |
| 后端 / 前端自动化门禁通过 | [已核实] | 本轮命令输出与 surefire / vitest 汇总 |
| 迁移脚本可正向执行 | [已核实] | 脚本内容与列名逐条核对 |
| down→up 真库演练通过 | [假设] | 环境缺口令，尚未执行，脚本已备 |
| DUT / 负载结论 | [假设] | 无环境，不给出结论 |

## 8. 修订记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v1.0 | 2026-11-10 | 初稿：前置条件、影响面、风险、回退、不做清单、证据分级 |

