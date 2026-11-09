# 最小事件字典（v3.5.0 冻结 · 隐私核对通过）

| 项 | 内容 |
|---|---|
| 采集策略 | 服务端聚合优先；客户端可选事件仅内部评估期开启（D5） |
| 开关 | `UX_TELEMETRY_ENABLED=false`（默认）、`AI_CLIENT_EVENTS_ENABLED=false`（默认） |
| 关闭语义 | 开关置 false 时服务端不再聚合、客户端不上报；**停采不等于把历史读数清零** |
| 证据 | `docs/evidence/v3.5.0/EW35-03/`；聚合器 `tools/ux_metrics.py` |

## 1. 事件与字段白名单（只含 ID / 序号 / 桶 / 结果码 / 是否类标记）

| 事件 | 字段 | 用途 |
|---|---|---|
| `task_step` | task_id、step_no、duration_bucket、result_code | Top 8 成功率 / 耗时 / 步数 |
| `search_query` | query_set_id、top3_hit、zero_result | 搜索质量 |
| `form_submit` | form_id、validation_blocked、success、duration_bucket | 一次提交成功率 |
| `error_shown` | error_code、has_next_action、has_diagnostic_id | 错误可恢复率 |
| `ai_answer` | session_id、latency_bucket、adopted、refused、degraded | 采纳率 / 时延 / 降级 |
| `route_nav` | route_id、load_bucket、cwv_sample | INP / LCP / CLS |

## 2. 明确禁止采集

凭据 / 令牌 / 密钥（`sk-` 等）、用例正文、脚本内容、实网配置、设备口令、
用户姓名与工号、自由文本查询原文、AI 提示词与回答正文、任何可反推客户环境的内容。

## 3. 隐私评审结论

| 检查项 | 结论 | 依据 |
|---|---|---|
| 默认关闭 | PASS | 两个开关默认 false，聚合器在关闭时拒绝写事件 |
| 可关闭 | PASS | 配置置 false 即全停，不依赖前端 |
| 字段最小化 | PASS | 字段白名单仅 6 类 × 3–5 字段，均为枚举或桶 |
| 无凭据 / 无正文 | PASS | 禁止清单 + 聚合器 `--selfcheck` 注入非法字段必红 |
| 对外可见面隔离 | PASS | `output.xml` metadata 十项与对外字段白名单不受埋点影响 |
| 无数据显示语义 | PASS | 看板与 `metrics.json` 对空样本输出「未测量」而非 0 |

## 4. 复现命令

```bash
cd ~/robot-autotest
python3 tools/ux_metrics.py --selfcheck     # 负向自检：注入凭据字段必红
python3 tools/ux_metrics.py --aggregate    # 默认关闭时不得写入任何事件
UX_TELEMETRY_ENABLED=true python3 tools/ux_metrics.py --aggregate   # 显式开启才聚合
```

## 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-11-09 | 首版；D5 裁定服务端聚合 + 可选客户端事件、默认关闭 |
