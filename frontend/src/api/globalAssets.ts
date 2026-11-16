/** 公共资产库接口（v4.7.0）：列表、引用、导入、导出、删除保护；v4.9.3 补校验和与采集。 */

import { request, requestBlob, requestMultipart, toQuery } from './http'

export interface GlobalAssetRow {
  id: number
  assetType: string
  name: string
  detail: string
  managementIp: string
  status: string
  source: string
  externalId: string
  environment: string
  referenceCount: number
  portCount: number
  collectCount: number
  lastCollectedAt: string | null
  collectStatus: string | null
  /** v4.12.0：两个地址面各自的绑定凭据；都为 null 时「采集」按钮不渲染（FR-v4.12-10）。 */
  managementCredentialId: number | null
  bmcCredentialId: number | null
  /** 凭据行的采集通道；设备行恒为 null（走哪条通道取决于采集时选的凭据）。 */
  collectChannel: string | null
}

export interface AssetPreview {
  batchId: number
  sha256: string
  devices: number
  ports: number
  credentials: number
  total: number
  samples: string[]
  warnings: string[]
  sheetsScanned: number
  replayed: boolean
  /** v5.18.0 / FR-v5.18-01：五步向导的「冲突预览」三数（与提交结果同口径）。 */
  toCreate?: number
  toUpdate?: number
  conflicts?: number
  /** 表头原始文本（映射页左侧）与前 3 行样例（映射页右侧）。 */
  headers?: string[]
  sampleRows?: string[][]
}

export interface AssetImportResult {
  batchId: number
  sha256: string
  devices: number
  ports: number
  credentials: number
  deprecations: number
  statusSupplemented: number
  warnings: string[]
  replayed: boolean
}

export interface AssetCollectResult {
  deviceId: number
  host: string
  status: string
  collectedAt: string | null
  nicCount: number
  rocePorts: number
  roceMaxGbps: number | null
}

/* ------------------------------------------------ v4.12.0 列举与手工录入 */

/**
 * 一条「凭据 → 设备」的绑定（FR-v4.12-11）。
 *
 * `plane` 是**地址面**（mgmt / bmc）而非采购类型：同一台机器可能两条地址各绑各的凭据，
 * 所以接口按 (设备, 地址面) 展开成行。`channel` 由凭据类型推出，页面靠它在点击前
 * 决定按钮文案与显隐（FR-v4.12-10）——不要在页面里再写一遍类型映射。
 */
export interface CredentialDevice {
  deviceId: number
  name: string
  plane: 'mgmt' | 'bmc' | string
  address: string | null
  channel: 'ssh' | 'network' | 'bmc' | string
  lastStatus: string | null
  lastCollectedAt: string | null
}

export interface BatchCollectItem {
  deviceId: number
  name: string
  plane: string
  host: string | null
  status: string
  reason: string | null
}

/** 恒有 `total === ok + failed`；没有地址的也计入 failed，摘要才能对上总数。 */
export interface BatchCollectResult {
  total: number
  ok: number
  failed: number
  items: BatchCollectItem[]
}

/** 手工录入后的凭据视图：后端显式排除 secret / 密文，这里也不该出现这两个字段。 */
export interface CreatedCredential {
  id: number
  name: string
  credType: string
  username: string | null
  connectOptions: string | null
  configured: boolean
}

export interface CreateCredentialPayload {
  name: string
  credType: string
  username?: string
  secret: string
  connectOptions?: string
}

export interface CreatedDevice {
  id: number
  name: string
  deviceType: string | null
  managementIp: string | null
  managementIpAlt: string | null
  bmcAddress: string | null
  bmcAddressAlt: string | null
  managementCredentialId: number | null
  bmcCredentialId: number | null
  assetSource: string
}

export interface CreateDevicePayload {
  name: string
  deviceType?: string
  managementIp?: string
  managementIpAlt?: string
  bmcAddress?: string
  bmcAddressAlt?: string
  managementCredentialId?: number | null
  bmcCredentialId?: number | null
  model?: string
  vendor?: string
  serialNo?: string
  location?: string
  domainTag?: string
}

/** W62-14：批量删逐条结果；conflicted 只统计「被项目引用」的条数。 */
export interface GlobalAssetBatchDeleteResult {
  deleted: number
  conflicted: number
  items: { id: number; deleted: boolean; reason: string; projects: string[] }[]
}

export const globalAssetApi = {
  list(params: { type?: string; keyword?: string; availability?: string }) {
    return request<GlobalAssetRow[]>(`/global/assets${toQuery(params)}`)
  },
  /**
   * 改显示名 / 管理 IP（v5.9.0 W59-20 → v5.12.0 W62-12）。
   *
   * 不传 managementIp 时只改 name，5.9 老调用方行为完全不变；传了才写事实列，
   * 且只有 DEVICE 类型允许。保存后由页面重拉列表，以服务端为准。
   */
  rename(type: string, id: number, name: string, managementIp?: string) {
    const body: Record<string, string> = { name }
    if (managementIp !== undefined) body.managementIp = managementIp
    return request<{ name: string; managementIp?: string }>(`/global/assets/${type}/${id}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },
  /** W62-14：逐条返回成功 / 409（含引用项目名），不因一条被引用整批失败。 */
  batchDelete(type: string, ids: number[]) {
    return request<GlobalAssetBatchDeleteResult>('/global/assets/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ type, ids }),
    })
  },
  references(type: string, id: number) {
    return request<Record<string, number>>(`/global/assets/${type}/${id}/references`)
  },
  /**
   * 预检。
   *
   * @param mapping v5.18.0 / FR-v5.18-01：字段映射（{源列名: 目标字段}）；不传即老行为，
   *   后端按表头自动识别。传了非法 JSON 时后端 400，不静默退回。
   */
  preview(file: File, mapping?: Record<string, string>) {
    const form = new FormData()
    form.append('file', file)
    const query = mapping && Object.keys(mapping).length > 0
      ? toQuery({ mapping: JSON.stringify(mapping) })
      : ''
    return requestMultipart<AssetPreview>(
      `/global/assets/import/preview${query}`,
      form,
      120_000,
    )
  },
  commit(file: File) {
    const form = new FormData()
    form.append('file', file)
    return requestMultipart<AssetImportResult>('/global/assets/import/commit', form, 120_000)
  },
  batches(page = 1, size = 20) {
    return request<{ items: Record<string, unknown>[]; total: number; page: number; size: number }>(
      `/global/assets/import/batches${toQuery({ page, size })}`,
    )
  },
  collect(id: number, params: { credentialId?: number; username?: string; confirm: boolean }) {
    return request<AssetCollectResult>(
      `/global/assets/${id}/collect${toQuery(params)}`,
      { method: 'POST' },
      180_000,
    )
  },
  export() {
    return requestBlob('/api/global/assets/export', 60_000)
  },
  /** 凭据列举关联设备（FR-v4.12-11）。 */
  credentialDevices(credentialId: number) {
    return request<CredentialDevice[]>(`/global/assets/credentials/${credentialId}/devices`)
  },
  /**
   * 按凭据批量采集（FR-v4.12-12）。
   *
   * 超时给 10 分钟：12 台逐台 ssh + Redfish，单台最坏 30 秒，8 台串行就能到 4 分钟；
   * 原来的 180 秒会让「已经采到一半」被前端判成失败，白采。
   */
  collectByCredential(credentialId: number, confirm: boolean) {
    return request<BatchCollectResult>(
      `/global/assets/credentials/${credentialId}/collect${toQuery({ confirm })}`,
      { method: 'POST' },
      600_000,
    )
  },
  createCredential(payload: CreateCredentialPayload) {
    return request<CreatedCredential>('/global/assets/credentials', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  createDevice(payload: CreateDevicePayload) {
    return request<CreatedDevice>('/global/assets/devices', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  reference(type: string, id: number, projectId: number) {
    return request<{ referenced: boolean }>(
      `/global/assets/${type}/${id}/reference?projectId=${projectId}`,
      { method: 'POST' },
    )
  },
  unreference(type: string, id: number, projectId: number) {
    return request<{ unreferenced: boolean }>(
      `/global/assets/${type}/${id}/unreference?projectId=${projectId}`,
      { method: 'POST' },
    )
  },
  remove(type: string, id: number) {
    return request<{ deleted: boolean }>(`/global/assets/${type}/${id}`, { method: 'DELETE' })
  },
}
