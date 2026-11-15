/** 总览聚合接口（OVW-01，PRD §3.4）。 */

import { request, toQuery } from './http'

export interface CountItem {
  key: string
  count: number
}

export interface OverviewCaseStats {
  total: number
  byPriority: CountItem[]
  byReviewStatus: CountItem[]
  topDomains: CountItem[]
}

export interface OverviewAssetStats {
  devices: number
  ports: number
  links: number
  credentials: number
  environments: number
  testSets: number
}

export interface OverviewRunStats {
  total: number
  inWindow: number
  running: number
  finished: number
  failed: number
  canceled: number
  casePass: number
  caseFail: number
  caseSkip: number
  /** 基点（1/10000）；null = 窗口内没有完成过运行，显示「—」而不是 0%。 */
  passRateBp: number | null
  avgDurationMs: number | null
  lastRunAt: string | null
}

export interface OverviewRecentRun {
  runId: string
  state: string
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  total: number
  passed: number
  failed: number
  dut: string | null
  link: string | null
  testSetName: string | null
  triggerType: string
  passRateBp: number | null
}

export interface OverviewDailyPoint {
  /** YYYY-MM-DD，后端已补零，恰好 windowDays 个点。 */
  date: string
  finished: number
  failed: number
  canceled: number
}

export interface ProjectOverview {
  projectId: number
  generatedAt: string
  windowDays: number
  cases: OverviewCaseStats
  assets: OverviewAssetStats
  runs: OverviewRunStats
  recentRuns: OverviewRecentRun[]
  dailyTrend: OverviewDailyPoint[]
}

export const overviewApi = {
  load(projectId: number, recent = 8, days = 30): Promise<ProjectOverview> {
    return request<ProjectOverview>(
      `/projects/${projectId}/overview${toQuery({ recent, days })}`,
    )
  },
}

/** 报告结果概览（PRJ-04，PRD FR-v0.2-53）：结果视角，与总览的规模视角互补。 */
export interface DigestReportRow {
  runId: string
  state: string
  finishedAt: string | null
  total: number
  pass: number
  fail: number
  skip: number
  /** 基点；null = 该运行没有用例明细，显示「—」而不是 0%。 */
  passRateBp: number | null
  /** v5.14.0 / W64-03：与执行报告列表同源的 run.suite_count。 */
  suiteCount?: number | null
}

export interface DigestFailedCase {
  caseKey: string
  failures: number
  lastFailedAt: string | null
}

export interface DigestCoverage {
  caseTotal: number
  covered: number
  /** 从未在任何已完成运行的结果里出现过的启用用例数（上界口径，见后端注释）。 */
  neverExecuted: number
}

export interface DigestTrendPoint {
  runId: string
  finishedAt: string | null
  passRateBp: number | null
  fail: number
}

export interface ReportDigest {
  projectId: number
  generatedAt: string
  windowDays: number
  recentReports: DigestReportRow[]
  topFailedCases: DigestFailedCase[]
  coverage: DigestCoverage
  passRateTrend: DigestTrendPoint[]
}

export const digestApi = {
  load(projectId: number, days = 30): Promise<ReportDigest> {
    return request<ReportDigest>(`/projects/${projectId}/report-digest${toQuery({ days })}`)
  },
}
