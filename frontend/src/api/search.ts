/**
 * 全局实体搜索（v3.1.0「顺手」）。
 *
 * <p>命令面板过去只能搜页面，找不到「那条用例 / 那个运行」。后端把五类实体
 * 统一成一张结果表，前端只按 `type` 分组展示，不关心各域自己的分页语义。
 *
 * 契约：`GET /api/search?q=&projectId=&scope=current|all&types=...&limit=5`
 * 返回 `{query, scope, items, counts, truncated}`。
 */

import { request, toQuery } from './http'

export type EntityType = 'case' | 'run' | 'report' | 'plan' | 'asset'

export type EntityScope = 'current' | 'all'

/** 一条搜索结果；`path` 由后端给出，前端不拼路由，避免两处规则漂移。 */
export interface SearchHit {
  type: EntityType
  id: number | string
  title: string
  subtitle: string | null
  projectId: number | null
  projectName: string | null
  path: string
  updatedAt: string | null
  /** 后端已本地化好的状态词（如「已通过」），前端原样展示即可 */
  badge: string | null
}

export interface SearchResponse {
  query: string
  scope: EntityScope
  items: SearchHit[]
  counts: Partial<Record<EntityType, number>>
  truncated: boolean
}

export interface SearchParams {
  q: string
  projectId?: number | null
  scope: EntityScope
  types?: EntityType[]
  limit?: number
}

export function searchEntities(params: SearchParams): Promise<SearchResponse> {
  return request<SearchResponse>(
    `/search${toQuery({
      q: params.q,
      projectId: params.projectId || undefined,
      scope: params.scope,
      types: params.types?.join(','),
      limit: params.limit,
    })}`,
  )
}
