/**
 * 项目执行协调 API（v5.2.0 / W52-04~06）。
 *
 * 五个端点对应页面上的「现在能不能跑 / 会拆成几单 / 今天的账」三件事：
 * 准入与窗口是一件事的两面（能不能跑），台账是事后看，拆单预览是事前看。
 *
 * 统一挂在 `/execution-coordination` 而不是复用 `/runs`：这些是**决策**接口，
 * 不产生执行副作用（submit 除外），与运行记录的 CRUD 混在一起会让权限边界变模糊。
 */
import { request } from './http'

/** 准入结论。`allowed=false` 时 `reason` 与 `hint` 必有值。 */
export interface Admission {
  allowed: boolean
  windowDescription: string
  exclusive: boolean
  reason: string | null
  hint: string | null
}

export interface WindowInfo {
  description: string
  timeBounded: boolean
  exclusive: boolean
  nextOpening: string
}

/** 今日台账。成功率按用例级算（一个 run 里失败 1 条不会把整体拉到 0%）。 */
export interface ExecutionLedger {
  runsToday: number
  finishedRuns: number
  failedRuns: number
  running: number
  queued: number
  totalTests: number
  passedTests: number
  failedTests: number
  successRatePercent: number
  avgDurationSeconds: number
  windowDescription: string
  windowOpen: boolean
  exclusive: boolean
}

/** 拆单预览的单个 run。 */
export interface SplitRun {
  kernel: string
  requestKey: string
  cases: PlannedCase[]
  suitePaths: string[]
  priority: number
}

export interface PlannedCase {
  caseCode: string
  kernel: string
  suitePath: string
  priority: number
}

export interface SplitPlan {
  runs: SplitRun[]
  totalCases: number
  mixedKernel: boolean
  windowDescription: string
  windowOpen: boolean
}

function base(projectId: number): string {
  return `/projects/${projectId}/execution-coordination`
}

export const executionCoordinationApi = {
  admission(projectId: number): Promise<Admission> {
    return request<Admission>(`${base(projectId)}/admission`)
  },

  window(projectId: number): Promise<WindowInfo> {
    return request<WindowInfo>(`${base(projectId)}/window`)
  },

  ledger(projectId: number): Promise<ExecutionLedger> {
    return request<ExecutionLedger>(`${base(projectId)}/ledger`)
  },

  split(projectId: number, cases: PlannedCase[]): Promise<SplitPlan> {
    return request<SplitPlan>(`${base(projectId)}/split`, {
      method: 'POST',
      body: JSON.stringify(cases),
    })
  },
}
