/**
 * useCategorySelection（W57-10）纯逻辑单测：三态勾选、搜索不扰动已选、上限。
 * 组合式函数不依赖 DOM / 网络，直接读 computed.value 断言。
 */
import { describe, expect, it } from 'vitest'
import { useCategorySelection } from './useCategorySelection'

describe('useCategorySelection 基础', () => {
  it('初始为空：count=0，selectedIds=[]', () => {
    const s = useCategorySelection()
    expect(s.count.value).toBe(0)
    expect(s.selectedIds.value).toEqual([])
    expect(s.atLimit.value).toBe(false)
  })

  it('toggleCase 添加与移除', () => {
    const s = useCategorySelection()
    s.toggleCase(1)
    s.toggleCase(2)
    expect(s.selectedIds.value).toEqual([1, 2])
    expect(s.isCaseChecked(1)).toBe(true)
    s.toggleCase(1)
    expect(s.selectedIds.value).toEqual([2])
  })

  it('selectedIds 有序去重（Set 语义）', () => {
    const s = useCategorySelection()
    s.toggleCase(9)
    s.toggleCase(3)
    s.toggleCase(9)
    expect(s.selectedIds.value).toEqual([3])
    s.toggleCase(9)
    expect(s.selectedIds.value).toEqual([3, 9])
  })

  it('clear 一键清空', () => {
    const s = useCategorySelection()
    s.setMany([1, 2, 3], true)
    s.clear()
    expect(s.count.value).toBe(0)
    expect(s.selectedIds.value).toEqual([])
  })

  it('setMany 批量开', () => {
    const s = useCategorySelection()
    s.setMany([1, 2, 3], true)
    expect(s.selectedIds.value).toEqual([1, 2, 3])
  })

  it('setMany 批量关', () => {
    const s = useCategorySelection()
    s.setMany([1, 2, 3, 4], true)
    s.setMany([2, 3], false)
    expect(s.selectedIds.value).toEqual([1, 4])
  })
})

describe('useCategorySelection 三态（一级 / 二级 / 用例）', () => {
  it('全没选 → none', () => {
    const s = useCategorySelection()
    expect(s.stateOf([1, 2, 3])).toBe('none')
    expect(s.isChecked([1, 2, 3])).toBe(false)
    expect(s.isIndeterminate([1, 2, 3])).toBe(false)
  })

  it('部分选 → some（indeterminate=true）', () => {
    const s = useCategorySelection()
    s.toggleCase(2)
    expect(s.stateOf([1, 2, 3])).toBe('some')
    expect(s.isIndeterminate([1, 2, 3])).toBe(true)
    expect(s.isChecked([1, 2, 3])).toBe(false)
  })

  it('全选 → all', () => {
    const s = useCategorySelection()
    s.setMany([1, 2, 3], true)
    expect(s.stateOf([1, 2, 3])).toBe('all')
    expect(s.isChecked([1, 2, 3])).toBe(true)
    expect(s.isIndeterminate([1, 2, 3])).toBe(false)
  })

  it('空 id 列表恒为 none，不产生全选态', () => {
    const s = useCategorySelection()
    s.setMany([1, 2], true)
    expect(s.stateOf([])).toBe('none')
    expect(s.isChecked([])).toBe(false)
  })

  it('toggleL1：未全选 → 整批选；已全选 → 整批取消', () => {
    const s = useCategorySelection()
    s.toggleCase(1)
    s.toggleL1([1, 2, 3])
    expect(s.selectedIds.value).toEqual([1, 2, 3])
    s.toggleL1([1, 2, 3])
    expect(s.selectedIds.value).toEqual([])
  })

  it('toggleL2：部分选时补全体，全选时整批取消', () => {
    const s = useCategorySelection()
    s.toggleCase(2)
    s.toggleL2([1, 2, 3])
    expect(s.selectedIds.value).toEqual([1, 2, 3])
    s.toggleL2([1, 2, 3])
    expect(s.count.value).toBe(0)
  })
})

describe('useCategorySelection 搜索与上限', () => {
  it('设置 keyword 后 selected 不变（搜索只影响可见项）', () => {
    const s = useCategorySelection()
    s.setMany([1, 2, 3], true)
    s.keyword.value = '以太网'
    expect(s.selectedIds.value).toEqual([1, 2, 3])
    expect(s.count.value).toBe(3)
  })

  it('isVisible：空关键词全可见；命中/不命中按大小写无关', () => {
    const s = useCategorySelection()
    expect(s.isVisible('以太网 / 链路')).toBe(true)
    s.keyword.value = 'RDMA'
    expect(s.isVisible('RDMA 性能')).toBe(true)
    expect(s.isVisible('以太网')).toBe(false)
    s.keyword.value = '  链路  '
    expect(s.isVisible('以太网 / 链路')).toBe(true)
  })

  it('到达默认上限 1000 后拒绝新增，但仍可取消', () => {
    const s = useCategorySelection()
    s.setMany(Array.from({ length: 1000 }, (_, i) => i + 1), true)
    expect(s.count.value).toBe(1000)
    expect(s.atLimit.value).toBe(true)
    s.toggleCase(1001)
    expect(s.isCaseChecked(1001)).toBe(false)
    s.toggleCase(1)
    expect(s.isCaseChecked(1)).toBe(false)
    expect(s.count.value).toBe(999)
  })

  it('上限可配：maxSelect=2', () => {
    const s = useCategorySelection({ maxSelect: 2 })
    s.toggleCase(1)
    s.toggleCase(2)
    s.toggleCase(3)
    expect(s.selectedIds.value).toEqual([1, 2])
    expect(s.maxSelect).toBe(2)
    expect(s.atLimit.value).toBe(true)
  })

  it('setMany 尊重上限，超限跳过新增但不截断已选', () => {
    const s = useCategorySelection({ maxSelect: 3 })
    s.setMany([1, 2, 3, 4, 5], true)
    expect(s.selectedIds.value).toEqual([1, 2, 3])
  })
})
