# v4.9.2 测试计划

## 覆盖矩阵

| 用例 | 覆盖需求 | 关键场景 | 类型 |
| --- | --- | --- | --- |
| TC-v4.9.2-01 | FR-v4.9.2-01 | 多角色并集、成员批量添加部分失败、改码后旧码失效 | 单测 / 接口 |
| TC-v4.9.2-02 | FR-v4.9.2-02 | 平台项目唯一、全局路由守卫、顶栏四段渲染 | 组件单测 |
| TC-v4.9.2-03 | FR-v4.9.2-03 | 三层幂等、预览回放、导出行数、错误报告 | 服务单测 |
| TC-v4.9.2-04 | FR-v4.9.2-04 | 分节解析告警、business-key upsert、软失效、409 保护 | 服务单测 |
| TC-v4.9.2-05 | FR-v4.9.2-05 | 编码正则、内置模板只读、应用内容生成用例集 | 服务单测 |
| TC-v4.9.2-06 | FR-v4.9.2-06 | 五态分类、冲突三分支、回滚删除与恢复 | 服务单测 |
| TC-v4.9.2-07 | FR-v4.9.2-07 | 错误码与 traceId 展示、空 / 错 / 载态 | 组件单测 |
| TC-v4.9.2-08 | FR-v4.9.2-01、FR-v4.9.2-02 | 品牌标记来自资产、无文字、颜色走 token；账户菜单收敛 | 组件单测 |
| TC-v4.9.2-09 | FR-v4.9.2-08 | 代码引用 i18n 键全部存在 | 脚本 |
| TC-v4.9.2-10 | FR-v4.9.2-08 | VERSION / build-info / OpenAPI / CHANGELOG 四处一致 | 脚本 |

## 门禁命令

```bash
# 后端
cd backend && mvn -q -DskipTests compile && mvn test -Dtest='!*IT'
# 前端
cd frontend && npx vue-tsc --noEmit && npx vitest run
# 静态
python3 tools/check_i18n.py
python3 tools/check_version_consistency.py --static
```

## 环境限制

| 项 | 处理 |
| --- | --- |
| 真库迁移 down→up | 提供脚本，需 `DB_PASSWORD`；缺失时 NOT-RUN |
| 真实 DUT / 负载 / 网络损伤 | 无环境，不执行、不编造 |
| 样本工作簿 `基本功能导入测试v0.5.0.0.xlsx` | 仓库缺失，改用 `fixtures/ed202/ED202测试用例_V0.4.xlsx`，统计差异在报告注明 |

## 退出准则

- 所有自动化门禁 0 失败；
- NOT-RUN 项均有明确原因与可复现命令；
- 人工核对清单全部勾选或记录偏差。
