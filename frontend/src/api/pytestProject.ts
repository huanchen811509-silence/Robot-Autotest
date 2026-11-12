/** v5.1.0 · pytest 工程接口（FR-v5.1-05 / FR-v5.1-12）。 */
import { request } from './http'

export interface PytestHealth {
  healthy: boolean
  pythonBin: string | null
  pythonVersion: string | null
  minPythonVersion: string
  pythonVersionProblem: string | null
  pytestVersion: string | null
  foundOptions: string[]
  contractMissing: string[]
  contractFailure: string | null
  repoConfigured: boolean
  repoReachable: boolean
  repoPath: string | null
  modeDefault: string
  totalCases: number
  bound: number
  unbound: number
  /** 待评审条数（双口径 B 要减掉的那部分）。 */
  pendingCases: number
  totalExcludingPending: number
  boundExcludingPending: number
  coveragePercent: number
  coverageExcludingPendingPercent: number
  hints: string[]
}

export interface CaseScriptBinding {
  id: number
  projectId: number
  caseId: number
  kernel: string
  scriptPath: string
  nodeId: string | null
  scriptSha256: string | null
  resolveSource: string
  verified: boolean
}

export interface BindingStats {
  totalCases: number
  bound: number
  unbound: number
  pendingCases: number
  totalExcludingPending: number
  boundExcludingPending: number
  orphanScripts: number
  duplicateRisk: number
  coveragePercent: number
  coverageExcludingPendingPercent: number
  items: CaseScriptBinding[]
}

export interface SyncReport {
  totalCases: number
  bound: number
  unbound: number
  pendingCases: number
  totalExcludingPending: number
  boundExcludingPending: number
  orphanScripts: number
  duplicateBindings: number
  renamedScripts: number
  scriptsScanned: number
  orphanCodes: string[]
  duplicateCodes: string[]
  unboundCodes: string[]
  renamedPairs: string[]
  balanced: boolean
  coveragePercent: number
  coverageExcludingPendingPercent: number
}

/** 一条步骤证据行（v5.1.0 · FR-v5.1-08）。 */
export interface PytestCaseStep {
  id: number
  runId: number
  testResultId: number | null
  nodeId: string
  caseKey: string
  seq: number
  /** DETAIL / VERDICT / COMMAND / FAILURE */
  kind: string
  label: string
  content: string | null
  /** PASS / FAIL / SKIP；非判定行为 null */
  status: string | null
  commandId: string | null
  stdoutPath: string | null
  logLine: number
  source: string
}

/** 抽取结果。captured=false 表示「没捕获到日志」，与「用例没写步骤」必须分开看。 */
export interface EvidenceExtractResult {
  runId: string
  runDbId: number
  cases: number
  stepCount: number
  commandCount: number
  capturedCases: number
  unboundCases: number
  captured: boolean
  junitSource: string | null
  commandRoot: string | null
  hints: string[]
}

function base(projectId: number): string {
  return `/projects/${projectId}/pytest`
}

export const pytestProjectApi = {
  health(projectId: number, refresh = false): Promise<PytestHealth> {
    return request<PytestHealth>(`${base(projectId)}/health${refresh ? '?refresh=true' : ''}`)
  },
  bindings(projectId: number): Promise<BindingStats> {
    return request<BindingStats>(`${base(projectId)}/bindings`)
  },
  sync(projectId: number): Promise<SyncReport> {
    return request<SyncReport>(`${base(projectId)}/bindings/sync`, { method: 'POST' })
  },
  settings(projectId: number): Promise<Record<string, string>> {
    return request<Record<string, string>>(`${base(projectId)}/settings`)
  },
  saveSettings(projectId: number, body: Record<string, string>): Promise<Record<string, string>> {
    return request<Record<string, string>>(`${base(projectId)}/settings`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },
  /** 抽取并入库某次 run 的步骤与命令证据（POST：要读两个目录且覆盖写）。 */
  extractEvidence(
    projectId: number,
    runId: string,
    commands = true,
  ): Promise<EvidenceExtractResult> {
    return request<EvidenceExtractResult>(
      `${base(projectId)}/runs/${encodeURIComponent(runId)}/evidence?commands=${commands}`,
      { method: 'POST' },
    )
  },
  evidence(projectId: number, runId: string): Promise<PytestCaseStep[]> {
    return request<PytestCaseStep[]>(
      `${base(projectId)}/runs/${encodeURIComponent(runId)}/evidence`,
    )
  },
}
