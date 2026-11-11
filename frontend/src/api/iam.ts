/** IAM 接口（IAM-01 / IAM-02 / IAM-03）：用户、角色、项目、登录态。 */

import { request, toQuery } from './http'

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface UserSummary {
  id: number
  username: string
  displayName: string
  email: string | null
  orgId: number | null
  enabled: boolean
  passwordExpiresAt: string | null
  lastLoginAt: string | null
  roles: string[]
}

export interface Role {
  id: number
  code: string
  name: string
  description: string | null
  builtIn: boolean
  permissionsJson: string | null
}

export interface Project {
  id: number
  code: string
  name: string
  description: string | null
  enabled: boolean
  ownerId: number | null
  visibility: string | null
  /** 测试内核：robot（Robot Framework，默认）| pytest。缺省按 robot 显示。 */
  testKernel?: string | null
  /** standard（普通项目）| platform（公共库载体，v4.5.0）。 */
  kind?: string | null
  /** 编码唯一一次变更时间；非空表示已用掉改码机会（v4.4.0）。 */
  codeChangedAt?: string | null
}

/** 项目成员；roleCode 是**项目内主角色**，roleCodes 是全部项目角色（v4.4.0）。 */
export interface ProjectMember {
  id: number | null
  projectId: number
  userId: number
  username: string
  roleCode: string
  roleCodes?: string[]
  displayName: string | null
  enabled: boolean
}

export interface LoginResult {
  token: string
  expiresAt: string
  user: UserSummary
  passwordExpired: boolean
}

export interface CreateUserRequest {
  username: string
  displayName: string
  email?: string
  password: string
  roleIds: number[]
}

export interface UpdateUserRequest {
  displayName: string
  email?: string
  enabled: boolean
  roleIds: number[]
}

export const authApi = {
  login(username: string, password: string): Promise<LoginResult> {
    return request<LoginResult>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
  },
  logout(): Promise<void> {
    return request<void>('/auth/logout', { method: 'POST' })
  },
  me(): Promise<UserSummary> {
    return request<UserSummary>('/auth/me')
  },
  /** 会话续期（FR-v0.2-04）：过期前 5 分钟调用，后端把有效期再推一个 TTL。 */
  renew(): Promise<{ expiresAt: string }> {
    return request<{ expiresAt: string }>('/auth/renew', { method: 'POST' })
  },
}

export const userApi = {
  page(params: { keyword?: string; status?: number; page?: number; size?: number }) {
    return request<PageResult<UserSummary>>(`/users${toQuery(params)}`)
  },
  create(body: CreateUserRequest) {
    return request<UserSummary>('/users', { method: 'POST', body: JSON.stringify(body) })
  },
  update(id: number, body: UpdateUserRequest) {
    return request<UserSummary>(`/users/${id}`, { method: 'PUT', body: JSON.stringify(body) })
  },
  remove(id: number) {
    return request<void>(`/users/${id}`, { method: 'DELETE' })
  },
  resetPassword(id: number, password: string) {
    return request<void>(`/users/${id}/reset-password`, {
      method: 'POST',
      body: JSON.stringify({ password }),
    })
  },
}

export const roleApi = {
  list() {
    return request<Role[]>('/roles')
  },
}

export interface UpdateProjectRequest {
  name: string
  description?: string | null
  /** 停用（归档）= false；可随时重新启用，数据不删。 */
  enabled: boolean
  ownerId?: number | null
  visibility?: string | null
  testKernel?: string | null
}

export const projectApi = {
  list() {
    return request<Project[]>('/projects')
  },
  /** 服务端建议编码（FR-v4.4-07）：前缀-引擎-年月-序号。 */
  nextCode(prefix: string, kernel: string) {
    return request<{ code: string }>(`/projects/next-code${toQuery({ prefix, kernel })}`)
  },
  /** 服务端能力码（只做显隐；后端仍逐请求判定）。 */
  capabilities() {
    return request<{ globalEnter: boolean }>('/projects/capabilities')
  },
  create(body: { code: string; name: string; description?: string; visibility?: string;
                  testKernel?: string }) {
    return request<Project>('/projects', { method: 'POST', body: JSON.stringify(body) })
  },
  update(id: number, body: UpdateProjectRequest) {
    return request<Project>(`/projects/${id}`, { method: 'PUT', body: JSON.stringify(body) })
  },
  /** 编码唯一一次变更（FR-v4.4-08）：仅 ADMIN、必须 reason。 */
  changeCode(id: number, code: string, reason: string) {
    return request<Project>(`/projects/${id}/code`, {
      method: 'PUT',
      body: JSON.stringify({ code, reason }),
    })
  },
  remove(id: number) {
    return request<void>(`/projects/${id}`, { method: 'DELETE' })
  },
  members(id: number) {
    return request<ProjectMember[]>(`/projects/${id}/members`)
  },
  addMember(id: number, body: { userId: number; roleCode?: string; roleCodes?: string[] }) {
    return request<ProjectMember>(`/projects/${id}/members`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  /** 批量添加（FR-v4.4-03）：逐人失败原因返回，不整体回滚。 */
  addMembers(id: number, members: { userId: number; roleCodes: string[] }[]) {
    return request<{ added: number; failed: number; failures: { userId: number; reason: string }[] }>(
      `/projects/${id}/members/batch`,
      { method: 'POST', body: JSON.stringify({ members }) },
    )
  },
  updateMember(id: number, userId: number, roleCodes: string[]) {
    return request<ProjectMember>(`/projects/${id}/members/${userId}`, {
      method: 'PUT',
      body: JSON.stringify({ roleCodes }),
    })
  },
  removeMember(id: number, userId: number) {
    return request<void>(`/projects/${id}/members/${userId}`, { method: 'DELETE' })
  },
}

/** 当前用户偏好（v4.5.0）：记住上次界面模式；v4.12.0 增改自己的口令。 */
export const meApi = {
  uiMode() {
    return request<{ mode: 'global' | 'project' }>('/me/ui-mode')
  },
  setUiMode(mode: 'global' | 'project') {
    return request<{ mode: 'global' | 'project' }>('/me/ui-mode', {
      method: 'PUT',
      body: JSON.stringify({ mode }),
    })
  },
  /**
   * 改自己的口令（FR-v4.12-23）。
   *
   * 路径里没有 userId，身份由会话决定 —— 这不是省略，而是设计：有 id 的接口迟早会被
   * 传别人的 id 试试，没有 id 就没有这个面。
   */
  changePassword(oldPassword: string, newPassword: string) {
    return request<{ changed: boolean }>('/me/password', {
      method: 'POST',
      body: JSON.stringify({ oldPassword, newPassword }),
    })
  },
}
