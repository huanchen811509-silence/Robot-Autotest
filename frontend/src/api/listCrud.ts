/**
 * 全站列表 CRUD 统一封装（W57-32/35~37/43）。
 *
 * 端点全部按后端已落地的 Controller 核实过，不臆造：
 *  - `PUT    /api/reports/{runId}`       ReportCrudController#update
 *  - `DELETE /api/reports/{runId}`       ReportCrudController#delete
 *  - `PATCH  /api/runs/{runId}`          RunRecordController#patch
 *  - `DELETE /api/runs/{runId}`          RunRecordController#delete
 *  - `POST   /api/runs/{runId}/restore`  RunRecordController#restore
 *  - `GET    /api/runs/{runId}/impact`   RunRecordController#impact（影响面 count 来自后端）
 *  - `PUT    /api/test-reports/{id}`     TestReportCrudController#update
 *  - `DELETE /api/test-reports/{id}`     TestReportCrudController#delete
 *  - `POST   /api/test-reports/{id}/restore` TestReportCrudController#restore
 *
 * B 类（系统产物）只做 R + 元数据编辑 + 软删 + 恢复，**不提供 create**：
 * 这些记录由执行/采集链路产生，界面上不渲染「新增」，本模块也不导出任何 create。
 *
 * `projectId` 一律作为 query 参数（后端 `@RequestParam long projectId`），
 * 与既有 `reports.ts` / `execution.ts` 的口径一致。
 */
import { request, toQuery } from './http'

/** 执行报告 / 运行展示层的元数据。 */
export interface RunMeta {
  runId: string
  displayName: string
  remark: string
  tags: string
}

/** 用户测试报告的元数据。 */
export interface TestReportMeta {
  id: number
  title: string
  remark: string
  tags: string
  state: string
}

/** 软删前的影响面；条数由后端回算，前端只展示，不自己推断。 */
export interface RunImpact {
  testReportExportCount: number
  runArtifactCount: number
}

export interface DeleteResult {
  deleted: boolean
  impact: RunImpact
}

export interface RunUpdate {
  displayName?: string
  remark?: string
  tags?: string
}

export interface TestReportUpdate {
  title?: string
  remark?: string
  tags?: string
}

function runPath(runId: string, suffix = ''): string {
  return `/runs/${encodeURIComponent(runId)}${suffix}`
}
function reportPath(runId: string, suffix = ''): string {
  return `/reports/${encodeURIComponent(runId)}${suffix}`
}
function testReportPath(id: number, suffix = ''): string {
  return `/test-reports/${id}${suffix}`
}

export const listCrudApi = {
  /* ------------------------------------------------ 执行报告（/api/reports） */

  updateReport(projectId: number, runId: string, body: RunUpdate): Promise<RunMeta> {
    return request<RunMeta>(`${reportPath(runId)}${toQuery({ projectId })}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },

  deleteReport(projectId: number, runId: string): Promise<DeleteResult> {
    return request<DeleteResult>(`${reportPath(runId)}${toQuery({ projectId })}`, {
      method: 'DELETE',
    })
  },

  /* ------------------------------------------------ 运行记录（/api/runs） */

  /** 读运行记录的展示层元数据（编辑框回填用）。 */
  getRun(projectId: number, runId: string): Promise<RunMeta> {
    return request<RunMeta>(`${runPath(runId)}${toQuery({ projectId })}`)
  },

  patchRun(projectId: number, runId: string, body: RunUpdate): Promise<RunMeta> {
    return request<RunMeta>(`${runPath(runId)}${toQuery({ projectId })}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    })
  },

  deleteRun(projectId: number, runId: string): Promise<DeleteResult> {
    return request<DeleteResult>(`${runPath(runId)}${toQuery({ projectId })}`, {
      method: 'DELETE',
    })
  },

  /** 恢复软删的运行记录（同时恢复其展示层，后端同一服务方法）。 */
  restoreRun(projectId: number, runId: string): Promise<RunMeta> {
    return request<RunMeta>(`${runPath(runId, '/restore')}${toQuery({ projectId })}`, {
      method: 'POST',
    })
  },

  /** 删除前影响面预览：给 `ConfirmAction` 的 count 用，避免前端拍脑袋写「可能影响 N 项」。 */
  runImpact(projectId: number, runId: string): Promise<RunImpact> {
    return request<RunImpact>(`${runPath(runId, '/impact')}${toQuery({ projectId })}`)
  },

  /* ------------------------------------------------ 用户测试报告（/api/test-reports） */

  updateTestReport(projectId: number, id: number, body: TestReportUpdate): Promise<TestReportMeta> {
    return request<TestReportMeta>(`${testReportPath(id)}${toQuery({ projectId })}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },

  deleteTestReport(projectId: number, id: number): Promise<DeleteResult> {
    return request<DeleteResult>(`${testReportPath(id)}${toQuery({ projectId })}`, {
      method: 'DELETE',
    })
  },

  restoreTestReport(projectId: number, id: number): Promise<TestReportMeta> {
    return request<TestReportMeta>(`${testReportPath(id, '/restore')}${toQuery({ projectId })}`, {
      method: 'POST',
    })
  },
}
