<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  NButton,
  NCheckbox,
  NRadioButton,
  NRadioGroup,
  NSelect,
  type DataTableColumns,
} from 'naive-ui'
import {
  REPORT_METADATA_KEYS,
  reportsApi,
  type CompareBaseline,
  type CompareResult,
  type CompareRow,
  type CompareSide,
  type CaseTrendResult,
  type PassRateResult,
  type ReportDetail,
  type ReportExportFormat,
  type ReportExportHistoryItem,
  type ReportExportMode,
  type ReportExportResult,
  type ReportLogLine,
  type ReportSuiteNode,
  type ReportSummary,
} from '@/api/reports'
import { useAuthStore } from '@/stores/auth'
import DataTable from '@/components/DataTable.vue'
import SuiteTree from '@/components/report/SuiteTree.vue'
import TrendChart from '@/components/report/TrendChart.vue'
import type { TrendChartSeries } from '@/components/report/trendChart'
import ErrorLineView from '@/components/ErrorLine.vue'
import EmptyState from '@/components/EmptyState.vue'
import ReportSummaryCard from '@/components/ReportSummaryCard.vue'
import AiAnswerCard from '@/components/AiAnswerCard.vue'
import { aiWorkbenchApi, type AiResult } from '@/api/aiWorkbench'
import StatusTag from '@/components/StatusTag.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { formatBytes, formatDateTime, formatDuration } from '@/utils/format'
import { verdictTone, type StatusTone } from '@/utils/statusLabels'
import { confirmAction } from '@/composables/useConfirm'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const projectId = computed(() => auth.projectId ?? 0)
const runId = computed(() => String(route.params.runId ?? ''))

const detail = ref<ReportDetail | null>(null)
const tree = ref<ReportSuiteNode | null>(null)
const loading = ref(false)
const error = ref<ErrorLine | null>(null)

// 速览（FR-3.1-07）：独立请求，失败不拖垮详情；数字直接来自后端聚合。
const summary = ref<ReportSummary | null>(null)
const summaryLoading = ref(false)
const summaryError = ref<ErrorLine | null>(null)

// v3.3.0 报告叙述：按需生成，不阻塞首屏；事实/推测由后端分段。
const narrative = ref<AiResult | null>(null)
const narrativeLoading = ref(false)
const narrativeError = ref('')

const showCompare = ref(false)
const compare = ref<CompareResult | null>(null)
const compareLoading = ref(false)
const compareError = ref<ErrorLine | null>(null)
/** 手动基线（RPT-03 补口）：'' 表示自动基线，其余是同环境候选的 RUN_ID。 */
const baselineCandidates = ref<CompareBaseline[]>([])
const selectedBaseline = ref('')
const candidatesLoading = ref(false)

// 趋势区块（RPT-04）：用例维度 + 环境断线 + 自写 SVG 折线。
const trend = ref<CaseTrendResult | null>(null)
const trendLoading = ref(false)
const trendError = ref<ErrorLine | null>(null)
const selectedCase = ref('')
const granularity = ref<'day' | 'week'>('day')
const passRate = ref<PassRateResult | null>(null)
const passRateLoading = ref(false)
const passRateError = ref<ErrorLine | null>(null)

const logOpen = ref(false)
const logCaseKey = ref('')
const logLines = ref<ReportLogLine[]>([])
const logTotal = ref(0)
const nextOffset = ref<number | null>(0)
const logLoading = ref(false)
const logError = ref<ErrorLine | null>(null)

// 导出（RPT-05）：外部版受 exportable 门禁；内部版允许缺项但需二次确认。
const exportBusy = ref('')
const exportError = ref<ErrorLine | null>(null)
const exportResult = ref<ReportExportResult | null>(null)
const exportHistory = ref<ReportExportHistoryItem[]>([])
const exportHistoryError = ref<ErrorLine | null>(null)

/** 外部导出被门禁挡住时的提示（按钮 title 与下方说明共用一份文案）。 */
const externalHint = computed(() =>
  detail.value && !detail.value.exportable
    ? t('reports.exportSection.missingHint', { keys: detail.value.metadataMissing.join(', ') })
    : '',
)


function metadataValue(key: string): string {
  const value = detail.value?.metadata?.[key]
  return value ?? ''
}

function metadataMissing(key: string): boolean {
  return metadataValue(key).trim().length === 0
}

async function loadAll(): Promise<void> {
  error.value = null
  if (!projectId.value || !runId.value) {
    detail.value = null
    tree.value = null
    summary.value = null
    summaryLoading.value = false
    return
  }
  loading.value = true
  void loadSummary()
  try {
    const [d, tr] = await Promise.all([
      reportsApi.detail(projectId.value, runId.value),
      reportsApi.tree(projectId.value, runId.value),
    ])
    detail.value = d
    tree.value = tr
    await loadPassRate()
    await loadExports()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
}

async function loadSummary(): Promise<void> {
  summaryError.value = null
  if (!projectId.value || !runId.value) {
    summary.value = null
    summaryLoading.value = false
    return
  }
  summaryLoading.value = true
  try {
    summary.value = await reportsApi.summary(projectId.value, runId.value)
  } catch (e) {
    summary.value = null
    summaryError.value = localizeError(e)
  } finally {
    summaryLoading.value = false
  }
}

/** 锚点到失败明细；尊重 reduced-motion 偏好，并把焦点一并送过去。 */
function scrollToFailures(): void {
  const target = document.getElementById('report-failures')
  if (!target) return
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
  target.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' })
  target.focus({ preventScroll: true })
}

/** AI 叙述（FR-3.3-05）：按需触发，数字仍由后端证据装配器校验，前端不做二次加工。 */
async function loadNarrative(): Promise<void> {
  if (!projectId.value || !runId.value) return
  narrativeLoading.value = true
  narrativeError.value = ''
  try {
    narrative.value = await aiWorkbenchApi.narrative(projectId.value, runId.value)
  } catch (error) {
    narrative.value = null
    narrativeError.value = error instanceof Error ? error.message : t('reportAi.failed')
  } finally {
    narrativeLoading.value = false
  }
}

async function loadCompare(): Promise<void> {
  compareError.value = null
  compareLoading.value = true
  try {
    compare.value = await reportsApi.compare(
      projectId.value,
      runId.value,
      selectedBaseline.value || undefined,
    )
  } catch (e) {
    compareError.value = localizeError(e)
  } finally {
    compareLoading.value = false
  }
}

/**
 * 手动基线候选（RPT-03 补口）：拉取失败只让下拉剩「自动」一项，
 * 不把对比本身一起判红 —— 没有候选不代表对比做不了。
 */
async function loadBaselineCandidates(): Promise<void> {
  candidatesLoading.value = true
  try {
    baselineCandidates.value = await reportsApi.baselineCandidates(projectId.value, runId.value)
  } catch {
    baselineCandidates.value = []
  } finally {
    candidatesLoading.value = false
  }
}

const baselineOptions = computed(() =>
  ([{ label: t('reports.compare.baselineAuto'), value: '' }]).concat(
    baselineCandidates.value.map((item) => {
      const base = item.startedAt
        ? t('reports.compare.baselineOption', { runId: item.runId, at: formatDateTime(item.startedAt) })
        : item.runId
      // N4：跨环境候选必须在下拉里就看得见，别让用户选完才发现环境不同
      return {
        label: item.crossEnvironment ? `${base} · ${t('reports.compare.crossEnvironment')}` : base,
        value: item.runId,
      }
    }),
  ),
)

/** `NSelect` 的 value 是 `string | number | null`，收敛成字符串再落 ref。 */
function selectBaseline(value: string | number | null): void {
  selectedBaseline.value = value == null ? '' : String(value)
  void loadCompare()
}

/** 对比开关：换成 `NCheckbox` 后拿到的是布尔值，不再从 `Event` 里刨 `checked`。 */
async function toggleCompare(checked: boolean): Promise<void> {
  showCompare.value = checked
  if (showCompare.value && !compare.value) {
    await Promise.all([loadBaselineCandidates(), loadCompare()])
  }
}

/** 本次运行里出现的全部 caseKey（趋势选择器的数据源）。 */
const caseKeys = computed(() => {
  const keys: string[] = []
  function walk(node: ReportSuiteNode): void {
    for (const item of node.cases) keys.push(item.caseKey)
    for (const child of node.suites) walk(child)
  }
  if (tree.value) walk(tree.value)
  return Array.from(new Set(keys))
})

const currentTrendPoint = computed(
  () => trend.value?.points.find((point) => point.runId === runId.value) ?? null,
)

/** 趋势用例下拉的选项。`NSelect` 要 `{label,value}` 而不是裸字符串数组。 */
const caseOptions = computed(() => caseKeys.value.map((key) => ({ label: key, value: key })))

/**
 * 下面两个 setter 而不是 `v-model`：`NSelect` / `NRadioGroup` 的 `value` 类型是
 * `string | number | null`，直接 `v-model` 到 `ref<string>` / `ref<'day'|'week'>`
 * 上会被 vue-tsc 判成不兼容赋值。收敛点写在一处，别在模板里撒 `as`。
 */
function selectCase(value: string | null): void {
  selectedCase.value = value ?? ''
}

function setGranularity(value: string): void {
  granularity.value = value === 'week' ? 'week' : 'day'
}

/** 全局时间顺序：用 points 的升序下标做横轴，避免各环境各自从 0 开始错位。 */
const pointOrder = computed(() => {
  const order = new Map<string, number>()
  const points = trend.value?.points ?? []
  points.forEach((point, index) => order.set(point.runId, index))
  return order
})

/** 耗时折线：后端已按 envKey 分组，组内按时间升序；环境变化天然断开。 */
const durationSeries = computed<TrendChartSeries[]>(() =>
  (trend.value?.series ?? []).map((series) => ({
    key: series.envKey,
    label: series.envKey,
    points: series.points.map((point, index) => ({
      id: point.runId,
      label: point.runId,
      x: pointOrder.value.get(point.runId) ?? index,
      y: point.durationMs,
      deviated: point.deviated,
    })),
  })),
)

/** 通过率折线：bucket 为横轴，passRate 换算成百分比。 */
const passRateSeries = computed<TrendChartSeries[]>(() => {
  const buckets = passRate.value?.buckets ?? []
  if (buckets.length === 0) return []
  return [
    {
      key: 'pass-rate',
      label: t('reports.trend.passRateTitle'),
      points: buckets.map((bucket, index) => ({
        id: bucket.bucket,
        label: `${bucket.bucket} · ${bucket.pass}/${bucket.total}`,
        x: index,
        y: bucket.passRate * 100,
        deviated: false,
      })),
    },
  ]
})

async function loadTrend(): Promise<void> {
  trendError.value = null
  if (!selectedCase.value || !projectId.value || !runId.value) {
    trend.value = null
    return
  }
  trendLoading.value = true
  try {
    trend.value = await reportsApi.trend(projectId.value, runId.value, selectedCase.value, 5)
  } catch (e) {
    trendError.value = localizeError(e)
  } finally {
    trendLoading.value = false
  }
}

async function loadPassRate(): Promise<void> {
  passRateError.value = null
  if (!projectId.value) {
    passRate.value = null
    return
  }
  passRateLoading.value = true
  try {
    passRate.value = await reportsApi.passRate(projectId.value, granularity.value, 12)
  } catch (e) {
    passRateError.value = localizeError(e)
  } finally {
    passRateLoading.value = false
  }
}

function openRun(targetRunId: string): void {
  if (!targetRunId || targetRunId === runId.value) return
  void router.push({ name: 'report-detail', params: { runId: targetRunId } })
}

async function openLogs(caseKey: string): Promise<void> {
  if (logOpen.value && logCaseKey.value === caseKey) {
    logOpen.value = false
    return
  }
  logOpen.value = true
  logCaseKey.value = caseKey
  logLines.value = []
  logTotal.value = 0
  nextOffset.value = 0
  logError.value = null
  await loadMoreLogs()
}

async function loadMoreLogs(): Promise<void> {
  if (nextOffset.value === null) return
  logError.value = null
  logLoading.value = true
  try {
    const page = await reportsApi.logs(
      projectId.value,
      runId.value,
      logCaseKey.value,
      nextOffset.value,
      200,
    )
    // 增量追加：只把已加载段放进 DOM，未请求的历史行不入内存。
    logLines.value = [...logLines.value, ...page.lines]
    logTotal.value = page.total
    nextOffset.value = page.nextOffset
  } catch (e) {
    logError.value = localizeError(e)
  } finally {
    logLoading.value = false
  }
}

/**
 * 「本轮 vs 基线」里真正的语义是**两轮都是 FAIL**（持续失败），但后端 `changeOf`
 * 把它归到 `SAME`（因为它只比较「变没变」）。直接显示「无变化」会被读成「通过且没变」，
 * 于是这里单独给一条 `BOTH_FAIL` 文案 —— 对比表存在的意义就是让人一眼看出哪些在持续坏。
 */
function bothFail(row: CompareRow): boolean {
  return row.history?.verdict === 'FAIL' && row.current?.verdict === 'FAIL'
}

function changeLabel(row: CompareRow): string {
  return bothFail(row)
    ? t('reports.compare.changes.BOTH_FAIL')
    : t(`reports.compare.changes.${row.change}`)
}

/**
 * 变化 → 色档。持续失败用 warn 而不是 fail：它本轮没有**新**坏，
 * 与刚回归的那条（fail）要分开，否则表里「本次新坏」和「一直都坏」同色，看不出增量。
 */
function changeTone(row: CompareRow): StatusTone {
  if (bothFail(row)) return 'warn'
  switch (row.change) {
    case 'PASS_TO_FAIL':
      return 'fail'
    case 'FAIL_TO_PASS':
      return 'ok'
    default:
      return 'neutral'
  }
}

/** 对比表的一侧：结论用徽标（图标 + 文字），耗时留在后面一行读完。 */
function sideCell(side: CompareSide | null, emptyText: string) {
  if (!side) return h('span', { class: 'muted' }, emptyText)
  return h('div', { class: 'verdict-cell' }, [
    h(StatusTag, { label: side.verdict, tone: verdictTone(side.verdict) }),
    h('span', { class: 'muted' }, formatDuration(side.durationMs)),
  ])
}

/**
 * 导出历史列（UX-04b 逐页迁移）。
 *
 * 「版本」列用徽标而不是红字：内部版是**适用范围提示**，不是错误 —— 之前用 `text-fail`
 * 的红色，读起来像「导出失败了」。改成 warn 档 + 图标后，一眼能区分「这是内部版」和
 * 「这次导出有问题」（后者走 `ErrorLineView`）。
 */
const exportHistoryColumns = computed<DataTableColumns<ReportExportHistoryItem>>(() => [
  {
    title: t('reports.exportSection.historyFormat'),
    key: 'format',
    width: 110,
    render: (row) => row.format.toUpperCase(),
  },
  {
    title: t('reports.exportSection.historyMode'),
    key: 'mode',
    width: 120,
    render: (row) =>
      h(StatusTag, {
        label:
          row.mode === 'internal'
            ? t('reports.exportSection.internalTag')
            : t('reports.exportSection.externalTag'),
        tone: row.mode === 'internal' ? 'warn' : 'ok',
      }),
  },
  {
    title: t('reports.exportSection.historySize'),
    key: 'bytes',
    width: 140,
    render: (row) => formatBytes(row.bytes),
  },
  {
    title: t('reports.exportSection.historyTime'),
    key: 'createdAt',
    width: 190,
    render: (row) => formatDateTime(row.createdAt),
  },
  {
    title: '',
    key: 'action',
    width: 110,
    render: (row) =>
      h(
        NButton,
        {
          text: true,
          type: 'primary',
          onClick: () => void saveExport(exportDownloadUrl(row), row.fileName),
        },
        { default: () => t('reports.exportSection.historyDownload') },
      ),
  },
])

/** 对比表列：用例 / 历史（基线） / 本轮 / 变化。 */
const compareColumns = computed<DataTableColumns<CompareRow>>(() => [
  { title: t('reports.case'), key: 'caseKey', minWidth: 240 },
  {
    title: t('reports.compare.history'),
    key: 'history',
    width: 200,
    render: (row) => sideCell(row.history, t('reports.compare.noneBaseline')),
  },
  {
    title: t('reports.compare.current'),
    key: 'current',
    width: 200,
    render: (row) => sideCell(row.current, t('reports.compare.noneThisRun')),
  },
  {
    title: t('reports.compare.change'),
    key: 'change',
    width: 140,
    render: (row) => h(StatusTag, { label: changeLabel(row), tone: changeTone(row) }),
  },
])

function backToList(): void {
  void router.push({ name: 'reports' })
}

async function loadExports(): Promise<void> {
  exportHistoryError.value = null
  if (!projectId.value || !runId.value) {
    exportHistory.value = []
    return
  }
  try {
    exportHistory.value = await reportsApi.exports(projectId.value, runId.value)
  } catch (e) {
    exportHistoryError.value = localizeError(e)
  }
}

/** 外部导出：缺项时只提示并列出缺失键，不发请求；内部导出：二次确认后允许。 */
async function doExport(mode: ReportExportMode, format: ReportExportFormat): Promise<void> {
  if (!detail.value || exportBusy.value) return
  if (mode === 'external' && !detail.value.exportable) {
    return
  }
  if (mode === 'internal') {
    const keys = detail.value.metadataMissing.join(', ')
    const message = keys
      ? t('reports.exportSection.internalConfirm')
      : t('reports.exportSection.internalConfirmNoMissing')
    // 「允许缺项导出」是二次确认而非破坏性操作，用默认语气
    if (!(await confirmAction(message))) return
  }

  exportBusy.value = `${mode}-${format}`
  exportError.value = null
  try {
    exportResult.value = await reportsApi.exportReport(
      projectId.value,
      runId.value,
      mode,
      format,
    )
    await loadExports()
  } catch (e) {
    exportError.value = localizeError(e)
  } finally {
    exportBusy.value = ''
  }
}

async function saveExport(downloadUrl?: string, fileName?: string): Promise<void> {
  const url = downloadUrl ?? exportResult.value?.downloadUrl
  if (!url) return
  exportError.value = null
  try {
    const blob = await reportsApi.downloadExport(url)
    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download =
      fileName ??
      `report_${runId.value}_${exportResult.value?.mode ?? 'external'}.` +
        `${exportResult.value?.format ?? 'pdf'}`
    link.click()
    URL.revokeObjectURL(objectUrl)
  } catch (e) {
    exportError.value = localizeError(e)
  }
}

function exportDownloadUrl(item: ReportExportHistoryItem): string {
  return `/api/exports/${item.exportId}/download?projectId=${projectId.value}`
}

watch([projectId, runId], () => {
  summary.value = null
  summaryError.value = null
  summaryLoading.value = false
  compare.value = null
  showCompare.value = false
  baselineCandidates.value = []
  selectedBaseline.value = ''
  logOpen.value = false
  trend.value = null
  passRate.value = null
  selectedCase.value = ''
  exportResult.value = null
  exportError.value = null
  exportHistory.value = []
  void loadAll()
}, { immediate: true })

// 树加载后默认选中第一条用例；换运行/换项目时重新取默认值。
watch(caseKeys, (keys) => {
  if (!keys.includes(selectedCase.value)) {
    selectedCase.value = keys[0] ?? ''
  }
}, { immediate: true })

watch(selectedCase, () => {
  void loadTrend()
})

watch(granularity, () => {
  void loadPassRate()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <NButton text type="primary" class="back-link" @click="backToList">
          ← {{ t('reports.backToList') }}
        </NButton>
        <h1 class="page-title">{{ runId }}</h1>
      </div>
    </div>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="loading" class="muted">{{ t('common.loading') }}</p>

    <template v-if="detail">
      <ReportSummaryCard
        :summary="summary"
        :loading="summaryLoading"
        :error="summaryError?.summary ?? ''"
        @retry="loadSummary"
        @show-failures="scrollToFailures"
      />

      <section class="card wide-card" data-testid="report-ai-narrative">
        <div class="toolbar narrative-bar">
          <span class="card-title">{{ t('reportAi.title') }}</span>
          <NButton size="small" :loading="narrativeLoading" @click="loadNarrative">
            {{ narrative ? t('reportAi.regenerate') : t('reportAi.generate') }}
          </NButton>
        </div>
        <p v-if="!narrative && !narrativeLoading && !narrativeError" class="muted">
          {{ t('reportAi.hint') }}
        </p>
        <AiAnswerCard
          v-else
          :result="narrative"
          :loading="narrativeLoading"
          :error="narrativeError"
        />
      </section>

      <div class="toolbar summary-bar">
        <span class="text-ok">PASS {{ detail.passCount }}</span>
        <span class="text-fail">FAIL {{ detail.failCount }}</span>
        <span class="muted">SKIP {{ detail.skipCount }}</span>
        <span v-if="detail.exportable" class="text-ok">{{ t('reports.exportable') }}</span>
        <span v-else class="text-fail">
          {{ t('reports.notExportable') }}：{{ detail.metadataMissing.join(', ') }}
        </span>
      </div>

      <section class="card wide-card">
        <h2 class="card-title">{{ t('reports.metadataTitle') }}</h2>
        <div class="table-scroll">
          <table class="table meta-table">
            <tbody>
              <tr v-for="key in REPORT_METADATA_KEYS" :key="key">
                <th>{{ key }}</th>
                <td v-if="metadataMissing(key)" class="text-fail">
                  {{ t('reports.missingValue') }}
                </td>
                <td v-else>{{ metadataValue(key) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <div class="toolbar export-bar">
        <span class="muted export-title">{{ t('reports.exportSection.title') }}</span>
        <NButton
          type="primary"
          :disabled="!detail.exportable || Boolean(exportBusy)"
          :loading="exportBusy === 'external-pdf'"
          :title="externalHint"
          @click="doExport('external', 'pdf')"
        >
          {{
            exportBusy === 'external-pdf'
              ? t('reports.exportSection.exporting')
              : t('reports.exportSection.externalPdf')
          }}
        </NButton>
        <NButton
          type="primary"
          :disabled="!detail.exportable || Boolean(exportBusy)"
          :loading="exportBusy === 'external-docx'"
          :title="externalHint"
          @click="doExport('external', 'docx')"
        >
          {{
            exportBusy === 'external-docx'
              ? t('reports.exportSection.exporting')
              : t('reports.exportSection.externalWord')
          }}
        </NButton>
        <NButton
          :disabled="Boolean(exportBusy)"
          :loading="exportBusy === 'internal-pdf'"
          @click="doExport('internal', 'pdf')"
        >
          {{
            exportBusy === 'internal-pdf'
              ? t('reports.exportSection.exporting')
              : t('reports.exportSection.internalPdf')
          }}
        </NButton>
        <NButton
          :disabled="Boolean(exportBusy)"
          :loading="exportBusy === 'internal-docx'"
          @click="doExport('internal', 'docx')"
        >
          {{
            exportBusy === 'internal-docx'
              ? t('reports.exportSection.exporting')
              : t('reports.exportSection.internalWord')
          }}
        </NButton>
      </div>
      <p v-if="!detail.exportable" class="text-fail export-hint">
        {{ externalHint }}
      </p>
      <ErrorLineView v-if="exportError" :error="exportError" />
      <p v-if="exportResult" class="text-ok export-result">
        {{
          t('reports.exportSection.done', {
            format: exportResult.format.toUpperCase(),
            size: formatBytes(exportResult.bytes),
            sha256: exportResult.sha256,
          })
        }}
        <NButton text type="primary" @click="saveExport()">
          {{ t('reports.exportSection.download') }}
        </NButton>
      </p>
      <p
        v-if="exportResult?.qualityGate && !exportResult.qualityGate.passed"
        class="text-fail export-hint"
        data-test="export-draft-hint"
      >
        {{ t('reports.exportSection.draftHint', { missing: exportResult.qualityGate.missing.join('；') }) }}
      </p>

      <section class="card wide-card">
        <h2 class="card-title">{{ t('reports.exportSection.historyTitle') }}</h2>
        <ErrorLineView v-if="exportHistoryError" :error="exportHistoryError" />
        <p v-else-if="!exportHistory.length" class="muted">
          {{ t('reports.exportSection.historyEmpty') }}
        </p>
        <DataTable
          v-else
          :columns="exportHistoryColumns"
          :data="exportHistory"
          :row-key="(row: ReportExportHistoryItem) => row.exportId"
          :error="Boolean(exportHistoryError)"
        />
      </section>

      <section class="card wide-card">
        <div class="section-head">
          <h2 class="card-title">{{ t('reports.trend.title') }}</h2>
          <label class="trend-case muted">
            {{ t('reports.trend.case') }}
            <NSelect
              class="trend-select"
              size="small"
              :value="selectedCase"
              :options="caseOptions"
              @update:value="selectCase"
            />
          </label>
        </div>

        <ErrorLineView v-if="trendError" :error="trendError" />
        <p v-if="trendLoading" class="muted">{{ t('common.loading') }}</p>

        <template v-else-if="trend">
          <div class="toolbar trend-hints">
            <span class="muted">{{ t('reports.trend.envHint') }}</span>
            <span class="muted">{{ t('reports.trend.currentHint') }}</span>
          </div>

          <h3 class="card-title trend-subtitle">{{ t('reports.trend.durationTitle') }}</h3>
          <p
            v-if="trend.baselineMs !== null"
            :class="trend.deviated ? 'trend-deviated' : 'muted'"
          >
            {{
              trend.deviated && currentTrendPoint
                ? t('reports.trend.deviated', {
                    ms: currentTrendPoint.durationMs,
                    base: trend.baselineMs,
                  })
                : t('reports.trend.baseline', { ms: trend.baselineMs })
            }}
          </p>
          <TrendChart
            :series="durationSeries"
            :baseline="trend.baselineMs"
            unit="ms"
            clickable
            :current-id="runId"
            @select="openRun"
          />

          <div class="pass-rate-block">
            <div class="section-head">
              <h3 class="card-title">{{ t('reports.trend.passRateTitle') }}</h3>
              <NRadioGroup
                :value="granularity"
                size="small"
                @update:value="setGranularity"
              >
                <NRadioButton value="day">{{ t('reports.trend.day') }}</NRadioButton>
                <NRadioButton value="week">{{ t('reports.trend.week') }}</NRadioButton>
              </NRadioGroup>
            </div>
            <ErrorLineView v-if="passRateError" :error="passRateError" />
            <p v-if="passRateLoading" class="muted">{{ t('common.loading') }}</p>
            <TrendChart v-else :series="passRateSeries" unit="%" />
          </div>
        </template>
        <EmptyState v-else :title="t('reports.trend.empty')" icon="reports" />
      </section>

      <section id="report-failures" class="card wide-card" tabindex="-1">
        <div class="section-head">
          <h2 class="card-title">{{ t('reports.treeTitle') }}</h2>
          <NCheckbox :checked="showCompare" @update:checked="toggleCompare">
            {{ t('reports.compare.toggle') }}
          </NCheckbox>
        </div>

        <SuiteTree v-if="tree" :node="tree" :depth="0" @open-logs="openLogs" />
        <p v-else class="muted">{{ t('reports.treeEmpty') }}</p>

        <div v-if="logOpen" class="log-panel">
          <div class="section-head">
            <h3 class="card-title">{{ t('reports.logTitle') }} · {{ logCaseKey }}</h3>
            <span class="muted">
              {{ t('reports.logTotal', { total: logTotal, loaded: logLines.length }) }}
            </span>
          </div>
          <ErrorLineView v-if="logError" :error="logError" />
          <pre v-if="logLines.length" class="log-lines"><span
  v-for="line in logLines"
  :key="line.seq"
  class="log-line"
  :class="`level-${line.level.toLowerCase()}`"
>{{ line.seq }} [{{ line.keyword }}] {{ line.message }}
</span></pre>
          <p v-else class="muted">{{ t('reports.logEmpty') }}</p>
          <div class="toolbar">
            <NButton
              v-if="nextOffset !== null"
              :loading="logLoading"
              @click="loadMoreLogs"
            >
              {{ logLoading ? t('common.loading') : t('reports.loadMore') }}
            </NButton>
          </div>
        </div>
      </section>

      <section v-if="showCompare" class="card wide-card">
        <div class="section-head">
          <h2 class="card-title">{{ t('reports.compare.title') }}</h2>
          <label class="baseline-picker muted">
            {{ t('reports.compare.baselineLabel') }}
            <NSelect
              class="baseline-select"
              size="small"
              :value="selectedBaseline"
              :options="baselineOptions"
              :loading="candidatesLoading"
              :placeholder="t('reports.compare.baselineAuto')"
              @update:value="selectBaseline"
            />
          </label>
          <span v-if="compare?.baseline" class="muted">
            {{ t('reports.compare.baseline', { runId: compare.baseline.runId }) }}
          </span>
          <StatusTag
            v-if="compare?.crossEnvironment"
            tone="warn"
            :label="t('reports.compare.crossEnvironment')"
          />
          <span v-if="compare?.crossEnvironment" class="muted">
            {{ t('reports.compare.crossEnvironmentHint') }}
          </span>
          <span v-else-if="compare" class="text-fail">{{ t('reports.compare.noBaseline') }}</span>
        </div>

        <ErrorLineView v-if="compareError" :error="compareError" />
        <p v-if="compareLoading" class="muted">{{ t('common.loading') }}</p>

        <template v-if="compare">
          <div class="toolbar summary-bar">
            <StatusTag
              tone="warn"
              :label="t('reports.compare.summary.bothFail', { count: compare.summary.bothFail })"
            />
            <StatusTag
              tone="fail"
              :label="t('reports.compare.summary.newFail', { count: compare.summary.newFail })"
            />
            <StatusTag
              tone="ok"
              :label="t('reports.compare.summary.newPass', { count: compare.summary.newPass })"
            />
            <StatusTag
              tone="neutral"
              :label="t('reports.compare.summary.notRun', { count: compare.summary.notRun })"
            />
          </div>

          <DataTable
            :columns="compareColumns"
            :data="compare.rows"
            :row-key="(row: CompareRow) => row.caseKey"
            :error="Boolean(compareError)"
          />
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.summary-bar {
  gap: 16px;
}

.meta-table th {
  width: 140px;
  color: var(--text-muted);
  font-weight: 500;
}

.trend-case {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

/* NSelect 不给宽度时按父容器撑满，这里固定成能放下完整 caseKey 的宽度 */
.trend-select {
  width: 220px;
}

.baseline-picker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

/* 手动基线下拉：与趋势下拉同宽，RUN_ID + 时间放不下时由 NSelect 自身省略 */
.baseline-select {
  width: 220px;
}

.trend-hints {
  gap: 16px;
}

.trend-subtitle {
  margin: 12px 0 6px;
}

.trend-deviated {
  color: var(--warn);
}

.pass-rate-block {
  margin-top: 18px;
  border-top: 1px solid var(--border);
  padding-top: 10px;
}

.log-panel {
  margin-top: 14px;
  border-top: 1px solid var(--border);
  padding-top: 10px;
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

.level-error,
.level-fail {
  color: var(--fail);
}

/* 对比表的一侧：徽标 + 耗时同排。徽标自身是 inline-flex，这里只负责两者的间距 */
.verdict-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3);
}

/* 返回列表 / 下载这类文字按钮换成 NButton 后需要保留原来的左对齐外观 */
.back-link {
  margin-bottom: var(--space-1);
}
</style>
