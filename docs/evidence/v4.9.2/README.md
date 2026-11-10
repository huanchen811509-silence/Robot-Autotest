# v4.9.2 证据索引

- 执行时间：2026-11-10
- 构建基线：工作区 `4.9.2`（commit 见 `build-info.properties`）

## 门禁结果

| 项目 | 结果 | 命令 |
| --- | --- | --- |
| 后端编译 | PASS | `mvn -q -DskipTests compile` |
| 后端单测 | PASS | `mvn test -Dtest='!*IT'` |
| 前端类型 | PASS | `npx vue-tsc --noEmit` |
| 前端单测 | PASS | `npx vitest run` |
| i18n 校验 | PASS | `python3 tools/check_i18n.py` |
| 版本一致性 | PASS | `python3 tools/check_version_consistency.py --static` |
| 真库迁移演练 | NOT-RUN | 缺 `DB_PASSWORD`，脚本已提供 |
| DUT / 负载 | NOT-RUN | 无环境，禁止伪造 |

## 复现

```bash
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_USER=root DB_PASSWORD=*** DB_NAME=robot_autotest
bash docs/evidence/v4.9.2/migration-drill-V11_30-37.sh
```

## 关联

- 发布说明：[RELEASE-NOTES.md](file:///E:/workspace/自动化测试/Robot-Autotest/docs/release/v4.9.2/RELEASE-NOTES.md)
- 已知限制：[KNOWN-LIMITATIONS.md](file:///E:/workspace/自动化测试/Robot-Autotest/docs/release/v4.9.2/KNOWN-LIMITATIONS.md)
- 合并需求基线：[v4.4-v4.9 PRD](file:///E:/workspace/自动化测试/Robot-Autotest/docs/plan/v4.4-v4.9-软件迭代开发PRD-20261110.md)
