/**
 * AI 助手配置接口（v2.5 / FR-AI-10..13）。
 *
 * 约定三条，与后端一一对应：
 * 1. **密钥永不回显**：`keyMasked` 只给 `****` + 尾 4 位；保存时留空表示不改；
 * 2. `injectable=false` 表示该 CLI 不吃这些配置（如 Hermes 走自身配置文件），
 *    页面必须显式说明「仅记录」，不能让人以为填了就生效；
 * 3. `installed` 只代表「可执行文件在 PATH 上」，不代表能推理（claude 就被黑名单拦过）。
 */
import { request } from './http'

export interface AiAssistantItem {
  key: string
  name: string
  /** 非交互调用模板，纯展示 */
  command: string
  installed: boolean
  injectable: boolean
  credentialEnv: string | null
  baseUrlEnv: string | null
  modelFlag: string | null
  hint: string
  models: string[]
  baseUrl: string
  model: string
  enabled: boolean
  hasKey: boolean
  keyMasked: string
  /** 库里有密文但解不开（密钥轮换）：提示用户重填，而不是显示「未配置」 */
  keyUnreadable?: boolean
  updatedAt: string
}

export interface AiAssistantSaveRequest {
  baseUrl?: string
  model?: string
  /** 留空 = 不改；填了 = 换成新密钥 */
  apiKey?: string
  /** true = 明确清空（与「留空」区分） */
  clearKey?: boolean
  enabled?: boolean
}

export interface AiAssistantTestResult {
  ok: boolean
  reason: string
  elapsedMs: number
  exitCode: number
  stdout: string
  stderr: string
  /** 实际注入的变量**名**（不含值），用来确认配置真的送进去了 */
  appliedEnv?: string[]
  appliedArgs?: string[]
}

export const aiAssistantsApi = {
  list(): Promise<AiAssistantItem[]> {
    return request<AiAssistantItem[]>('/ai/assistants')
  },
  save(key: string, body: AiAssistantSaveRequest): Promise<AiAssistantItem> {
    return request<AiAssistantItem>(`/ai/assistants/${key}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },
  test(key: string): Promise<AiAssistantTestResult> {
    return request<AiAssistantTestResult>(`/ai/assistants/${key}/test`, { method: 'POST' })
  },
}
