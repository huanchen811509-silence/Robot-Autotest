/**
 * FR-v3.9-04 · 每页条数策略的单元测试。
 *
 * 只测「纯决策逻辑」：档位估算、记忆优先级、非法值兜底。
 * 真实页面的空白高度由 `scripts/density-probe.mjs` 在浏览器里量（见 EW39-04 证据）。
 */

import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PAGE_SIZE_OPTIONS,
  estimatePageSize,
  readRememberedPageSize,
  useListPageSize,
} from './useListPageSize'

const KEY = 'robot.list.pageSize'

describe('estimatePageSize（视口自适应）', () => {
  it('档位只有 10/20/50/100 四档', () => {
    expect([...PAGE_SIZE_OPTIONS]).toEqual([10, 20, 50, 100])
  })

  it('1440×900 取 20 档：能放下 13 行就向上取到 20，而不是死守 10', () => {
    expect(estimatePageSize(900)).toBe(20)
  })

  it('小屏 1280×720 不硬塞，退回 10 档', () => {
    expect(estimatePageSize(720)).toBe(10)
  })

  it('超高屏按行数继续上探，但不超过最大档位', () => {
    // 2160 高 ≈ 42 行 → 50 档；再高也封顶 100
    expect(estimatePageSize(2160)).toBe(50)
    expect(estimatePageSize(4320)).toBe(100)
  })

  it('估算值永远落在合法档位内', () => {
    for (const h of [500, 600, 720, 900, 1080, 1440, 2160, 4320]) {
      expect(PAGE_SIZE_OPTIONS).toContain(estimatePageSize(h))
    }
  })
})

describe('readRememberedPageSize（记忆）', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('空存储返回 null（交给视口估算）', () => {
    expect(readRememberedPageSize()).toBeNull()
  })

  it('记忆值合法时优先于视口估算', () => {
    window.localStorage.setItem(KEY, '50')
    expect(readRememberedPageSize()).toBe(50)
    expect(useListPageSize().size.value).toBe(50)
  })

  it('非法记忆值（旧版本残留/被改坏）当没记住', () => {
    window.localStorage.setItem(KEY, '37')
    expect(readRememberedPageSize()).toBeNull()
  })
})

describe('useListPageSize（写入即持久化）', () => {
  beforeEach(() => {
    window.localStorage.clear()
    vi.restoreAllMocks()
  })

  it('改档位会写回 localStorage', () => {
    const { size } = useListPageSize()
    size.value = 100
    expect(window.localStorage.getItem(KEY)).toBe('100')
  })

  it('档位外的值被忽略，不污染当前状态', () => {
    const { size } = useListPageSize()
    const before = size.value
    // 视图恢复或 URL 回填可能传进脏值
    ;(size as unknown as { value: number }).value = 37
    expect(size.value).toBe(before)
  })

  it('localStorage 不可用时降级为视口估算，不抛错', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })
    const { size } = useListPageSize()
    expect(() => {
      size.value = 20
    }).not.toThrow()
    expect(size.value).toBe(20)
  })
})
