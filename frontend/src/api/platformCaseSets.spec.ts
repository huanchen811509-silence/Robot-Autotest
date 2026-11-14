/**
 * W57-56 契约守门：平台用例集的**请求体**与**响应信封**都必须与后端同名同形。
 *
 * 1. 请求体必须是 `{ caseIds: [...] }`。后端入参是 `List<Long> caseIds`，发 `{ caseId }`
 *    会被 Jackson 静默忽略成 `caseIds=null`，接口返回 200 + `added=0`——界面看着成功、
 *    成员其实没加进去（v5.7.0 首版真实缺陷，见 06-ACCEPTANCE-AUDIT P0-3）。
 * 2. `list()` 必须拆掉分页信封。后端返回 `{ items, page, size, total }`，首版 declare 成
 *    `PlatformCaseSet[]`，调用方 `rows.map(...)` 抛 TypeError，`/g/case-sets` 列表与向导
 *    「内容」步的用例集分区因此永远是空的（2026-11-14 真实界面取证发现）。
 */
import { describe, expect, it, vi } from 'vitest'

interface Captured {
  url: string
  method: string
  body: unknown
}

const state = vi.hoisted(() => ({
  calls: [] as Captured[],
  /** 一次性响应队列；为空时返回 undefined（模拟「只关心请求体」的老用例）。 */
  queue: [] as unknown[],
  request: vi.fn(),
}))

vi.mock('@/api/http', () => ({
  request: async (url: string, init?: { method?: string; body?: string }) => {
    state.calls.push({
      url,
      method: init?.method ?? 'GET',
      body: init?.body ? JSON.parse(init.body) : undefined,
    })
    if (state.queue.length > 0) return state.queue.shift()
    return undefined
  },
  toQuery: (params: Record<string, unknown>) => {
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) search.append(key, String(value))
    }
    const qs = search.toString()
    return qs ? `?${qs}` : ''
  },
}))

import { platformCaseSetApi } from '@/api/platformCaseSets'

const calls = state.calls

describe('platformCaseSetApi 加成员请求体契约', () => {
  it('addItem 单条也包成 caseIds 数组', async () => {
    calls.length = 0
    await platformCaseSetApi.addItem(1, 99)
    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/platform/case-sets/1/items')
    expect(calls[0].method).toBe('POST')
    expect(calls[0].body).toEqual({ caseIds: [99] })
  })

  it('addItems 批量原样透传 caseIds', async () => {
    calls.length = 0
    await platformCaseSetApi.addItems(2, [7, 8, 9])
    expect(calls).toHaveLength(1)
    expect(calls[0].body).toEqual({ caseIds: [7, 8, 9] })
  })

  it('请求体里不许出现单数 caseId / setIds 等后端不认的字段', async () => {
    calls.length = 0
    await platformCaseSetApi.addItem(3, 5)
    const body = calls[0].body as Record<string, unknown>
    expect(Object.keys(body)).toEqual(['caseIds'])
    expect(body).not.toHaveProperty('caseId')
    expect(body).not.toHaveProperty('setIds')
  })
})

describe('platformCaseSetApi.list 响应信封契约', () => {
  it('拆掉分页信封：返回 data.items 数组', async () => {
    calls.length = 0
    const rows = [{ id: 47, setCode: 'SAMPLE-1', name: '样例', description: null, itemCount: 7 }]
    state.queue.push({ items: rows, page: 1, size: 100, total: 1 })

    await expect(platformCaseSetApi.list()).resolves.toEqual(rows)
    expect(calls[0].url).toBe('/platform/case-sets')
  })

  it('items 缺失时返回空数组，不返回 undefined（调用方要能安全 .map）', async () => {
    state.queue.push({ page: 1, size: 100, total: 0 })
    const rows = await platformCaseSetApi.list({ keyword: 'x' })
    expect(Array.isArray(rows)).toBe(true)
    expect(rows).toEqual([])
  })
})
