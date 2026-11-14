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

/** 批量加成员的返回：`added` 真加进去的条数、`skipped` 已存在跳过数。 */
export interface AddItemsResult {
  added: number
  skipped: number
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
  /**
   * 列表。后端返回的是**分页信封** `{ items, page, size, total }`，不是裸数组——
   * v5.7.0 首版这里declare 成 `PlatformCaseSet[]`，调用方 `rows.map(...)` 直接抛
   * `rows.map is not a function`，`/g/case-sets` 列表与向导「内容」步的用例集分区
   * 因此**永远是空的**（2026-11-14 真实界面取证发现，见 `06-ACCEPTANCE-AUDIT.md` 补记）。
   * 这里统一拆信封，调用方只拿数组；需要翻页元数据的场景另开 `page()`。
   */
  async list(params: PlatformCaseSetQuery = {}): Promise<PlatformCaseSet[]> {
    const page = await request<{ items?: PlatformCaseSet[] }>(
      `/platform/case-sets${toQuery(params)}`,
    )
    return Array.isArray(page?.items) ? page.items : []
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
  /**
   * 批量加成员。后端入参是 `List<Long> caseIds`——**单条也要包成数组**，
   * 直接发 `{caseId}` 会被 Jackson 忽略成 `caseIds=null`，静默返回 `added=0`
   * （v5.7.0 首版缺陷，见 06-ACCEPTANCE-AUDIT P0-3）。
   */
  addItems(id: number, caseIds: number[]) {
    return request<AddItemsResult>(`/platform/case-sets/${id}/items`, {
      method: 'POST',
      body: JSON.stringify({ caseIds }),
    })
  },
  /** 单条添加：转调批量端点，全站只保留一套契约。 */
  addItem(id: number, caseId: number) {
    return request<AddItemsResult>(`/platform/case-sets/${id}/items`, {
      method: 'POST',
      body: JSON.stringify({ caseIds: [caseId] }),
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
