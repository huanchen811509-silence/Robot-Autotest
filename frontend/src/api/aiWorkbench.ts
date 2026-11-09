/**
 * AI 工作台 API（v3.3.0 / FR-3.3-01..08）。
 *
 * 普通请求走统一信封；只有 `/chat/stream` 是 SSE，必须绕开 `request()`——
 * 它会把响应体当 JSON 解析，流式响应永远解析不完。
 */
import { request, toQuery } from '@/api/http'

export interface AiCitation {
  id: string
  type: string
  ref: string
  label: string
  path: string
}

export interface AiAnswer {
  mode: 'GROUNDED' | 'NO_EVIDENCE' | 'DRAFT' | string
  text: string
  facts: string[]
  inferences: string[]
  suggestions: string[]
  citations: AiCitation[]
  degraded: boolean
}

export interface AiVerification {
  pass: boolean
  unsupportedNumbers: string[]
  missingCitations: string[]
  citationCount: number
}

export interface AiResult {
  sessionId: number
  answer: AiAnswer
  verification: AiVerification
  elapsedMs: number
}

export interface PromptTemplate {
  id: number
  projectId: number
  key: string
  name: string
  body: string
  version: number
  builtin: boolean
  enabled: boolean
  updatedAt: string
}

export interface SessionRow {
  id: number
  status: string
  title: string
  messageCount: number
  lastPreview: string
  createdAt: string
  updatedAt: string
}

export interface MessageRow {
  id: number
  role: string
  content: string
  citationsJson: string
  mode: string
  createdAt: string
}

export interface DraftCase {
  title: string
  precondition: string
  steps: string
  expected: string
  source: string
}

export interface QuotaView {
  dailyQuota: number
  used: number
  remaining: number
  resetAt: string
}

export interface StreamHandlers {
  onMeta?: (payload: Record<string, unknown>) => void
  onCitations?: (citations: AiCitation[]) => void
  onDelta?: (text: string) => void
  onDone?: (payload: Record<string, unknown>) => void
  onError?: (message: string) => void
}

export interface StreamHandle {
  streamId: string | null
  cancel: () => Promise<void>
  done: Promise<void>
}

const TOKEN_KEY = 'auth_token'

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem(TOKEN_KEY)
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** 把 `data:` 行还原成字符串：后端 delta 是 JSON 序列化后的片段。 */
function decodeDelta(raw: string): string {
  try {
    const parsed = JSON.parse(raw)
    return typeof parsed === 'string' ? parsed : String(parsed ?? '')
  } catch {
    return raw
  }
}

export const aiWorkbenchApi = {
  quota: (projectId: number) =>
    request<QuotaView>(`/ai/workbench/quota${toQuery({ projectId })}`),

  templates: (projectId: number) =>
    request<PromptTemplate[]>(`/ai/workbench/prompts${toQuery({ projectId })}`),

  saveTemplate: (payload: { projectId: number; key: string; name: string; body: string }) =>
    request<PromptTemplate>('/ai/workbench/prompts', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  deleteTemplate: (projectId: number, key: string) =>
    request<boolean>(`/ai/workbench/prompts${toQuery({ projectId, key })}`, { method: 'DELETE' }),

  sessions: (projectId: number, limit = 20) =>
    request<SessionRow[]>(`/ai/workbench/sessions${toQuery({ projectId, limit })}`),

  messages: (projectId: number, sessionId: number) =>
    request<MessageRow[]>(`/ai/workbench/sessions/${sessionId}/messages${toQuery({ projectId })}`),

  exportSession: async (projectId: number, sessionId: number): Promise<string> => {
    const response = await fetch(
      `/api/ai/workbench/sessions/${sessionId}/export${toQuery({ projectId })}`,
      { headers: authHeaders() },
    )
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    return response.text()
  },

  triage: (projectId: number, runId: string, sessionId = 0) =>
    request<AiResult>('/ai/workbench/triage', {
      method: 'POST',
      body: JSON.stringify({ projectId, runId, sessionId }),
    }),

  narrative: (projectId: number, runId: string, sessionId = 0) =>
    request<AiResult>('/ai/workbench/narrative', {
      method: 'POST',
      body: JSON.stringify({ projectId, runId, sessionId }),
    }),

  chat: (projectId: number, prompt: string, sessionId = 0) =>
    request<AiResult>('/ai/workbench/chat', {
      method: 'POST',
      body: JSON.stringify({ projectId, sessionId, prompt }),
    }),

  caseDraft: (projectId: number, requirement: string, moduleName: string, count: number) =>
    request<AiResult>('/ai/workbench/case-draft', {
      method: 'POST',
      body: JSON.stringify({ projectId, requirement, module: moduleName, count, sessionId: 0 }),
    }),

  drafts: (projectId: number, moduleName: string, count = 5) =>
    request<DraftCase[]>(
      `/ai/workbench/drafts${toQuery({ projectId, module: moduleName, count })}`,
    ),

  applyDrafts: (projectId: number, drafts: DraftCase[], reviewed: boolean) =>
    request<number[]>('/ai/workbench/case-draft/apply', {
      method: 'POST',
      body: JSON.stringify({ projectId, drafts, reviewed }),
    }),

  /**
   * 发起 SSE 对话。返回的句柄既能取消（写库为 CANCELLED），也能 await 完成。
   *
   * 解析按行进行：SSE 的 `event:` 与 `data:` 是两行，空行结束一个事件；
   * 这里只在遇到空行时派发，避免把半个 JSON 当完整片段渲染。
   */
  stream: (
    payload: { projectId: number; sessionId: number; prompt: string },
    handlers: StreamHandlers,
  ): StreamHandle => {
    const controller = new AbortController()
    let streamId: string | null = null
    const done = (async () => {
      try {
        const response = await fetch('/api/ai/workbench/chat/stream', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream',
            ...authHeaders(),
          },
          body: JSON.stringify(payload),
          signal: controller.signal,
        })
        streamId = response.headers.get('X-AI-Stream-Id')
        if (!response.ok || !response.body) {
          throw new Error(`HTTP ${response.status}`)
        }
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        let eventName = ''
        let data = ''
        const flush = () => {
          if (!eventName) return
          if (eventName === 'meta') handlers.onMeta?.(JSON.parse(data || '{}'))
          else if (eventName === 'citations') handlers.onCitations?.(JSON.parse(data || '[]'))
          else if (eventName === 'delta') handlers.onDelta?.(decodeDelta(data))
          else if (eventName === 'done') handlers.onDone?.(JSON.parse(data || '{}'))
          else if (eventName === 'error') handlers.onError?.(decodeDelta(data))
          else if (eventName === 'cancelled') handlers.onDone?.({ cancelled: true })
          eventName = ''
          data = ''
        }
        for (;;) {
          const chunk = await reader.read()
          if (chunk.done) break
          buffer += decoder.decode(chunk.value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() ?? ''
          for (const rawLine of lines) {
            const line = rawLine.replace(/\r$/, '')
            if (!line) {
              flush()
            } else if (line.startsWith('event:')) {
              eventName = line.slice(6).trim()
            } else if (line.startsWith('data:')) {
              data += line.slice(5).trim()
            }
          }
        }
        flush()
        handlers.onDone?.({})
      } catch (error) {
        if ((error as Error)?.name !== 'AbortError') {
          handlers.onError?.((error as Error)?.message ?? 'stream failed')
        }
      }
    })()
    return {
      get streamId() {
        return streamId
      },
      cancel: async () => {
        controller.abort()
        if (streamId) {
          await request<boolean>(
            `/ai/workbench/chat/stream/${streamId}/cancel${toQuery({
              projectId: payload.projectId,
            })}`,
            { method: 'POST' },
          )
        }
      },
      done,
    }
  },
}
