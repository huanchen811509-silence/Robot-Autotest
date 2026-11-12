# v3.5+ 特性开关清单（B0 交付）

| 项 | 内容 |
|---|---|
| 文档 | `FEATURE-FLAGS.md` ｜ 归属 B0 ｜ 状态 已授权 |
| 规则 | 新增能力默认关闭或灰度；开关状态可查询；过期开关按 ADR 清理；开关清单每次变更留修订记录 |

## 1. 开关清单

| 开关 | 默认值 | 影响范围 | 观察指标 | 关闭方式 | 证据 |
|---|---|---|---|---|---|
| `UX_TELEMETRY_ENABLED` | `false` | 体验埋点（task_step / search_query / form_submit / error_shown / ai_answer / route_nav） | 事件量、P95 增量、无内容字段 | 配置置 false 即全停，服务端不再聚合 | `docs/evidence/v3.5.0/EW35-03/` |
| `AI_CLIENT_EVENTS_ENABLED` | `false` | 客户端可选事件（内部评估期） | 事件量、CWV 影响 | 同上；与服务端开关独立 | 同上 |
| `ARCHIVE_ENABLED` | `false` | 归档四表与 `ArchiveService` | 主库体积、归档行数、恢复冲突 | 配置置 false；dry-run 为默认 | `docs/evidence/v3.7.0/EW37-01/` |
| `ADMISSION_ENFORCE` | `true`（既有） | `RunAdmissionService` 50 / 20 / 10 三层上限 | 409 背压次数、队列积压 | 需 ADR 与评审，不擅自关 | 同上 |
| `AI_ASSISTANT_ENABLED` | `true`（既有） | AI 工作台与 SSE 流式 | 首字 / 完成时延、降级次数、配额 | 配置关闭；CLI 故障时自动 `degraded=true` | 门禁 24 步 |
| `AI_DRAFT_AUTO_PERSIST` | `false` | 用例草稿落库 | `reviewed=true` 比例 | 恒 false，禁止自动写库 | 门禁 19 步 AI 契约机检 |
| `V50_RUM_ENABLED` | `false` | 真实用户 CWV 采集 | 事件量、p75 分布 | 恒 false，D5 裁定不启用 | `docs/plan/v5.0-口径裁决清单.md` |
| `V50_ALERT_NOTIFY_ENABLED` | `false` | 告警通知外接通道 | 通知送达率 | 恒 false，D5 裁定不外接 | `docs/OPS-MANUAL.md` |
| `V50_AI_LIVE_EVAL_ENABLED` | `false` | AI 真实端点评测 | 事实一致性 | 恒 false，D6 裁定不授权 | `docs/plan/v5.0-AI评测协议.md` |

> 机检类检查（门禁 33~36 步）**常开**，属于判据而非灰度能力，不纳入上表；由 `tools/check_v50_flags.py` 校验登记与默认值。

## 2. 隐私与数据边界

- 事件字段只含 ID / 序号 / 耗时桶 / 结果码 / 是否类标记；**不含**凭据、令牌、用例正文、实网配置、设备口令。
- `output.xml` metadata 十项仍为对外可见面，键大写、值 ≤40 字符、不放凭据；`RUN_ID` 键名不可改。
- 对外报告字段白名单不因埋点回退；AI 提示词与日志脱敏。

## 3. 灰度与回退

内部用户 → 单项目 → 全量；每档有观察指标与回退阈值。任一 P0 指标越线、高危漏洞、数据不一致、
执行结果语义变化即回退，回退后复跑回归。发版判定与 G 门申请仍归大师，本轮不申请。

## 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-11-09 | 首版，随 B0 基线固化一并入库 |
