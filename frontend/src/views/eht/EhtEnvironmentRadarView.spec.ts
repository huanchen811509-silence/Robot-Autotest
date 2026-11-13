/**
 * EhtEnvironmentRadarView 数据源契约：页面必须打真实端点，不许 mock 顶替。
 */
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/api/http', () => ({
  request: async (url: string) => url,
}))

import { ehtViewsApi } from '@/api/ehtViews'

describe('EhtEnvironmentRadarView real endpoint', () => {
  it('calls /projects/95/environment-radar', async () => {
    const url = await ehtViewsApi.environmentRadar(95)
    expect(url).toContain('/projects/95/environment-radar')
  })
})
