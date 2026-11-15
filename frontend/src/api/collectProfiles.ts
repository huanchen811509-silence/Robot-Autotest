/**
 * 采集画像与凭证关联（v5.13.0 / FR-v5.13-01、05、07）。
 *
 * URL 不带 /api 前缀，由 http.ts 统一拼 BASE_URL。
 * 历史复用既有 GET /asset-host-inventory 摘要端点，不为展示再开一份采集实现。
 */
import { request, toQuery } from './http'

export type ProfileState = 'READY' | 'PARTIAL' | 'BLOCKED'

export interface CollectProfile {
  deviceId: number
  projectId: number | null
  ownerScope: string | null
  name: string | null
  managementIp: string | null
  managementCredentialId: number | null
  bmcCredentialId: number | null
  boundCredentialName: string | null
  boundCredentialMasked: string | null
  lastVerifiedCredentialId: number | null
  lastVerifiedAt: string | null
  verifyResult: string | null
  verifyFailureKind: string | null
  lastCollectAt: string | null
  lastCollectStatus: string | null
  collectCount: number
  usable: boolean
  profileState: ProfileState
  blockedReason: string | null
}

export interface BindingResult {
  deviceId: number
  managementCredentialId: number | null
  bmcCredentialId: number | null
}

export interface BindingPayload {
  managementCredentialId?: number | null
  bmcCredentialId?: number | null
}

/** 一次采集的摘要（与后端 HostInventoryRecord 的展示字段对齐，不取 specJson 原文）。 */
export interface CollectHistoryRow {
  id: number
  deviceId: number | null
  status: string | null
  collectedAt: string | null
  nicCount: number
  rocePorts: number
  roceMaxGbps: number | null
}

export const collectProfileApi = {
  list(projectId?: number, state?: string) {
    return request<CollectProfile[]>(
      `/assets/collect-profiles${toQuery({ projectId, state })}`,
    )
  },
  bindGlobal(deviceId: number, payload: BindingPayload) {
    return request<BindingResult>(`/global/assets/${deviceId}/credentials`, {
      method: 'PUT', body: JSON.stringify(payload),
    })
  },
  bindProject(projectId: number, deviceId: number, payload: BindingPayload) {
    return request<BindingResult>(
      `/projects/${projectId}/assets/${deviceId}/credentials`,
      { method: 'PUT', body: JSON.stringify(payload) },
    )
  },
  clearProject(projectId: number, deviceId: number) {
    return request<BindingResult>(
      `/projects/${projectId}/assets/${deviceId}/credentials`,
      { method: 'DELETE' },
    )
  },
  /** v5.13.0 W63-10：只读摘要端点，不回 specJson 原文。 */
  history(deviceId: number, limit = 20) {
    return request<CollectHistoryRow[]>(
      `/assets/collect-history${toQuery({ deviceId, limit })}`,
    )
  },
}
