/**
 * 连接与拓扑派生接口（v4.3.0 / FR-v4.3-07）。
 *
 * 数据全部来自后端 `GET /api/projects/{projectId}/asset-info`：设备 + 端口（含网卡归属）
 * + 链路派生，不读远端、不读手工拓扑。`refresh` 是唯一写请求（POST），需要 ASSET/UPDATE。
 */

import { request, toQuery } from './http'

/** 元信息：生成时间之外，说明这次数据是命中复用还是实时计算（PRD FR-v4.3-05 R5）。 */
export interface AssetInfoMeta {
  cacheHit: boolean
  source: string
  expired: boolean
  ttlSeconds: number
}

/** 一条连接 = 一条链路的两端；端口缺失时对应字段为 null，行保留。 */
export interface AssetConnection {
  linkId: number
  linkName: string | null
  linkType: string | null
  aDevice: string | null
  aNic: string | null
  aPort: string | null
  bDevice: string | null
  bNic: string | null
  bPort: string | null
  protocol: string | null
  speedGbps: number | null
  mtu: number | null
  macAddress: string | null
  ipAddress: string | null
  portStatus: string | null
  enabled: boolean
}

export interface AssetTopologyNode {
  id: string
  name: string
  /** `device` / `nic` / `port` */
  type: string
  parent: string | null
  enabled: boolean
}

export interface AssetTopologyEdge {
  from: string
  to: string
  linkId: number
  linkType: string | null
  speedGbps: number | null
}

export interface AssetTopologyStats {
  deviceCount: number
  nicCount: number
  portCount: number
  linkCount: number
}

export interface AssetTopology {
  nodes: AssetTopologyNode[]
  edges: AssetTopologyEdge[]
  stats: AssetTopologyStats
}

export interface AssetInfo {
  generatedAt: string
  meta: AssetInfoMeta
  connections: { items: AssetConnection[]; count: number }
  topology: AssetTopology
  /** 设备与链路都为空时为 true（空态引导，不是错误）。 */
  empty: boolean
}

/** 支持的派生范围；后端只认这两个值，拼错会 400。 */
export type AssetInfoScope = 'connections' | 'topology'

function base(projectId: number): string {
  return `/projects/${projectId}/asset-info`
}

/**
 * 读取连接与拓扑。
 *
 * @param scope 只需其中一块时传入，减少返回体；缺省两块全取
 * @param includeDisabled 是否包含已停用资产；接口始终全量返回并带 enabled 标记，
 *   该参数只作为显式意愿声明（PRD FR-v4.3-04 R5）
 */
export function getAssetInfo(
  projectId: number,
  scope?: AssetInfoScope[],
  includeDisabled = true,
): Promise<AssetInfo> {
  const params: Record<string, unknown> = { includeDisabled }
  if (scope && scope.length > 0) params.scope = scope.join(',')
  return request<AssetInfo>(`${base(projectId)}${toQuery(params)}`)
}

/**
 * 手工刷新：跳过复用窗口强制重算（PRD FR-v4.3-06）。
 *
 * 后端会写一条 `asset-info.refresh` 审计；无 ASSET/UPDATE 的角色会收到 403。
 */
export function refreshAssetInfo(
  projectId: number,
  scope: AssetInfoScope[] = ['connections', 'topology'],
  force = true,
): Promise<AssetInfo> {
  return request<AssetInfo>(`${base(projectId)}/refresh`, {
    method: 'POST',
    body: JSON.stringify({ scope, force }),
  })
}
