/** 用例域接口（CAS-02 / CAS-03 / CAS-05）：台账、步骤、用例集、导入、能力索引。 */

import { request, requestMultipart, toQuery } from './http'
import type { PageResult } from './iam'

export interface TestCase {
  id: number
  projectId: number
  caseCode: string
  title: string
  category: string | null
  priority: string
  /** 执行安全级（ADR-0013）：L0 / L1 / L2 / LX；L2 与 LX 禁止自动执行。 */
  safetyLevel: string
  precondition: string | null
  expected: string | null
  domainTag: string | null
  reviewStatus: string
  source: string
  capability: string | null
  description: string | null
}

export interface TestCaseStep {
  id: number
  caseId: number
  stepNo: number
  action: string
  expectation: string | null
}

export interface StepCommand {
  stepNo: number
  action: string
  expectation?: string
}

export interface CaseCommand {
  caseCode: string
  title: string
  category?: string
  priority: string
  safetyLevel?: string
  precondition?: string
  expected?: string
  domainTag?: string
  capability?: string
  description?: string
  steps?: StepCommand[]
}

export interface CaseDetail {
  testCase: TestCase
  steps: TestCaseStep[]
}

export interface CapabilityStat {
  key: string
  count: number
}

export interface ImportRow {
  rowNum: number
  caseCode: string
  title: string
  category: string | null
  priority: string
  safetyLevel: string | null
  precondition: string | null
  expected: string | null
  domainTag: string | null
  capability: string | null
  description: string | null
}

export interface RowError {
  rowNum: number
  field: string
  message: string
}

export interface ImportPreview {
  totalRows: number
  validRows: number
  errors: RowError[]
  rows: ImportRow[]
}

export interface ImportResult {
  imported: number
  skipped: number
}

export interface TestSet {
  id: number
  projectId: number
  setCode: string
  name: string
  description: string | null
}

/** 批量动作（FR-v0.2-37：提交评审/通过/驳回/改优先级/改技术域/改安全级/删除）。 */
export type BatchAction =
  | 'submit'
  | 'approve'
  | 'reject'
  | 'setPriority'
  | 'setDomainTag'
  | 'setSafetyLevel'
  | 'delete'

/**
 * 批量请求：reject 必填 comment；setPriority 必填 priority；
 * setDomainTag 必填 domainTag；setSafetyLevel 必填 safetyLevel（人工补判 LX）。
 */
export interface BatchRequest {
  action: BatchAction
  ids: number[]
  comment?: string
  priority?: string
  domainTag?: string
  safetyLevel?: string
}

/** FR-49-28：分类批量加入结果，matched 必须等于 added（筛选条数 = 加入条数）。 */
export interface CategoryFilterResult {
  matched: number
  added: number
  itemSource: string
}

/** 逐条结果：失败带稳定错误码名（NOT_FOUND / CONFLICT）。 */
export interface BatchItemResult {
  id: number
  ok: boolean
  code: string | null
  message: string | null
}

/** 批量结果（部分成功语义：HTTP 200 + 逐条结果，FR-v0.2-38）。 */
export interface BatchResult {
  total: number
  succeeded: number
  failed: number
  items: BatchItemResult[]
}

const base = (projectId: number | string): string => `/projects/${projectId}`

export const caseApi = {
  page(projectId: number, params: {
    keyword?: string
    domain?: string
    priority?: string
    reviewStatus?: string
    capability?: string
    safetyLevel?: string
    page?: number
    size?: number
  }) {
    return request<PageResult<TestCase>>(`${base(projectId)}/cases${toQuery(params)}`)
  },
  get(projectId: number, id: number) {
    return request<CaseDetail>(`${base(projectId)}/cases/${id}`)
  },
  create(projectId: number, body: CaseCommand) {
    return request<CaseDetail>(`${base(projectId)}/cases`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  update(projectId: number, id: number, body: CaseCommand) {
    return request<CaseDetail>(`${base(projectId)}/cases/${id}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },
  remove(projectId: number, id: number) {
    return request<void>(`${base(projectId)}/cases/${id}`, { method: 'DELETE' })
  },
  submit(projectId: number, id: number) {
    return request<CaseDetail>(`${base(projectId)}/cases/${id}/submit`, { method: 'POST' })
  },
  review(projectId: number, id: number, approved: boolean, comment?: string) {
    return request<CaseDetail>(`${base(projectId)}/cases/${id}/review`, {
      method: 'POST',
      body: JSON.stringify({ action: approved ? 'approve' : 'reject', comment }),
    })
  },
  batch(projectId: number, body: BatchRequest) {
    return request<BatchResult>(`${base(projectId)}/cases/batch`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  capabilities(projectId: number) {
    return request<CapabilityStat[]>(`${base(projectId)}/capabilities`)
  },
  previewImport(projectId: number, file: File) {
    const form = new FormData()
    form.append('file', file)
    return requestMultipart<ImportPreview>(`${base(projectId)}/cases/import/preview`, form)
  },
  commitImport(projectId: number, rows: ImportRow[]) {
    return request<ImportResult>(`${base(projectId)}/cases/import/commit`, {
      method: 'POST',
      body: JSON.stringify({ rows }),
    })
  },
}

export const caseSetApi = {
  page(projectId: number, params: { keyword?: string; page?: number; size?: number }) {
    return request<PageResult<TestSet>>(`${base(projectId)}/case-sets${toQuery(params)}`)
  },
  create(projectId: number, body: { setCode: string; name: string; description?: string }) {
    return request<TestSet>(`${base(projectId)}/case-sets`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  update(projectId: number, id: number, body: { name: string; description?: string }) {
    return request<TestSet>(`${base(projectId)}/case-sets/${id}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },
  remove(projectId: number, id: number) {
    return request<void>(`${base(projectId)}/case-sets/${id}`, { method: 'DELETE' })
  },
  items(projectId: number, id: number) {
    return request<TestCase[]>(`${base(projectId)}/case-sets/${id}/items`)
  },
  /** 加入用例：后端校验用例属于同项目且未重复（重复返回 409）。 */
  addItem(projectId: number, id: number, caseId: number) {
    return request<void>(`${base(projectId)}/case-sets/${id}/items`, {
      method: 'POST',
      body: JSON.stringify({ caseId }),
    })
  },
  removeItem(projectId: number, id: number, caseId: number) {
    return request<void>(`${base(projectId)}/case-sets/${id}/items/${caseId}`, {
      method: 'DELETE',
    })
  },
  /** 重排：`caseIds` 必须是当前集合的完整成员列表（后端据此整体覆盖顺序）。 */
  reorder(projectId: number, id: number, caseIds: number[]) {
    return request<void>(`${base(projectId)}/case-sets/${id}/order`, {
      method: 'PUT',
      body: JSON.stringify({ caseIds }),
    })
  },
  /** FR-49-28：全选一级 / 全选二级 / 跨表交集三种筛选批量加入用例集。 */
  addByCategoryFilter(
    projectId: number | string,
    id: number | string,
    body: { categoryL1?: string; categoryL2?: string; intersectWithSetId?: number },
  ) {
    return request<CategoryFilterResult>(
      `${base(projectId)}/case-sets/${id}/items/category-filter`,
      { method: 'POST', body: JSON.stringify(body) },
    )
  },
}

/** FR-49-29：场景仍限一层（case_set_ids 逗号串，不新建中间表）。 */
export interface ScenarioRow {
  id: number
  name: string
  case_set_ids: string | null
  step_order: number
  parent_scenario_id: number | null
}

export const scenarioApi = {
  /** GET /api/scenarios?projectId= —— 同时回传一层违规清单。 */
  list(projectId: number | string) {
    return request<{ items: ScenarioRow[]; oneLevelViolations: string[] }>(
      `/scenarios${toQuery({ projectId })}`,
    )
  },
  /** 创建场景：复用 test_set.kind=scenario 三态。 */
  create(projectId: number | string, body: { setCode: string; name: string; caseSetIds: number[] }) {
    return request<number>(`${base(projectId)}/case-sets/scenario-groups`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
  /** 编组：只更新场景引用的一层用例集 id 串。 */
  regroup(projectId: number | string, scenarioId: number | string, caseSetIds: number[]) {
    return request<{ regrouped: boolean }>(
      `${base(projectId)}/case-sets/scenario-groups/${scenarioId}/case-sets`,
      { method: 'PUT', body: JSON.stringify({ caseSetIds }) },
    )
  },
  /** 删除场景（连同 kind=scenario 的用例集行）。 */
  remove(projectId: number | string, scenarioId: number | string) {
    return request<{ deleted: boolean }>(
      `${base(projectId)}/case-sets/scenario-groups/${scenarioId}`,
      { method: 'DELETE' },
    )
  },
}

/** 用例集的资产版本读数（PUBX-01）。 */
export interface AssetVersionView {
  assetVersion: number
  contentHash: string | null
  forkState: string
  syncedAssetVersion: number | null
  syncedContentHash: string | null
  sourceAssetId: number | null
  hasNewVersion: boolean
  latestAssetVersion: number | null
  latestContentHash: string | null
}

/** 差异一行（PUBX-02）：type ∈ ADDED（平台新增）/ MODIFIED / REMOVED（平台已删）。 */
export interface AssetDiffRow {
  type: 'ADDED' | 'MODIFIED' | 'REMOVED'
  caseCode: string
  title: string
  detail: string
}

/** 同步策略（N9）：FULL 整体覆盖；ADD_ONLY 只补平台新增，保留本地修改与顺序。 */
export type SyncStrategy = 'FULL' | 'ADD_ONLY'

/** 平台集被哪些项目副本引用（N9 只读视图）。 */
export interface AssetVersionReference {
  projectId: number
  projectCode: string
  copyAssetId: number
  syncedAssetVersion: number | null
  forkState: string
  latestAssetVersion: number
  hasNewVersion: boolean
}

/** 平台用例集行（N9 只读库）：不属于任何项目，`test_set.project_id IS NULL`。 */
export interface PlatformCaseSetRow {
  id: number
  setCode: string
  name: string
  description: string | null
  itemCount: number
  assetVersion: number
  contentHash: string | null
  hasNewVersion: boolean
  referenceCount: number
  references: AssetVersionReference[]
}

/**
 * 用例集资产版本（PUBX-01/02）。
 *
 * 注意路径是 `/api/test-sets/{id}/asset-version`，不在 `/api/projects/{projectId}` 之下：
 * 版本是用例集的属性，平台集（projectId=0 的管理路径）也要走同一组端点。
 */
export const assetVersionApi = {
  version(projectId: number, setId: number) {
    return request<AssetVersionView>(`/test-sets/${setId}/asset-version?projectId=${projectId}`)
  },
  diff(projectId: number, setId: number) {
    return request<AssetDiffRow[]>(
      `/test-sets/${setId}/asset-version/diff?projectId=${projectId}`,
    )
  },
  sync(projectId: number, setId: number, strategy: SyncStrategy = 'FULL') {
    return request<AssetVersionView>(
      `/test-sets/${setId}/asset-version/sync?projectId=${projectId}&strategy=${strategy}`,
      { method: 'POST' },
    )
  },
  diverge(projectId: number, setId: number) {
    return request<AssetVersionView>(
      `/test-sets/${setId}/asset-version/diverge?projectId=${projectId}`,
      { method: 'POST' },
    )
  },
}

/**
 * 平台用例集库（N9，只读）。
 *
 * 平台集不属于任何项目，路径不带 projectId；写操作刻意不提供——
 * 平台库一改，所有副本会静默跟着变，风险高于收益。
 */
export const platformCaseSetApi = {
  page(params: { keyword?: string; page?: number; size?: number }) {
    return request<PageResult<PlatformCaseSetRow>>(
      `/platform/case-sets${toQuery(params)}`,
    )
  },
  items(id: number) {
    return request<TestCase[]>(`/platform/case-sets/${id}/items`)
  },
}
