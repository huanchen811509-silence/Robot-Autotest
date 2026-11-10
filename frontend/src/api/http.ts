/**
 * 请求封装（FR-v0.1-11）：统一 baseURL、超时、错误提示位、token 注入位。
 *
 * 后端统一响应体是 { code, codeName, message, data }；code=0 表示成功。
 * 不引入 axios，只用 fetch + AbortController，减少一个依赖面。
 */

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'
const DEFAULT_TIMEOUT_MS = 10_000

/**
 * 前端合成错误码（9xxxx 段，后端 `ErrorCode` 从未占用）。
 *
 * <p>为什么需要：`fetch` 断网时抛 `TypeError: Failed to fetch`、超时抛
 * `AbortError`，都是**英文且面向开发者**的字符串。不做归一的话，
 * 中文界面会弹出英文 `Failed to fetch`，英文界面也无法区分"超时"和"断网"。
 */
const SYNTHETIC_CODE_BASE = 90_000
export { SYNTHETIC_CODE_BASE }
export const TIMEOUT_CODE = SYNTHETIC_CODE_BASE + 1
export const NETWORK_CODE = SYNTHETIC_CODE_BASE + 2
export const MALFORMED_CODE = SYNTHETIC_CODE_BASE + 3

export interface ApiEnvelope<T> {
  code: number
  codeName: string
  message: string
  data: T | null
}

/** token 注入位：v0.2 接登录后写这里，v0.1 恒为空 */
let tokenProvider: () => string | null = () => null

export function setTokenProvider(provider: () => string | null): void {
  tokenProvider = provider
  // 换身份后旧 GET 缓存不能复用（否则另一个用户会读到上一个用户的列表）
  resetRequestCache()
}

/** GET 短缓存默认 3 秒：足够吸收同一页面的重复刷新，又不会让写后读到旧值。 */
const DEFAULT_GET_CACHE_TTL_MS = 3000
let getCacheTtlMs = DEFAULT_GET_CACHE_TTL_MS

interface CacheEntry {
  value: unknown
  expiresAt: number
}

const getCache = new Map<string, CacheEntry>()
const inFlightGets = new Map<string, Promise<unknown>>()

/** 测试 / 退出登录 / 手动刷新时清空 GET 缓存与在途去重表。 */
export function resetRequestCache(): void {
  getCache.clear()
  inFlightGets.clear()
}

/** 调整短缓存 TTL；0 关闭缓存（在途去重仍在，避免同一瞬间重复请求）。 */
export function configureRequestCache(ttlMs: number): void {
  getCacheTtlMs = Math.max(0, ttlMs)
  getCache.clear()
}

/** 浏览器端请求追踪号：后端 TraceIdFilter 会写入 MDC 并回显 X-Trace-Id。 */
function newRequestId(): string {
  const cryptoApi = globalThis.crypto
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export class ApiError extends Error {
  constructor(
    readonly code: number,
    readonly codeName: string,
    message: string,
    /** 服务端回显的追踪号（v4.9.1 / T49-17），便于用户直接贴给运维定位。 */
    readonly traceId: string | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 把 `fetch` 层的原始异常归一成带错误码的 `ApiError`。
 *
 * <p>已经是 `ApiError` 的原样返回——业务错误码不能被这里覆盖成"网络错误"。
 */
function normalizeFailure(e: unknown): ApiError {
  if (e instanceof ApiError) return e
  if (e instanceof DOMException && e.name === 'AbortError') {
    return new ApiError(TIMEOUT_CODE, 'TIMEOUT', 'Request aborted after timeout')
  }
  const text = e instanceof Error ? e.message : String(e)
  return new ApiError(NETWORK_CODE, 'NETWORK_ERROR', text)
}

/** 响应体不是 JSON 信封（典型：反向代理返回 502 HTML 页）时给出可读错误。 */
function parseEnvelope<T>(raw: string): ApiEnvelope<T> {
  try {
    return JSON.parse(raw) as ApiEnvelope<T>
  } catch {
    throw new ApiError(MALFORMED_CODE, 'MALFORMED_RESPONSE', raw.slice(0, 200))
  }
}

async function doRequest<T>(
  path: string,
  init: RequestInit,
  timeoutMs: number,
): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const token = tokenProvider()

  try {
    const response = await fetch(`${BASE_URL}${path}`, {
      ...init,
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        'X-Request-Id': newRequestId(),
        ...(init.headers ?? {}),
      },
    })

    const envelope = parseEnvelope<T>(await response.text())
    if (envelope.code !== 0) {
      throw new ApiError(
        envelope.code,
        envelope.codeName,
        envelope.message,
        response.headers.get('X-Trace-Id'),
      )
    }
    return envelope.data as T
  } catch (e) {
    throw normalizeFailure(e)
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 统一请求入口（FR-3.2-06）：
 *
 * <p>① **在途去重**：同一 GET 并发（页面两个组件同时拉同一列表）只发一次网络请求；
 * ② **短缓存**：成功后缓存 3 秒，吸收「收起/展开」「切换 tab 再切回」的重复刷新；
 * ③ **写后失效**：任何非 GET 成功/失败都清空 GET 缓存，避免展示写前旧值；
 * ④ **换身份失效**：{@link setTokenProvider} 清空缓存。
 *
 * <p>带外部 signal 的请求不参与去重与缓存（调用方可能在组件卸载时 abort，
 * 复用在途 Promise 会让另一个组件拿到被中止的结果）。
 */
export async function request<T>(
  path: string,
  init: RequestInit = {},
  timeoutMs: number = DEFAULT_TIMEOUT_MS,
): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const cacheable = method === 'GET' && getCacheTtlMs > 0 && !init.signal
  const key = `${BASE_URL}${path}`

  if (cacheable) {
    const cached = getCache.get(key)
    if (cached && cached.expiresAt > Date.now()) {
      return cached.value as T
    }
    const pending = inFlightGets.get(key)
    if (pending) {
      return pending as Promise<T>
    }
  }

  if (method !== 'GET') {
    // 写请求发出前先失效：即使写失败，也不该继续展示可能已被改动的旧列表
    getCache.clear()
  }

  const tracked = doRequest<T>(path, init, timeoutMs).then(
    (value) => {
      if (cacheable) {
        getCache.set(key, { value, expiresAt: Date.now() + getCacheTtlMs })
      }
      return value
    },
    (error) => {
      if (method !== 'GET') {
        getCache.clear()
      }
      throw error
    },
  )

  if (cacheable) {
    inFlightGets.set(key, tracked)
    const cleanup = () => inFlightGets.delete(key)
    tracked.then(cleanup, cleanup)
  }
  return tracked
}

/**
 * 二进制下载（RPT-05）。
 *
 * <p>下载接口返回文件流而不是 JSON 信封，不能走 request&lt;T&gt;。
 * 传入的 URL 可能自带 `/api` 前缀（后端返回的 downloadUrl），这里统一按 BASE_URL 拼接。
 */
export async function requestBlob(
  url: string,
  timeoutMs: number = 30_000,
): Promise<Blob> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const token = tokenProvider()
  const path = url.startsWith('/api/') ? url.slice(4) : url
  const target = path.startsWith('http') ? path : `${BASE_URL}${path}`

  try {
    const response = await fetch(target, {
      signal: controller.signal,
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!response.ok) {
      // 兜底文案保持中性（只是 HTTP 状态），实际可读文案由 error.DOWNLOAD_FAILED 提供
      let message = `HTTP ${response.status}`
      try {
        const envelope = parseEnvelope<unknown>(await response.text())
        if (envelope?.message) message = envelope.message
      } catch {
        // 非 JSON 错误体：保留 HTTP 状态提示
      }
      throw new ApiError(response.status, 'DOWNLOAD_FAILED', message)
    }
    return await response.blob()
  } catch (e) {
    throw normalizeFailure(e)
  } finally {
    clearTimeout(timer)
  }
}

/** 把对象拼成查询串；空值（undefined / null / ''）不拼，避免后端把空串当筛选条件。 */
export function toQuery(params: Record<string, unknown>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value))
    }
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

/** multipart 上传：不能带 Content-Type，边界必须由浏览器自己生成。 */
export async function requestMultipart<T>(
  path: string,
  form: FormData,
  timeoutMs: number = 30_000,
): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const token = tokenProvider()

  try {
    const response = await fetch(`${BASE_URL}${path}`, {
      method: 'POST',
      body: form,
      signal: controller.signal,
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })

    const envelope = parseEnvelope<T>(await response.text())
    if (envelope.code !== 0) {
      throw new ApiError(
        envelope.code,
        envelope.codeName,
        envelope.message,
        response.headers.get('X-Trace-Id'),
      )
    }
    return envelope.data as T
  } catch (e) {
    throw normalizeFailure(e)
  } finally {
    clearTimeout(timer)
  }
}

export function healthPing(): Promise<{ status: string; app: string; version: string }> {
  return request('/_ping')
}
