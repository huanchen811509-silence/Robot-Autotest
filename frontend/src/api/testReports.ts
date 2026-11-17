/** W2 / RPTX 测试报告 API。页面只经这里访问 /api/test-reports。 */
import { request, requestBlob } from './http'

export interface TestReportItem {
  id: number
  projectId: number
  runId: string
  /** 来源类型（v3.10.0）：`RUN` = 按执行记录 / `CASE_SET` = 按用例集。后端返回枚举名。 */
  sourceType?: string
  /** 来源用例集 id，仅 `sourceType = CASE_SET` 时有值。 */
  caseSetId?: number | null
  title: string
  state: string
  version: number
  snapshotHash: string | null
  createdAt: string | null
  frozenAt: string | null
}

export interface TestReportExportItem {
  id: number
  reportId: number
  format: string
  fileName: string
  sizeBytes: number
  watermark: string | null
  createdAt: string | null
}

/** 导出文件名全平台统一，避免后端 Content-Disposition 与前端另存名不一致。 */
export function exportFileName(id: number, format: string): string {
  // v5.14.0 / W64-07：后端实际支持四格式（含 xlsx），前端不得再写死三种。
  const ext = ['html', 'pdf', 'docx', 'xlsx'].includes(format.toLowerCase()) ? format.toLowerCase() : 'html'
  return `report-${id}.${ext}`
}

/**
 * v5.14.0 / W64-05：只读 HTML 快照视图。
 * `html` 已由服务端 ReportHtmlSanitizer 清洗；前端仍必须用 iframe sandbox 兜第二层。
 */
export interface TestReportSnapshot {
  html: string
  hash: string | null
  state: string
  title: string
  /** 已冻结：预览以冻结快照为准，模板后续变更不影响。 */
  frozen: boolean
  /** false = 历史脏数据没有 snapshot_html，详情页显示可解释的空态。 */
  hasSnapshot: boolean
}

/** v5.14.0 / W64-14：三段式报告的只读装配（结论 / 证据 / 附录）。 */
export interface EhtCountRow {
  passed: number
  failed: number
  missingEvidence: number
  undetermined: number
  total: number
}

export interface EhtEvidenceRow {
  /** 优先真实 pytest nodeid，退化为 test_result.case_key */
  nodeRef: string
  criteriaText: string
  measuredValue: string
  /** 证据文件相对路径；null = 本次执行没有登记产物 */
  evidencePath: string | null
}

export interface EhtSections {
  kernel: string
  counts: EhtCountRow
  undetermined: { nodeRef: string; reason: string }[]
  evidence: EhtEvidenceRow[]
  metadata: Record<string, string>
  metadataRequired: string[]
  commitSha: string | null
  diff: {
    hasBaseline: boolean
    baselineRunId: string | null
    newFail: number
    newPass: number
    bothFail: number
    notRun: number
  }
}

/**
 * v5.14.0 / W64-11：模板回指元信息。字段清单仍来自冻结快照，
 * kernel / templateSource 是查询期补充，供界面判断「当前使用通用模板」。
 */
export interface TestReportTemplateRef {
  id: number | null
  version: number | null
  name: string | null
  fieldKeys: string[]
  kernel: string | null
  /** PROJECT_KERNEL_MATCH / PLATFORM_KERNEL_MATCH / PLATFORM_ANY / PLATFORM_FALLBACK */
  templateSource: string | null
}

export const testReportsApi = {
  list: (projectId: number) =>
    request<TestReportItem[]>(`/test-reports?projectId=${projectId}`),
  /**
   * 按执行记录建草稿（旧路径，v3.10.0 之前唯一的一条）。
   *
   * <p>刻意保持三个位置参数不动：这条路径是「零回归」判据的对象，改签名等于顺手改契约。
   */
  create: (projectId: number, runId: string, title: string) =>
    request<TestReportItem>('/test-reports', {
      method: 'POST',
      body: JSON.stringify({ projectId, runId, title }),
    }),
  /**
   * 按用例集建草稿（FR-v3.10-03）。
   *
   * <p>`runId` 传空 = 取该用例集**最近一次执行**；传值则必须属于该用例集（后端校验，不属于则 400）。
   * 显式带 `sourceType: 'case_set'`，不靠「有 caseSetId 就是用例集」这种隐式推断——
   * 隐式推断在将来加第三种来源时会立刻失效。
   */
  createFromCaseSet: (projectId: number, caseSetId: number, runId: string | null, title: string) =>
    request<TestReportItem>('/test-reports', {
      method: 'POST',
      body: JSON.stringify({
        projectId,
        caseSetId,
        runId: runId && runId.trim() ? runId.trim() : null,
        title,
        sourceType: 'case_set',
      }),
    }),
  freeze: (id: number) =>
    request<TestReportItem>(`/test-reports/${id}/freeze`, { method: 'POST' }),
  snapshot: (id: number) =>
    request<TestReportSnapshot>(`/test-reports/${id}/snapshot`),
  template: (id: number) =>
    request<TestReportTemplateRef>(`/test-reports/${id}/template`),
  sections: (id: number) =>
    request<EhtSections>(`/test-reports/${id}/sections`),
  render: (id: number, external = true) =>
    request<Record<string, unknown>>(`/test-reports/${id}?external=${external}`),
  exportBlob: (id: number, format: string, mode: 'external' | 'internal' = 'external') =>
    requestBlob(`/test-reports/${id}/export?format=${format}&mode=${mode}`),
  exports: (id: number) =>
    request<TestReportExportItem[]>(`/test-reports/${id}/exports`),
}
