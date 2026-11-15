/**
 * v5.14.0 / W64-16：批量软删的可删集合唯一真源。
 *
 * 此前列表勾选列对所有行都渲染 checkbox，冻结件也能被勾中并送进批量删；
 * 后端虽会 409 拒绝，但整批回执变成「部分失败」，界面还要靠报错解释原因。
 * 现在前端先按同一口径排除，后端拒绝保留为第二道防线。
 */
import type { TestReportItem } from '@/api/testReports'

/** 与 TestReportsView 原 isFrozen 完全同口径：state=FROZEN 或有 frozenAt 即冻结。 */
export function isFrozenReport(row: TestReportItem): boolean {
  return row.state.toUpperCase() === 'FROZEN' || row.frozenAt !== null
}

/** 保持 selected 原顺序，只剔除冻结行；找不到对应行的 id 不擅自丢弃（由后端判定）。 */
export function deletableIds(rows: TestReportItem[], selected: Iterable<number>): number[] {
  const frozen = new Set(rows.filter(isFrozenReport).map((row) => row.id))
  return [...selected].filter((id) => !frozen.has(id))
}
