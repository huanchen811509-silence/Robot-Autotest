# v3.4.0 · 归档、准入与安全（证据目录）

对应版本：`v3.4.0-scale-archive`；开发项：EW34-01..05（PRD §5.4）。

## 机器可读证据

| 文件 | 判据 | 生成方式 |
| --- | --- | --- |
| `security-scan.json` | 密钥 / SAST / 危险依赖，高危必须为 0 | `python3 tools/security_scan.py`（门禁第 25 步） |
| `capacity-report.json` | 归档候选查询走 `idx_run_project_created`、目标规模外推体积 ≤ 预算 | `python3 tools/capacity_report.py`（门禁第 26 步） |
| `../ops/backup-drill-latest.json` | RTO ≤ 2h、RPO ≤ 24h、verdict=PASS、证据 ≤ 90 天 | `bash scripts/backup-drill.sh` + `tools/check_backup_status.py` |

## 本轮交付（可复核入口）

- 迁移：`V11_22__v3_4_scale.sql`（归档四表 + `run(project_id, created_at)` 索引）
- 归档：`application/archive/ArchiveService`（dry-run 默认、逐运行事务、原主键恢复、冲突 409）
- 准入：`application/execution/RunAdmissionService`（50/20/10 三层上限、公平锁、背压 409、兜底回收）
- 安全：`api/SecurityHeadersFilter`、`application/iam/LoginRateLimiter`
- 端点：`/api/admin/archive/*`、`/api/admin/ops/admission`
- 前端：归档中心页、运维中心准入卡片

## 边界与未覆盖项

- 容量报告为**实测行数与体积外推**，不是压测结论；并发压测在 v3.2.0 目录。
- 归档调度默认每小时检查，但策略默认 `enabled=false`：未显式启用前不会移动任何数据。
- 备份演练证据包含即时全量快照；正式 RPO 仍由备份计划决定，JSON 中 `rpoNote` 已写明。
