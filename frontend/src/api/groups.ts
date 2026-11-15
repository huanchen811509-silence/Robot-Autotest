/** W5 / IAMX 组授权与场景 API。 */
import { request } from './http'

export interface EffectiveRoleView {
  role: string
}

export interface ScenarioItem {
  id: number
  name: string
  case_set_ids: string
  parent_scenario_id: number | null
}

/** 角色 -> 可读标签的语义档位，与后端 EffectiveRole.rank 对齐。 */
export function roleRank(role: string): number {
  switch (role.toUpperCase()) {
    case 'ADMIN': return 4
    case 'PM': return 3
    case 'ENGINEER': return 2
    case 'VIEWER': return 1
    default: return 0
  }
}

export interface GroupRow {
  id: number
  name: string
  description: string | null
  memberCount?: number
  projectCount?: number
}

export interface GroupInput {
  name: string
  description: string
}

export const groupsApi = {
  groups: () => request<GroupRow[]>('/groups'),
  detail: (id: number) => request<{
    group: GroupRow | null
    members: { id: number; username: string; displayName: string }[]
    projects: { id: number; code: string; name: string; roleCode: string }[]
  }>(`/groups/${id}`),
  rename: (id: number, body: GroupInput) =>
    request<unknown>(`/groups/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  remove: (id: number) => request<void>(`/groups/${id}`, { method: 'DELETE' }),
  addMember: (groupId: number, userId: number) =>
    request<{ added: number; skipped: number; members: number[] }>('/groups/member', {
      method: 'POST',
      body: JSON.stringify({ groupId, userId }),
    }),
  removeMember: (groupId: number, userId: number) =>
    request<unknown>(`/groups/${groupId}/members/${userId}`, { method: 'DELETE' }),
  revoke: (groupId: number, projectId: number) =>
    request<{ removed: boolean }>(`/groups/${groupId}/grants/${projectId}`, { method: 'DELETE' }),
  create: (name: string, description: string) =>
    request<number>('/groups', { method: 'POST', body: JSON.stringify({ name, description }) }),
  grant: (projectId: number, groupId: number, roleCode: string, userId: number) =>
    request<Record<string, unknown>>('/groups/grant', {
      method: 'POST',
      body: JSON.stringify({ projectId, groupId, roleCode, userId }),
    }),
  effective: (projectId: number, userId: number) =>
    request<EffectiveRoleView>(`/groups/effective?projectId=${projectId}&userId=${userId}`),
  scenarios: (projectId: number) =>
    request<{ items: ScenarioItem[]; oneLevelViolations: string[] }>(
      `/scenarios?projectId=${projectId}`),
  overview: () => request<Record<string, number>>('/admin/overview'),
}
