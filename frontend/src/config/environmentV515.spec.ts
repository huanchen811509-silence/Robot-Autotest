import { describe, expect, it } from 'vitest'
import type { NavGroup } from './navigation'
import { NAVIGATION } from './navigation'
import {
  buildCollectBody, deviceSnapshotPaths, type CollectPayload,
} from '@/api/environment'

// v5.15.0 W2/W5/W7: path contract, credential pass-through and navigation convergence.
describe('v5.15.0 environment contract', () => {
  it('device snapshot paths stay under the real snapshots prefix', () => {
    expect(deviceSnapshotPaths.list(95)).toBe('/projects/95/snapshots/device?limit=20')
    expect(deviceSnapshotPaths.detail(95, 3)).toBe('/projects/95/snapshots/3')
    expect(deviceSnapshotPaths.items(95, 3, 'ROCE'))
      .toBe('/projects/95/snapshots/3/items?key=ROCE')
    expect(deviceSnapshotPaths.latestItems(95)).toBe('/projects/95/snapshots/items/latest')
    expect(deviceSnapshotPaths.diffByItem(95)).toBe('/projects/95/snapshots/diff-by-item')
  })

  it('collect body passes credentialId / username / confirm through', () => {
    const payload: CollectPayload = {
      host: '10.0.0.2', deviceId: 7, credentialId: 12, username: 'ops', confirm: true,
    }
    expect(buildCollectBody(95, payload)).toEqual({ projectId: 95, ...payload })
  })

  it('local collection keeps the minimal body', () => {
    expect(buildCollectBody(95, { host: 'localhost' }))
      .toEqual({ projectId: 95, host: 'localhost' })
  })

  it('environment group converges from 6 leaves to 4', () => {
    const group = NAVIGATION.find((node) => node.key === 'environment') as NavGroup
    expect(group.children.map((child) => child.key)).toEqual([
      'environment-overview', 'environment-collect',
      'environment-topology', 'environment-connections',
    ])
  })
})
