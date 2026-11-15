import { describe, expect, it } from 'vitest'
import type { TestReportItem } from '@/api/testReports'
import { deletableIds, isFrozenReport } from '@/utils/reportSelection'

function row(id: number, state: string, frozenAt: string | null): TestReportItem {
  return {
    id,
    projectId: 1,
    runId: 'RUN-' + id,
    title: '报告 ' + id,
    state,
    version: 1,
    snapshotHash: null,
    createdAt: '2026-11-15T09:00:00+08:00',
    frozenAt,
  }
}

describe('reportSelection（W64-16 冻结态批量删防线）', () => {
  it('state=FROZEN 与 frozenAt 非空两种口径都算冻结', () => {
    expect(isFrozenReport(row(1, 'FROZEN', null))).toBe(true)
    expect(isFrozenReport(row(2, 'DRAFT', '2026-11-15T10:00:00+08:00'))).toBe(true)
    expect(isFrozenReport(row(3, 'draft', null))).toBe(false)
  })

  it('批量选择只剔除冻结 id，且保持原顺序', () => {
    const rows = [row(1, 'DRAFT', null), row(2, 'FROZEN', null), row(3, 'DRAFT', null)]
    expect(deletableIds(rows, [3, 2, 1, 9])).toEqual([3, 1, 9])
  })

  it('全冻结时返回空数组 —— 不弹确认框、不发删除请求', () => {
    const rows = [row(1, 'FROZEN', null), row(2, 'FROZEN', null)]
    expect(deletableIds(rows, [1, 2])).toEqual([])
  })
})
