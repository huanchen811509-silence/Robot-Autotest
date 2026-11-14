/** 通用批量接口（UX-02b / FR-v0.2-40）：资产与用户列表共用同一契约。 */

import { request } from './http'
import type { BatchResult } from '@/composables/useSelection'

export type BatchResource = 'DEVICE' | 'LINK' | 'CREDENTIAL' | 'USER'
export type GenericBatchAction = 'delete' | 'setEnabled'

/**
 * 条件选择（FR-v5.8-02）：与 `ids` **互斥**，同时给后端 400 并点名两个字段。
 *
 * 字段按资源白名单生效——后端对不支持的字段直接 400（如 LINK 不接受 status），
 * 不做静默忽略：静默忽略会让「按条件全选」变成「按更宽的条件全选」。
 */
export interface GenericBatchSelection {
  mode: 'filter'
  keyword?: string
  status?: number
  domain?: string
  /** 「全选后手动取消勾选」的对象。 */
  excludeIds?: number[]
}

export interface GenericBatchRequest {
  resource: BatchResource
  action: GenericBatchAction
  /** 与 `selection` 互斥。 */
  ids?: number[]
  enabled?: boolean
  selection?: GenericBatchSelection
}

export const batchApi = {
  /** 部分成功语义：HTTP 200 + 逐条结果，失败项留在 items 里由调用方保留勾选。 */
  run(projectId: number, body: GenericBatchRequest): Promise<BatchResult> {
    return request<BatchResult>(`/projects/${projectId}/batch`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
}
