/**
 * 平台（全局）用例集接口（W57-09）。
 *
 * 与项目内 `caseSetApi`（`/projects/{id}/case-sets`）是两套数据面：平台集不带 projectId，
 * 端点统一在 `/api/platform/case-sets` 下。`request` 的 BASE_URL 已是 `/api`，
 * 因此这里一律写不带 `/api` 前缀的路径。
 */
import { request, toQuery } from './http'

export interface PlatformCaseSet {
  id: number
  setCode: string
  name: string
  description: string | null
  itemCount: number
}

export interface PlatformCaseSetItem {
  id: number
  caseCode: string
  title: string
  categoryL1: string | null
}

export interface SeedSamplesResult {
  created: number
  rebuilt: boolean
  l1Count: number
}

export interface PlatformCaseSetQuery {
  keyword?: string
  page?: number
  size?: number
  [key: string]: unknown
}

export interface PlatformCaseSetCommand {
  setCode?: string
  name: string
  description?: string
}

export const platformCaseSetApi = {
  list(params: PlatformCaseSetQuery = {}) {
    return request<PlatformCaseSet[]>(`/platform/case-sets${toQuery(params)}`)
  },
  create(body: PlatformCaseSetCommand) {
    return request<PlatformCaseSet>('/platform/case-sets', {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  update(id: number, body: PlatformCaseSetCommand) {
    return request<PlatformCaseSet>(`/platform/case-sets/${id}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },
  remove(id: number) {
    return request<void>(`/platform/case-sets/${id}`, { method: 'DELETE' })
  },
  /** 成员列表（页面「成员管理」用；端点与既有 platformCaseSetApi.items 同源）。 */
  items(id: number) {
    return request<PlatformCaseSetItem[]>(`/platform/case-sets/${id}/items`)
  },
  addItem(id: number, caseId: number) {
    return request<void>(`/platform/case-sets/${id}/items`, {
      method: 'POST',
      body: JSON.stringify({ caseId }),
    })
  },
  removeItem(id: number, caseId: number) {
    return request<void>(`/platform/case-sets/${id}/items/${caseId}`, { method: 'DELETE' })
  },
  /** 重排：caseIds 必须是当前集合的完整成员序列。 */
  reorder(id: number, caseIds: number[]) {
    return request<void>(`/platform/case-sets/${id}/order`, {
      method: 'PUT',
      body: JSON.stringify({ caseIds }),
    })
  },
  /** 按一级分类重建样例集；rebuild=true 时先清掉旧的样例集再生成。 */
  seedSamples(rebuild = true) {
    return request<SeedSamplesResult>(
      `/platform/case-sets/seed-samples${toQuery({ rebuild: rebuild ? 'true' : undefined })}`,
      { method: 'POST' },
    )
  },
}

/** 全局用例归组数据（W57-10/11）：`GET /api/global/cases/grouped`。 */
export interface GroupedL2 {
  l2Id: number
  l2Name: string
  count: number
}
export interface GroupedCase {
  l1Id: number
  l1Name: string
  l2s: GroupedL2[]
  total: number
  uncategorized: number
}

export const globalGroupedApi = {
  grouped() {
    return request<GroupedCase[]>('/global/cases/grouped')
  },
}
