<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { NButton, type DataTableColumns } from 'naive-ui'
import {
  digestApi,
  overviewApi,
  type CountItem,
  type DigestFailedCase,
  type DigestReportRow,
  type DigestTrendPoint,
  type OverviewRecentRun,
  type ProjectOverview,
  type ReportDigest,
} from '@/api/overview'
import { useAuthStore } from '@/stores/auth'
import DataTable from '@/components/DataTable.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import OnboardingChecklist from '@/components/OnboardingChecklist.vue'
import StatusTag from '@/components/StatusTag.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { EMPTY, formatDateTime, formatDuration, formatPercent } from '@/utils/format'
import {
  reviewStatusLabel,
  runStatusLabel,
  runStatusTone,
  triggerTypeLabel,
} from '@/utils/statusLabels'

/**
 * 项目总览（OVW-01）。
 *
 * 这里只负责「把后端算好的数展示出来」，任何比例/合计都不在前端重算——
 * 前后端各算一套是同一份数据出现两个数的最常见原因。
 *
 * 空态是这个页面的正事：真实新项目就是 0 用例 0 运行，如果照常渲染一排 0 和 0%，
 * 用户会以为坏了。所以每个区块都有「下一步点哪里」的引导。
 */
const { t } = useI18n()
const auth = useAuthStore()

const projectId = computed(() => auth.projectId ?? 0)
const data = ref<ProjectOverview | null>(null)
const digest = ref<ReportDigest | null>(null)
const digestError = ref<ErrorLine | null>(null)
const loading = ref(false)
const error = ref<ErrorLine | null>(null)

const BAR_HEIGHT = 110

const trendMax = computed(() => {
  if (!data.value) return 1
  const max = Math.max(
    1,
    ...data.value.dailyTrend.map((day) => day.finished + day.failed + day.canceled),
  )
  return max
})

const hasRuns = computed(() => (data.value?.runs.total ?? 0) > 0)
const hasFinishedRuns = computed(() => (data.value?.runs.finished ?? 0) > 0)

async function load(): Promise<void> {
  error.value = null
  digestError.value = null
  if (!projectId.value) {
    data.value = null
    digest.value = null
    return
  }
  loading.value = true
  // 两个接口互不依赖：总览挂了不该把报告结果一起清空，反之亦然。
  const [overviewResult, digestResult] = await Promise.allSettled([
    overviewApi.load(projectId.value),
    digestApi.load(projectId.value),
  ])
  if (overviewResult.status === 'fulfilled') {
    data.value = overviewResult.value
  } else {
    error.value = localizeError(overviewResult.reason)
    data.value = null
  }
  if (digestResult.status === 'fulfilled') {
    digest.value = digestResult.value
  } else {
    digest.value = null
    digestError.value = localizeError(digestResult.reason)
  }
  loading.value = false
}

// immediate 不能省：projectId 来自 localStorage，进页面时通常已经是目标值，
// 不加 immediate 时 watch 不触发，load() 永不执行——页面会一直停在「加载中」。
watch(projectId, () => void load(), { immediate: true })

/**
 * 三张表的列定义（UX-04b 逐页迁移）。
 *
 * 状态列原来是一个按 class 上色的 `<span>`（`badge verdict-pass`），**只有颜色没有图标**：
 * 六预设里 crimson/gold 与 fail/warn 同色相，色觉障碍下等于没有状态信息。
 * 统一换成 `StatusTag`（图标 + 文字双编码），与执行页/报告页口径一致。
 */
const digestReportColumns = computed<DataTableColumns<DigestReportRow>>(() => [
  {
    title: t('overview.digest.runId'),
    key: 'runId',
    width: 220,
    ellipsis: { tooltip: true },
    render: (row) => h(RouterLink, { to: `/reports/${row.runId}` }, () => row.runId),
  },
  {
    title: t('overview.digest.state'),
    key: 'state',
    width: 120,
    render: (row) => h(StatusTag, { label: runStatusLabel(row.state), tone: runStatusTone(row.state) }),
  },
  {
    title: t('overview.digest.finishedAt'),
    key: 'finishedAt',
    width: 180,
    render: (row) => formatDateTime(row.finishedAt),
  },
  {
    title: t('overview.digest.cases'),
    key: 'cases',
    width: 140,
    render: (row) => `${row.pass} / ${row.fail} / ${row.skip}`,
  },
  {
    title: t('overview.digest.passRate'),
    key: 'passRateBp',
    width: 110,
    render: (row) => formatPercent(row.passRateBp),
  },
])

const digestFailedColumns = computed<DataTableColumns<DigestFailedCase>>(() => [
  { title: t('overview.digest.caseKey'), key: 'caseKey', minWidth: 200, ellipsis: { tooltip: true } },
  { title: t('overview.digest.failures'), key: 'failures', width: 120 },
  {
    title: t('overview.digest.lastFailedAt'),
    key: 'lastFailedAt',
    width: 180,
    render: (row) => formatDateTime(row.lastFailedAt),
  },
])

const recentRunColumns = computed<DataTableColumns<OverviewRecentRun>>(() => [
  { title: t('overview.recent.runId'), key: 'runId', width: 220, ellipsis: { tooltip: true } },
  {
    title: t('overview.recent.state'),
    key: 'state',
    width: 120,
    render: (row) => h(StatusTag, { label: runStatusLabel(row.state), tone: runStatusTone(row.state) }),
  },
  {
    title: t('overview.recent.startedAt'),
    key: 'startedAt',
    width: 180,
    render: (row) => formatDateTime(row.startedAt),
  },
  {
    title: t('overview.recent.duration'),
    key: 'durationMs',
    width: 110,
    render: (row) => formatDuration(row.durationMs),
  },
  { title: t('overview.recent.cases'), key: 'total', width: 100 },
  {
    title: t('overview.recent.passRate'),
    key: 'passRateBp',
    width: 110,
    render: (row) => formatPercent(row.passRateBp),
  },
  {
    title: t('overview.recent.device'),
    key: 'dut',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row) =>
      h('span', null, [
        row.dut || EMPTY,
        row.link ? h('span', { class: 'muted' }, ` · ${row.link}`) : null,
      ]),
  },
  {
    title: t('overview.recent.trigger'),
    key: 'triggerType',
    width: 120,
    render: (row) => triggerTypeLabel(row.triggerType),
  },
])

function chips(items: CountItem[]): string {
  return items.map((item) => `${item.key} ${item.count}`).join(' · ')
}

/**
 * 后端把空的 domain_tag 原样返回空串（OVW-01 修订：SQL 不再吐中文哨兵），
 * 空串才代表「未标注」，其余值原样展示。
 */
function domainChips(items: CountItem[]): string {
  return items
    .map((item) => `${item.key === '' ? t('overview.cases.unset') : item.key} ${item.count}`)
    .join(' · ')
}

function segHeight(value: number): string {
  return `${Math.round((value / trendMax.value) * BAR_HEIGHT)}px`
}

function dayLabel(date: string): string {
  return date.slice(5)
}

const RATE_HEIGHT = 56
const RATE_MIN_HEIGHT = 2

/**
 * 通过率柱：粒度过粗时最小的「有失败」运行会正好落在 0 高度上，
 * 所以给最低 2px，保证「跑过但通过率极低」和「没跑过」在视觉上仍可区分。
 */
function rateHeight(point: DigestTrendPoint): string {
  const bp = point.passRateBp
  if (bp === null) return `${RATE_MIN_HEIGHT}px`
  return `${Math.max(RATE_MIN_HEIGHT, Math.round((bp / 10000) * RATE_HEIGHT))}px`
}

function rateBarClass(point: DigestTrendPoint): string {
  if (point.passRateBp === null) return 'rate-bar empty'
  if (point.fail > 0) return 'rate-bar mixed'
  return 'rate-bar pass'
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">{{ t('menu.overview') }}</h1>
      <div class="page-head-actions">
        <NButton :disabled="loading || !projectId" :loading="loading" @click="load">
          {{ t('common.refresh') }}
        </NButton>
      </div>
    </div>

    <p class="page-hint">
      {{ t('overview.subtitle') }}
      <span v-if="data"> · {{ t('overview.generatedAt', { time: formatDateTime(data.generatedAt) }) }}</span>
    </p>

    <p v-if="!projectId" class="muted">{{ t('overview.noProject') }}</p>
    <p v-else-if="loading" class="muted">{{ t('common.loading') }}</p>
    <ErrorLineView v-else-if="error" :error="error" />

    <template v-else-if="data">
      <OnboardingChecklist :data="data" />

      <section class="stat-grid">
        <article class="stat-card">
          <span class="stat-label">{{ t('overview.cases.title') }}</span>
          <span class="stat-value">{{ data.cases.total }}</span>
          <span class="stat-sub">{{ t('overview.cases.total') }}</span>
          <template v-if="data.cases.total > 0">
            <div class="stat-row">
              <span class="muted">{{ t('overview.cases.priority') }}</span>
              <span>{{ chips(data.cases.byPriority) }}</span>
            </div>
            <div class="stat-row">
              <span class="muted">{{ t('overview.cases.review') }}</span>
              <span>{{ data.cases.byReviewStatus
                .map((item) => `${reviewStatusLabel(item.key)} ${item.count}`).join(' · ') }}</span>
            </div>
            <div class="stat-row">
              <span class="muted">{{ t('overview.cases.domains') }}</span>
              <span>{{ domainChips(data.cases.topDomains) }}</span>
            </div>
          </template>
          <div v-else class="empty-box">
            <p>{{ t('overview.cases.empty') }}</p>
            <p class="muted">{{ t('overview.cases.emptyHint') }}</p>
          </div>
        </article>

        <article class="stat-card">
          <span class="stat-label">{{ t('overview.assets.title') }}</span>
          <span class="stat-value">{{ data.assets.devices }}</span>
          <span class="stat-sub">{{ t('overview.assets.devices') }}</span>
          <div class="stat-row">
            <span class="muted">{{ t('overview.assets.ports') }}</span>
            <span>{{ data.assets.ports }}</span>
          </div>
          <div class="stat-row">
            <span class="muted">{{ t('overview.assets.links') }}</span>
            <span>{{ data.assets.links }}</span>
          </div>
          <div class="stat-row">
            <span class="muted">{{ t('overview.assets.credentials') }}</span>
            <span>{{ data.assets.credentials }}</span>
          </div>
          <div class="stat-row">
            <span class="muted">{{ t('overview.assets.environments') }} / {{ t('overview.assets.testSets') }}</span>
            <span>{{ data.assets.environments }} / {{ data.assets.testSets }}</span>
          </div>
          <div v-if="data.assets.devices === 0" class="empty-box">
            <p class="muted">{{ t('overview.assets.emptyHint') }}</p>
          </div>
        </article>

        <article class="stat-card">
          <span class="stat-label">{{ t('overview.runs.passRate') }}</span>
          <span class="stat-value" :class="{ muted: data.runs.passRateBp === null }">
            {{ formatPercent(data.runs.passRateBp) }}
          </span>
          <span class="stat-sub">{{ t('overview.runs.passRateHint', { days: data.windowDays }) }}</span>
          <template v-if="hasFinishedRuns">
            <div class="stat-row">
              <span class="muted">PASS / FAIL / SKIP</span>
              <span>{{ data.runs.casePass }} / {{ data.runs.caseFail }} / {{ data.runs.caseSkip }}</span>
            </div>
            <div class="stat-row">
              <span class="muted">{{ t('overview.runs.avgDuration') }}</span>
              <span>{{ formatDuration(data.runs.avgDurationMs) }}</span>
            </div>
            <div class="stat-row">
              <span class="muted">{{ t('overview.runs.lastRun') }}</span>
              <span>{{ formatDateTime(data.runs.lastRunAt) }}</span>
            </div>
          </template>
          <div v-else class="empty-box">
            <p>{{ t('overview.runs.never') }}</p>
            <p class="muted">{{ t('overview.runs.neverHint') }}</p>
          </div>
        </article>

        <article class="stat-card">
          <span class="stat-label">{{ t('overview.runs.title') }}</span>
          <span class="stat-value">{{ data.runs.total }}</span>
          <span class="stat-sub">{{ t('overview.runs.total') }}</span>
          <div class="stat-row">
            <span class="muted">{{ t('overview.runs.inWindow', { days: data.windowDays }) }}</span>
            <span>{{ data.runs.inWindow }}</span>
          </div>
          <div class="stat-row">
            <span class="muted">{{ t('overview.runs.finished') }}</span>
            <span>{{ data.runs.finished }}</span>
          </div>
          <div class="stat-row">
            <span class="muted">{{ t('overview.runs.failed') }}</span>
            <span>{{ data.runs.failed }}</span>
          </div>
          <div class="stat-row">
            <span class="muted">{{ t('overview.runs.running') }}</span>
            <span>{{ data.runs.running }}</span>
          </div>
        </article>
      </section>

      <section class="card wide-card">
        <div class="section-head">
          <h2 class="card-title">{{ t('overview.trend.title', { days: data.windowDays }) }}</h2>
          <div class="trend-legend">
            <span><i class="trend-dot finished" />{{ t('overview.trend.legendFinished') }}</span>
            <span><i class="trend-dot failed" />{{ t('overview.trend.legendFailed') }}</span>
            <span><i class="trend-dot canceled" />{{ t('overview.trend.legendCanceled') }}</span>
          </div>
        </div>
        <template v-if="hasRuns">
          <div class="trend-bars">
            <div
              v-for="day in data.dailyTrend"
              :key="day.date"
              class="trend-bar"
              :title="`${day.date} · ${t('overview.trend.legendFinished')} ${day.finished} / `
                + `${t('overview.trend.legendFailed')} ${day.failed} / `
                + `${t('overview.trend.legendCanceled')} ${day.canceled}`"
            >
              <span class="trend-seg canceled" :style="{ height: segHeight(day.canceled) }" />
              <span class="trend-seg failed" :style="{ height: segHeight(day.failed) }" />
              <span class="trend-seg finished" :style="{ height: segHeight(day.finished) }" />
            </div>
          </div>
          <div class="trend-axis">
            <span>{{ dayLabel(data.dailyTrend[0]?.date ?? '') }}</span>
            <span>{{ dayLabel(data.dailyTrend[data.dailyTrend.length - 1]?.date ?? '') }}</span>
          </div>
        </template>
        <p v-else class="muted">{{ t('overview.trend.empty') }}</p>
      </section>

      <section v-if="digest || digestError" class="card wide-card">
        <div class="section-head">
          <h2 class="card-title">{{ t('overview.digest.title') }}</h2>
          <span v-if="digest" class="muted">{{ t('overview.digest.window', { days: digest.windowDays }) }}</span>
        </div>
        <ErrorLineView v-if="digestError" :error="digestError" />
        <template v-else-if="digest">
          <div class="digest-facts">
            <span>
              <span class="muted">{{ t('overview.digest.coverage') }}</span>
              {{ t('overview.digest.coverageValue', {
                covered: digest.coverage.covered,
                total: digest.coverage.caseTotal,
              }) }}
            </span>
            <span>
              <span class="muted">{{ t('overview.digest.neverExecuted') }}</span>
              {{ digest.coverage.neverExecuted }}
            </span>
          </div>
          <p class="muted">{{ t('overview.digest.coverageHint') }}</p>

          <h3 class="card-title">{{ t('overview.digest.trendTitle') }}</h3>
          <template v-if="digest.passRateTrend.length > 0">
            <div class="rate-bars">
              <span
                v-for="point in digest.passRateTrend"
                :key="point.runId"
                :class="rateBarClass(point)"
                :style="{ height: rateHeight(point) }"
                :title="`${point.runId} · ${formatPercent(point.passRateBp)} · `
                  + `${t('overview.digest.failures')} ${point.fail}`"
              />
            </div>
            <div class="trend-axis">
              <span>{{ digest.passRateTrend[0]?.runId }}</span>
              <span>{{ digest.passRateTrend[digest.passRateTrend.length - 1]?.runId }}</span>
            </div>
          </template>
          <p v-else class="muted">{{ t('overview.digest.trendEmpty') }}</p>

          <h3 class="card-title">{{ t('overview.digest.recentTitle') }}</h3>
          <DataTable
            :columns="digestReportColumns"
            :data="digest.recentReports"
            :row-key="(row: DigestReportRow) => row.runId"
            :empty-title="t('overview.digest.recentEmpty')"
            empty-icon="reports"
            :error="Boolean(digestError)"
          />

          <h3 class="card-title">{{ t('overview.digest.failedTitle') }}</h3>
          <DataTable
            :columns="digestFailedColumns"
            :data="digest.topFailedCases"
            :row-key="(row: DigestFailedCase) => row.caseKey"
            :empty-title="t('overview.digest.failedEmpty')"
            empty-icon="warn"
            :error="Boolean(digestError)"
          />
        </template>
      </section>

      <section class="card wide-card">
        <div class="section-head">
          <h2 class="card-title">{{ t('overview.recent.title') }}</h2>
        </div>
        <DataTable
          :columns="recentRunColumns"
          :data="data.recentRuns"
          :row-key="(row: OverviewRecentRun) => row.runId"
          :empty-title="t('overview.recent.empty')"
          :empty-description="t('overview.recent.emptyHint')"
          empty-icon="runs"
          :error="Boolean(error)"
        />
      </section>
    </template>
  </div>
</template>
