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
  const ext = ['html', 'pdf', 'docx'].includes(format.toLowerCase()) ? format.toLowerCase() : 'html'
  return `report-${id}.${ext}`
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
  render: (id: number, external = true) =>
    request<Record<string, unknown>>(`/test-reports/${id}?external=${external}`),
  exportBlob: (id: number, format: string) =>
    requestBlob(`/test-reports/${id}/export?format=${format}`),
  exports: (id: number) =>
    request<TestReportExportItem[]>(`/test-reports/${id}/exports`),
}
