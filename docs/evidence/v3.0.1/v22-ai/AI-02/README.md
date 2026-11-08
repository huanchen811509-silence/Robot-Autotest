# AI-02 · 会话全局唯一且持久化 + 三张 AI 表落库

> 层 N 独立证据包（`docs/evidence/v3.0.1/v22-ai/AI-02/README.md`）
> 关联：FR-AI-03 / FR-AI-04 ｜ TC-AI-03 / TC-AI-04
> 状态：已回填（2026-11-08，命令真实执行；原始件见 `docs/evidence/v3.0.1/v22/raw-20261108/`）。两个连库 IT 全过，跑通前先修的是测试库环境缺陷，不是代码。

## 三栏证据表

| 命令原文 | 判据 | 真实输出（2026-11-08） |
|---|---|---|
| `mvn -o -B -Pit test -Dtest=AiSessionPersistenceIT` | 连续两次开会话 id 不同；用同一 `AiSessionRepositoryJdbc` 新建服务实例模拟进程重启后，新 id 不与历史重复；`ai_session` 有对应行且 `session_uuid` 非空 | AiSessionPersistenceIT Tests run 1、Failures 0、Errors 0（0.162 s），库 robot_it |
| `mvn -o -B -Pit test -Dtest=AiTablesPersistIT` | 一轮 `suggest` + `execute` + `evaluate` + `state` 后，`ai_session` / `ai_action` / `ai_cli_probe` 各 ≥1 行；`ai_action.summary` 与响应体 `summary` 完全一致 | AiTablesPersistIT Tests run 1、Failures 0、Errors 0（1.330 s）；同批 `Tests run: 4, Failures: 0`、`BUILD SUCCESS` |
| `ls -l db/migration/V11_9__ai_runtime.sql db/down/V11_9__ai_runtime.down.sql` | up/down 成对；只增列与索引，`V8_4__ai_panel.sql` 字节不变；会话 id 来自自增主键，弃用内存 AtomicLong | up 704 字节（ALTER TABLE ai_session 加 session_uuid / status + uk_ai_session_uuid；ai_action 加 stdout / stderr / exit_code / duration_ms / cli_name / timed_out），down 496 字节（先删索引再删同名列，与 up 严格对称）；`V8_4__ai_panel.sql` 1298 字节、mtime 2026-11-06 17:50，本轮未触碰 |

## 环境缺陷与修复（如实记录：不是代码缺陷）

两个连库 IT 早先是**跑不过的**，原因是 `robot_it` 测试库自身状态不一致，与代码无关：

1. 失败现象：`robot_it` 的 Flyway 历史停在 9.1，迁移链在 `V9_2__report_template.sql` 处报 `Table 'report_template' already exists`（MySQL Error 1050）——库里已有表但历史记录缺失，属历史遗留的不一致状态。
2. 修复第一步（备份）：`mysqldump` 备份到 `/tmp/robot_it-before-rebuild-20261108-082225.sql`，实测 **113299 字节**（md5 `fd872ce6ffbb5489f9e5ccab64db0344`），确认字节数后才重建。
3. 修复第二步（重建 + 真实迁移链）：重建空库后执行 `flyway migrate`，从 `<< Empty Schema >>` 一路建到 **v11.13**，**47 个迁移全部 success**，耗时 **9.797s**，`BUILD SUCCESS`（2026-11-08 08:22:44）。原始日志 `docs/evidence/v3.0.1/v22/raw-20261108/robot_it-rebuild-migrate.log`，其中可见 `9.2 - report template`、`11.9 - ai runtime` 均按序迁移成功。
4. 修复第三步（重跑 IT）：三个 AI 集成测试随后全过——`Tests run: 4, Failures: 0, Errors: 0`、`BUILD SUCCESS`（08:22:59），日志 `docs/evidence/v3.0.1/v22/raw-20261108/ai-it-robot_it.log`。

结论：这两个 IT 之前的红灯来自测试库（缺历史 / 半迁移态），不是被测代码或 V11_9 迁移脚本的问题；修复手段是备份后按真实迁移链从零重建，未手工补 `flyway_schema_history`、未跳过任何迁移。

## 说明

- 集成测试需真实 MariaDB 的 `robot_it` 库（ADR-0002），并以 `-Pit` 显式开启（默认 profile 排除 `it` 标签）。
- 仓储实现：`backend/src/main/java/com/qa/autotest/infrastructure/AiSessionRepositoryJdbc.java`；接口：`application/repository/AiSessionRepository.java`。
- V11_9 只为 `ai_session` / `ai_action` 补列，不删除、不改写 V8_4 的任何内容。

## 验证读数

| 项 | 真实读数 | 原始件 |
|---|---|---|
| 会话持久化 IT | AiSessionPersistenceIT Tests run 1、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/ai-it-robot_it.log |
| 三表落库 IT | AiTablesPersistIT Tests run 1、Failures 0、Errors 0 | docs/evidence/v3.0.1/v22/raw-20261108/ai-it-robot_it.log |
| IT 批次汇总 | `Tests run: 4, Failures: 0, Errors: 0`、`BUILD SUCCESS`（08:22:59，-Pit，库 robot_it） | docs/evidence/v3.0.1/v22/raw-20261108/ai-it-robot_it.log |
| 库备份 | `/tmp/robot_it-before-rebuild-20261108-082225.sql` 113299 字节 | 实测 `ls -l` 与 `md5sum` |
| 重建迁移 | 47 个迁移全 success，`now at version v11.13`，9.797s，`BUILD SUCCESS` | docs/evidence/v3.0.1/v22/raw-20261108/robot_it-rebuild-migrate.log |
| 迁移成对 | V11_9 up 704 字节 / down 496 字节，列与索引严格对称 | 仓库内两文件实测 |
| 后端全量 | `Tests run: 1050, Failures: 0, Errors: 0`（门禁第 05 步） | docs/evidence/gates/logs/05-backend_test.log |
| 门禁全量 | 19 步全 PASS，`GATES_RESULT FAILS=0`（2026-11-08 08:19） | docs/evidence/gates/logs/00-full-run.log |

未覆盖项（如实登记）：`robot_it` 重建后只跑了三个 AI IT，未把全库其他连库 IT 一起整套重跑（门禁第 05 步的 1050 条属默认 profile，不含 `it` 标签）；「会话 id 来自自增主键、弃用内存 AtomicLong」由 IT 断言与源码共同覆盖，本轮未单独做并发压测。
