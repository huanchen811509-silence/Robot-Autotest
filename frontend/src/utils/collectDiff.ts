/**
 * 两次采集对比（v5.13.0 / W63-10）。
 *
 * 对比口径是「摘要项」：网口数 / RoCE 端口数 / 最高速率 / 采集状态。
 * 不解析 specJson 原文（那是命令回显，可能含敏感片段），前端只消费后端已提列的数值。
 * 新增 / 变化 / 消失三类互斥，变化项带旧值与新值。
 */
import type { CollectHistoryRow } from '@/api/collectProfiles'

export interface SnapshotItem {
  key: string
  /** i18n key，组件里再 t() 成文案，纯函数不碰语言 */
  label: string
  value: string
}

export interface ChangedItem {
  key: string
  label: string
  oldValue: string
  newValue: string
}

export interface DiffResult {
  added: SnapshotItem[]
  changed: ChangedItem[]
  removed: SnapshotItem[]
}

const EMPTY = '-'

export function snapshotItemsOf(row: CollectHistoryRow | undefined): SnapshotItem[] {
  if (!row) return []
  return [
    { key: 'nicCount', label: 'w63.itemNicCount', value: String(row.nicCount ?? 0) },
    { key: 'rocePorts', label: 'w63.itemRocePorts', value: String(row.rocePorts ?? 0) },
    { key: 'roceMaxGbps', label: 'w63.itemMaxGbps',
      value: row.roceMaxGbps == null ? EMPTY : String(row.roceMaxGbps) },
    { key: 'status', label: 'w63.itemStatus', value: row.status ?? EMPTY },
  ]
}

export function diffCollectSnapshots(
  before: readonly SnapshotItem[],
  after: readonly SnapshotItem[],
): DiffResult {
  const oldMap = new Map(before.map((item) => [item.key, item]))
  const newMap = new Map(after.map((item) => [item.key, item]))
  const added = after.filter((item) => !oldMap.has(item.key))
  const removed = before.filter((item) => !newMap.has(item.key))
  const changed: ChangedItem[] = []
  for (const item of after) {
    const previous = oldMap.get(item.key)
    if (previous && previous.value !== item.value) {
      changed.push({ key: item.key, label: item.label,
        oldValue: previous.value, newValue: item.value })
    }
  }
  return { added, changed, removed }
}

/** 历史按时间倒序时的「上一次 / 这一次」：rows[1] 是更早的一次。 */
export function latestPair(rows: readonly CollectHistoryRow[]) {
  return { previous: rows.length > 1 ? rows[1] : undefined, current: rows[0] }
}
