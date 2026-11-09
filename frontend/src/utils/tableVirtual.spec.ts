import { describe, expect, it } from 'vitest'
import { shouldVirtualize, VIRTUAL_ROW_THRESHOLD } from './tableVirtual'

describe('shouldVirtualize（FR-3.2-06 长列表窗口化）', () => {
  it('达到阈值自动开启：1 万行必须窗口化', () => {
    expect(shouldVirtualize(10_000, 'calc(100vh - 340px)', undefined)).toBe(true)
  })

  it('低于阈值保持全量渲染：分页 100 行的页面语义不变', () => {
    expect(shouldVirtualize(VIRTUAL_ROW_THRESHOLD - 1, '300px', undefined)).toBe(false)
  })

  it('整页滚动（maxHeight=null）时不开启，否则虚拟滚动没有视口', () => {
    expect(shouldVirtualize(10_000, null, true)).toBe(false)
  })

  it('显式 false 尊重调用方；显式 true 即使行数少也开启', () => {
    expect(shouldVirtualize(10_000, '300px', false)).toBe(false)
    expect(shouldVirtualize(10, '300px', true)).toBe(true)
  })
})
