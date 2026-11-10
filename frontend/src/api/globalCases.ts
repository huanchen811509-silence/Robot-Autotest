/** 公共用例库接口（v4.6.0 / v4.6.1）：列表、分类、幂等导入、反向导出。 */

import { request, requestBlob, requestMultipart, toQuery } from './http'
import type { PageResult } from './iam'

export interface GlobalCaseRow {
  id: number
  caseCode: string
  externalId: string | null
  title: string
  categoryL1: string | null
  categoryL2: string | null
  priority: string | null
  safetyLevel: string | null
  lifecycleStatus: string
  versionNo: number
  sourceSheet: string | null
  contentSha: string | null
}

export interface CaseCategoryRow {
  id: number
  level: number
  parentId: number | null
  name: string
  sortNo: number
  status: string
  caseCount: number
}

export type ImportAction = 'NEW' | 'UPDATE' | 'UNCHANGED' | 'DEPRECATE'

export interface ImportPreviewRow {
  rowNo: number
  caseCode: string
  title: string
  categoryL1: string | null
  categoryL2: string | null
  priority: string | null
  action: ImportAction
  message: string
  contentSha: string
  sheet: string
  sourceRow: number
}

export interface ImportRowError {
  rowNum: number
  field: string
  message: string
}

export interface GlobalImportPreview {
  batchId: number
  sha256: string
  total: number
  newCount: number
  updateCount: number
  unchangedCount: number
  deprecateCount: number
  errorCount: number
  replayed: boolean
  rows: ImportPreviewRow[]
  errors: ImportRowError[]
}

export interface GlobalImportCommit {
  batchId: number
  inserted: number
  updated: number
  unchanged: number
  deprecated: number
  replayed: boolean
}

export interface ImportBatchRow {
  id: number
  source_name: string
  checksum: string
  status: string
  total_rows: number
  new_count: number
  updated_count: number
  unchanged_count: number
  deprecated_count: number
  created_at: string
  committed_at: string | null
}

export const globalCaseApi = {
  page(params: {
    keyword?: string
    categoryL1?: string
    categoryL2?: string
    lifecycleStatus?: string
    page?: number
    size?: number
  }) {
    return request<PageResult<GlobalCaseRow>>(`/global/cases${toQuery(params)}`)
  },
  categories() {
    return request<CaseCategoryRow[]>('/global/cases/categories')
  },
  createCategory(body: { level: number; parentId?: number; name: string; sortNo?: number }) {
    return request<CaseCategoryRow>('/global/cases/categories', {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  removeCategory(id: number) {
    return request<{ deleted: boolean }>(`/global/cases/categories/${id}`, { method: 'DELETE' })
  },
  previewImport(file: File) {
    const form = new FormData()
    form.append('file', file)
    return requestMultipart<GlobalImportPreview>('/global/cases/import/preview', form, 120_000)
  },
  commitImport(batchId: number) {
    return request<GlobalImportCommit>(`/global/cases/import/${batchId}/commit`, { method: 'POST' })
  },
  batches(params: { page?: number; size?: number }) {
    return request<PageResult<ImportBatchRow>>(`/global/cases/import/batches${toQuery(params)}`)
  },
  errorReport(batchId: number) {
    return requestBlob(`/api/global/cases/import/${batchId}/errors`)
  },
  export(params: { categoryL1?: string; lifecycleStatus?: string }) {
    return requestBlob(`/api/global/cases/export${toQuery(params)}`, 60_000)
  },
  deprecate(id: number) {
    return request<{ deprecated: boolean }>(`/global/cases/${id}/deprecate`, { method: 'POST' })
  },
  restore(id: number) {
    return request<{ restored: boolean }>(`/global/cases/${id}/restore`, { method: 'POST' })
  },
}
