/**
 * 关系聚合读接口（v5.9.0 / FR-v5.9-05）。
 *
 * 用户组页、用户页、项目关联页的**所有计数**都读这里——三页各写一条 count，
 * 迟早出现「组页说 3 个项目、项目页说 5 个人」这种同一事实三个数字。
 *
 * 后端保证：同一 `dimension + id` 两次响应逐字节相同（排序稳定），可直接 diff。
 */
import { request } from './http'

export type RelationDimension = 'user' | 'group' | 'project'

export interface RelationGroup {
  id: number
  name: string
  roleCode?: string | null
}

export interface RelationUser {
  id: number
  username: string
  displayName: string
  /** OWNER / MEMBER / GROUP —— 「为什么这个人能访问这个项目」 */
  source?: string
  roleCodes?: string[]
}

export interface RelationProject {
  id: number
  code: string
  name: string
  source?: string
  roleCode?: string | null
  roleCodes?: string[]
}

export interface IamRelations {
  dimension: RelationDimension
  id: number
  groups: RelationGroup[]
  users: RelationUser[]
  projects: RelationProject[]
  counts: { groups: number; users: number; projects: number }
}

export const iamRelationApi = {
  of(dimension: RelationDimension, id: number) {
    return request<IamRelations>(`/iam/relations?dimension=${dimension}&id=${id}`)
  },
}
