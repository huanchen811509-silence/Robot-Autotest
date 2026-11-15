/**
 * 报告模板接口（TPLX-01，v2.1）。
 *
 * 与后端 `ReportTemplateView` 字段一一对应。
 * `fields()` 是**可选字段的唯一来源**：本文件里不出现任何字段名常量，
 * 界面也不许内联一份名单——内联了就是第二份白名单，白名单收紧后界面还能选到已禁用的字段。
 */
import { request, toQuery } from './http'

export interface ReportTemplateField {
  fieldKey: string
  /** 0 起，升序即导出顺序 */
  sortOrder: number
  /** 自定义列名；null 表示用默认标题 */
  titleOverride: string | null
}

export interface ReportTemplate {
  id: number
  projectId: number
  name: string
  description: string
  /** 每次更新自增，供报告快照回指「冻结时用的哪一版模板」 */
  version: number
  isDefault: boolean
  /** 平台级内置模板：只读 */
  platform: boolean
  fields: ReportTemplateField[]
  createdAt: string
  /** v5.14.0 / W64-10：适用内核 robot / pytest / any；旧后端无此字段时按 robot 显示。 */
  kernel?: string
}

export interface ReportTemplateFieldPayload {
  fieldKey: string
  titleOverride?: string | null
}

export interface ReportTemplatePayload {
  projectId: number
  name: string
  description?: string | null
  fields: ReportTemplateFieldPayload[]
  /** 不传 = robot（旧客户端行为不变）。 */
  kernel?: string
}

export const reportTemplateApi = {
  list(projectId: number): Promise<ReportTemplate[]> {
    return request<ReportTemplate[]>(`/report-templates${toQuery({ projectId })}`)
  },

  /** 可选字段（C5 白名单顺序）；传 kernel=pytest 会额外拿到 5 项专属字段。 */
  fields(kernel?: string): Promise<string[]> {
    return request<string[]>(`/report-templates/fields${toQuery({ kernel })}`)
  },

  create(payload: ReportTemplatePayload): Promise<ReportTemplate> {
    return request<ReportTemplate>('/report-templates', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  update(projectId: number, id: number, payload: ReportTemplatePayload): Promise<ReportTemplate> {
    return request<ReportTemplate>(`/report-templates/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ ...payload, projectId }),
    })
  },

  remove(projectId: number, id: number): Promise<void> {
    return request<void>(`/report-templates/${id}${toQuery({ projectId })}`, { method: 'DELETE' })
  },

  setDefault(projectId: number, id: number): Promise<ReportTemplate> {
    return request<ReportTemplate>(`/report-templates/${id}/default${toQuery({ projectId })}`, {
      method: 'PUT',
    })
  },
}
