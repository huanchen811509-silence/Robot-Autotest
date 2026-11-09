/**
 * 环境与拓扑 / 维护与恢复（FR-v3.11-02 / 03）的只读数据入口。
 *
 * 口径：页面只负责渲染，所有事实都从后端接口取；没取到就显示空态或错误态，
 * 绝不在前端编造一行「示例数据」。只写动作只有两个，且都必须由用户显式点击：
 *   - POST /asset-host-inventory/collect  手动采集一次本机清单
 *   - POST /projects/{id}/snapshots       创建一份环境快照
 * 其余（含 /snapshots/diff，虽然走 POST 但只是把两份快照算个差）一律是取数。
 */
import { request, toQuery } from './http'

// ------------------------------------------------------------------ 主机清单

export interface HostInventoryItem {
  category: string
  label: string
  command: string
  collectedAt: string
  value: unknown
  reason: string | null
}

export interface HostInventorySpec {
  host: string
  deviceId: number | null
  collectedAt: string
  status: 'OK' | 'PARTIAL' | 'FAILED' | string
  items: HostInventoryItem[]
}

export interface HostInventoryRecord {
  id: number
  projectId: number
  deviceId: number | null
  host: string
  collectedAt: string
  status: string
  /** 列表接口恒为 null：整份清单只在详情里给，避免列表页拖几十 KB。 */
  specJson: string | null
  specHash: string | null
  nicCount: number
  rocePorts: number
  roceMaxGbps: number | null
}

export interface CollectPayload {
  host: string
  deviceId?: number | null
}

const HOST = '/asset-host-inventory'

export const hostInventoryApi = {
  commands(projectId: number): Promise<Record<string, string>> {
    return request<Record<string, string>>(`${HOST}/commands${toQuery({ projectId })}`)
  },
  list(projectId: number, params: { deviceId?: number | null; limit?: number } = {}):
    Promise<HostInventoryRecord[]> {
    return request<HostInventoryRecord[]>(`${HOST}${toQuery({ projectId, ...params })}`)
  },
  detail(projectId: number, id: number): Promise<HostInventoryRecord> {
    return request<HostInventoryRecord>(`${HOST}/${id}${toQuery({ projectId })}`)
  },
  /** 显式采集：只有用户点了「立即采集」才会调用，页面加载时不自动跑。 */
  collect(projectId: number, payload: CollectPayload): Promise<HostInventoryRecord> {
    return request<HostInventoryRecord>(`${HOST}/collect`, {
      method: 'POST',
      body: JSON.stringify({ projectId, ...payload }),
    })
  },
}

/** specJson 是「JSON 字符串」，页面要读成对象；解析失败按「没有详情」处理，不抛到界面外。 */
export function parseSpec(raw: string | null): HostInventorySpec | null {
  if (!raw) return null
  try {
    const value = JSON.parse(raw) as HostInventorySpec
    return Array.isArray(value.items) ? value : null
  } catch {
    return null
  }
}

// ------------------------------------------------------------------ 环境画像与快照

export interface EnvProfileSummary {
  name: string
  profileType: string | null
  snapshotCount: number
  latestAt: string | null
}

export interface EnvSnapshot {
  id: number
  name: string
  profileType: string | null
  description: string | null
  snapshotJson: string
  snapshotAt: string | null
}

export interface SnapshotDiff {
  path: string
  change: 'added' | 'removed' | 'changed' | string
  before: string | null
  after: string | null
}

const snapshots = (projectId: number) => `/projects/${projectId}/snapshots`

export const envApi = {
  profiles(projectId: number): Promise<EnvProfileSummary[]> {
    return request<EnvProfileSummary[]>(`${snapshots(projectId)}/profiles`)
  },
  snapshots(projectId: number, name: string): Promise<EnvSnapshot[]> {
    return request<EnvSnapshot[]>(`${snapshots(projectId)}${toQuery({ name })}`)
  },
  latest(projectId: number, name: string): Promise<EnvSnapshot> {
    return request<EnvSnapshot>(`${snapshots(projectId)}/latest${toQuery({ name })}`)
  },
  create(projectId: number, payload: {
    name: string
    profileType: string
    description: string
    snapshotJson: string
  }): Promise<{ id: number }> {
    return request<{ id: number }>(snapshots(projectId), {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  /** 纯计算的 diff：不写库，只是把两份快照原文交给后端展平后比较。 */
  diff(projectId: number, before: EnvSnapshot, after: EnvSnapshot): Promise<SnapshotDiff[]> {
    return request<SnapshotDiff[]>(`${snapshots(projectId)}/diff`, {
      method: 'POST',
      body: JSON.stringify({ before, after }),
    })
  },
}
