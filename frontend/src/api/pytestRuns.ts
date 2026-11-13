/** v5.6.0 · pytest 执行入口（FR-v5.6-11~14）。 */
import { request } from './http'

/** 预检单项。`required=false` 表示「列出来但不拦」，如 fake 模式下的安全级。 */
export interface PrecheckItem {
  key: 'bench' | 'config' | 'devices' | 'safety'
  required: boolean
  ok: boolean
  detail: string
  items: unknown
  action: string
}

export interface PrecheckResult {
  mode: string
  ready: boolean
  checks: PrecheckItem[]
  blocking: number
}

/** 进度快照。字段与后端一一对应；`terminal` 为真表示不用再轮询。 */
export interface RunProgress {
  runId: string
  /** PREPARING / RENDERING / LAUNCHING / RUNNING / FINALIZING / FINISHED / FAILED / CANCELED */
  stage: string
  total: number
  finished: number
  passed: number
  failed: number
  elapsedMs: number
  cancelRequested: boolean
  terminal: boolean
  /** live = 内存登记表；db = 进程重启后从 run 表读的终态 */
  source: 'live' | 'db'
}

export interface AsyncRunAccepted {
  runId: string
  mode: string
  total: number
  accepted: boolean
  progressUrl: string
}

export interface RunRequest {
  runId?: string
  mode?: 'fake' | 'real'
  nodeIds?: string[]
  hostKeys?: string[]
  timeoutSeconds?: number
  metadata?: Record<string, string>
  confirmReal?: boolean
  /** 失败复测轮数上限（FR-v5.6-24）：只重跑失败用例，0 表示不复测。 */
  retry?: number
}

function base(projectId: number): string {
  return `/projects/${projectId}/pytest`
}

export const pytestRunApi = {
  precheck(projectId: number, mode: 'fake' | 'real', nodeIds: string[] = []):
    Promise<PrecheckResult> {
    const query = new URLSearchParams({ mode })
    // nodeIds 用重复参数而不是逗号拼接：nodeid 本身可能含逗号（参数化用例）。
    for (const id of nodeIds) query.append('nodeIds', id)
    return request<PrecheckResult>(`${base(projectId)}/precheck?${query.toString()}`)
  },

  /** 异步提交：立即拿到 runId，执行在后台。 */
  startAsync(projectId: number, body: RunRequest): Promise<AsyncRunAccepted> {
    return request<AsyncRunAccepted>(`${base(projectId)}/runs/async`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },

  progress(projectId: number, runId: string): Promise<RunProgress> {
    return request<RunProgress>(
      `${base(projectId)}/runs/${encodeURIComponent(runId)}/progress`,
    )
  },

  cancel(projectId: number, runId: string): Promise<{ runId: string; canceled: boolean }> {
    return request<{ runId: string; canceled: boolean }>(
      `${base(projectId)}/runs/${encodeURIComponent(runId)}/cancel`,
      { method: 'POST' },
    )
  },
}
