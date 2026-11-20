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
  /** v5.15.0 / FR-v5.15-11: remote collection needs a credential and explicit confirmation. */
  username?: string
  credentialId?: number
  confirm?: boolean
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

// ------------------------------------------------------------------ v5.15.0 按项快照 / 探针包 / 拓扑 / 闸门

export interface DeviceSnapshot {
  id: number
  projectId: number
  deviceId: number | null
  host: string
  source: string
  snapshotHash: string
  specHash: string | null
  sourceRecordId: number | null
  collectedAt: string
  itemCount: number
  createdAt: string
}

export interface DeviceSnapshotItem {
  id: number
  projectId: number
  snapshotId: number
  itemKey: string
  category: string
  command: string | null
  valueJson: string | null
  valueHash: string | null
  collected: boolean
  reason: string | null
  createdAt: string
}

export interface ItemDiff {
  key: string
  change: string
  before: string | null
  after: string | null
}

/** Path builders are exported so the contract can be asserted without a live server. */
export const deviceSnapshotPaths = {
  list: (projectId: number, limit = 20) =>
    `/projects/${projectId}/snapshots/device${toQuery({ limit })}`,
  detail: (projectId: number, id: number) => `/projects/${projectId}/snapshots/${id}`,
  items: (projectId: number, id: number, key?: string) =>
    `/projects/${projectId}/snapshots/${id}/items${toQuery(key ? { key } : {})}`,
  latestItems: (projectId: number, key?: string) =>
    `/projects/${projectId}/snapshots/items/latest${toQuery(key ? { key } : {})}`,
  diffByItem: (projectId: number) => `/projects/${projectId}/snapshots/diff-by-item`,
}

export const deviceSnapshotApi = {
  list(projectId: number, limit = 20): Promise<DeviceSnapshot[]> {
    return request<DeviceSnapshot[]>(deviceSnapshotPaths.list(projectId, limit))
  },
  detail(projectId: number, id: number): Promise<DeviceSnapshot> {
    return request<DeviceSnapshot>(deviceSnapshotPaths.detail(projectId, id))
  },
  items(projectId: number, id: number, key?: string): Promise<DeviceSnapshotItem[]> {
    return request<DeviceSnapshotItem[]>(deviceSnapshotPaths.items(projectId, id, key))
  },
  latestItems(projectId: number, key?: string): Promise<DeviceSnapshotItem[]> {
    return request<DeviceSnapshotItem[]>(deviceSnapshotPaths.latestItems(projectId, key))
  },
  diffByItem(projectId: number, beforeId: number, afterId: number): Promise<ItemDiff[]> {
    return request<ItemDiff[]>(deviceSnapshotPaths.diffByItem(projectId), {
      method: 'POST',
      body: JSON.stringify({ beforeId, afterId }),
    })
  },
}

export interface PackCommandView {
  key: string
  command: string
  required: boolean
}

export interface PackView {
  code: string
  deviceType: string
  commands: PackCommandView[]
}

export const probePackApi = {
  commands(projectId: number, deviceType = 'HOST_LINUX'): Promise<PackView> {
    return request<PackView>(`/probe-packs/commands${toQuery({ projectId, deviceType })}`)
  },
  evaluate(projectId: number, snapshotId: number): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>(
      `/probe-packs/evaluate${toQuery({ projectId, snapshotId })}`)
  },
}

export interface TrustView {
  buckets: { confidence: string; source: string; count: number }[]
  measured: number
  planned: number
  overriddenPairs: number
  priority: string
}

/** 拓扑边的判定类别（v5.18.0 / FR-v5.18-03）：图上每条边恰好属于一类。 */
export type TopologyEdgeKind = 'MEASURED' | 'MANUAL' | 'PLANNED'

export interface TopologyEdge {
  id: number
  name: string
  aDeviceId: number | null
  aDeviceName: string | null
  bDeviceId: number | null
  bDeviceName: string | null
  kind: TopologyEdgeKind
  source: string | null
}

export interface PlannedNode {
  id: number
  name: string
  nodeType: string
  refDeviceId: number | null
  parentId: number | null
}

/** 拓扑图的节点（v5.34.0 / FR-v5.34-03）：只有 id/名称/类型，其余字段由图外的详情面板补齐。 */
export interface TopoNode {
  id: number
  name: string
  /** 后端 device_type 原值（大小写与中文混用：switch / HOST / GPU服务器 …）。 */
  deviceType: string | null
}

export interface TopologyEdges {
  edges: TopologyEdge[]
  /** 三类边的计数，三者之和恒等于 total。 */
  kindCounts: Record<string, number>
  /** 图例四类：三类边 + CONFLICT（被实测覆盖的规划边）。 */
  counts: Record<string, number>
  total: number
  /** 规划被实测覆盖的条数：不再单独成边，但必须让人看见。 */
  overridden: number
  plannedNodes: PlannedNode[]
}

export const topologyTrustApi = {
  edges(projectId: number): Promise<TopologyEdges> {
    return request<TopologyEdges>(`/projects/${projectId}/topology/edges`)
  },

  trust(projectId: number): Promise<TrustView> {
    return request<TrustView>(`/projects/${projectId}/topology/trust`)
  },
  lldpImport(projectId: number, neighbors: unknown[]): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>(`/projects/${projectId}/topology/lldp-import`, {
      method: 'POST',
      body: JSON.stringify({ neighbors }),
    })
  },
}

export interface GateRequirement {
  key: string
  op: string
  value: string
}

export interface GateCheckResult {
  pass: boolean
  undeclared: boolean
  snapshotId: number
  failures: { key: string; expected: string; actual: string; snapshotId: number }[]
  note: string
  notedRows: number
}

export const envGateApi = {
  check(projectId: number, payload: {
    caseKey?: string
    runId?: string
    requirements?: GateRequirement[]
  }): Promise<GateCheckResult> {
    return request<GateCheckResult>(`/projects/${projectId}/env-check`, {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  notes(projectId: number): Promise<Record<string, unknown>[]> {
    return request<Record<string, unknown>[]>(`/projects/${projectId}/env-check/notes`)
  },
}

/** Explicit collection body: credential fields pass through, undefined fields stay absent. */
export function buildCollectBody(projectId: number, payload: CollectPayload) {
  return { projectId, ...payload }
}
