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
}

/** 项目成员；roleCode 是**项目内角色**（member_role），不是全局角色。 */
export interface ProjectMember {
  id: number | null
  projectId: number
  userId: number
  username: string
  roleCode: string
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

export const projectApi = {
  list() {
    return request<Project[]>('/projects')
  },
  create(body: { code: string; name: string; description?: string; visibility?: string;
                  testKernel?: string }) {
    return request<Project>('/projects', { method: 'POST', body: JSON.stringify(body) })
  },
  remove(id: number) {
    return request<void>(`/projects/${id}`, { method: 'DELETE' })
  },
  members(id: number) {
    return request<ProjectMember[]>(`/projects/${id}/members`)
  },
  addMember(id: number, body: { userId: number; roleCode: string }) {
    return request<ProjectMember>(`/projects/${id}/members`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  updateMember(id: number, userId: number, roleCode: string) {
    return request<ProjectMember>(`/projects/${id}/members/${userId}`, {
      method: 'PUT',
      body: JSON.stringify({ roleCode }),
    })
  },
  removeMember(id: number, userId: number) {
    return request<void>(`/projects/${id}/members/${userId}`, { method: 'DELETE' })
  },
}
