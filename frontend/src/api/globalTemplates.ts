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
  /**
   * 模板明细（v5.10.0 / FR-v5.10-05）：`itemType` 取 CASE / CASE_SET / SCENARIO / DEVICE。
   * 只写两张模板表（引用），不复制用例正文。空数组在 POST 上会被后端按「空模板」处理，
   * 因此模板向导在没有内容时传 `SCENARIO` 标签或直接依赖复核步的显式确认。
   */
  items?: {
    itemType: string
    refId: number | null
    mode: string
    name: string
  }[]
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
  /** 编码前缀注册表（v5.10.0 / FR-v5.10-08）：模板向导第 1 步的前缀下拉。 */
  prefixes() {
    return request<{ code: string; meaning: string; builtin: number; used: number }[]>(
      '/global/templates/prefixes',
    )
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
    body: {
      caseIds: number[]
      assets: { assetType: string; assetId: number }[]
      setName?: string
      /** 与后端 `ContentRequest.caseSetIds` 同名：集合成员由服务端展开后并入 caseIds。 */
      caseSetIds?: number[]
      /** 场景 id（test_scenario）。服务端按表校验，不存在即 400。 */
      scenarioIds?: number[]
    },
  ) {
    return request<ContentResult>(`/projects/${projectId}/content`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
}
