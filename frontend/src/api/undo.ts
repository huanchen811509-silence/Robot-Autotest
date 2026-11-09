/**
 * 批量撤销（v3.1.0「顺手」第二批）。
 *
 * 后端契约：POST /projects/{projectId}/batch/undo  body {token}
 * 返回 {action, restored, failed, items:[{id, ok, message}]}
 *
 * 批量接口成功响应若带 undoToken/undoUntil 则直接沿用；字段名做大小写与
 * snake_case 兼容，后端当前版本没有撤销能力时不会误显示入口。
 */
import { request } from './http'

export interface UndoResultItem {
  id: number
  ok: boolean
  message: string | null
}

export interface BatchUndoResult {
  action: string | null
  restored: number
  failed: number
  items: UndoResultItem[]
}

/** 批量响应里可能携带的撤销凭据（后端扩展字段，暂不写进共享 BatchResult）。 */
export interface UndoHandle {
  token: string
  until: string | null
}

export const UNDO_WINDOW_MS = 5 * 60 * 1000

export function undoBatch(projectId: number, token: string): Promise<BatchUndoResult> {
  return request<BatchUndoResult>(`/projects/${projectId}/batch/undo`, {
    method: 'POST',
    body: JSON.stringify({ token }),
  })
}

function pickString(source: Record<string, unknown>, keys: string[]): string | null {
  for (const key of keys) {
    const value = source[key]
    if (typeof value === 'string' && value.trim()) return value
  }
  return null
}

/**
 * 从批量响应中提取撤销凭据。
 *
 * - 优先读 `undoToken` / `undo_token`；
 * - `undoUntil` / `undo_until` 缺失时由调用方按 5 分钟窗口倒计时，不依赖时钟绝对时间；
 * - 删除等不可逆动作由调用方通过 action 过滤，这里只负责字段容错。
 */
export function readUndoHandle(result: unknown): UndoHandle | null {
  if (!result || typeof result !== 'object') return null
  const source = result as Record<string, unknown>
  const token = pickString(source, ['undoToken', 'undo_token'])
  if (!token) return null
  const until = pickString(source, ['undoUntil', 'undo_until'])
  return { token, until }
}

/** 仅这些动作后端会返回撤销凭据；delete 明确不可逆。 */
export const REVERSIBLE_BATCH_ACTIONS = new Set([
  'submit',
  'approve',
  'reject',
  'priority',
  'domain',
  'securityLevel',
  'security_level',
  // 用例批量接口的实际动作名
  'setPriority',
  'setDomainTag',
  'setSafetyLevel',
])

export function isReversibleAction(action: string | null | undefined): boolean {
  if (!action) return false
  if (action === 'delete') return false
  return REVERSIBLE_BATCH_ACTIONS.has(action)
}
