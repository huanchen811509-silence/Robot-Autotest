<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  NButton,
  NCheckbox,
  NInput,
  NInputNumber,
  NModal,
  NPagination,
  NRadioButton,
  NRadioGroup,
  NSelect,
  type DataTableColumns,
} from 'naive-ui'
import {
  executionApi,
  runLogSocketUrl,
  type ExecutionRun,
  type LeaseWait,
  type PendingActivity,
  type PrecheckPayload,
  type RunLogLine,
  type RunPrecheckResult,
  type RunProgress,
  type RunRetryOrigin,
} from '@/api/execution'
import { caseSetApi, type TestSet } from '@/api/cases'
import { deviceApi, type Device } from '@/api/asset'
import { useAuthStore } from '@/stores/auth'
import DataTable from '@/components/DataTable.vue'
import { useListPageSize, type PageSize } from '@/composables/useListPageSize'
import ErrorLineView from '@/components/ErrorLine.vue'
import FormField from '@/components/FormField.vue'
import StatusTag from '@/components/StatusTag.vue'
import ShardBoard from '@/components/ShardBoard.vue'
import RunTimeline from '@/components/RunTimeline.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { EMPTY, formatDateTime } from '@/utils/format'
import { formatEta } from '@/utils/eta'
import { groupPrechecks, isPrecheckBlocked, needsPrecheck } from '@/utils/precheck'
import { RUN_STATUSES, runStatusLabel, runStatusTone } from '@/utils/statusLabels'

const { t } = useI18n()
const auth = useAuthStore()

const runs = ref<ExecutionRun[]>([])
const total = ref(0)
const page = ref(1)
// FR-v3.9-04：档位化 + 记忆 + 视口估算
const { size, pageSizes } = useListPageSize()
const stateFilter = ref('')
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const message = ref('')

const selected = ref<ExecutionRun | null>(null)
const logLines = ref<RunLogLine[]>([])
/** 排队位次（EXEC-10 残余）：只有 state=QUEUED 时才轮询，结束后不再请求。 */
const pending = ref<PendingActivity | null>(null)
/** 租约等待耗时（EXEC-09 残余 / FR-08）：等设备租约时显示「已等待 N 秒」。 */
const leaseWait = ref<LeaseWait | null>(null)
const leaseWaitSeconds = computed(() => Math.floor((leaseWait.value?.elapsedMs ?? 0) / 1000))
/** 上一次尝试（EXEC-13 残余 / FR-v0.4-29）：只有重试记录才有值。 */
const retryOrigin = ref<RunRetryOrigin | null>(null)
/**
 * 执行进度（N15）：与实时日志同源，但日志只有「行」没有「总量」，进度另走 3 秒轮询。
 * `total = 0` 表示后端还没拿到 RF 汇总行，此时按不确定进度渲染，不伪造百分比。
 */
const progress = ref<RunProgress | null>(null)
let progressTimer: number | undefined
let pendingTimer: number | undefined
const socketState = ref<'idle' | 'connecting' | 'open' | 'closed'>('idle')
const logPanel = ref<HTMLElement | null>(null)

const projectId = computed(() => auth.projectId ?? 0)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

const PHASE_KEYS: Record<string, string> = {
  queued: 'execution.phaseQueued',
  preparing: 'execution.phasePreparing',
  running: 'execution.phaseRunning',
  reporting: 'execution.phaseReporting',
  done: 'execution.phaseDone',
}
const progressPhase = computed(() => t(PHASE_KEYS[progress.value?.phase ?? 'running'] ?? 'execution.phaseRunning'))
/** 总数未知 → 不确定进度；有总数才给百分比，且夹在 0–100 之间。 */
const progressPercent = computed(() => {
  const snapshot = progress.value
  if (!snapshot || snapshot.total <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((snapshot.completed / snapshot.total) * 100)))
})
const progressIndeterminate = computed(() => !progress.value || progress.value.total <= 0)

/* ---------- EXEC-01 手动执行表单 ---------- */

const manualOpen = ref(false)
/** 'suite' = 直接填套件路径；'testSet' = 交给后端按用例编号解析套件。 */
const manualMode = ref('suite')
const manualSuites = ref('suites')
const manualTestSetId = ref<number | null>(null)
const manualEnv = ref('')
const manualConcurrency = ref(1)
const manualTimeout = ref(3600)
/** EXEC-12：单用例超时；0 = 沿用后端全局默认（execution.test-timeout-seconds）。 */
const manualTestTimeout = ref(0)
const manualDeviceKeys = ref<string[]>([])
const manualTag = ref('')
const manualBusy = ref(false)
const testSets = ref<TestSet[]>([])
const devices = ref<Device[]>([])

const manualSourceOptions = computed(() => [
  { label: t('execution.manual.sourceSuite'), value: 'suite' },
  { label: t('execution.manual.sourceTestSet'), value: 'testSet' },
])
const testSetOptions = computed(() =>
  testSets.value.map((set) => ({ label: `${set.name} (${set.setCode})`, value: set.id })))
/**
 * 设备范围选项：默认列本项目设备名，同时 `tag` 允许手填端口键（`DUT-01/mlx5_0`）。
 * 端口级互斥才是实际需求的粒度，只给设备名会让两台机器共用一块网卡时判错。
 */
const deviceOptions = computed(() =>
  devices.value.map((device) => ({ label: device.name, value: device.name })))
const manualPaths = computed(() =>
  manualSuites.value.split('\n').map((path) => path.trim()).filter((path) => path.length > 0))
const canManualSubmit = computed(() => {
  if (!projectId.value || manualBusy.value) return false
  return manualMode.value === 'suite'
    ? manualPaths.value.length > 0
    : manualTestSetId.value !== null
})

function openManual(): void {
  manualOpen.value = true
  void loadTestSets()
  void loadDevices()
}

function closeManual(): void {
  manualOpen.value = false
}

async function loadTestSets(): Promise<void> {
  if (!projectId.value) {
    testSets.value = []
    return
  }
  try {
    const result = await caseSetApi.page(projectId.value, { page: 1, size: 100 })
    testSets.value = result.items
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function loadDevices(): Promise<void> {
  if (!projectId.value) {
    devices.value = []
    return
  }
  try {
    const result = await deviceApi.page(projectId.value, { page: 1, size: 100 })
    devices.value = result.items
  } catch (e) {
    error.value = localizeError(e)
  }
}

function parsedEnv(): number | null {
  const value = Number.parseInt(manualEnv.value.trim(), 10)
  return Number.isNaN(value) ? null : value
}

// ---- 执行前预检（v3.1.0「顺手」）----
const precheckOpen = ref(false)
const precheckResult = ref<RunPrecheckResult | null>(null)
const precheckGroups = computed(() => {
  const checks = precheckResult.value?.checks ?? []
  const labels: Record<'BLOCK' | 'WARN' | 'OK', string> = {
    BLOCK: t('execution.precheck.blocked'),
    WARN: t('execution.precheck.warn'),
    OK: t('execution.precheck.ok'),
  }
  return groupPrechecks(checks).map((group) => ({
    ...group,
    label: labels[group.level],
  }))
})
const precheckBlocked = computed(() => isPrecheckBlocked(precheckResult.value))

const etaLabels = computed(() => ({
  second: t('execution.eta.second'),
  minute: t('execution.eta.minute'),
  hour: t('execution.eta.hour'),
}))
const progressEta = computed(() => formatEta(progress.value?.etaSeconds, etaLabels.value))
const precheckEstimate = computed(() => {
  const result = precheckResult.value
  if (!result) return ''
  if (!result.estimatedTotal && !result.estimatedSeconds) {
    return t('execution.precheck.estimateUnknown')
  }
  return t('execution.precheck.estimate', {
    count: result.estimatedTotal,
    time: formatEta(result.estimatedSeconds, etaLabels.value),
    samples: result.sampleSize,
  })
})

function manualCommon() {
  return {
    environmentId: parsedEnv(),
    deviceKeys: manualDeviceKeys.value.length > 0 ? [...manualDeviceKeys.value] : undefined,
    concurrency: Number(manualConcurrency.value) || 1,
    timeoutSeconds: Number(manualTimeout.value) || 3600,
    testTimeoutSeconds: Number(manualTestTimeout.value) || 0,
    includeTag: manualTag.value.trim() || null,
  }
}

function precheckPayload(): PrecheckPayload {
  return {
    projectId: projectId.value,
    testSetId: manualMode.value === 'testSet' ? manualTestSetId.value : null,
  }
}

async function submitManual(): Promise<void> {
  if (!canManualSubmit.value) return
  error.value = null
  message.value = ''
  if (manualMode.value === 'suite' && manualPaths.value.length === 0) {
    error.value = { summary: t('execution.manual.needSuites'), detail: null }
    return
  }
  if (manualMode.value === 'testSet' && manualTestSetId.value === null) {
    error.value = { summary: t('execution.manual.needTestSet'), detail: null }
    return
  }

  // 预检只是提高成功率，不是硬门禁：接口不存在或失败时直接按原流程执行。
  try {
    const result = await executionApi.precheck(precheckPayload())
    if (needsPrecheck(result)) {
      precheckResult.value = result
      precheckOpen.value = true
      return
    }
  } catch {
    // 旧后端 / 预检不可用：继续执行
  }
  await executeManual()
}

async function executeManual(): Promise<void> {
  manualBusy.value = true
  const testSetId = manualTestSetId.value
  try {
    const common = manualCommon()
    const run =
      manualMode.value === 'suite'
        ? await executionApi.quickRun({
            projectId: projectId.value,
            suitePaths: manualPaths.value,
            ...common,
            source: 'runs-view',
          })
        : await executionApi.runTestSet(projectId.value, testSetId as number, common)
    precheckOpen.value = false
    manualOpen.value = false
    page.value = 1
    await load()
    await selectRun(run)
    // load() 会清空 message，提示必须放在刷新之后
    message.value = t('execution.manual.created', { runId: run.runId })
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    manualBusy.value = false
  }
}

function confirmPrecheck(): void {
  if (precheckBlocked.value) return
  void executeManual()
}

const stateOptions = computed(() => [
  { label: t('execution.allStates'), value: '' },
  ...RUN_STATUSES.map((item) => ({ label: runStatusLabel(item), value: item.toUpperCase() })),
])

/** 终止态：之后再点「取消」没有意义（后端也会拒），按钮直接不渲染。 */
const TERMINAL_STATES = ['FINISHED', 'FAILED', 'CANCELED']

function isTerminal(state: string): boolean {
  return TERMINAL_STATES.includes(state)
}

/**
 * 运行记录列定义（UX-04b 逐页迁移）。
 *
 * 两个刻意的处理：
 * ① 状态列用 `StatusTag` 而不是纯文字 —— 六预设里 crimson/gold 与 fail/warn 同色相，
 *    只靠颜色区分不可靠，标签本身带图标+文字双编码；
 * ② 「查看日志」用 `text` 型按钮而不是 `link` 型 —— 链接样式在本页会被误读成外链，
 *    且 text 型仍保留键盘焦点环与禁用态。
 */
const columns = computed<DataTableColumns<ExecutionRun>>(() => [
  {
    title: t('execution.runId'),
    key: 'runId',
    width: 220,
    // RUN_ID 是三方关联的主键（RF / 看板 / 报告），长且不允许折行打乱对齐
    ellipsis: { tooltip: true },
  },
  {
    title: t('execution.state'),
    key: 'state',
    width: 120,
    render: (row) =>
      h(StatusTag, { label: runStatusLabel(row.state), tone: runStatusTone(row.state) }),
  },
  {
    title: t('execution.tests'),
    key: 'passedTests',
    width: 110,
    render: (row) => `${row.passedTests} / ${row.totalTests}`,
  },
  {
    // EXEC-11 残余：设备范围原来只在日志面板里看得到，列表页筛不出「哪次占了哪几台」。
    // null 表示提交时未声明（执行期不做设备互斥），不能显示成空白，否则和「字段丢了」无法区分。
    title: t('execution.deviceScope'),
    key: 'deviceScope',
    width: 220,
    ellipsis: { tooltip: true },
    render: (row) => row.deviceScope || t('execution.allDevices'),
  },
  {
    title: t('execution.failureReason'),
    key: 'failureReason',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row) => row.failureReason || EMPTY,
  },
  {
    title: t('execution.startedAt'),
    key: 'startedAt',
    width: 180,
    render: (row) => formatDateTime(row.startedAt),
  },
  {
    title: t('execution.finishedAt'),
    key: 'finishedAt',
    width: 180,
    render: (row) => formatDateTime(row.finishedAt),
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 180,
    render: (row) =>
      h('div', { class: 'row-actions' }, [
        h(
          NButton,
          {
            text: true,
            type: 'primary',
            size: 'small',
            onClick: () => void selectRun(row),
          },
          { default: () => t('execution.viewLog') },
        ),
        !isTerminal(row.state) &&
          h(
            NButton,
            {
              text: true,
              type: 'error',
              size: 'small',
              onClick: () => void cancelRun(row),
            },
            { default: () => t('execution.cancelRun') },
          ),
      ]),
  },
])

/** 当前看日志的那一行加底色，避免「打开了面板却不确定是哪条」的错配。 */
function rowClassName(row: ExecutionRun): string {
  return selected.value?.runId === row.runId ? 'row-active' : ''
}

let socket: WebSocket | null = null
let closedByUser = false
let reconnectTimer: number | undefined


async function load(): Promise<void> {
  error.value = null
  message.value = ''
  if (!projectId.value) {
    runs.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const result = await executionApi.page(projectId.value, {
      state: stateFilter.value,
      page: page.value,
      size: size.value,
    })
    runs.value = result.items
    total.value = result.total
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
}

async function selectRun(run: ExecutionRun): Promise<void> {
  selected.value = run
  logLines.value = []
  stopPendingPoll()
  pending.value = null
  leaseWait.value = null
  retryOrigin.value = null
  progress.value = null
  stopProgressPoll()
  await Promise.all([loadRecentLog(), loadRetryOrigin(run), loadProgress()])
  if (run.state === 'QUEUED' || run.state === 'PREPARING') {
    await loadPending()
    // 5 秒一轮：位次会随前面的 run 结束而前进；等设备租约时也能看到「等待资源」到自动消失
    pendingTimer = window.setInterval(() => void loadPending(), 5000)
  }
  startProgressPoll()
  connect()
}

function stopPendingPoll(): void {
  if (pendingTimer) {
    window.clearInterval(pendingTimer)
    pendingTimer = undefined
  }
}

/**
 * 排队位次。失败不弹错：这是辅助信息，接口偶发失败不该盖住日志面板；
 * 位次未知时保持 `null`，界面退化为不显示排队行。
 */
async function loadPending(): Promise<void> {
  const run = selected.value
  if (!run || !projectId.value) {
    pending.value = null
    return
  }
  try {
    pending.value = await executionApi.pendingActivity(projectId.value, run.runId)
    // 等租约时补一次耗时：只写「等待资源」看不出已经等了多久（EXEC-09 残余 / FR-08）
    leaseWait.value = pending.value.state === 'waiting'
      ? await executionApi.leaseWait(projectId.value, run.runId)
      : null
    // queued / waiting 都要继续轮询：位次会变，租约也会在拿到后转 running
    if (pending.value.state === 'running' || pending.value.state === 'terminal') {
      stopPendingPoll()
    }
  } catch {
    pending.value = null
    leaseWait.value = null
  }
}

/**
 * 上一次尝试（EXEC-13 残余 / FR-v0.4-29）。辅助信息：失败不弹错、不挡日志面板，
 * 拿不到就退化为只显示「第 N 次尝试」。首次尝试不请求，避免每次看日志都多打一次空接口。
 */
async function loadRetryOrigin(run: ExecutionRun): Promise<void> {
  if (run.attemptNo <= 1 || !projectId.value) return
  try {
    retryOrigin.value = await executionApi.retryOrigin(projectId.value, run.runId)
  } catch {
    retryOrigin.value = null
  }
}

/**
 * 执行进度（N15）。辅助信息：失败不弹错、不挡日志面板；`total = 0` 时前端按不确定进度渲染。
 * 终态（phase=done 或 run 已结束）自动停表，避免空转长轮询。
 */
async function loadProgress(): Promise<void> {
  const run = selected.value
  if (!run || !projectId.value) {
    progress.value = null
    return
  }
  try {
    progress.value = await executionApi.progress(projectId.value, run.runId)
    if (progress.value.phase === 'done' || isTerminal(progress.value.state)) {
      stopProgressPoll()
    }
  } catch {
    progress.value = null
  }
}

function startProgressPoll(): void {
  stopProgressPoll()
  if (!selected.value || isTerminal(selected.value.state)) return
  // 3 秒一轮：比日志 WS 慢，但足以让进度条与耗时持续前进
  progressTimer = window.setInterval(() => void loadProgress(), 3000)
}

function stopProgressPoll(): void {
  if (progressTimer) {
    window.clearInterval(progressTimer)
    progressTimer = undefined
  }
}

/** 页面隐藏时暂停进度/排队轮询，回到前台先补一拍再恢复节奏。 */
function onVisibilityChange(): void {
  if (document.hidden) {
    stopProgressPoll()
    stopPendingPoll()
    return
  }
  if (!selected.value || isTerminal(selected.value.state)) return
  void loadProgress()
  startProgressPoll()
}

async function loadRecentLog(): Promise<void> {
  if (!selected.value) return
  try {
    logLines.value = await executionApi.recentLog(projectId.value, selected.value.runId, 200)
    scrollToBottom()
  } catch (e) {
    error.value = localizeError(e)
  }
}

function connect(): void {
  disconnect()
  if (!selected.value) return
  closedByUser = false
  socketState.value = 'connecting'
  try {
    socket = new WebSocket(runLogSocketUrl(selected.value.runId))
  } catch (e) {
    socketState.value = 'closed'
    error.value = localizeError(e)
    return
  }
  socket.onopen = () => {
    socketState.value = 'open'
  }
  socket.onmessage = (event: MessageEvent) => {
    try {
      const payload = JSON.parse(String(event.data)) as { type: string; lines: RunLogLine[] }
      if (payload.type !== 'log' || !Array.isArray(payload.lines)) return
      // G-B2-2：REST 预取的 200 行与 WS 连上后的补发是同一条历史，按 seq 水位去重，
      // 否则首屏会出现两份（实测 52 行 vs 应有 26 行）。
      const lastSeq = logLines.value.reduce((max, line) => Math.max(max, line.seq), 0)
      const fresh = payload.lines.filter((line) => line.seq > lastSeq)
      if (fresh.length === 0) return
      logLines.value = [...logLines.value, ...fresh].slice(-2000)
      scrollToBottom()
    } catch {
      // 非法帧忽略，不影响已展示内容
    }
  }
  socket.onerror = () => {
    socketState.value = 'closed'
  }
  socket.onclose = () => {
    socketState.value = 'closed'
    if (!closedByUser) {
      reconnectTimer = window.setTimeout(() => connect(), 3000)
    }
  }
}

function disconnect(): void {
  closedByUser = true
  if (reconnectTimer) {
    window.clearTimeout(reconnectTimer)
    reconnectTimer = undefined
  }
  if (socket) {
    socket.onclose = null
    socket.close()
    socket = null
  }
  socketState.value = 'idle'
}

/**
 * 日志级别过滤（FR-v0.4-20）：后端只产出 ERROR / WARN / INFO 三档（FAIL 在 RunLogService 里已归到
 * ERROR），所以这里给三个可独立勾选的档位，默认全开。三档全关也允许——面板会明说「当前级别过滤下
 * 没有日志」，而不是假装这份 run 没日志。
 */
type LogLevelFilter = 'error' | 'warn' | 'info'

const enabledLevels = ref<LogLevelFilter[]>(['error', 'warn', 'info'])
const downloadingLog = ref(false)

const levelOptions = computed<{ value: LogLevelFilter; label: string }[]>(() => [
  { value: 'error', label: t('execution.levelError') },
  { value: 'warn', label: t('execution.levelWarn') },
  { value: 'info', label: t('execution.levelInfo') },
])

/** 未知级别按 info 处理：宁可多显示一行，也不要把没见过的 level 藏掉。 */
function lineLevel(level: string): LogLevelFilter {
  const upper = level.toUpperCase()
  if (upper === 'ERROR') return 'error'
  if (upper === 'WARN') return 'warn'
  return 'info'
}

function toggleLevel(level: LogLevelFilter, checked: boolean): void {
  enabledLevels.value = checked
    ? [...enabledLevels.value, level]
    : enabledLevels.value.filter((item) => item !== level)
}

const visibleLines = computed(() =>
  logLines.value.filter((line) => enabledLevels.value.includes(lineLevel(line.level))),
)

async function downloadFullLog(): Promise<void> {
  const run = selected.value
  if (!run) return
  error.value = null
  message.value = ''
  downloadingLog.value = true
  try {
    const blob = await executionApi.downloadLog(projectId.value, run.runId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `run-${run.runId}.log`
    link.click()
    URL.revokeObjectURL(url)
    message.value = t('execution.logDownloaded')
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    downloadingLog.value = false
  }
}

function scrollToBottom(): void {
  requestAnimationFrame(() => {
    if (logPanel.value) {
      logPanel.value.scrollTop = logPanel.value.scrollHeight
    }
  })
}

/**
 * 分片规划/聚合会改 run 的计数与状态：静默刷新当前 run 与列表，不打断日志面板。
 * 辅助刷新失败不弹错（分片看板自己已经展示过错误）。
 */
async function refreshAfterShardChange(): Promise<void> {
  if (!selected.value || !projectId.value) return
  try {
    selected.value = await executionApi.get(projectId.value, selected.value.runId)
    await load()
  } catch {
    // 忽略：分片看板内部会显示错误
  }
}

async function cancelRun(run: ExecutionRun): Promise<void> {
  error.value = null
  message.value = ''
  try {
    await executionApi.cancel(projectId.value, run.runId, t('execution.cancelReason'))
    message.value = t('execution.canceled', { runId: run.runId })
    if (selected.value?.runId === run.runId) {
      selected.value = await executionApi.get(projectId.value, run.runId)
    }
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

/** 页大小切换（FR-v3.9-04）：改档位即回到第 1 页重新取数。 */
function changePageSize(next: number): void {
  size.value = next as PageSize
  page.value = 1
  void load()
}

function turnPage(next: number): void {
  if (next < 1 || next > pageCount.value) return
  page.value = next
  void load()
}

// immediate 同 CasesView：projectId 初值即目标值时 watch 不会触发，页面会永远空着
watch([projectId], () => {
  page.value = 1
  selected.value = null
  manualOpen.value = false
  disconnect()
  stopPendingPoll()
  stopProgressPoll()
  pending.value = null
  leaseWait.value = null
  progress.value = null
  logLines.value = []
  void load()
}, { immediate: true })

watch([stateFilter], () => {
  page.value = 1
  void load()
})

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  disconnect()
  stopPendingPoll()
  stopProgressPoll()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <div class="page is-list">
    <div class="page-head">
      <h1 class="page-title">{{ t('menu.runs') }}</h1>
    </div>

    <div class="toolbar">
      <NButton type="primary" @click="openManual">{{ t('execution.manual.open') }}</NButton>
      <NSelect
        v-model:value="stateFilter"
        :options="stateOptions"
        style="width: 180px"
        :aria-label="t('execution.state')"
      />
      <NButton quaternary :loading="loading" @click="load">{{ t('common.refresh') }}</NButton>
      <RouterLink v-slot="{ navigate }" to="/plans" custom>
        <NButton quaternary @click="navigate">{{ t('plans.title') }}</NButton>
      </RouterLink>
      <!-- 失败聚类（ORCH-03）是运行的下钻页：入口在这里，先选 runId 再下钻。 -->
      <RouterLink v-slot="{ navigate }" to="/runs/failure-clusters" custom>
        <NButton quaternary data-testid="entry-failure-clusters" @click="navigate">
          {{ t('execution.clustersEntry') }}
        </NButton>
      </RouterLink>
    </div>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="message" class="text-ok">{{ message }}</p>

    <div v-if="manualOpen" class="card form-card">
      <div class="form-head">
        <h2 class="card-title">{{ t('execution.manual.title') }}</h2>
        <NButton text @click="closeManual">{{ t('common.cancel') }}</NButton>
      </div>

      <div class="form-grid">
        <FormField :label="t('execution.manual.source')">
          <NSelect v-model:value="manualMode" :options="manualSourceOptions" />
        </FormField>

        <FormField
          v-if="manualMode === 'testSet'"
          :label="t('execution.manual.testSet')"
          required
          :hint="t('execution.manual.testSetHint')"
        >
          <NSelect
            v-model:value="manualTestSetId"
            :options="testSetOptions"
            filterable
            clearable
            :placeholder="t('execution.manual.noTestSets')"
          />
        </FormField>

        <FormField :label="t('execution.manual.environment')" :hint="t('execution.manual.environmentHint')">
          <NInput v-model:value="manualEnv" />
        </FormField>

        <FormField :label="t('execution.manual.concurrency')" :hint="t('execution.manual.concurrencyHint')">
          <NRadioGroup v-model:value="manualConcurrency">
            <NRadioButton :value="1">1</NRadioButton>
            <NRadioButton :value="2">2</NRadioButton>
            <NRadioButton :value="4">4</NRadioButton>
          </NRadioGroup>
        </FormField>

        <FormField
          :label="t('execution.manual.deviceScope')"
          :hint="t('execution.manual.deviceScopeHint')"
        >
          <NSelect
            v-model:value="manualDeviceKeys"
            :options="deviceOptions"
            multiple
            filterable
            tag
            clearable
            :placeholder="t('execution.allDevices')"
          />
        </FormField>

        <FormField :label="t('execution.manual.timeout')">
          <NInputNumber v-model:value="manualTimeout" :min="1" :max="86400" />
        </FormField>

        <FormField :label="t('execution.manual.testTimeout')" :hint="t('execution.manual.testTimeoutHint')">
          <NInputNumber v-model:value="manualTestTimeout" :min="0" :max="86400" />
        </FormField>

        <FormField :label="t('execution.manual.includeTag')">
          <NInput v-model:value="manualTag" :maxlength="64" />
        </FormField>
      </div>

      <FormField
        v-if="manualMode === 'suite'"
        :label="t('execution.manual.suitePaths')"
        required
        :hint="t('execution.manual.suitePathsHint')"
      >
        <NInput
          v-model:value="manualSuites"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 6 }"
        />
      </FormField>

      <div class="form-actions">
        <NButton type="primary" :disabled="!canManualSubmit" :loading="manualBusy" @click="submitManual">
          {{ t('execution.manual.submit') }}
        </NButton>
        <NButton quaternary @click="closeManual">{{ t('common.cancel') }}</NButton>
      </div>
    </div>

    <NModal v-model:show="precheckOpen">
      <div class="precheck-dialog" role="dialog" aria-modal="true" :aria-label="t('execution.precheck.title')">
        <h2 class="card-title">{{ t('execution.precheck.title') }}</h2>
        <p class="muted">{{ precheckEstimate }}</p>
        <div v-for="group in precheckGroups" :key="group.level" class="precheck-group" :class="`is-${group.level.toLowerCase()}`">
          <h3 class="precheck-group__title">{{ group.label }}</h3>
          <ul>
            <li v-for="check in group.items" :key="check.key">
              <strong>{{ check.title }}</strong>
              <span class="muted"> — {{ check.detail }}</span>
              <p v-if="check.hint" class="precheck-hint">{{ check.hint }}</p>
            </li>
          </ul>
        </div>
        <p v-if="precheckBlocked" class="precheck-blocked-hint" role="alert">
          {{ t('execution.precheck.needBlocked') }}
        </p>
        <div class="precheck-actions">
          <NButton quaternary @click="precheckOpen = false">{{ t('execution.precheck.cancel') }}</NButton>
          <NButton type="primary" :disabled="precheckBlocked" @click="confirmPrecheck">
            {{ t('execution.precheck.continueAnyway') }}
          </NButton>
        </div>
      </div>
    </NModal>

    <div class="list-fill">
      <DataTable
        :columns="columns"
        :data="runs"
        :loading="loading"
        :row-key="(row: ExecutionRun) => row.id"
        :row-class-name="rowClassName"
        :empty-title="t('common.noData')"
        :empty-description="projectId ? undefined : t('overview.noProject')"
        empty-icon="runs"
        :error="Boolean(error)"
        flex-height
      />
    </div>

    <NPagination
      v-if="total > 0"
      class="pager"
      :page="page"
      :page-count="pageCount"
      :page-slot="7"
      :page-size="size"
      :page-sizes="pageSizes"
      show-size-picker
      @update:page="turnPage"
      @update:page-size="changePageSize"
    />

    <div v-if="selected" class="card log-card">
      <div class="log-head">
        <h2 class="card-title">{{ t('execution.logPanel') }} · {{ selected.runId }}</h2>
        <StatusTag
          :label="runStatusLabel(selected.state)"
          :tone="runStatusTone(selected.state)"
        />
        <span class="muted">{{ t(`execution.socket.${socketState}`) }}</span>
        <span class="muted">
          {{ t('execution.deviceScopeLine', { scope: selected.deviceScope || t('execution.allDevices') }) }}
        </span>
        <!-- 排队位次（EXEC-10 残余）：只有真正在队列里才显示，位次未知就明说未知 -->
        <span v-if="pending && pending.state === 'queued'" class="muted queue-hint">
          {{
            pending.position > 0
              ? t('execution.queuePosition', { position: pending.position, waiting: pending.waiting })
              : t('execution.queueUnknown')
          }}
        </span>
        <!-- 等待资源（FR-08）：设备被占时不再显示成「运行中」，单独说明在等租约 -->
        <span v-if="pending && pending.state === 'waiting'" class="muted queue-hint">
          {{ t('execution.leaseWaiting') }}
          <template v-if="leaseWait && leaseWait.state === 'waiting'">
            · {{ t('execution.leaseWaitElapsed', { seconds: leaseWaitSeconds }) }}
          </template>
        </span>
        <!-- 重试可追溯（FR-v0.4-29）：首次尝试不渲染，只有重试记录才带「第 N 次尝试」 -->
        <span v-if="selected.attemptNo > 1" class="muted">
          {{ t('execution.attemptLine', { n: selected.attemptNo }) }}
        </span>
        <!-- 重试来源（EXEC-13 残余）：把「为什么重试」直接写在面板上，不用再翻上一次的日志 -->
        <span v-if="retryOrigin" class="muted">
          {{
            t('execution.retryOriginLine', {
              runId: retryOrigin.runId,
              reason:
                retryOrigin.failureReason ||
                retryOrigin.failureClass ||
                t('execution.retryOriginUnknown'),
            })
          }}
        </span>
        <NCheckbox
          v-for="option in levelOptions"
          :key="option.value"
          :checked="enabledLevels.includes(option.value)"
          class="level-filter"
          @update:checked="(checked: boolean) => toggleLevel(option.value, checked)"
        >
          {{ option.label }}
        </NCheckbox>
        <NButton
          quaternary
          size="tiny"
          :loading="downloadingLog"
          @click="downloadFullLog"
        >
          {{ t('execution.downloadLog') }}
        </NButton>
      </div>
      <!--
        执行进度（N15）：数据来自后端对同一批控制台行的解析（不是另开通道）。
        总数未知时走不确定进度条 —— 宁可显示「已完成 N」也不伪造百分比。
      -->
      <div v-if="progress" class="run-progress" data-testid="run-progress">
        <div class="progress-meta">
          <span class="progress-phase">{{ progressPhase }}</span>
          <span class="muted">
            {{
              progress.total > 0
                ? t('execution.progressCountsKnown', { completed: progress.completed, total: progress.total })
                : t('execution.progressCountsUnknown', { completed: progress.completed })
            }}
          </span>
          <span class="muted">{{ t('execution.progressElapsed', { seconds: progress.elapsedSeconds }) }}</span>
          <span v-if="progress.etaSeconds !== null && progress.etaSeconds !== undefined" class="muted">
            {{ t('execution.progressEta', { eta: progressEta }) }}
          </span>
          <span v-if="progress.stalled" class="progress-stalled" role="status">
            {{ t('execution.progressStalled') }}
          </span>
          <span v-if="progress.finished" class="muted">{{ t('execution.progressFinished') }}</span>
          <!-- RF 只在用例结束时打印结果行，所以这里说「最近完成」而不是「当前执行」 -->
          <span v-if="progress.lastTest" class="muted progress-last">
            {{ t('execution.progressLast', { test: progress.lastTest, verdict: progress.lastVerdict ?? '' }) }}
          </span>
        </div>
        <div
          class="progress-track"
          :class="{ indeterminate: progressIndeterminate, failed: progress.failed > 0 }"
          role="progressbar"
          :aria-valuenow="progressIndeterminate ? undefined : progressPercent"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <div
            class="progress-fill"
            :style="progressIndeterminate ? undefined : { width: `${progressPercent}%` }"
          />
        </div>
      </div>
      <ShardBoard
        :run-id="selected.runId"
        :project-id="projectId"
        @changed="refreshAfterShardChange"
      />
      <!-- 执行过程时间线（W4）：run / 分片的全部时间点合并成一条事件流 -->
      <RunTimeline :run-id="selected.runId" :project-id="projectId" />
      <pre v-if="visibleLines.length" ref="logPanel" class="log-lines">
<span
  v-for="line in visibleLines"
  :key="`${line.seq}-${line.tsGen}`"
  class="log-line"
  :class="`level-${line.level.toLowerCase()}`"
>{{ line.tsGen }} [{{ line.source }}] {{ line.text }}
</span></pre>
      <p v-else class="muted">
        {{ t(enabledLevels.length < 3 ? 'execution.logFilteredEmpty' : 'execution.emptyLog') }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.form-card {
  margin-bottom: 16px;
}

.form-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.form-head .card-title {
  margin: 0;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-3) var(--space-4);
}

.form-actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-4);
}

.log-card {
  margin-top: 16px;
}

.log-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.log-head .card-title {
  margin: 0;
}

.only-fail {
  margin-left: auto;
}

.run-progress {
  padding: 10px 0 12px;
}

.progress-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  font-size: 12px;
}

.progress-phase {
  font-weight: 600;
  color: var(--accent);
}

.progress-last {
  max-width: 46%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.progress-track {
  position: relative;
  overflow: hidden;
  height: 6px;
  margin-top: 6px;
  border-radius: 3px;
  background: var(--surface-2);
}

.progress-fill {
  height: 100%;
  width: 0;
  border-radius: 3px;
  background: var(--accent);
  transition: width 0.4s ease;
}

/* 总数未知：只做前后扫动，不假装知道还剩多少 */
.progress-track.indeterminate .progress-fill {
  width: 30%;
  animation: progress-sweep 1.4s ease-in-out infinite;
}

.progress-track.failed .progress-fill {
  background: var(--fail);
}

@keyframes progress-sweep {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(340%); }
}

.log-lines {
  max-height: 420px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: var(--surface-2);
  color: var(--text);
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.log-line {
  display: block;
}

.level-warn {
  color: var(--warn);
}

.level-error {
  color: var(--fail);
}

/*
 * 当前打开日志的那一行：只加底色，不加边框 —— 边框会和表格自身的分隔线打架。
 * 用 accent-soft 而不是 surface-selected：后者是固定的中性蓝，换成金色/翡翠绿预设时
 * 会和整站强调色对不上；accent-soft 本来就是这个项目「选中」的统一语义色。
 */
:deep(.row-active td) {
  background: var(--accent-soft);
}

/* 操作列两个按钮并排时不能贴在一起；h() 渲染的节点拿不到 scoped 类，靠 :deep 兜 */
:deep(.row-actions) {
  display: flex;
  gap: var(--space-2);
}
.progress-stalled {
  color: var(--warn);
  font-weight: 600;
}

.precheck-dialog {
  width: min(560px, 92vw);
  background: var(--panel);
  border-radius: 12px;
  padding: 20px 24px;
  box-shadow: var(--shadow);
}

.precheck-group {
  margin-top: 12px;
  padding: 8px 12px;
  border-left: 3px solid var(--border);
  border-radius: 6px;
  background: var(--surface-2);
}

.precheck-group.is-block {
  border-left-color: var(--fail);
}

.precheck-group.is-warn {
  border-left-color: var(--warn);
}

.precheck-group.is-ok {
  border-left-color: var(--ok);
}

.precheck-group__title {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
}

.precheck-group ul {
  margin: 0;
  padding-left: 18px;
}

.precheck-group li {
  margin-bottom: 6px;
  font-size: 13px;
}

.precheck-hint {
  margin: 2px 0 0;
  color: var(--text-muted);
  font-size: 12px;
}

.precheck-blocked-hint {
  margin: 12px 0 0;
  color: var(--fail);
  font-size: 13px;
}

.precheck-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}

</style>
