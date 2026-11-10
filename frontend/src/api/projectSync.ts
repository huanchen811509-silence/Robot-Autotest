/** 存量同步接口（v4.9.0）：预览、执行、回滚、作业历史、本地覆盖标记。 */

import { request, toQuery } from './http'

export interface SyncItemRow {
  id: number
  externalId: string
  remoteId: number
  localId: number | null
  action: 'NEW' | 'UPDATE' | 'CONFLICT' | 'UNCHANGED' | 'SKIP'
  localSha: string | null
  remoteSha: string | null
  resolution: string | null
  message: string
  title: string
  categoryL1: string
  priority: string
}

export interface SyncPreview {
  jobId: number
  total: number
  newCount: number
  updateCount: number
  conflictCount: number
  unchangedCount: number
  skippedCount: number
  previewSha: string
  items: SyncItemRow[]
}

export interface SyncExecResult {
  jobId: number
  created: number
  updated: number
  conflictsKept: number
  duplicated: number
  replayed: boolean
}

export interface SyncRollbackResult {
  jobId: number
  removed: number
  restored: number
}

export interface SyncJobRow {
  id: number
  status: string
  total: number
  newCount: number
  updatedCount: number
  conflictCount: number
  unchangedCount: number
  skippedCount: number
  previewSha: string | null
  createdAt: string
  executedAt: string | null
  rolledBackAt: string | null
}

export const projectSyncApi = {
  preview(projectId: number) {
    return request<SyncPreview>(`/projects/${projectId}/sync/preview`, { method: 'POST' })
  },
  execute(projectId: number, jobId: number, resolutions: Record<string, string>) {
    return request<SyncExecResult>(`/projects/${projectId}/sync/jobs/${jobId}/execute`, {
      method: 'POST',
      body: JSON.stringify({ resolutions }),
    })
  },
  rollback(projectId: number, jobId: number) {
    return request<SyncRollbackResult>(`/projects/${projectId}/sync/jobs/${jobId}/rollback`, {
      method: 'POST',
    })
  },
  jobs(projectId: number, limit = 20) {
    return request<SyncJobRow[]>(`/projects/${projectId}/sync/jobs${toQuery({ limit })}`)
  },
  markLocalOverride(projectId: number, caseId: number) {
    return request<{ updated: boolean }>(
      `/projects/${projectId}/sync/cases/${caseId}/local-override`,
      { method: 'POST' },
    )
  },
}
