/**
 * useRunSelection（W57-29/30）纯逻辑单测。
 *
 * 最重要的一条是文件末尾的「硬对照回归」：集 A 有 3 条 + 手工勾 1 条 → 取消 A → 剩 1 条。
 * 现行 ExecutionPlansView.toggleSet 用「剩余集合并集」重建已选，这条会剩 0 条（缺陷）。
 */
import { describe, expect, it } from 'vitest'
import { useRunSelection } from './useRunSelection'

describe('useRunSelection 基础', () => {
  it('初始 MIXED：selectedCount=0，selectedCaseIds=[]', () => {
    const s = useRunSelection()
    expect(s.mode.value).toBe('MIXED')
    expect(s.selectedCount.value).toBe(0)
    expect(s.selectedCaseIds.value).toEqual([])
    expect(s.selectedSetIds.value).toEqual([])
  })

  it('toggleCase 添加与移除（只增删 case:<id> 来源）', () => {
    const s = useRunSelection()
    s.toggleCase(11)
    expect(s.selectedCaseIds.value).toEqual([11])
    expect(s.sourcesOf(11)).toEqual(['case:11'])
    s.toggleCase(11)
    expect(s.selectedCaseIds.value).toEqual([])
  })

  it('toggleSet 把集合展开成 caseIds，并记下 set:<id> 来源', () => {
    const s = useRunSelection()
    s.toggleSet(71, [11, 12])
    expect(s.selectedCaseIds.value).toEqual([11, 12])
    expect(s.selectedSetIds.value).toEqual([71])
    expect(s.sourcesOf(11)).toEqual(['set:71'])
    expect(s.isSetSelected(71, [11, 12])).toBe(true)
  })

  it('取消集合只摘该集合来源：另一集合的来源保留', () => {
    const s = useRunSelection()
    s.toggleSet(71, [11, 12])
    s.toggleSet(72, [12, 13]) // 12 同时属于两个集合
    expect(s.selectedCaseIds.value).toEqual([11, 12, 13])
    s.toggleSet(71, [11, 12])
    // 11 只来自 71 → 删除；12 仍有 set:72 → 保留；13 不受影响
    expect(s.selectedCaseIds.value).toEqual([12, 13])
    expect(s.sourcesOf(12)).toEqual(['set:72'])
  })

  it('手工勾选与集合重叠时：取消手工来源不影响集合来源', () => {
    const s = useRunSelection()
    s.toggleSet(71, [11, 12])
    s.toggleCase(11) // 11 同时有 set:71 + case:11
    expect(s.sourcesOf(11)).toContain('case:11')
    s.toggleCase(11) // 只删 case:11
    expect(s.sourcesOf(11)).toEqual(['set:71'])
    expect(s.isCaseSelected(11)).toBe(true)
  })

  it('空集合勾选不产生任何已选', () => {
    const s = useRunSelection()
    s.toggleSet(71, [])
    expect(s.selectedCount.value).toBe(0)
    expect(s.isSetSelected(71, [])).toBe(false)
  })

  it('clear 清空当前模式', () => {
    const s = useRunSelection()
    s.toggleSet(71, [11, 12])
    s.toggleCase(99)
    s.clear()
    expect(s.selectedCount.value).toBe(0)
    expect(s.selectedSetIds.value).toEqual([])
  })

  it('toPayload 带 mode / caseIds / setIds / sources', () => {
    const s = useRunSelection()
    s.toggleSet(71, [11, 12])
    s.toggleCase(99)
    const p = s.toPayload()
    expect(p.mode).toBe('MIXED')
    expect(p.caseIds).toEqual([11, 12, 99])
    expect(p.setIds).toEqual([71])
    expect(p.sources.find((x) => x.caseId === 11)?.sources).toEqual(['set:71'])
    expect(p.all).toBe(false)
  })

  it('manualCaseIds 只列手工来源', () => {
    const s = useRunSelection()
    s.toggleSet(71, [11, 12])
    s.toggleCase(99)
    expect(s.manualCaseIds.value).toEqual([99])
  })
})

describe('useRunSelection 四模式草稿', () => {
  it('setMode 切走再切回，集合选择原样保留', () => {
    const s = useRunSelection()
    s.toggleSet(71, [11, 12])
    s.setMode('CASE')
    expect(s.selectedCaseIds.value).toEqual([])
    s.setMode('MIXED')
    expect(s.selectedCaseIds.value).toEqual([11, 12])
    expect(s.selectedSetIds.value).toEqual([71])
  })

  it('不同模式草稿互不影响', () => {
    const s = useRunSelection()
    s.toggleSet(71, [11, 12]) // MIXED 草稿
    s.setMode('SET')
    s.toggleSet(72, [13]) // SET 草稿
    expect(s.selectedCaseIds.value).toEqual([13])
    s.setMode('MIXED')
    expect(s.selectedCaseIds.value).toEqual([11, 12])
  })

  it('ALL 模式 setAll(true) → toPayload.all=true；切回 MIXED 恢复各自草稿', () => {
    const s = useRunSelection()
    s.toggleCase(11)
    s.setMode('ALL')
    s.setAll(true)
    expect(s.toPayload().all).toBe(true)
    s.setMode('MIXED')
    expect(s.toPayload().all).toBe(false)
    expect(s.selectedCaseIds.value).toEqual([11])
  })

  it('切到同一模式是空操作，不清空当前选择', () => {
    const s = useRunSelection()
    s.toggleCase(11)
    s.setMode('MIXED')
    expect(s.selectedCaseIds.value).toEqual([11])
  })

  it('clear 只清当前模式，其它模式草稿仍在', () => {
    const s = useRunSelection()
    s.toggleSet(71, [11, 12])
    s.setMode('SET')
    s.toggleCase(13)
    s.clear() // 清 SET
    expect(s.selectedCaseIds.value).toEqual([])
    s.setMode('MIXED')
    expect(s.selectedCaseIds.value).toEqual([11, 12])
  })
})

describe('W57-29 toggleSet 缺陷硬对照回归', () => {
  it('集 A 有 3 条 + 手工勾 1 条 → 取消 A → 剩 1 条（现行实现剩 0 条）', () => {
    const s = useRunSelection('MIXED')
    s.toggleSet(1, [101, 102, 103]) // 集 A：3 条
    s.toggleCase(999) // 手工单独勾 1 条
    expect(s.selectedCaseIds.value).toEqual([101, 102, 103, 999])
    expect(s.selectedCount.value).toBe(4)

    s.toggleSet(1, [101, 102, 103]) // 取消 A

    // 旧实现：keep = 剩余集合(0 个)并集 = 空 → 4 条全删 → 剩 0；
    // 新实现：只摘 set:1，手工的 case:999 原样保留 → 剩 1。
    expect(s.selectedCaseIds.value).toEqual([999])
    expect(s.selectedCount.value).toBe(1)
    expect(s.selectedSetIds.value).toEqual([])
    expect(s.sourcesOf(999)).toEqual(['case:999'])
  })

  it('回归后再勾回集合 A：4 条完整恢复', () => {
    const s = useRunSelection()
    s.toggleSet(1, [101, 102, 103])
    s.toggleCase(999)
    s.toggleSet(1, [101, 102, 103])
    expect(s.selectedCount.value).toBe(1)
    s.toggleSet(1, [101, 102, 103])
    expect(s.selectedCaseIds.value).toEqual([101, 102, 103, 999])
  })
})
