/**
 * 请求层短缓存与在途去重（FR-3.2-06）。
 *
 * <p>只测「网络被调用几次」这一个可观察事实：缓存是否命中、写请求是否失效、
 * 换身份是否清空，都能用 fetch mock 的调用次数直接证明，不依赖组件。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  configureRequestCache,
  request,
  resetRequestCache,
  setTokenProvider,
} from './http'

function envelope(data: unknown): { text: () => Promise<string> } {
  return {
    text: async () => JSON.stringify({ code: 0, codeName: 'OK', message: '', data }),
  }
}

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  resetRequestCache()
  configureRequestCache(3000)
  fetchMock = vi.fn(async () => envelope({ rows: [] }))
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  resetRequestCache()
  setTokenProvider(() => null)
  configureRequestCache(3000)
})

describe('request 短缓存与去重', () => {
  it('同一 GET 并发只发一次网络请求', async () => {
    let release!: (value: { text: () => Promise<string> }) => void
    fetchMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          release = resolve
        }),
    )

    const first = request('/cases')
    const second = request('/cases')
    expect(fetchMock).toHaveBeenCalledTimes(1)

    release(envelope({ rows: ['a'] }))
    await Promise.all([first, second])
  })

  it('短缓存窗口内重复 GET 不再请求网络', async () => {
    await request('/cases')
    await request('/cases')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('不同路径各自请求', async () => {
    await request('/cases')
    await request('/runs')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('写请求清空 GET 缓存，写后读到新值', async () => {
    await request('/cases')
    await request('/cases/1', { method: 'PUT', body: '{}' })
    await request('/cases')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('TTL=0 关闭缓存（顺序请求仍各自发网络）', async () => {
    configureRequestCache(0)
    await request('/cases')
    await request('/cases')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('换 token 清空缓存，避免跨身份读到旧列表', async () => {
    await request('/cases')
    setTokenProvider(() => 'token-2')
    await request('/cases')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('每个请求带 X-Request-Id，后端可据此串联 traceId', async () => {
    await request('/cases')
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect((init.headers as Record<string, string>)['X-Request-Id']).toMatch(/[A-Za-z0-9-]{8,}/)
  })
})
