/**
 * 保存视图（v3.1.0「顺手」）。
 *
 * <p>用例页的筛选条件组合每天都要重选一遍。后端只把 `payload` 当作不透明 JSON
 * 存取（不解释字段），所以这里给出一份**前端自用**的载荷形状：升级字段时
 * 旧视图读回来由 `normalizeViewPayload` 补默认值，不会因为少一个键而崩。
 */

import { request, toQuery } from './http'

export type ViewScope = 'case' | 'run' | 'report'

/** 视图排序：目前只支持单列升降序，字段名用表格列的 key。 */
export interface ViewSort {
  columnKey: string
  order: 'asc' | 'desc'
}

export interface CaseViewPayload {
  keyword: string
  domain: string
  priority: string
  reviewStatus: string
  safetyLevel: string
  size: number
  /** 可见列的 key，顺序即展示顺序；未列出的列视为隐藏 */
  columns: string[]
  sort: ViewSort | null
}

export interface SavedView {
  id: number
  projectId: number
  userId: number | null
  name: string
  scope: string
  /** 后端按不透明 JSON 存取，前端读取时必须经过 normalize */
  payload: unknown
  isDefault: boolean
  createdAt: string
  updatedAt: string
}

export interface SavedViewInput {
  name: string
  scope: string
  payload: CaseViewPayload
  isDefault: boolean
}

export const viewsApi = {
  list(projectId: number, scope: string) {
    return request<SavedView[]>(
      `/projects/${projectId}/views${toQuery({ scope })}`,
    )
  },
  create(projectId: number, body: SavedViewInput) {
    return request<SavedView>(`/projects/${projectId}/views`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  update(projectId: number, id: number, body: SavedViewInput) {
    return request<SavedView>(`/projects/${projectId}/views/${id}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },
  remove(projectId: number, id: number) {
    return request<void>(`/projects/${projectId}/views/${id}`, { method: 'DELETE' })
  },
}
