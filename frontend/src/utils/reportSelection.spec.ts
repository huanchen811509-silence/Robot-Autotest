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

describe('reportSelection（v5.24.0 物理删口径）', () => {
  it('state=FROZEN 与 frozenAt 非空两种口径都算冻结（仅用于导出入口展示）', () => {
    expect(isFrozenReport(row(1, 'FROZEN', null))).toBe(true)
    expect(isFrozenReport(row(2, 'DRAFT', '2026-11-15T10:00:00+08:00'))).toBe(true)
    expect(isFrozenReport(row(3, 'draft', null))).toBe(false)
  })

  it('物理删：冻结件同样可删，可删集合 = 勾选集合且保持原顺序', () => {
    const rows = [row(1, 'DRAFT', null), row(2, 'FROZEN', null), row(3, 'DRAFT', null)]
    expect(deletableIds(rows, [3, 2, 1, 9])).toEqual([3, 2, 1, 9])
  })

  it('物理删：全冻结时仍返回全部勾选 id（大师 2026-11-20 裁决）', () => {
    const rows = [row(1, 'FROZEN', null), row(2, 'FROZEN', null)]
    expect(deletableIds(rows, [1, 2])).toEqual([1, 2])
  })
})
