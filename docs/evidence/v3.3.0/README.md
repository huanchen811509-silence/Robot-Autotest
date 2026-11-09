# v3.3.0 · AI 工作台与运维闭环（证据目录）

对应版本：`v3.3.0-ai-ops`；开发项：EW33-01..11（PRD §5.3）。

## 机器可读证据

| 文件 | 判据 | 生成方式 |
| --- | --- | --- |
| `ai-eval.json` | 事实一致性 ≥95%、严重幻觉 0、无证据必须明确拒答、流式首字 ≤3s | `python3 tools/ai_eval.py`（门禁第 24 步，直连真实端点，默认 fake CLI 契约） |

## 本轮交付（可复核入口）

- 迁移：`backend/src/main/resources/db/migration/V11_21__v3_3_ai_ops.sql`（8 张表 + 7 条内置模板）
- 证据装配：`application/ai/AiEvidenceService`、`AiAnswerFactory`、`AiFactVerifier`
- 工作台：`application/ai/AiWorkbenchService`（配额 / 会话 / 草稿审阅）、`AiStreamService`（SSE 取消与超时落库）
- 运维：`application/ops/{AlertService,SloService,OpsSummaryService,BackupStatusService,ReleaseService}`
- 端点：`/api/ai/workbench/*`、`/api/admin/ops/*`、`/api/admin/alerts/*`
- 前端：AI 抽屉「证据问答 / 用例草稿」、报告 AI 叙述、失败聚类 AI 分诊、运维中心

## 边界与未覆盖项

- 未接入真实模型 CLI：评测与页面均以本地证据装配（`degraded=true`）为准，模型接入不在本轮验收范围。
- 长稳测试按用户要求跳过，本目录不放长稳结论；仅在 v3.2.0 目录保留历史采样。
