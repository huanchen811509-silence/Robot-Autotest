/**
 * 归档中心 API（v3.4.0 / FR-3.4-01）。
 *
 * dry-run 是默认值：执行归档必须显式传 `dryRun=false`，避免误触搬运明细。
 */
import { request, toQuery } from '@/api/http'

export interface ArchivePolicy {
  projectId: number
  enabled: boolean
  retentionDays: number
  batchSize: number
}

export interface ArchivePreviewItem {
  runId: string
  testResults: number
  keywordResults: number
  ageDays: number
}

export interface ArchivePreview {
  dryRun: boolean
  retentionDays: number
  batchSize: number
  items: ArchivePreviewItem[]
}

export interface ArchiveExecuteResult {
  dryRun: boolean
  archivedRuns: number
  movedTestResults: number
  movedKeywordResults: number
  skipped: string[]
}

export interface ArchivedRunInfo {
  id: number
  projectId: number
  runId: string
  runDbId: number
  testResultCount: number
  keywordResultCount: number
  archivedAt: string
  restoredAt: string | null
}

export interface ColdResult {
  id: number
  caseId: number | null
  verdict: string
  durationMs: number | null
  failureMsg: string
  failureKind: string
  createdAt: string
}

export interface RestoreResult {
  runId: string
  restoredTestResults: number
  restoredKeywordResults: number
}

export const archiveApi = {
  policy: (projectId: number) =>
    request<ArchivePolicy>(`/admin/archive/policy${toQuery({ projectId })}`),

  savePolicy: (policy: ArchivePolicy) =>
    request<ArchivePolicy>('/admin/archive/policy', {
      method: 'PUT',
      body: JSON.stringify(policy),
    }),

  preview: (projectId: number) =>
    request<ArchivePreview>(`/admin/archive/preview${toQuery({ projectId })}`),

  run: (projectId: number, dryRun: boolean) =>
    request<ArchiveExecuteResult>(
      `/admin/archive/run${toQuery({ projectId, dryRun })}`,
      { method: 'POST' },
    ),

  runs: (projectId: number, limit = 30) =>
    request<ArchivedRunInfo[]>(`/admin/archive/runs${toQuery({ projectId, limit })}`),

  coldResults: (projectId: number, runId: string, limit = 200) =>
    request<ColdResult[]>(
      `/admin/archive/runs/${encodeURIComponent(runId)}/results${toQuery({ projectId, limit })}`,
    ),

  restore: (projectId: number, runId: string) =>
    request<RestoreResult>(
      `/admin/archive/runs/${encodeURIComponent(runId)}/restore${toQuery({ projectId })}`,
      { method: 'POST' },
    ),
}
