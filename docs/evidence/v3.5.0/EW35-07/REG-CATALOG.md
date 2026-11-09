# REG-* 回归基线扩容目录（EW35-07）

| 项 | 内容 |
|---|---|
| 目的 | 把 v3.1–v3.4 新增的 11 类能力固化为后续必跑回归组，按能力域而非版本堆用例 |
| 机检 | `python3 tools/check_reg_catalog.py`（`--selfcheck` 删一类 / 删命令必红） |
| 纪律 | 每类 ≥1 条真实链路；进 CI 且可单跑；负向注入证明「删能力必红」；不许改断言迁就实现 |

## 1. 回归清单（11 类 / 12 条）

| 编号 | 能力域 | 链路 | 可复现命令 | 证据路径 |
|---|---|---|---|---|
| REG-UX-01 | 搜索 | 全局搜索 → 结果跳转 → 零结果提示 | `npx vitest run src/config/commandPalette.spec.ts` | `docs/evidence/v3.5.0/EW35-07/logs/` |
| REG-UX-02 | 保存视图 | 新建 → 刷新 → 重登 → 条件保持与权限隔离 | `npx vitest run src/views/CasesView.spec.ts` | `docs/evidence/v3.5.0/EW35-07/logs/` |
| REG-UX-03 | 通知 | 触发站内通知 → 未读计数 → 已读回写 | `npx vitest run src/components/AppHeader.spec.ts` | `docs/evidence/v3.5.0/EW35-07/logs/` |
| REG-PERF-01 | 缓存 | `TtlCache` 写后失效 → 异常降级直查 | `mvn -B -f backend/pom.xml -Dtest=TtlCacheTest test` | `docs/evidence/v3.5.0/EW35-07/logs/` |
| REG-API-01 | 契约 | 控制器 → OpenAPI 快照 → `--check` 漂移即红 | `python3 tools/gen_api_contract.py --check` | `docs/evidence/gates/logs/20-api_contract.log` |
| REG-AI-01 | 流式 | SSE meta → citations → delta → done，取消 / 断连落库 | `python3 tools/ai_eval.py` | `docs/evidence/gates/logs/24-ai_eval.log` |
| REG-AI-02 | 证据问答 | 证据装配 → 事实校验 → 无证据拒答 | `python3 tools/ai_eval.py` | `docs/evidence/gates/logs/24-ai_eval.log` |
| REG-OPS-01 | 归档 | dry-run 默认 → 逐运行事务 → 恢复冲突 409 | `mvn -B -f backend/pom.xml -Dtest=ArchiveServiceTest test` | `docs/evidence/v3.5.0/EW35-07/logs/` |
| REG-OPS-02 | 准入 | 50 / 20 / 10 三层上限 → 409 背压 → 终态释放名额 | `mvn -B -f backend/pom.xml -Dtest=RunAdmissionServiceTest test` | `docs/evidence/v3.5.0/EW35-07/logs/` |
| REG-SEC-01 | 安全头 | nosniff / DENY / no-referrer / CSP / HSTS 与登录限流 | `python3 tools/security_scan.py` | `docs/evidence/gates/logs/25-security_scan.log` |
| REG-SLO-01 | SLO | 错误预算 → 告警规则 → 冷却去重 → 恢复通知 | `python3 tools/check_backup_status.py` | `docs/evidence/gates/logs/26-capacity_backup.log` |

## 2. 负向注入

`check_reg_catalog.py --selfcheck` 实证两件事：删掉任一能力域后目录判红；删掉任一行的可复现命令后判红。
这保证「回归基线扩容」不是一张静态清单，而是会被机检拦住的真实判据。

## 3. 执行状态

| 项 | 状态 |
|---|---|
| 清单与机检 | PASS（本包交付） |
| 12 条链路逐条实跑 | PARTIAL：门禁 20/24/25/26 已提供契约 / AI / 安全 / 容量的真实执行证据；单测类需按命令逐条跑并留日志 |
| 进 CI 可单跑 | PARTIAL：命令均可单跑；是否纳入 27 步门禁按 ADR-35-03「新增步骤先负向自检」另行评审，本轮不擅自加步 |

## 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-11-09 | 首版：11 类 12 条 + 机检 + 负向注入 |
