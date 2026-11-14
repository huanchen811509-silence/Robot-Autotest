/**
 * W57-16 · 服务端选择模型单测。
 *
 * 覆盖：useSelection 兼容面、条件模式（筛选表达全选）、排除集、>阈值二次确认、
 * 筛选变化重置、toDescriptor、批量回执后退回精确模式。纯组合式，无 DOM / 网络。
 */
import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { useServerSelection } from './useServerSelection'

describe('useServerSelection 兼容面（useSelection 语义）', () => {
  it('初始为空，toggle 增删，toDescriptor 为 ids 模式', () => {
    const s = useServerSelection(ref([1, 2, 3]))
    expect(s.isEmpty.value).toBe(true)
    s.toggle(1)
    s.toggle(2)
    expect(s.count.value).toBe(2)
    expect(s.isSelected(1)).toBe(true)
    expect(s.toDescriptor()).toEqual({ mode: 'ids', ids: [1, 2] })
  })

  it('表头全选只作用本页，跨页已选项不受影响', () => {
    const s = useServerSelection(ref([1, 2]))
    s.toggle(99)
    s.togglePage()
    expect(s.count.value).toBe(3)
    expect(s.isSelected(99)).toBe(true)
    expect(s.pageAllSelected.value).toBe(true)
  })

  it('半选态与跨页计数与 useSelection 同口径', () => {
    const s = useServerSelection(ref([1, 2, 3]))
    s.toggle(1)
    expect(s.pageIndeterminate.value).toBe(true)
    expect(s.pageAllSelected.value).toBe(false)
    s.toggle(99)
    expect(s.crossPageCount.value).toBe(1)
  })

  it('retain 只保留失败项，clear 一键清空', () => {
    const s = useServerSelection(ref([1, 2, 3, 4]))
    s.togglePage()
    s.retain([3, 4])
    expect(s.count.value).toBe(2)
    expect(s.isSelected(1)).toBe(false)
    expect(s.isSelected(3)).toBe(true)
    s.clear()
    expect(s.isEmpty.value).toBe(true)
  })
})

describe('条件模式：全选用筛选表达', () => {
  it('命中数 <= 阈值直接生效并返回 applied', () => {
    const s = useServerSelection(ref([1, 2]))
    const result = s.requestSelectAllMatching({ state: 'PASS' }, 42)
    expect(result).toBe('applied')
    expect(s.matchAll.value).toBe(true)
    expect(s.selectionMode.value).toBe('filter')
    expect(s.count.value).toBe(42)
    expect(s.activeFilters.value.state).toBe('PASS')
  })

  it('条件模式全部命中，取消一行只是加入排除集', () => {
    const s = useServerSelection(ref([1, 2]))
    s.requestSelectAllMatching({}, 10)
    expect(s.isSelected(1)).toBe(true)
    expect(s.isSelected(2)).toBe(true)
    s.toggle(1)
    expect(s.isSelected(1)).toBe(false)
    expect(s.excludeIds.value.has(1)).toBe(true)
    expect(s.count.value).toBe(9)
  })

  it('条件模式表头 togglePage：本页全排除后再全恢复', () => {
    const s = useServerSelection(ref([1, 2, 3]))
    s.requestSelectAllMatching({}, 100)
    expect(s.pageAllSelected.value).toBe(true)
    s.togglePage()
    expect(s.selectedOnPage.value).toBe(0)
    expect(s.pageAllSelected.value).toBe(false)
    s.togglePage()
    expect(s.selectedOnPage.value).toBe(3)
    expect(s.pageAllSelected.value).toBe(true)
  })

  it('条件模式 crossPageCount = 命中总数 - 本页选中数', () => {
    const s = useServerSelection(ref([1, 2]))
    s.requestSelectAllMatching({}, 50)
    expect(s.crossPageCount.value).toBe(48)
    expect(s.isEmpty.value).toBe(false)
  })

  it('toDescriptor 输出 filter 模式与排除集', () => {
    const s = useServerSelection(ref([7, 8]))
    s.requestSelectAllMatching({ q: 'rdma', f_state: 'PASS' }, 30)
    s.toggle(7)
    expect(s.toDescriptor()).toEqual({
      mode: 'filter',
      filters: { q: 'rdma', f_state: 'PASS' },
      excludeIds: [7],
    })
  })
})

describe('二次确认与筛选重置', () => {
  it('命中数 > 阈值返回 needs-confirm，且选择未生效', () => {
    const s = useServerSelection(ref([1]), { confirmThreshold: 10000 })
    const result = s.requestSelectAllMatching({}, 10001)
    expect(result).toBe('needs-confirm')
    expect(s.awaitingConfirm.value).toBe(true)
    expect(s.matchAll.value).toBe(false)
    expect(s.isEmpty.value).toBe(true)
  })

  it('confirmSelectAllMatching 后生效并关闭确认态', () => {
    const s = useServerSelection(ref([1]), { confirmThreshold: 5 })
    s.requestSelectAllMatching({ q: 'x' }, 6)
    s.confirmSelectAllMatching()
    expect(s.awaitingConfirm.value).toBe(false)
    expect(s.matchAll.value).toBe(true)
    expect(s.count.value).toBe(6)
  })

  it('cancelSelectAllMatching 取消后保持未选', () => {
    const s = useServerSelection(ref([1]), { confirmThreshold: 5 })
    s.requestSelectAllMatching({}, 6)
    s.cancelSelectAllMatching()
    expect(s.awaitingConfirm.value).toBe(false)
    expect(s.matchAll.value).toBe(false)
    expect(s.isEmpty.value).toBe(true)
    expect(s.toDescriptor()).toEqual({ mode: 'ids', ids: [] })
  })

  it('resetForFilters 作废旧全选并记录新条件', () => {
    const s = useServerSelection(ref([1, 2]))
    s.requestSelectAllMatching({ q: 'old' }, 20)
    expect(s.matchAll.value).toBe(true)
    s.resetForFilters({ q: 'new' })
    expect(s.matchAll.value).toBe(false)
    expect(s.activeFilters.value.q).toBe('new')
    expect(s.matchedTotal.value).toBe(0)
    expect(s.excludeIds.value.size).toBe(0)
    expect(s.isEmpty.value).toBe(true)
  })

  it('retain 让条件模式退回精确 ids 模式', () => {
    const s = useServerSelection(ref([1, 2]))
    s.requestSelectAllMatching({}, 20)
    s.retain([2, 3])
    expect(s.selectionMode.value).toBe('ids')
    expect(s.matchAll.value).toBe(false)
    expect(s.toDescriptor()).toEqual({ mode: 'ids', ids: [2, 3] })
  })
})
