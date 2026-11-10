/** 公共资产库接口（v4.7.0）：列表、引用、导入、导出、删除保护。 */

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
}

export interface AssetPreview {
  devices: number
  ports: number
  credentials: number
  total: number
  samples: string[]
  warnings: string[]
  sheetsScanned: number
}

export interface AssetImportResult {
  devices: number
  ports: number
  credentials: number
  deprecations: number
  warnings: string[]
}

export const globalAssetApi = {
  list(params: { type?: string; keyword?: string; availability?: string }) {
    return request<GlobalAssetRow[]>(`/global/assets${toQuery(params)}`)
  },
  references(type: string, id: number) {
    return request<Record<string, number>>(`/global/assets/${type}/${id}/references`)
  },
  preview(file: File) {
    const form = new FormData()
    form.append('file', file)
    return requestMultipart<AssetPreview>('/global/assets/import/preview', form, 120_000)
  },
  commit(file: File) {
    const form = new FormData()
    form.append('file', file)
    return requestMultipart<AssetImportResult>('/global/assets/import/commit', form, 120_000)
  },
  export() {
    return requestBlob('/api/global/assets/export', 60_000)
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
