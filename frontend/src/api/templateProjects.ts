/**
 * 模板明细 / 模板建项目 / 模板工作台契约（v4.9.2 / OPT-05、OPT-06 / FR-49-17~22）。
 *
 * 四类计数取自 `GET /api/global/templates/{id}` 的 items 明细，不只信 `itemCount` 聚合值。
 * 工作台只写「模板内容清单」（project_template_item），刻意不提供任何写回公共用例本体的接口。
 *
 * URL 一律不带 `/api` 前缀 —— `http.ts` 的 `request()` 会统一拼 BASE_URL。
 */
import { request } from './http'
import type { TemplateRow } from './globalTemplates'

export interface TemplateItemRow {
  itemType: string
  /** 后端 insertItems 跳过 refId 为 null 的明细：新建引用项必须带引用 ID。 */
  refId: number | null
  mode: string
  name: string
  /** 后端 TemplateItemInput 的快照列；前端清单不写正文，恒为 null。 */
  snapshotJson?: string | null
}

/** PUT /api/global/templates/{id}/content 复用 CreateTemplateRequest 的元信息字段。 */
export interface TemplateContentMeta {
  name?: string
  code?: string
  kernel?: string
  visibility?: string
  description?: string
}

export interface TemplateDetailView {
  template: TemplateRow & { updatedAt?: string | null }
  /** 四类明细：逐项计数与卡片展示一一对应（FR-49-18）。 */
  cases: TemplateItemRow[]
  caseSets: TemplateItemRow[]
  scenarios: TemplateItemRow[]
  assets: TemplateItemRow[]
  /** 全量清单，工作台保存时整体覆盖。 */
  items: TemplateItemRow[]
}

export interface TemplateCreateRequest {
  name: string
  code?: string
  kernel?: string
  visibility?: string
  description?: string
  /** 空 items 由后端返回 4xx；前端也会先拦截（FR-49-20）。 */
  items: TemplateItemRow[]
}

export interface FromTemplateRequest {
  templateId: number
  name: string
  /** 默认 copy：项目用例正文与公共库解耦（FR-49-23）。 */
  mode: 'copy'
  code?: string
}

/** 实装后端返回 domain Project，主键字段是 `id`（不是 projectId）。 */
export interface FromTemplateResult {
  id: number
  code?: string
  name?: string
}

export const templateProjectsApi = {
  /** 4th Agent 约定：GET /api/global/templates/{id}（含四类明细）。 */
  detail(id: number | string) {
    return request<TemplateDetailView>(`/global/templates/${id}`)
  },
  /** 4th Agent 约定：POST /api/global/templates（空 items 返回 4xx）。 */
  create(body: TemplateCreateRequest) {
    return request<TemplateRow>(`/global/templates`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  /**
   * 工作台明细持久化：实装后端为 PUT /api/global/templates/{id}/content，
   * 入参是 CreateTemplateRequest（模板元信息 + items 全量覆盖），不是 /items。
   * builtin 模板后端返回 4xx 且 items 不变，前端也先做只读拦截（FR-49-21）。
   */
  saveItems(id: number | string, items: TemplateItemRow[], meta: TemplateContentMeta = {}) {
    return request<TemplateRow>(`/global/templates/${id}/content`, {
      method: 'PUT',
      body: JSON.stringify({ ...meta, items }),
    })
  },
  /** 4th Agent 约定：POST /api/projects/from-template（{templateId,name,mode:'copy'}）。 */
  createFromTemplate(body: FromTemplateRequest) {
    return request<FromTemplateResult>(`/projects/from-template`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
}
