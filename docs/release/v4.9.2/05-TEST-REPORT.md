# v4.9.2 测试报告

执行环境：WSL Ubuntu-24.04 权威副本（`~/robot-autotest`），Java 21 / Node 20。
执行时间：2026-11-10 19:00–19:20 CST。

## 结果汇总

| 门禁 | 结果 | 数据 |
| --- | --- | --- |
| 后端编译 | PASS | `mvn -q -DskipTests compile` |
| 后端单测（非 IT） | PASS | **1396 例 / 0 失败 / 0 错误 / 4 条件跳过** |
| 前端类型检查 | PASS | `vue-tsc --noEmit` 0 错误 |
| 前端单测 | PASS | **83 文件 / 607 例全部通过** |
| i18n 校验 | PASS | 缺失键 0 |
| 版本一致性（静态） | PASS | `VERSION=4.9.2`、`build-info=4.9.2`、OpenAPI=4.9.2 |
| OpenAPI 快照 | PASS | 335 endpoints / 271 paths |

## 用例结果

| 用例 | 结果 | 证据 |
| --- | --- | --- |
| TC-v4.9.2-01 | PASS | 后端单测（权限 / 成员 / 编码） |
| TC-v4.9.2-02 | PASS | `AppHeader.spec`、路由守卫单测 |
| TC-v4.9.2-03 | PASS | 公共用例服务单测 |
| TC-v4.9.2-04 | PASS | 资产服务单测 |
| TC-v4.9.2-05 | PASS | 模板服务单测 |
| TC-v4.9.2-06 | PASS | 同步服务单测 |
| TC-v4.9.2-07 | PASS | `http.spec`、错误展示单测 |
| TC-v4.9.2-08 | PASS | `AppLogo.spec`、`AccountMenu.spec` |
| TC-v4.9.2-09 | PASS | `tools/check_i18n.py` |
| TC-v4.9.2-10 | PASS | `check_version_consistency.py --static` |

## 本版修复的回归

| 用例 | 原问题 | 处理 |
| --- | --- | --- |
| `AuditAspectTest.annotationDeclarations` | 重载方法顺序不定，命中无注解的 `addMember` | 改为扫描同名重载，任一带 `@Audited` 即通过 |
| `AuditAspectTest.memberUpdate` | 多角色后摘要口径变 `roles=[]` | 断言对齐新契约 |
| `SecretRedactorTest.masksKeyValue` | `Authorization: Bearer x` 被二次脱敏成两段 `<redacted>` | lookahead 移入值分组，方案词不再被吃掉 |
| `AppLogo.spec` | v4.5 换成品牌资产，旧手写几何断言失效 | 断言改为资产来源、viewBox、无文字、token 颜色 |
| `AppHeader.spec` | 新增模式段后段数与控件数变化 | 断言四段 / 工程师三段、7 与 6 枚控件 |
| `AccountMenu.spec` | 管理入口收敛到全局设置 | 断言仅保留帮助入口，管理项不再重复 |

## 未执行

| 项 | 原因 | 复现 |
| --- | --- | --- |
| 真库迁移 down→up（V11_30–V11_37） | 本机无 `DB_PASSWORD` | `docs/evidence/v4.9.2/migration-drill-V11_30-37.sh` |
| SchemaIT / 接口 IT | 同上，`-Pit` 需要 `robot_it` 库 | `mvn -Pit test` |
| DUT / 负载 / 网络损伤 | 无对应硬件与环境 | — |
| 用户样本工作簿导入 | `基本功能导入测试v0.5.0.0.xlsx` 不在仓库 | 用 `fixtures/ed202/ED202测试用例_V0.4.xlsx` 替代 |

## 结论

自动化可覆盖部分全部通过；集成与硬件相关结论标注 NOT-RUN，未以推断或样本外数据代替。
