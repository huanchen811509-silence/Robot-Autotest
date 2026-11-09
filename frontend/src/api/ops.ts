/**
 * 运维中心 API（v3.3.0 / FR-3.3-09..12，v3.4.0 / FR-3.4-02）。
 *
 * 所有端点都在 `/api/admin` 下：后端按项目做管理员校验，前端只负责展示与触发。
 * 类型与后端 record 字段一一对应（避免页面拿到 undefined 却静默显示空白）。
 */
import { request, toQuery } from '@/api/http'

export interface SloDay {
  day: string
  availabilityPct: number
  p95Ms: number
  samples: number
  errorBudgetMinutes: number
}

export interface SloSnapshot {
  window: string
  targetPct: number
  availabilityPct: number
  errorBudgetMinutes: number
  errorBudgetUsedMinutes: number
  p95Ms: number
  samples: number
  mttdSeconds: number
  mttrSeconds: number
  days: SloDay[]
}

export interface LiveMetrics {
  total: number
  errors: number
  availabilityPct: number
  p95Ms: number
}

export interface AlertRule {
  id: number
  projectId: number
  eventType: string
  threshold: number
  windowMinutes: number
  cooldownMinutes: number
  severity: string
  enabled: boolean
}

export interface AlertChannel {
  id: number
  projectId: number
  kind: string
  target: string
  enabled: boolean
  hasSecret: boolean
}

export interface AlertEvent {
  id: number
  projectId: number
  ruleId: number | null
  eventType: string
  dedupKey: string
  severity: string
  status: string
  summary: string
  detectedAt: string
  firedAt: string
  resolvedAt: string | null
  deliveryStatus: string
  attempts: number
  lastError: string
}

export interface BackupStatus {
  present: boolean
  generatedAt: string
  rtoSeconds: number
  rpoSeconds: number
  verdict: string
  tables: number
  database: string
  ageMinutes: number
}

export interface ReleaseRecord {
  id: number
  version: string
  status: string
  deployedAt: string
  verifiedAt: string | null
  rolledBackAt: string | null
  note: string
}

export interface AdmissionView {
  inflight: number
  maxInflight: number
  maxPerProject: number
  maxPerNode: number
  byProject: Record<string, number>
  byNode: Record<string, number>
}

export interface OpsSummary {
  slo: SloSnapshot
  live: LiveMetrics
  backup: BackupStatus
  rules: AlertRule[]
  channels: AlertChannel[]
  events: AlertEvent[]
  releases: ReleaseRecord[]
  generatedAt: string
}

export interface Evaluation {
  projectId: number
  scanned: number
  fired: number[]
  resolved: number[]
  suppressed: number
  deliveryFailures: number
  evaluatedAt: string
}

export const opsApi = {
  summary: (projectId: number) =>
    request<OpsSummary>(`/admin/ops/summary${toQuery({ projectId })}`),

  backup: (projectId: number) =>
    request<BackupStatus>(`/admin/ops/backup${toQuery({ projectId })}`),

  admission: (projectId: number) =>
    request<AdmissionView>(`/admin/ops/admission${toQuery({ projectId })}`),

  rules: (projectId: number) =>
    request<AlertRule[]>(`/admin/alerts/rules${toQuery({ projectId })}`),

  channels: (projectId: number) =>
    request<AlertChannel[]>(`/admin/alerts/channels${toQuery({ projectId })}`),

  events: (projectId: number, limit = 30) =>
    request<AlertEvent[]>(`/admin/alerts/events${toQuery({ projectId, limit })}`),

  evaluate: (projectId: number) =>
    request<Evaluation>(`/admin/alerts/evaluate${toQuery({ projectId })}`, { method: 'POST' }),

  testChannel: (projectId: number, channelId: number) =>
    request<{ status: string; error: string }>(
      `/admin/alerts/channels/${channelId}/test${toQuery({ projectId })}`,
      { method: 'POST' },
    ),

  releases: (limit = 20) => request<ReleaseRecord[]>(`/admin/ops/releases${toQuery({ limit })}`),
}
