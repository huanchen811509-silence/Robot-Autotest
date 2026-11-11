/**
 * 统一用例详情契约（v4.9.2 / OPT-02 / FR-49-07~09）。
 *
 * 公共用例本体 / 项目用例 / 同步副本三类详情共用后端
 * `GlobalCaseService.CaseDetailView` 的同一字段集（24 列），前端不做二次裁剪。
 * 详情只读：本模块刻意不提供任何 PUT / POST，避免「看详情」顺手写回公共库本体。
 *
 * URL 一律不带 `/api` 前缀 —— `http.ts` 的 `request()` 会统一拼 BASE_URL。
 */
import { request, toQuery } from './http'

/** 与后端 `GlobalCaseService.CaseDetailView` 逐字段对齐；可空列一律允许 null。 */
export interface CaseDetailView {
  id: number
  caseCode: string | null
  externalId: string | null
  title: string | null
  categoryL1: string | null
  categoryL2: string | null
  priority: string | null
  safetyLevel: string | null
  lifecycleStatus: string | null
  versionNo: number | null
  sourceSheet: string | null
  contentSha: string | null
  aSideCommand: string | null
  bSideCommand: string | null
  switchConfig: string | null
  testResult: string | null
  testResource: string | null
  syncJobId: number | null
  localOverride: boolean | null
  sourceBadge: string | null
  /** 凭据只回掩码（后端恒为 "****"），绝不出现明文口令。 */
  keyMaskedCredential: string | null
}

export type CaseDetailSource = 'global' | 'project' | 'copy'

export interface CaseDetailOptions {
  source: CaseDetailSource
  caseId: string | number
  /** project / copy 必填；global 忽略。 */
  projectId?: string | number | null
}

export const caseDetailApi = {
  /** 公共用例本体：GET /api/global/cases/{id} */
  global(caseId: string | number) {
    return request<CaseDetailView>(`/global/cases/${caseId}`)
  },
  /** 项目用例：GET /api/projects/{projectId}/cases/{id}（FR-49-07 已改为同一 DTO） */
  project(projectId: string | number, caseId: string | number) {
    return request<CaseDetailView>(`/projects/${projectId}/cases/${caseId}`)
  },
  /**
   * 同步副本：本体仍是项目用例（同一字段契约），`source=copy` 只是给详情面板
   * 一个显式的来源提示，用于强调 external_id / syncJobId / localOverride / sourceBadge。
   * 后端把未知 query 参数忽略，因此请求路径与 project 一致、语义可区分。
   */
  copy(projectId: string | number, caseId: string | number) {
    return request<CaseDetailView>(
      `/projects/${projectId}/cases/${caseId}${toQuery({ source: 'copy' })}`,
    )
  },
  /** 统一入口：按 source 分派到对应 API，三者字段集完全一致。 */
  get(options: CaseDetailOptions) {
    const { source, caseId, projectId } = options
    if (source === 'global') return caseDetailApi.global(caseId)
    const pid = projectId ?? 0
    return source === 'copy'
      ? caseDetailApi.copy(pid, caseId)
      : caseDetailApi.project(pid, caseId)
  },
}
