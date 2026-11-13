import { request, requestBlob, toQuery } from './http'

/**
 * 报告域 API（RPT-02 / RPT-03）。
 *
 * 页面只能通过这里访问 `/api/reports`，禁止在 view 内直接 fetch：
 * 统一走 http.ts 的 ApiError（401/403 与其他业务失败共用一套提示）。
 */

/** 十项 metadata 必填键，顺序与后端 MetadataGate.REQUIRED 一致。 */
export const REPORT_METADATA_KEYS = [
  'CASE_ID',
  'RUN_ID',
  'QA_PROJECT',
  'DUT',
  'LINK',
  'SW_BUILD',
  'BASELINE',
  'OPERATOR',
  'STARTED_AT',
  'CASE_KIND',
] as const

/** 与后端 ReportRecord 对齐：计数是平铺三列，不是嵌套对象。 */
export interface ReportListItem {
  projectId: number
  runId: string
  state: string
  exportable: boolean
  metadataMissing: string[]
  passCount: number
  failCount: number
  skipCount: number
  startedAt: string | null
  finishedAt: string | null
}

export interface ReportDetail extends ReportListItem {
  metadata: Record<string, string>
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** 关键字步骤树节点。 */
export interface ReportKeywordNode {
  name: string
  status: string
  children: ReportKeywordNode[]
}

/**
 * 用例节点；caseKey 是日志接口的 testKey。
 *
 * `commandText` 三列是 v3.9.0 追加（FR-v3.9-02/03），语义必须分清：
 * - `null`：v3.9.0 之前入库的报告，**还没采集**；
 * - `'NONE'`：本用例本来就没下发外部命令；
 * - 其余：命令原文。
 * 前端把前两种显示成不同文案，不能都当空白。
 */
export interface ReportCaseNode {
  caseKey: string
  name: string
  verdict: string
  durationMs: number
  failureMsg: string | null
  /** 命令原文 / `NONE` / null（历史未采集）。 */
  commandText: string | null
  /** 来源：RF_KEYWORD / PYTEST_MARK / STDOUT_EXTRACT / COMMAND_INDEX / NONE / null。 */
  commandSource: string | null
  /** 退出码，未知为 null。 */
  commandExitCode: number | null
  keywords: ReportKeywordNode[]
}

/** 套件节点；根节点是本次运行的合成根。 */
export interface ReportSuiteNode {
  name: string
  status: string
  suites: ReportSuiteNode[]
  cases: ReportCaseNode[]
}

/** 单行日志：seq 为该用例内的相对序号。 */
export interface ReportLogLine {
  seq: number
  level: string
  keyword: string
  message: string
}

/** 分页日志；nextOffset=null 表示已到末尾。 */
export interface ReportLogPage {
  lines: ReportLogLine[]
  total: number
  nextOffset: number | null
}

export interface CompareSide {
  verdict: string
  durationMs: number
}

/** 一行对比；history/current 允许为 null，且互不兜底。 */
export interface CompareRow {
  caseKey: string
  history: CompareSide | null
  current: CompareSide | null
  change: 'PASS_TO_FAIL' | 'FAIL_TO_PASS' | 'SAME' | 'NOT_RUN'
}

export interface CompareSummary {
  newFail: number
  newPass: number
  bothFail: number
  notRun: number
}

export interface CompareBaseline {
  runId: string
  startedAt: string | null
  /** N4：与本轮 DUT/LINK 不同时为 true，前端需显示「跨环境」提示 */
  crossEnvironment: boolean
  dut: string | null
  link: string | null
}

export interface CompareResult {
  baseline: CompareBaseline | null
  rows: CompareRow[]
  summary: CompareSummary
  crossEnvironment: boolean
}

/** 趋势单点；envKey 为 DUT+LINK，环境变化时必须断线。 */
export interface TrendPointView {
  runId: string
  startedAt: string | null
  verdict: string
  durationMs: number
  envKey: string
  deviated: boolean
}

/** 同一环境的一条折线。 */
export interface TrendSeriesView {
  envKey: string
  points: TrendPointView[]
}

/** 用例趋势 + 性能基线；baselineMs=null 表示没有同环境历史。 */
export interface CaseTrendResult {
  caseKey: string
  currentEnvKey: string
  baselineMs: number | null
  deviated: boolean
  points: TrendPointView[]
  series: TrendSeriesView[]
}

export interface PassRateItem {
  bucket: string
  pass: number
  fail: number
  skip: number
  total: number
  passRate: number
}

export interface PassRateResult {
  granularity: 'day' | 'week'
  buckets: PassRateItem[]
}

/** 产物清理清单行；reason 为 expired / quota。 */
export interface RetentionItem {
  runId: string
  bytes: number
  reason: string
}

export interface RetentionResult {
  dryRun: boolean
  runCount: number
  totalBytes: number
  projectBytes: number
  keptRecentRuns: number
  deletedFiles: number
  items: RetentionItem[]
}

/** 导出模式（RPT-05）：external 对外交付；internal 允许缺项但仅内部使用。 */
export type ReportExportMode = 'external' | 'internal'

/** 导出格式。 */
export type ReportExportFormat = 'pdf' | 'docx'

/**
 * 导出前质量自检（v5.6.0 / FR-v5.6-27）。
 *
 * `label` 为 `DRAFT` 时文件名也带 `_DRAFT`，两处同源——文件被改名转发后仍能看出是草稿。
 */
export interface ReportQualityGate {
  passed: boolean
  missing: string[]
  label: 'FINAL' | 'DRAFT'
}

/** 导出成功响应，字段与后端 ExportService.ExportResult 对齐。 */
export interface ReportExportResult {
  exportId: number
  format: ReportExportFormat
  mode: ReportExportMode
  bytes: number
  sha256: string
  downloadUrl: string
  qualityGate: ReportQualityGate
}

/** 导出历史行。 */
export interface ReportExportHistoryItem {
  exportId: number
  runId: string
  format: ReportExportFormat
  mode: ReportExportMode
  fileName: string
  bytes: number
  sha256: string
  status: string
  requestedBy: number | null
  createdAt: string | null
}

/** 打印页数据：与 Word 同源（metadata + 套件树 + 失败消息）。 */
export interface ReportPrintData {
  runId: string
  mode: ReportExportMode
  internal: boolean
  exportable: boolean
  metadataMissing: string[]
  metadata: Record<string, string>
  passCount: number
  failCount: number
  skipCount: number
  startedAt: string | null
  finishedAt: string | null
  tree: ReportSuiteNode
}

/** 打印页一次性 token。 */
export interface IssuedPrintToken {
  token: string
  expiresAt: string
  printUrl: string
  mode: ReportExportMode
}

/**
 * Top 失败项（v5.6.0 / FR-v5.6-22）。
 *
 * `display` 是可读原文，列表请用它；`clusterKey` 是内部指纹，只用于重跑与去重。
 * `name` 为后端兼容字段，值与 `display` 相同，新代码不要再读它。
 */
export interface ReportSummaryTopFailure {
  name: string
  display: string
  clusterKey: string
  count: number
  /** 最近窗口内首次出现时间；无历史样本时为 null（v5.6.0 / FR-v5.6-26） */
  firstSeenAt?: string | null
  /** 从最近一轮往前连续出现的轮数；无历史时为 null，最新一轮已消失时为 0 */
  consecutiveRuns?: number | null
  /** 出现轮数 / 统计轮数（0~1）；无历史时为 null */
  reproRate?: number | null
}

/** 报告速览（FR-3.1-07）：数字由后端从报告详情直接汇总，前端不重算。 */
export interface ReportSummary {
  conclusion: 'NO_DATA' | 'PASSED' | 'FAILED'
  total: number
  passed: number
  failed: number
  skipped: number
  passRate: number
  durationMs: number
  topFailures: ReportSummaryTopFailure[]
  risks: string[]
  generatedAt: string
}

export const reportsApi = {
  page(projectId: number, page = 1, size = 20): Promise<PageResult<ReportListItem>> {
    return request<PageResult<ReportListItem>>(
      `/reports${toQuery({ projectId, page, size })}`,
    )
  },

  detail(projectId: number, runId: string): Promise<ReportDetail> {
    return request<ReportDetail>(
      `/reports/${encodeURIComponent(runId)}${toQuery({ projectId })}`,
    )
  },

  tree(projectId: number, runId: string): Promise<ReportSuiteNode> {
    return request<ReportSuiteNode>(
      `/reports/${encodeURIComponent(runId)}/tree${toQuery({ projectId })}`,
    )
  },

  summary(projectId: number, runId: string): Promise<ReportSummary> {
    return request<ReportSummary>(
      `/reports/${encodeURIComponent(runId)}/summary${toQuery({ projectId })}`,
    )
  },

  logs(
    projectId: number,
    runId: string,
    testKey: string,
    offset = 0,
    limit = 200,
  ): Promise<ReportLogPage> {
    return request<ReportLogPage>(
      `/reports/${encodeURIComponent(runId)}/logs${toQuery({ projectId, testKey, offset, limit })}`,
    )
  },

  compare(projectId: number, runId: string, baselineRunId?: string): Promise<CompareResult> {
    return request<CompareResult>(
      `/reports/${encodeURIComponent(runId)}/compare${toQuery({ projectId, baselineRunId })}`,
    )
  },

  /** 手动基线候选（RPT-03 补口）：后端只返回同项目同环境的 finished 运行。 */
  baselineCandidates(projectId: number, runId: string): Promise<CompareBaseline[]> {
    return request<CompareBaseline[]>(
      `/reports/${encodeURIComponent(runId)}/baseline-candidates${toQuery({ projectId })}`,
    )
  },

  trend(
    projectId: number,
    runId: string,
    caseKey: string,
    limit = 5,
  ): Promise<CaseTrendResult> {
    return request<CaseTrendResult>(
      `/reports/${encodeURIComponent(runId)}/trend${toQuery({ projectId, caseKey, limit })}`,
    )
  },

  passRate(
    projectId: number,
    granularity: 'day' | 'week',
    limit = 12,
  ): Promise<PassRateResult> {
    return request<PassRateResult>(
      `/reports/trend/pass-rate${toQuery({ projectId, granularity, limit })}`,
    )
  },

  retention(projectId: number, dryRun = true): Promise<RetentionResult> {
    return request<RetentionResult>(
      `/reports/retention/run${toQuery({ projectId, dryRun })}`,
      { method: 'POST' },
    )
  },

  /** 导出报告（RPT-05）。外部版缺项时后端 400，data 里带 metadataMissing。 */
  exportReport(
    projectId: number,
    runId: string,
    mode: ReportExportMode,
    format: ReportExportFormat,
  ): Promise<ReportExportResult> {
    return request<ReportExportResult>(
      `/reports/${encodeURIComponent(runId)}/export${toQuery({ projectId, mode, format })}`,
      { method: 'POST' },
    )
  },

  /** 某次运行的导出历史。 */
  exports(projectId: number, runId: string): Promise<ReportExportHistoryItem[]> {
    return request<ReportExportHistoryItem[]>(
      `/reports/${encodeURIComponent(runId)}/exports${toQuery({ projectId })}`,
    )
  },

  /** 打印页取数：token 一次性，失败时抛 ApiError（打印页展示中文提示）。 */
  printData(runId: string, token: string): Promise<ReportPrintData> {
    return request<ReportPrintData>(
      `/print/${encodeURIComponent(runId)}${toQuery({ token })}`,
    )
  },

  /** 下载导出文件（返回 Blob，由页面触发浏览器保存）。 */
  downloadExport(downloadUrl: string): Promise<Blob> {
    return requestBlob(downloadUrl)
  },
}
