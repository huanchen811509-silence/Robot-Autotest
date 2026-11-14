/**
 * 两步发起运行（W57-47 契约对齐）：预览 → 确认并发起。
 *
 * <p>字段与后端 `RunLaunchController` 的 record **严格同名**，不做任何改名或别名：
 * 预览返回 `snapshotId / selectionSha256 / caseCount / items`，确认只提交
 * `snapshotId` + 本次执行参数。**没有 token**——v5.7.0 首版曾在此虚构 token 字段，
 * 导致确认阶段恒传 0 并静默回退旧链路（见 06-ACCEPTANCE-AUDIT P0-1）。
 *
 * <p>调用方**不得**在失败时自动改走 `executionApi.runCases`：那会绕过选择快照、
 * 摘要比对与 409 拦截，等于把「执行可信度」整条主题架空。
 */
import { request } from './http'

/** 与后端 `SelectionPreviewRequest` 一一对应；未填的字段不下发。 */
export interface RunPreviewPayload {
  mode: string
  setIds?: number[]
  caseIds?: number[]
  filter?: Record<string, unknown>
}

/** 与后端 `SelectionPreviewResponse` 一一对应。 */
export interface RunPreview {
  snapshotId: number
  /** 选择摘要（`selection_sha256`），确认阶段由后端重算比对。 */
  selectionSha256: string
  caseCount: number
  /** 前 N 条明细，仅供界面展示；不进确认请求。 */
  items: Array<{ caseId: number; caseCode: string }>
}

/** 与后端 `ConfirmAndStartRequest` 一一对应。 */
export interface RunConfirmPayload {
  snapshotId: number
  environmentId?: number | null
  concurrency?: number
  timeoutSeconds?: number
  testTimeoutSeconds?: number
  includeTag?: string
  runRequestId?: string
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
  confirmAndStart(projectId: number, payload: RunConfirmPayload): Promise<RunConfirmResult> {
    return request<RunConfirmResult>(`/projects/${projectId}/runs/confirm-and-start`, {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
}
