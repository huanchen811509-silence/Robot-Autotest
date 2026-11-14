/**
 * 两步发起运行（W57-30）：预览 → 确认并发起。
 *
 * 后端未部署新端点（404）或幂等冲突（409）时，调用方要**回退到旧的
 * `executionApi.runCases` 路径并给出可读提示**（ExecutionPlansView 负责），
 * 这样灰度期新旧后端都能用。
 */
import { request } from './http'

export interface RunPreviewPayload {
  caseIds: number[]
  setIds?: number[]
  mode?: string
  all?: boolean
  environmentId?: number | null
  concurrency?: number
  timeoutSeconds?: number
  testTimeoutSeconds?: number
  includeTag?: string
  runRequestId?: string
}

export interface RunPreview {
  /** 确认阶段回传的预览令牌。 */
  token: string
  caseIds: number[]
  total: number
  estimatedSeconds?: number | null
  /** 安全级阻断的用例编号；非空时确认阶段会被后端拒绝。 */
  blocked?: string[]
}

export interface RunConfirmResult {
  runId: string
  state?: string
}

export const runLaunchApi = {
  preview(projectId: number, payload: RunPreviewPayload): Promise<RunPreview> {
    return request<RunPreview>(`/projects/${projectId}/runs/preview`, {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  confirmAndStart(
    projectId: number,
    token: string,
    payload: RunPreviewPayload,
  ): Promise<RunConfirmResult> {
    return request<RunConfirmResult>(`/projects/${projectId}/runs/confirm-and-start`, {
      method: 'POST',
      body: JSON.stringify({ ...payload, token }),
    })
  },
}
