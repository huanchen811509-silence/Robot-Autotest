import { request, requestBlob, toQuery } from './http'

/** 与后端 RunRecord 字段一一对应；state 为枚举名（QUEUED / PREPARING / RUNNING / FINISHED / FAILED / CANCELED）。 */
export interface ExecutionRun {
  id: number
  runId: string
  projectId: number
  testSetId: number | null
  state: string
  priority: number
  concurrency: number
  /** 设备范围（逗号分隔的设备/端口键）；null = 提交时未声明，执行期不做设备互斥。 */
  deviceScope: string | null
  attemptNo: number
  totalTests: number
  passedTests: number
  failedTests: number
  failureClass: string | null
  failureReason: string | null
  failureMessage: string | null
  artifactsDir: string | null
  enqueuedAt: string | null
  startedAt: string | null
  finishedAt: string | null
  /**
   * 发起方带来的请求号（v4.11.0 / FR-v4.11-16）。
   *
   * 落的是既有的 `run.run_request_id` 列（`VARCHAR(64)`，`V4_1` 起就有，唯一键
   * `uk_run_project_request`），**本版没有新增表或列**。执行计划页发起的运行统一用
   * `EP-` 前缀，该页的「发起记录」只按前缀筛选；用例页发起时留空或给别的值，
   * 因此不会串到执行计划的记录里。
   *
   * 声明为可选：该字段只在运行列表/详情响应里回填，历史夹具与部分轻量端点不带它，
   * 判空一律写 `(run.runRequestId ?? '')`。
   */
  runRequestId?: string | null
}

export interface RunLogLine {
  seq: number
  tsGen: string
  source: string
  level: string
  text: string
}

/**
 * 上一次尝试（FR-v0.4-29）：`retry_of_run_id` 指向的原运行及其失败结论。
 * 首次尝试没有上一次，接口返回 `data = null`。
 */
export interface RunRetryOrigin {
  /** 上一次尝试的 runId（本次运行的重试来源） */
  runId: string
  state: string
  /** 失败分类，如 ENV / CASE；上一次成功时为 null */
  failureClass: string | null
  failureReason: string | null
  failureMessage: string | null
  finishedAt: string | null
}

/** 租约只读视图（FR-v0.4-09）：后端只返回生效中的租约（`lease_state = held`）。 */
export interface LeaseView {
  resourceKey: string
  runId: string
  jobId: number
  projectId: number
  acquiredAt: string | null
  expiresAt: string | null
  remainingSeconds: number
  state: string
}

/** 强制释放结果（FR-v0.4-10）：`runState` 是原运行被改成的状态（通常 FAILED）。 */
export interface ForceReleaseResult {
  runId: string
  runState: string
  releasedLeases: number
}

/**
 * 排队位次（EXEC-10 残余 / FR-v0.4-14）。
 *
 * - `state = queued`：在队列中等待，`position` 为 1 起的位次；`position = 0` 表示后端重启过、位次未知。
 * - `state = waiting`：已出队，正在等设备租约（设备被别的运行占用，FR-08）。
 * - `state = running`：已占用执行线程。
 * - `state = terminal`：已结束（或已取消/失败）。
 */
export interface PendingActivity {
  runId: string
  /** waiting = 已被派发但在等设备租约（FR-08），与 running 区分 */
  state: 'queued' | 'waiting' | 'running' | 'terminal'
  position: number
  waiting: number
  running: number
  maxParallel: number
}

/**
 * 租约等待耗时（EXEC-09 残余 / FR-08）。
 *
 * - `state = waiting`：此刻仍在等租约，`elapsedMs` 是实时已等待毫秒（`totalMs` 通常为 null）。
 * - `state = settled`：已拿到租约或已结束，`elapsedMs` 回落到落库的总耗时。
 */
export interface LeaseWait {
  state: 'waiting' | 'settled'
  elapsedMs: number
  /** 落库的本次等待总耗时；还没回写为 null（不是 0 秒） */
  totalMs: number | null
}

/**
 * 运行进度快照（N15）：与实时日志同源，由后端解析 pabot 控制台行得到。
 *
 * 口径说明：`total = 0` 表示总数未知（前端按不确定进度渲染，不伪造百分比）；
 * `lastTest` 是**最近一条完成**的用例，不是「此刻正在执行」的那条 —— RF 控制台只在用例
 * 结束时打印结果行，界面文案按「最近完成」表述。
 */
export interface RunProgress {
  runId: string
  state: string
  /** queued / preparing / running / reporting / done */
  phase: string
  total: number
  completed: number
  passed: number
  failed: number
  skipped: number
  lastTest: string | null
  lastVerdict: string | null
  elapsedSeconds: number
  /** 预计剩余秒数；后端未给出或已结束时为 null。 */
  etaSeconds?: number | null
  /** 最近一次心跳距今秒数；仅在 stalled 时前端展示。 */
  heartbeatAgeSeconds?: number | null
  /** 执行中超过 60s 没有心跳。 */
  stalled?: boolean
  /** 已结束（与 state 的终态对齐，便于前端直接隐藏 ETA）。 */
  finished?: boolean
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface CreateRunPayload {
  projectId: number
  testSetId?: number | null
  suitePaths: string[]
  concurrency?: number
  environmentId?: number | null
  deviceKeys?: string[]
  runRequestId?: string
  priority?: number
  timeoutSeconds?: number
  /** 单用例超时（秒）；0 / 省略表示沿用后端全局默认（EXEC-12）。 */
  testTimeoutSeconds?: number
  includeTag?: string
}

/** 页面手动执行（EXEC-01）：与 CI 直建端点分开，走 EXECUTION:EXECUTE 并立即派发。 */
export interface QuickRunPayload {
  projectId: number
  suitePaths: string[]
  testSetId?: number | null
  environmentId?: number | null
  deviceKeys?: string[]
  concurrency?: number
  timeoutSeconds?: number
  /** 单用例超时（秒）；0 / 省略表示沿用后端全局默认（EXEC-12）。 */
  testTimeoutSeconds?: number
  includeTag?: string | null
  runRequestId?: string
  source?: string
}

/** 用例集立即执行：套件路径与测试名由后端按用例编号解析，前端只传执行参数。 */
export interface TestSetRunPayload {
  environmentId?: number | null
  deviceKeys?: string[]
  concurrency?: number
  timeoutSeconds?: number
  /** 单用例超时（秒）；0 / 省略表示沿用后端全局默认（EXEC-12）。 */
  testTimeoutSeconds?: number
  includeTag?: string | null
  runRequestId?: string
}

/** 勾选台账用例执行（EXEC-02）：只传用例 id，绑定解析与未绑定校验都在后端。 */
export interface CaseRunPayload {
  caseIds: number[]
  environmentId?: number | null
  concurrency?: number
  timeoutSeconds?: number
  /** 单用例超时（秒）；0 / 省略表示沿用后端全局默认（EXEC-12）。 */
  testTimeoutSeconds?: number
  includeTag?: string | null
  runRequestId?: string
}

/** 被安全级拦下的用例（EXEC-15 残余）：编号给人看，id 用来定位勾选状态。 */
export interface BlockedCase {
  caseId: number
  caseCode: string
  safetyLevel: string
}

/**
 * 安全级预检结果（EXEC-15 残余 / ADR-0013）。
 *
 * 勾选是跨页的，当前页之外的安全级前端看不到；`allowed = false` 时
 * `blocked` 逐条给出「哪条、什么级」，调用方可提前禁用「执行所选」。
 */
export interface SafetyPrecheck {
  total: number
  allowed: boolean
  blocked: BlockedCase[]
}

export type PrecheckVerdict = 'BLOCK' | 'WARN' | 'OK'
export type PrecheckLevel = 'BLOCK' | 'WARN' | 'OK'

export interface PrecheckCheck {
  key: string
  level: PrecheckLevel
  title: string
  detail: string
  hint: string | null
}

/** 发起运行前的预检结果（阻断/警告/通过 + 规模估算）。 */
export interface RunPrecheckResult {
  verdict: PrecheckVerdict
  checks: PrecheckCheck[]
  estimatedTotal: number
  estimatedSeconds: number
  sampleSize: number
}

export interface PrecheckPayload {
  projectId: number
  testSetId?: number | null
  planId?: number | null
  caseIds?: number[]
  shards?: number | null
  nodeId?: number | null
}

export interface RunPageQuery {
  state?: string
  page?: number
  size?: number
}

export const executionApi = {
  create(payload: CreateRunPayload): Promise<ExecutionRun> {
    return request<ExecutionRun>('/execution/runs', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  quickRun(payload: QuickRunPayload): Promise<ExecutionRun> {
    return request<ExecutionRun>('/execution/quick-run', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  runTestSet(projectId: number, testSetId: number, payload: TestSetRunPayload): Promise<ExecutionRun> {
    return request<ExecutionRun>(
      `/execution/test-sets/${testSetId}/run${toQuery({ projectId })}`,
      { method: 'POST', body: JSON.stringify(payload) },
    )
  },

  runCases(projectId: number, payload: CaseRunPayload): Promise<ExecutionRun> {
    return request<ExecutionRun>(`/execution/cases/run${toQuery({ projectId })}`, {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  /**
   * 安全级预检（EXEC-15 残余 / ADR-0013）：勾选变化时按 id 判级，
   * 把当前页看不到的 L2 / LX 提前暴露出来，避免点了「执行所选」才吃 403。
   */
  safetyPrecheck(projectId: number, caseIds: number[]): Promise<SafetyPrecheck> {
    return request<SafetyPrecheck>(
      `/execution/cases/safety-precheck${toQuery({ projectId })}`,
      { method: 'POST', body: JSON.stringify({ caseIds }) },
    )
  },

  /**
   * 发起运行前的预检（v3.1.0）：返回阻断/警告/通过明细与预计规模。
   * 预检失败不阻断执行，调用方按「没有预检」直接往下走。
   */
  precheck(payload: PrecheckPayload): Promise<RunPrecheckResult> {
    return request<RunPrecheckResult>('/execution/precheck', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  page(projectId: number, query: RunPageQuery = {}): Promise<PageResult<ExecutionRun>> {
    return request<PageResult<ExecutionRun>>(
      `/execution/runs${toQuery({ projectId, ...query })}`,
    )
  },

  get(projectId: number, runId: string): Promise<ExecutionRun> {
    return request<ExecutionRun>(`/execution/runs/${encodeURIComponent(runId)}${toQuery({ projectId })}`)
  },

  /**
   * 取消运行。`reason` 必填：它会落库并在报告里显示，属于**数据**而非界面文案，
   * 因此由调用方按当前界面语言传入（`t('execution.cancelReason')`），
   * API 层不设中文默认值——否则英文界面取消的运行，报告里会留一句中文。
   */
  cancel(projectId: number, runId: string, reason: string): Promise<ExecutionRun> {
    return request<ExecutionRun>(
      `/execution/runs/${encodeURIComponent(runId)}/cancel${toQuery({ projectId })}`,
      { method: 'POST', body: JSON.stringify({ reason }) },
    )
  },

  recentLog(projectId: number, runId: string, limit = 200): Promise<RunLogLine[]> {
    return request<RunLogLine[]>(
      `/execution/runs/${encodeURIComponent(runId)}/log${toQuery({ projectId, limit })}`,
    )
  },

  /** 进度快照（N15）：日志面板按 3 秒轮询展示进度条、阶段与最近用例。 */
  progress(projectId: number, runId: string): Promise<RunProgress> {
    return request<RunProgress>(
      `/execution/runs/${encodeURIComponent(runId)}/progress${toQuery({ projectId })}`,
    )
  },

  /** 排队位次（EXEC-10 残余）：运行列表在 state=queued 时展示「第 N 位 / 共 M 个」。 */
  pendingActivity(projectId: number, runId: string): Promise<PendingActivity> {
    return request<PendingActivity>(
      `/execution/runs/${encodeURIComponent(runId)}/pending-activity${toQuery({ projectId })}`,
    )
  },

  /** 租约等待耗时（EXEC-09 残余 / FR-08）：等待中展示「已等待 N 秒」。 */
  leaseWait(projectId: number, runId: string): Promise<LeaseWait> {
    return request<LeaseWait>(
      `/execution/runs/${encodeURIComponent(runId)}/lease-wait${toQuery({ projectId })}`,
    )
  },

  /**
   * 上一次尝试（EXEC-13 残余 / FR-v0.4-29）：前端据此显示「重试自 … · 上次失败原因」。
   * 首次尝试返回 `null`（不是 404），调用方按「没有上一次」处理即可。
   */
  retryOrigin(projectId: number, runId: string): Promise<RunRetryOrigin | null> {
    return request<RunRetryOrigin | null>(
      `/execution/runs/${encodeURIComponent(runId)}/retry-origin${toQuery({ projectId })}`,
    )
  },

  /** 下载全量日志（EXEC-06 / FR-v0.4-21）：返回原始文件，不是 JSON 信封。 */
  downloadLog(projectId: number, runId: string): Promise<Blob> {
    return requestBlob(
      `/execution/runs/${encodeURIComponent(runId)}/log/download${toQuery({ projectId })}`,
    )
  },

  /** 租约只读视图（FR-v0.4-09）：当前哪些资源被占、被谁占、还剩多久。 */
  listLeases(projectId: number): Promise<LeaseView[]> {
    return request<LeaseView[]>(`/execution/leases${toQuery({ projectId })}`)
  },

  /**
   * 管理员强制释放（FR-v0.4-10）。`confirm` 必须等于占用该资源的 `runId`：
   * 后端拿它做二次确认，传别的值会被 400 拒掉——所以这里不传布尔，直接传 runId。
   */
  forceReleaseLease(
    projectId: number,
    resourceKey: string,
    confirm: string,
  ): Promise<ForceReleaseResult> {
    return request<ForceReleaseResult>(
      `/execution/leases/force-release${toQuery({ projectId, resourceKey, confirm })}`,
      { method: 'POST' },
    )
  },
}

/** 实时日志 WebSocket 地址；可用 VITE_WS_BASE_URL 覆盖（例如经反向代理的 wss 域名）。 */
export function runLogSocketUrl(runId: string): string {
  const override = import.meta.env.VITE_WS_BASE_URL as string | undefined
  const origin =
    override && override.length > 0
      ? override
      : `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}`
  return `${origin}/ws/runs/${encodeURIComponent(runId)}`
}
