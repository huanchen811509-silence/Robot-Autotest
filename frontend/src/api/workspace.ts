/**
 * 项目工作区导出 / 导入（FR-v4.1-01..03）前端客户端。
 *
 * 与后端 `WorkspaceController` 对齐：预检（dry-run）与真实导入共用 {@link WorkspaceReport}，
 * 任务行 `workspace_job` 既是进度也是项目锁（活动任务 409）。
 */

import { request } from './http'

export type WorkspaceGranularity = 'PROJECT' | 'DIR' | 'FILE'
export type WorkspaceStrategy = 'SKIP' | 'OVERWRITE' | 'RENAME'

export interface WorkspaceReportItem {
  category: string
  key: string
  action: string
  reason: string
}

export interface WorkspaceQuarantine {
  path: string
  findings: string[]
}

export interface WorkspaceReport {
  ok: boolean
  bundlePath: string | null
  errorCode: string | null
  message: string | null
  filesImported: number
  filesSkipped: number
  filesQuarantined: number
  items: WorkspaceReportItem[]
  quarantine: WorkspaceQuarantine[]
  warnings: string[]
}

export interface WorkspaceJob {
  id: number
  projectId: number
  jobType: string
  granularity: string | null
  targetPath: string | null
  status: string
  progress: number
  bundlePath: string | null
  strategy: string | null
  manifestSha256: string | null
  reportJson: string | null
  errorMessage: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string | null
}

export interface WorkspaceBundle {
  path: string
  projectCode: string
  exportedAt: string | null
  granularity: string
  bytes: number
  fileCount: number
  signed: boolean
}

const LONG_TASK_TIMEOUT_MS = 60_000

export const workspaceApi = {
  bundles(projectId: number) {
    return request<WorkspaceBundle[]>(`/projects/${projectId}/workspace/bundles`)
  },
  exportBundle(
    projectId: number,
    body: { granularity: WorkspaceGranularity; rootPath?: string },
  ) {
    return request<WorkspaceReport>(`/projects/${projectId}/workspace/export`, {
      method: 'POST',
      body: JSON.stringify(body),
    }, LONG_TASK_TIMEOUT_MS)
  },
  preflight(
    projectId: number,
    body: { bundlePath: string; strategy: WorkspaceStrategy; confirmQuarantine?: boolean },
  ) {
    return request<WorkspaceReport>(`/projects/${projectId}/workspace/import/preflight`, {
      method: 'POST',
      body: JSON.stringify(body),
    }, LONG_TASK_TIMEOUT_MS)
  },
  importBundle(
    projectId: number,
    body: { bundlePath: string; strategy: WorkspaceStrategy; confirmQuarantine?: boolean },
  ) {
    return request<WorkspaceReport>(`/projects/${projectId}/workspace/import`, {
      method: 'POST',
      body: JSON.stringify(body),
    }, LONG_TASK_TIMEOUT_MS)
  },
  jobs(projectId: number) {
    return request<WorkspaceJob[]>(`/projects/${projectId}/workspace/jobs`)
  },
  job(projectId: number, jobId: number) {
    return request<WorkspaceJob>(`/projects/${projectId}/workspace/jobs/${jobId}`)
  },
  retry(projectId: number, jobId: number) {
    return request<WorkspaceReport>(`/projects/${projectId}/workspace/jobs/${jobId}/retry`, {
      method: 'POST',
    }, LONG_TASK_TIMEOUT_MS)
  },
}
