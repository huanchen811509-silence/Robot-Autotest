/**
 * EHT-10 独立裁判：优先级字典必须来自真实 /cases 分页数据，不许写死枚举字面。
 */
import { describe, expect, it, vi } from 'vitest'

const calls: string[] = []

vi.mock('@/api/http', () => ({
  request: async (url: string) => {
    calls.push(url)
    const params = new URLSearchParams(url.split('?')[1] ?? '')
    const page = Number(params.get('page') ?? 1)
    return {
      items: page === 1
        ? [{ priority: 'T0' }, { priority: '待评审' }, { priority: 'T0' }]
        : [{ priority: 'T1' }],
      page,
      size: 100,
      total: 101,
    }
  },
}))

import { ehtViewsApi } from '@/api/ehtViews'

describe('ehtViewsApi.casePriorities', () => {
  it('逐页汇总 distinct priority，翻页覆盖 total', async () => {
    const values = await ehtViewsApi.casePriorities(95)
    expect(values).toEqual(['T0', 'T1', '待评审'])
    expect(calls.length).toBe(2)
    expect(calls[0]).toContain('/projects/95/cases')
    expect(calls[1]).toContain('page=2')
  })

  it('同一项目命中缓存，不重复翻页', async () => {
    const before = calls.length
    await ehtViewsApi.casePriorities(95)
    expect(calls.length).toBe(before)
  })

  it('换项目重新取数，端点带新 projectId', async () => {
    const before = calls.length
    await ehtViewsApi.casePriorities(96)
    expect(calls.length).toBe(before + 2)
    expect(calls[before]).toContain('/projects/96/cases')
  })
})
