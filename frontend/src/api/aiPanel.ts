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
  /** v2.6：本次请求「想要」的 agent（空串表示没指定） */
  requestedCli?: string
  /** v2.6：所选 agent 已不可用 → 后端回落到首选，前端据此提示，不静默 */
  cliFallback?: boolean
  /** v2.6：本次请求指定的模型（空串表示用已保存/默认） */
  model?: string
}

/** v2.6 / FR-AI-14：抽屉里可选的 agent（不含端点与密钥）。 */
export interface AiAgentItem {
  key: string
  name: string
  models: string[]
  defaultModel: string
  modelFlag: string | null
  injectable: boolean
  configured: boolean
}

export interface AiAgentsResponse {
  defaultKey: string
  realCliEnabled: boolean
  items: AiAgentItem[]
}

export interface AiChatRequest {
  projectId: number
  sessionId: number
  prompt: string
  cli?: string
  model?: string
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
  /**
   * v2.6 / FR-AI-14：可选 agent 清单 + 带 agent/模型覆盖的对话。
   * 覆盖字段留空（undefined）时后端按「首选 agent + 已保存模型」处理，与老行为一致。
   */
  agents: (projectId: number) =>
    request<AiAgentsResponse>(`/ai/agents?projectId=${projectId}`),
  chatWith: (payload: AiChatRequest) =>
    request<AiChatResponse>('/ai/chat', {
      method: 'POST', body: JSON.stringify(payload),
    }),
}
