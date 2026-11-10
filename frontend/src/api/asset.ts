/** 资产接口（AST-01 ~ AST-04）：设备 / 端口 / 链路 / 凭据 / 技术域。 */

import { request, toQuery } from './http'
import type { PageResult } from './iam'

export interface Device {
  id: number
  projectId: number
  name: string
  deviceType: string | null
  model: string | null
  vendor: string | null
  serialNo: string | null
  managementIp: string | null
  location: string | null
  rackNo: string | null
  domainTag: string | null
  enabled: boolean
  ownerId: number | null
  lastCheckedAt: string | null
}

export interface DevicePort {
  id: number
  deviceId: number
  portName: string
  portType: string | null
  protocol: string | null
  macAddress: string | null
  speedGbps: number | null
  mtu: number | null
  ipAddress: string | null
  portStatus: string | null
  /** v4.3.0 / FR-v4.3-03：端口所属网卡（可空，空 = 未归属）。 */
  nicName: string | null
  nicIndex: number | null
}

export interface Link {
  id: number
  projectId: number
  name: string
  linkType: string | null
  protocol: string | null
  speedGbps: number | null
  mtu: number | null
  aDeviceId: number | null
  aDeviceName: string | null
  aPortId: number | null
  aPortName: string | null
  bDeviceId: number | null
  bDeviceName: string | null
  bPortId: number | null
  bPortName: string | null
  description: string | null
  enabled: boolean
}

export interface Credential {
  id: number
  projectId: number
  name: string
  credType: string | null
  username: string | null
  configured: boolean
  keyVersion: number
  lastRotatedAt: string | null
}

export interface DeviceCommand {
  name: string
  deviceType?: string
  model?: string
  vendor?: string
  serialNo?: string
  managementIp?: string
  location?: string
  rackNo?: string
  domainTag?: string
  enabled: boolean
  ownerId?: number | null
}

export interface PortCommand {
  portName: string
  portType?: string
  protocol?: string
  macAddress?: string
  speedGbps?: number | null
  mtu?: number | null
  ipAddress?: string
  portStatus?: string
  /** v4.3.0 / FR-v4.3-03：网卡名与序号，空值表示「未归属」。 */
  nicName?: string | null
  nicIndex?: number | null
}

export interface LinkCommand {
  name: string
  linkType?: string
  protocol?: string
  speedGbps?: number | null
  mtu?: number | null
  aDeviceId?: number | null
  aPortId?: number | null
  bDeviceId?: number | null
  bPortId?: number | null
  description?: string
  enabled: boolean
}

const base = (projectId: number): string => `/projects/${projectId}`

export const deviceApi = {
  page(projectId: number, params: {
    keyword?: string
    status?: number
    domain?: string
    page?: number
    size?: number
  }) {
    return request<PageResult<Device>>(`${base(projectId)}/devices${toQuery(params)}`)
  },
  domains(projectId: number) {
    return request<string[]>(`${base(projectId)}/device-domains`)
  },
  create(projectId: number, body: DeviceCommand) {
    return request<Device>(`${base(projectId)}/devices`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  remove(projectId: number, id: number) {
    return request<void>(`${base(projectId)}/devices/${id}`, { method: 'DELETE' })
  },
  ports(projectId: number, id: number) {
    return request<DevicePort[]>(`${base(projectId)}/devices/${id}/ports`)
  },
  addPort(projectId: number, id: number, body: PortCommand) {
    return request<DevicePort>(`${base(projectId)}/devices/${id}/ports`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
}

export const linkApi = {
  page(projectId: number, params: { keyword?: string; page?: number; size?: number }) {
    return request<PageResult<Link>>(`${base(projectId)}/links${toQuery(params)}`)
  },
  create(projectId: number, body: LinkCommand) {
    return request<Link>(`${base(projectId)}/links`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  remove(projectId: number, id: number) {
    return request<void>(`${base(projectId)}/links/${id}`, { method: 'DELETE' })
  },
}

export const credentialApi = {
  list(projectId: number) {
    return request<Credential[]>(`${base(projectId)}/credentials`)
  },
  create(projectId: number, body: {
    name: string
    credType?: string
    username?: string
    secret: string
  }) {
    return request<Credential>(`${base(projectId)}/credentials`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  remove(projectId: number, id: number) {
    return request<void>(`${base(projectId)}/credentials/${id}`, { method: 'DELETE' })
  },
}

export const labelApi = {
  values(projectId: number, key: string) {
    return request<string[]>(`${base(projectId)}/labels${toQuery({ key })}`)
  },
}

/** 平台共享资产的类型段，与后端路径大小写一致（v4.3.0 / FR-v4.3-08）。 */
export type PlatformAssetType = 'DEVICE' | 'LINK' | 'CREDENTIAL'

/**
 * 引用 / 解除引用平台共享资产（P1）。
 *
 * 引用只写一条 `project_asset` 关系，不改资产本体；解除引用同样只删关系。
 * 两者都由后端校验：非平台共享资产、跨项目私有资产一律 403，页面按错误行提示。
 */
export const shareApi = {
  reference(projectId: number, assetType: PlatformAssetType, assetId: number) {
    return request<void>(`${base(projectId)}/assets/${assetType}/${assetId}`, { method: 'POST' })
  },
  unreference(projectId: number, assetType: PlatformAssetType, assetId: number) {
    return request<void>(`${base(projectId)}/assets/${assetType}/${assetId}`, { method: 'DELETE' })
  },
}
