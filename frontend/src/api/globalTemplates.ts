/** 模板中心接口（v4.8.0）：CRUD、共享范围、存为模板、向导内容落库。 */

import { request, toQuery } from './http'

export interface TemplateRow {
  id: number
  name: string
  code: string
  kernel: string
  visibility: string
  builtin: boolean
  status: string
  ownerId: number | null
  description: string
  itemCount: number
  createdAt: string
}

export interface TemplateItem {
  itemType: string
  refId: number | null
  mode: string
  name: string
}

export interface TemplateDetail {
  template: TemplateRow
  items: TemplateItem[]
}

export interface TemplateRequest {
  name?: string
  code?: string
  kernel?: string
  visibility?: string
  description?: string
}

export interface ContentResult {
  testSetId: number
  cases: number
  assets: number
}

export const templateApi = {
  list(params: { keyword?: string; visibility?: string }) {
    return request<TemplateRow[]>(`/global/templates${toQuery(params)}`)
  },
  get(id: number) {
    return request<TemplateDetail>(`/global/templates/${id}`)
  },
  create(body: TemplateRequest) {
    return request<TemplateRow>('/global/templates', {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  update(id: number, body: TemplateRequest) {
    return request<TemplateRow>(`/global/templates/${id}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },
  setStatus(id: number, status: string) {
    return request<TemplateRow>(`/global/templates/${id}/status`, {
      method: 'POST',
      body: JSON.stringify({ status }),
    })
  },
  remove(id: number) {
    return request<{ deleted: boolean }>(`/global/templates/${id}`, { method: 'DELETE' })
  },
  copy(id: number, code?: string, name?: string) {
    return request<TemplateRow>(`/global/templates/${id}/copy${toQuery({ code, name })}`, {
      method: 'POST',
    })
  },
  saveAsTemplate(projectId: number, name: string, code?: string, visibility?: string) {
    return request<TemplateRow>(
      `/projects/${projectId}/save-as-template${toQuery({ name, code, visibility })}`,
      { method: 'POST' },
    )
  },
  applyContent(
    projectId: number,
    body: { caseIds: number[]; assets: { assetType: string; assetId: number }[]; setName?: string },
  ) {
    return request<ContentResult>(`/projects/${projectId}/content`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
}
