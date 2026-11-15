import { describe, expect, it } from 'vitest'
import type { CollectHistoryRow } from '@/api/collectProfiles'
import { diffCollectSnapshots, latestPair, snapshotItemsOf } from '@/utils/collectDiff'

function row(id: number, nic: number, roce: number, status: string): CollectHistoryRow {
  return { id, deviceId: 9, status, collectedAt: '2026-11-15 10:00:00',
    nicCount: nic, rocePorts: roce, roceMaxGbps: 100 }
}

describe('两次采集对比（W63-10）', () => {
  it('摘要项固定四项，label 是 i18n key 而非硬编码中文', () => {
    const items = snapshotItemsOf(row(1, 2, 1, 'OK'))
    expect(items).toHaveLength(4)
    expect(items.every((item) => item.label.startsWith('w63.'))).toBe(true)
  })

  it('变化项带旧值 → 新值', () => {
    const diff = diffCollectSnapshots(snapshotItemsOf(row(1, 2, 1, 'OK')),
      snapshotItemsOf(row(2, 4, 1, 'OK')))
    expect(diff.changed).toHaveLength(1)
    expect(diff.changed[0].oldValue).toBe('2')
    expect(diff.changed[0].newValue).toBe('4')
  })

  it('新增 / 消失按 key 判定，互不重复', () => {
    const before = [{ key: 'a', label: 'w63.itemStatus', value: '1' }]
    const after = [{ key: 'b', label: 'w63.itemStatus', value: '1' }]
    const diff = diffCollectSnapshots(before, after)
    expect(diff.added.map((x) => x.key)).toEqual(['b'])
    expect(diff.removed.map((x) => x.key)).toEqual(['a'])
    expect(diff.changed).toHaveLength(0)
  })

  it('历史为空 / 只有一次时不产生假对比', () => {
    expect(latestPair([]).current).toBeUndefined()
    expect(latestPair([row(1, 2, 1, 'OK')]).previous).toBeUndefined()
    expect(snapshotItemsOf(undefined)).toEqual([])
  })
})
