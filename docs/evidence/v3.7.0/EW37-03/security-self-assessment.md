# OWASP ASVS / LLM Top 10（2025）逐条自评（EW37-03）

| 项 | 内容 |
|---|---|
| 状态 | **PARTIAL**：自评结论落盘；`medium=1` 浮动依赖未收口；轮换与专项用例待授权 |
| 依据 | 门禁 25 步 `security_scan.py`：`high=0 medium=1`；既有安全头 / 登录限流 / 凭据加密能力 |

## 1. OWASP ASVS 自评（摘要域）

| 域 | 结论 | 依据 / 缺口 |
|---|---|---|
| V1 架构与威胁建模 | PARTIAL | 模块化单体 + ADR 留痕；无正式威胁模型文档 |
| V2 认证 | PASS | 登录限流、令牌过期、`LoginRateLimiter` |
| V3 会话管理 | PASS | `auth_token` / `auth_expires_at`，前端守卫 |
| V4 访问控制 | PASS | 租户守卫 + 越权矩阵单测 + ArchUnit |
| V5 输入校验 | PASS | 统一分页白名单、参数校验、契约测试 |
| V6 密码学 | PARTIAL | `SecretCipher` + `CRED_MASTER_KEY`；**密钥轮换演练未完成** |
| V7 错误与日志 | PASS | traceId / 诊断 ID / 报告敏感扫描（门禁 14） |
| V8 数据保护 | PASS | 对外字段白名单、metadata 不放凭据、埋点默认关闭 |
| V9 通信 | PARTIAL | 安全头齐（nosniff / DENY / no-referrer / CSP / HSTS），HSTS 部署侧未验 |
| V10 恶意代码 | PASS | 门禁 25 密钥 / SAST / 危险依赖扫描 |
| V11 业务逻辑 | PASS | 幂等键、准入三层上限、状态机终态约束 |
| V12 文件与资源 | PARTIAL | 上传与产物目录有约束，未做专项模糊测试 |
| V13 API | PASS | 契约生成 + `--check` 漂移即红（门禁 20） |
| V14 配置 | PARTIAL | `medium=1` 浮动依赖未收口；SBOM 试点未做 |

## 2. OWASP LLM Top 10（2025）专项

| 编号 | 风险 | 结论 | 依据 / 缺口 |
|---|---|---|---|
| LLM01 | 提示注入 | PARTIAL | 黄金集含 10 条注入样本；真实端点专项评测未完成 |
| LLM02 | 敏感信息泄露 | PASS | 三段式回答 + 提示词脱敏 + 报告敏感扫描 |
| LLM03 | 供应链 | PARTIAL | SBOM 未试点；依赖扫描 high=0 medium=1 |
| LLM04 | 数据与模型投毒 | PARTIAL | 用例草稿 `reviewed=true` 才落库；投毒专项未做 |
| LLM05 | 不当输出处理 | PASS | 事实校验失败显式标红，引用可跳转 |
| LLM06 | 过度代理 | PASS | 门禁 19 步 AI 契约机检：零自动提交、零 L4-L5 自动执行 |
| LLM07 | 系统提示泄露 | PARTIAL | 脱敏机检覆盖；专项红队未完成 |
| LLM08 | 向量与嵌入弱点 | N/A | 本系统未使用向量库 |
| LLM09 | 错误信息 | PASS | 无证据必拒答，事实/推测/建议三段 |
| LLM10 | 无界消耗 | PARTIAL | 黄金集含 5 条无界消耗样本；配额与超时真实注入未完成 |

## 3. 未闭环项

| 项 | 状态 | 所需条件 |
|---|---|---|
| `medium=1` 浮动依赖收口 | BLOCKED | 需确认依赖版本策略与回归窗口 |
| 密钥轮换演练 | BLOCKED | 需凭据与维护窗口，不得在日志/提示词留明文 |
| 提示注入 / 过度代理 / 无界消耗真实端点用例 | BLOCKED | 需授权与 fake CLI 契约夹具扩展 |
| SBOM（CycloneDX / SPDX） | BLOCKED | 需引入生成工具并评审，不在本轮擅自加依赖 |
