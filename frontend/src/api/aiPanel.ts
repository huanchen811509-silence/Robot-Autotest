/** W6 / AIX AI 面板 API。无 CLI 时后端返回 DEGRADED，面板保持可用。 */
import { request } from './http'

export interface AiPanelState {
  anyAvailable: boolean
  maxLevel: string
  mode: string
  nonInteractiveOnly?: boolean
  projectId: number
  timeoutSeconds: number
  encoding: string
  contextMaxChars: number
  executeRequiresConfirmation: boolean
  /** FR-AI-07：false 时前端隐藏顶栏入口与抽屉。 */
  enabled?: boolean
  realCliEnabled?: boolean
  cliPathInjected?: boolean
}

export interface AiActionResult {
  action: string
  level: string
  degraded: boolean
  summary: string
  audited: boolean
  /** FR-AI-01：模型 stdout 等真实回显随响应体返回。 */
  stdout?: string
  stderr?: string
  exitCode?: number
  durationMs?: number
  cli?: string
  timedOut?: boolean
}

/** FR-AI-05：POST /api/ai/chat 的返回体。 */
export interface AiChatResponse {
  sessionId: number
  answer: string
  stdout: string
  stderr: string
  exitCode: number
  durationMs: number
  degraded: boolean
  mode: string
  cli: string
  timedOut: boolean
  audited: boolean
}

/** 面板状态 -> 是否允许发起执行（二次确认另算），纯逻辑便于单测。 */
export function canRequestExecution(state: AiPanelState, confirmed: boolean): boolean {
  return state.mode === 'NORMAL' && confirmed && state.executeRequiresConfirmation
}

// http.ts 的 request() 自身会拼 BASE_URL（默认 '/api'），这里一律写相对后端根的路径，
// 不带 /api 前缀，否则会变成 /api/api/...（见 tools/check_api_prefix.py 守门）。
export const aiPanelApi = {
  state: (projectId: number) => request<AiPanelState>(`/ai/state?projectId=${projectId}`),
  open: (projectId: number, userId: number) =>
    request<number>(`/ai/session?projectId=${projectId}&userId=${userId}`, { method: 'POST' }),
  suggest: (projectId: number, sessionId: number, context: Record<string, string>) =>
    request<AiActionResult>('/ai/suggest', {
      method: 'POST', body: JSON.stringify({ projectId, sessionId, context }),
    }),
  execute: (projectId: number, sessionId: number, target: string, confirmed: boolean) =>
    request<AiActionResult>('/ai/execute', {
      method: 'POST', body: JSON.stringify({ projectId, sessionId, target, confirmed }),
    }),
  terminate: (projectId: number, sessionId: number) =>
    request<AiActionResult>(
      `/ai/terminate?projectId=${projectId}&sessionId=${sessionId}`, { method: 'POST' }),
  /** FR-AI-05：prompt 为累积的多轮会话文本，由后端统一经 ContextPacker 脱敏。 */
  chat: (projectId: number, sessionId: number, prompt: string) =>
    request<AiChatResponse>('/ai/chat', {
      method: 'POST', body: JSON.stringify({ projectId, sessionId, prompt }),
    }),
}
