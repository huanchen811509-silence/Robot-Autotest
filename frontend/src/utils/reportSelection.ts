/**
 * v5.24.0：物理删口径——冻结 / 归档件同样允许删除（大师 2026-11-20 裁决）。
 *
 * 此前这里按 FROZEN 剔除，后端也会 409 拒绝；现在状态机只约束"改标题"，
 * 不约束删除，所以可删集合 = 勾选集合本身，不再做前端二次过滤。
 */
import type { TestReportItem } from '@/api/testReports'

/** 保留导出入口的冻结判定（冻结件才是正式交付物），但不再用于剔除删除。 */
export function isFrozenReport(row: TestReportItem): boolean {
  return row.state.toUpperCase() === 'FROZEN' || row.frozenAt !== null
}

/** 保持 selected 原顺序；物理删不因冻结状态剔除任何一行。 */
export function deletableIds(_rows: TestReportItem[], selected: Iterable<number>): number[] {
  return [...selected]
}
