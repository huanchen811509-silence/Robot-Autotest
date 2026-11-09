/**
 * 通知中心（v3.1.0「顺手」第二批）。
 *
 * 后端契约：
 * - GET  /notifications?projectId=&status=unread|all&page=&size=
 * - GET  /notifications/unread-count?projectId=
 * - POST /notifications/{id}/read
 * - POST /notifications/read-all   body {projectId}
 * - GET/PUT /notifications/preferences  PUT body {items:[{type,enabled}]}
 */
import { request, toQuery } from './http'
import type { PageResult } from './execution'

export type NotificationStatus = 'unread' | 'read'

/** 四类通知；未知枚举按原样展示，不丢消息。 */
export type NotificationType = 'RUN_COMPLETED' | 'RUN_FAILED' | 'NODE_OFFLINE' | 'PLAN_CONFLICT'

export interface NotificationItem {
  id: number
  projectId: number
  runKey: string | null
  type: string
  status: NotificationStatus | string
  message: string
  readAt: string | null
  createdAt: string
}

export interface NotificationQuery {
  projectId: number
  status?: 'unread' | 'all'
  page?: number
  size?: number
}

export interface NotificationPreference {
  type: string
  enabled: boolean
}

export const NOTIFICATION_TYPES: NotificationType[] = [
  'RUN_COMPLETED',
  'RUN_FAILED',
  'NODE_OFFLINE',
  'PLAN_CONFLICT',
]

export const notificationApi = {
  page(query: NotificationQuery): Promise<PageResult<NotificationItem>> {
    const { projectId, ...rest } = query
    return request<PageResult<NotificationItem>>(
      `/notifications${toQuery({ projectId, ...rest })}`,
    )
  },

  async unreadCount(projectId: number): Promise<number> {
    const data = await request<{ count: number }>(
      `/notifications/unread-count${toQuery({ projectId })}`,
    )
    const count = Number(data?.count)
    return Number.isFinite(count) ? count : 0
  },

  markRead(projectId: number, id: number): Promise<unknown> {
    return request<unknown>(`/notifications/${id}/read${toQuery({ projectId })}`, {
      method: 'POST',
    })
  },

  markAllRead(projectId: number): Promise<unknown> {
    return request<unknown>('/notifications/read-all', {
      method: 'POST',
      body: JSON.stringify({ projectId }),
    })
  },

  async preferences(projectId: number): Promise<NotificationPreference[]> {
    const data = await request<NotificationPreference[] | { items?: NotificationPreference[] }>(
      `/notifications/preferences${toQuery({ projectId })}`,
    )
    const items = Array.isArray(data) ? data : data?.items
    return Array.isArray(items) ? items : []
  },

  savePreferences(items: NotificationPreference[]): Promise<unknown> {
    return request<unknown>('/notifications/preferences', {
      method: 'PUT',
      body: JSON.stringify({ items }),
    })
  },
}
