/** 列设置纯逻辑：顺序调整、可见列输出与旧配置合并（新增列不丢、未知列不炸）。 */
import { describe, expect, it } from 'vitest'
import {
  moveColumn,
  reconcileColumns,
  visibleKeys,
  type ColumnOption,
} from '@/components/ui/columnSettings'

function options(): ColumnOption[] {
  return [
    { key: 'caseCode', label: '编号', visible: true },
    { key: 'title', label: '标题', visible: true },
    { key: 'priority', label: '优先级', visible: false },
    { key: 'actions', label: '操作', visible: false, alwaysVisible: true },
  ]
}

describe('moveColumn', () => {
  it('上移/下移一格，其余顺序不变', () => {
    const list = options()
    expect(moveColumn(list, 2, -1).map((column) => column.key)).toEqual([
      'caseCode',
      'priority',
      'title',
      'actions',
    ])
    expect(moveColumn(list, 1, 1).map((column) => column.key)).toEqual([
      'caseCode',
      'priority',
      'title',
      'actions',
    ])
    // 不修改入参
    expect(list.map((column) => column.key)).toEqual(['caseCode', 'title', 'priority', 'actions'])
  })

  it('越界时原样返回，调用处不必再写边界判断', () => {
    const list = options()
    expect(moveColumn(list, 0, -1)).toBe(list)
    expect(moveColumn(list, 3, 1)).toBe(list)
    expect(moveColumn(list, 9, -1)).toBe(list)
  })
})

describe('visibleKeys', () => {
  it('只输出可见列，顺序即展示顺序', () => {
    expect(visibleKeys(options())).toEqual(['caseCode', 'title'])
  })
})

describe('reconcileColumns', () => {
  it('从未配置时全部可见并保持默认顺序', () => {
    const result = reconcileColumns(options(), null)
    expect(result.every((column) => column.visible)).toBe(true)
    expect(result.map((column) => column.key)).toEqual(['caseCode', 'title', 'priority', 'actions'])
  })

  it('按保存的可见 key 还原顺序，未列出的列隐藏并追加在后', () => {
    const result = reconcileColumns(options(), ['priority', 'caseCode'])
    expect(result.map((column) => column.key)).toEqual([
      'priority',
      'caseCode',
      'title',
      'actions',
    ])
    expect(result.map((column) => column.visible)).toEqual([true, true, false, true])
  })

  it('未知 key 与重复 key 被丢弃，alwaysVisible 强制可见', () => {
    const result = reconcileColumns(options(), ['actions', 'ghost', 'actions'])
    expect(result.map((column) => column.key)).toEqual(['actions', 'caseCode', 'title', 'priority'])
    expect(result.find((column) => column.key === 'actions')?.visible).toBe(true)
  })

  it('空数组表示用户主动隐藏全部（必显列除外）', () => {
    const result = reconcileColumns(options(), [])
    expect(visibleKeys(result)).toEqual(['actions'])
  })
})
