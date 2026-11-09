<script setup lang="ts">
import { computed, h, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  NButton,
  NCheckbox,
  NDatePicker,
  NInput,
  NInputNumber,
  NPagination,
  NSelect,
  type DataTableColumns,
} from 'naive-ui'
import { planApi, type ExecutionPlan, type PlanConflict, type PlanPayload } from '@/api/plans'
import { auditApi, type AuditEntry } from '@/api/audit'
import { ApiError } from '@/api/http'
import { caseSetApi, type TestSet } from '@/api/cases'
import { useAuthStore } from '@/stores/auth'
import DataTable from '@/components/DataTable.vue'
import { useListPageSize, type PageSize } from '@/composables/useListPageSize'
import ErrorLineView from '@/components/ErrorLine.vue'
import FormField from '@/components/FormField.vue'
import StatusTag from '@/components/StatusTag.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { EMPTY, formatDateTime } from '@/utils/format'
import { runStatusLabel, type StatusTone } from '@/utils/statusLabels'
import { confirmAction } from '@/composables/useConfirm'

const { t, te } = useI18n()
const auth = useAuthStore()

const plans = ref<ExecutionPlan[]>([])

/* 展示分页（FR-v3.9-04）。
 * 这里刻意做**前端切片**而不是改后端分页参数：计划名在冲突面板里被按 id 反查
 * （见 planName()），只加载当前页会让跨页计划显示成 `#12`。
 * 取数仍一次 100 条（与后端上限一致），单页最多 50 行只约束**渲染**。 */
const planPage = ref(1)
const { size: planPageSize, pageSizes: planPageSizeAll } = useListPageSize()
// 计划页上限 50：一次渲染 55 行 + 行内 4 个操作是「一屏 244 个控件」的直接来源
const planPageSizes = planPageSizeAll.filter((option) => option <= 50)
const planRowsPerPage = computed(() => Math.min(planPageSize.value, 50))
const planPageCount = computed(() =>
  Math.max(1, Math.ceil(plans.value.length / planRowsPerPage.value)),
)
const visiblePlans = computed(() => {
  const start = (planPage.value - 1) * planRowsPerPage.value
  return plans.value.slice(start, start + planRowsPerPage.value)
})
const testSets = ref<TestSet[]>([])
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const message = ref('')
const saving = ref(false)
const formOpen = ref(false)
const editingId = ref<number | null>(null)
const previewTimes = ref<string[]>([])
const previewError = ref<ErrorLine | null>(null)

/** 下游跳过事件（PLN-02）：来自审计记录，按需拉取，不在进页时多打一次接口。 */
const skippedOpen = ref(false)
const skippedLoaded = ref(false)
const skippedLoading = ref(false)
const skippedError = ref('')
const skippedEvents = ref<AuditEntry[]>([])
// 分页与时间范围（PLN-02 残余）：原来写死「第一页 20 条」，多了就看不到、也筛不了。
// 后端 /api/audit 早就支持 page/size/from/to，这里只是把入口补齐。
const skippedPage = ref(1)
const skippedSize = 20
const skippedTotal = ref(0)
// from / to 必须初始化为 null：NDatePicker 的 formatted-value 拿到 '' 会交给 date-fns
// 解析，直接抛 RangeError（AuditView 早就踩过同一个坑）。
const skippedRange = reactive({ from: null as string | null, to: null as string | null })
const skippedPageCount = computed(() => Math.max(1, Math.ceil(skippedTotal.value / skippedSize)))

/**
 * 编排冲突（W5）：只读检测 + 批量启停。
 *
 * 检测与批量刻意分成两个动作：先看清「哪些计划撞在一起」，再决定停哪几条。
 * 自动修复会把用户没想动的计划一起停掉，事故成本远高于多点一次按钮。
 */
const conflicts = ref<PlanConflict[]>([])
const conflictLoading = ref(false)
const conflictError = ref<ErrorLine | null>(null)
const selectedConflictKeys = ref<string[]>([])
const batchApplying = ref(false)
const batchNotice = ref('')

function conflictKey(conflict: PlanConflict): string {
  return `${conflict.type}:${conflict.planIds.join(',')}`
}

const allConflictsSelected = computed(() =>
  conflicts.value.length > 0 && selectedConflictKeys.value.length === conflicts.value.length)

const selectedPlanIds = computed(() => {
  const ids = new Set<number>()
  for (const conflict of conflicts.value) {
    if (selectedConflictKeys.value.includes(conflictKey(conflict))) {
      for (const id of conflict.planIds) ids.add(id)
    }
  }
  return [...ids].sort((left, right) => left - right)
})

function conflictTypeLabel(type: string): string {
  const key = `plans.conflictTypes.${type}`
  return te(key) ? t(key) : type
}

function planNames(ids: number[]): string {
  return ids
    .map((id) => plans.value.find((plan) => plan.id === id)?.name ?? `#${id}`)
    .join('、')
}

function toggleConflict(key: string): void {
  selectedConflictKeys.value = selectedConflictKeys.value.includes(key)
    ? selectedConflictKeys.value.filter((value) => value !== key)
    : [...selectedConflictKeys.value, key]
}

function toggleAllConflicts(): void {
  selectedConflictKeys.value = allConflictsSelected.value
    ? []
    : conflicts.value.map((conflict) => conflictKey(conflict))
}

async function loadConflicts(): Promise<void> {
  if (!projectId.value) {
    conflicts.value = []
    return
  }
  conflictLoading.value = true
  conflictError.value = null
  try {
    conflicts.value = await planApi.conflicts(projectId.value)
    // 列表可能已变（新建/删除/改 cron），旧勾选里失效的键要清掉，否则批量会把
    // 「选择条数」算成 0 却仍显示有勾选
    const live = new Set(conflicts.value.map((conflict) => conflictKey(conflict)))
    selectedConflictKeys.value = selectedConflictKeys.value.filter((key) => live.has(key))
  } catch (e) {
    conflicts.value = []
    conflictError.value = localizeError(e)
  } finally {
    conflictLoading.value = false
  }
}

async function applyBatch(status: 'ENABLED' | 'PAUSED'): Promise<void> {
  const ids = selectedPlanIds.value
  if (ids.length === 0 || batchApplying.value) return
  batchApplying.value = true
  conflictError.value = null
  batchNotice.value = ''
  try {
    const results = await planApi.batch(projectId.value, ids, status)
    const succeeded = results.filter((result) => result.changed).length
    batchNotice.value = t('plans.conflictApplied', {
      ok: succeeded,
      failed: results.length - succeeded,
    })
    selectedConflictKeys.value = []
    await load()
  } catch (e) {
    conflictError.value = localizeError(e)
  } finally {
    batchApplying.value = false
  }
}

const projectId = computed(() => auth.projectId ?? 0)

interface PlanForm {
  name: string
  testSetId: number | null
  environmentIdText: string
  suitePathsText: string
  includeTag: string
  concurrency: number
  timeoutSeconds: number
  cron: string
  timezone: string
  dependencyIds: number[]
}

function emptyForm(): PlanForm {
  return {
    name: '',
    testSetId: null,
    environmentIdText: '',
    suitePathsText: 'suites',
    includeTag: '',
    concurrency: 1,
    timeoutSeconds: 3600,
    cron: '0 0 2 * * *',
    timezone: 'Asia/Shanghai',
    dependencyIds: [],
  }
}

const form = ref<PlanForm>(emptyForm())

const availableDependencies = computed(() =>
  plans.value.filter((plan) => plan.id !== editingId.value),
)

const testSetOptions = computed(() =>
  testSets.value.map((set) => ({ label: `${set.name} (${set.setCode})`, value: set.id })))

const canSubmit = computed(() => {
  return (
    form.value.name.trim().length > 0 &&
    form.value.suitePathsText.trim().length > 0 &&
    form.value.cron.trim().length > 0 &&
    !saving.value
  )
})

function statusLabel(status: string): string {
  const key = `plans.status.${status}`
  return te(key) ? t(key) : status
}

/** 「暂停」是人为停下，用中性色；只有真在跑的才算 ok —— 用色块说「停」比说「好」重要。 */
function planStatusTone(status: string): StatusTone {
  return status === 'paused' ? 'neutral' : 'ok'
}

function runStateLabel(state: string | null): string {
  return state ? runStatusLabel(state) : t('plans.noRun')
}

interface SkippedRow {
  id: number
  at: string
  downstream: string
  reason: string
  upstreamRun: string
}

/** 审计 detail 是 `k=v, k=v` 的行式记录（见 PlanScheduleService 的 audit 调用）。 */
function parseDetail(detail: string | null): Record<string, string> {
  const fields: Record<string, string> = {}
  for (const part of (detail ?? '').split(',')) {
    const [key, ...rest] = part.split('=')
    const name = key.trim()
    if (name && rest.length > 0) fields[name] = rest.join('=').trim()
  }
  return fields
}

/** 原因码可能带尾巴（`upstream_error: xxx`），取冒号前的码去查文案，查不到就显示原文。 */
function reasonLabel(raw: string): string {
  const code = raw.split(':')[0].trim()
  const key = `plans.skippedReasons.${code}`
  return code && te(key) ? t(key) : raw || EMPTY
}

const skippedRows = computed<SkippedRow[]>(() =>
  skippedEvents.value.map((entry) => {
    const detail = parseDetail(entry.detail)
    const downstreamId = detail.downstream ?? ''
    const plan = plans.value.find((item) => String(item.id) === downstreamId)
    return {
      id: entry.id,
      at: entry.occurredAt,
      downstream: plan?.name ?? (downstreamId ? `#${downstreamId}` : EMPTY),
      reason: reasonLabel(detail.reason ?? ''),
      upstreamRun: detail.upstream_run ?? EMPTY,
    }
  }))

const skippedColumns = computed<DataTableColumns<SkippedRow>>(() => [
  {
    title: t('plans.skippedTime'),
    key: 'at',
    width: 170,
    render: (row) => formatDateTime(row.at),
  },
  { title: t('plans.skippedDownstream'), key: 'downstream', minWidth: 160 },
  { title: t('plans.skippedReason'), key: 'reason', minWidth: 160 },
  {
    title: t('plans.skippedUpstreamRun'),
    key: 'upstreamRun',
    minWidth: 180,
    // 运行号要和列表里的 `lastRunId` 一样等宽呈现，否则一列数字对不齐
    render: (row) => h('span', { class: 'mono' }, row.upstreamRun),
  },
])

function resetSkipped(): void {
  skippedOpen.value = false
  skippedLoaded.value = false
  skippedLoading.value = false
  skippedError.value = ''
  skippedEvents.value = []
  skippedPage.value = 1
  skippedTotal.value = 0
  skippedRange.from = null
  skippedRange.to = null
}

async function loadSkipped(): Promise<void> {
  if (!projectId.value) return
  skippedLoading.value = true
  skippedError.value = ''
  try {
    const page = await auditApi.page({
      projectId: projectId.value,
      action: 'PLAN_DOWNSTREAM_SKIPPED',
      from: skippedRange.from || undefined,
      to: skippedRange.to || undefined,
      page: skippedPage.value,
      size: skippedSize,
    })
    skippedEvents.value = page.items
    skippedTotal.value = page.total
    skippedLoaded.value = true
  } catch (e) {
    skippedEvents.value = []
    skippedTotal.value = 0
    skippedError.value =
      e instanceof ApiError && e.codeName === 'FORBIDDEN'
        ? t('plans.skippedForbidden')
        : localizeError(e).summary
  } finally {
    skippedLoading.value = false
  }
}

function toggleSkipped(): void {
  skippedOpen.value = !skippedOpen.value
  // 展开即拉；已拉过就保留上次结果，避免每次收起再展开都打一次接口
  if (skippedOpen.value && !skippedLoaded.value) void loadSkipped()
}

/** 翻页：只在已展开时拉；收起状态下先记页码，展开时由 toggleSkipped 统一拉。 */
function turnSkippedPage(next: number): void {
  if (next < 1 || next > skippedPageCount.value || next === skippedPage.value) return
  skippedPage.value = next
  if (skippedOpen.value) void loadSkipped()
}

/** 改时间范围必须回到第一页：否则筛完只剩 1 页却停在第 3 页，表格空得莫名其妙。 */
function onSkippedRangeChange(): void {
  skippedPage.value = 1
  skippedLoaded.value = false
  if (skippedOpen.value) void loadSkipped()
}

/**
 * 计划列表列定义（UX-04b 逐页迁移）。
 *
 * 窄屏不再另渲染一套卡片：`scroll-x` 已经能在 760px 以下横向滚动，而双份 markup
 * 意味着两份都要跟着迁移、两份都可能只改一边（本页原来的卡片版就是纯复制）。
 */
const columns = computed<DataTableColumns<ExecutionPlan>>(() => [
  {
    title: t('plans.name'),
    key: 'name',
    minWidth: 200,
    render: (row) =>
      h('div', null, [
        h('div', { class: 'plan-name' }, row.name),
        h('div', { class: 'muted small' }, row.suitePaths.join(', ') || EMPTY),
      ]),
  },
  {
    title: t('plans.statusLabel'),
    key: 'status',
    width: 110,
    render: (row) =>
      h(StatusTag, { label: statusLabel(row.status), tone: planStatusTone(row.status) }),
  },
  {
    title: t('plans.cron'),
    key: 'cron',
    width: 190,
    render: (row) =>
      h('div', null, [
        h('code', null, row.cron),
        h('div', { class: 'muted small' }, row.timezone),
      ]),
  },
  {
    title: t('plans.nextFire'),
    key: 'nextFire',
    width: 190,
    render: (row) => {
      const times = row.nextFireTimes ?? []
      const first = times[0] ?? row.nextFireAt
      // 「未来 3 次」这个标签放进列头就够，逐行重复只会把每行撑高一行
      return h('div', null, [
        h('div', null, formatDateTime(first)),
        ...times.slice(1).map((time) => h('div', { class: 'muted small' }, formatDateTime(time))),
      ])
    },
  },
  {
    title: t('plans.lastResult'),
    key: 'lastRunState',
    width: 190,
    render: (row) =>
      h('div', null, [
        h('div', null, runStateLabel(row.lastRunState)),
        row.lastRunId ? h('div', { class: 'muted small' }, row.lastRunId) : null,
        row.lastRunFinishedAt
          ? h('div', { class: 'muted small' }, formatDateTime(row.lastRunFinishedAt))
          : null,
      ]),
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 300,
    render: (row) =>
      h('div', { class: 'row-actions' }, [
        h(
          NButton,
          { text: true, type: 'primary', size: 'small', onClick: () => openEdit(row) },
          { default: () => t('plans.edit') },
        ),
        h(
          NButton,
          { text: true, size: 'small', onClick: () => void toggleStatus(row) },
          { default: () => (row.status === 'paused' ? t('plans.resume') : t('plans.pause')) },
        ),
        h(
          NButton,
          { text: true, size: 'small', onClick: () => void triggerNow(row) },
          { default: () => t('plans.triggerNow') },
        ),
        h(
          NButton,
          { text: true, type: 'error', size: 'small', onClick: () => void removePlan(row) },
          { default: () => t('common.delete') },
        ),
      ]),
  },
])

async function load(): Promise<void> {
  error.value = null
  if (!projectId.value) {
    plans.value = []
    testSets.value = []
    conflicts.value = []
    return
  }
  loading.value = true
  try {
    const [planPage, setPage] = await Promise.all([
      planApi.page(projectId.value, 1, 100),
      caseSetApi.page(projectId.value, { page: 1, size: 100 }),
    ])
    plans.value = planPage.items
    testSets.value = setPage.items
    // 计划的增删改都会影响冲突判定，随列表一起刷新，避免面板显示上一次的结论
    await loadConflicts()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
}

/** 翻页：纯前端切片，不发请求。 */
function turnPlanPage(next: number): void {
  if (next < 1 || next > planPageCount.value) return
  planPage.value = next
}

/** 改档位：回到第 1 页，避免停在「第 3 页但只有 1 页」的空表。 */
function changePlanPageSize(next: number): void {
  planPageSize.value = next as PageSize
  planPage.value = 1
}

function resetForm(): void {
  form.value = emptyForm()
  editingId.value = null
  previewTimes.value = []
  previewError.value = null
}

function openCreate(): void {
  resetForm()
  formOpen.value = true
}

function openEdit(plan: ExecutionPlan): void {
  editingId.value = plan.id
  form.value = {
    name: plan.name,
    testSetId: plan.testSetId,
    environmentIdText: plan.environmentId === null ? '' : String(plan.environmentId),
    suitePathsText: plan.suitePaths.join('\n'),
    includeTag: plan.includeTag ?? '',
    concurrency: plan.concurrency,
    timeoutSeconds: plan.timeoutSeconds,
    cron: plan.cron,
    timezone: plan.timezone,
    dependencyIds: [...plan.dependencyIds],
  }
  previewTimes.value = plan.nextFireTimes ?? []
  previewError.value = null
  formOpen.value = true
}

function closeForm(): void {
  formOpen.value = false
  resetForm()
}

function toPayload(): PlanPayload {
  const parsedEnv = Number.parseInt(form.value.environmentIdText.trim(), 10)
  return {
    projectId: projectId.value,
    name: form.value.name.trim(),
    testSetId: form.value.testSetId,
    environmentId: Number.isNaN(parsedEnv) ? null : parsedEnv,
    suitePaths: form.value.suitePathsText
      .split('\n')
      .map((path) => path.trim())
      .filter((path) => path.length > 0),
    includeTag: form.value.includeTag.trim() || null,
    concurrency: Number(form.value.concurrency) || 1,
    timeoutSeconds: Number(form.value.timeoutSeconds) || 3600,
    cron: form.value.cron.trim(),
    timezone: form.value.timezone.trim() || 'Asia/Shanghai',
    overlapPolicy: 'skip',
    dependencyIds: form.value.dependencyIds,
  }
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  error.value = null
  message.value = ''
  saving.value = true
  try {
    const payload = toPayload()
    if (editingId.value === null) {
      await planApi.create(payload)
      message.value = t('plans.created')
    } else {
      await planApi.update(projectId.value, editingId.value, payload)
      message.value = t('plans.updated')
    }
    closeForm()
    await load()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    saving.value = false
  }
}

async function previewCron(): Promise<void> {
  previewError.value = null
  previewTimes.value = []
  try {
    previewTimes.value = await planApi.preview(
      projectId.value,
      form.value.cron.trim(),
      form.value.timezone.trim() || 'Asia/Shanghai',
    )
  } catch (e) {
    previewError.value = localizeError(e)
  }
}

function toggleDependency(id: number): void {
  const ids = form.value.dependencyIds
  form.value.dependencyIds = ids.includes(id)
    ? ids.filter((value) => value !== id)
    : [...ids, id]
}

async function toggleStatus(plan: ExecutionPlan): Promise<void> {
  error.value = null
  message.value = ''
  try {
    if (plan.status === 'paused') {
      await planApi.resume(projectId.value, plan.id)
      message.value = t('plans.resumed', { name: plan.name })
    } else {
      await planApi.pause(projectId.value, plan.id)
      message.value = t('plans.pausedMsg', { name: plan.name })
    }
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function triggerNow(plan: ExecutionPlan): Promise<void> {
  if (!(await confirmAction(t('plans.confirmTrigger', { name: plan.name })))) return
  error.value = null
  message.value = ''
  try {
    const run = await planApi.triggerNow(projectId.value, plan.id)
    message.value = t('plans.triggered', { runId: run.runId })
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function removePlan(plan: ExecutionPlan): Promise<void> {
  if (!(await confirmAction(t('plans.confirmDelete', { name: plan.name }), { tone: 'danger' }))) return
  error.value = null
  message.value = ''
  try {
    await planApi.remove(projectId.value, plan.id)
    message.value = t('plans.deleted', { name: plan.name })
    if (editingId.value === plan.id) closeForm()
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

watch(projectId, () => {
  closeForm()
  resetSkipped()
  void load()
})

void load()
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">{{ t('plans.title') }}</h1>
    </div>

    <div class="toolbar">
      <NButton type="primary" @click="openCreate">{{ t('plans.create') }}</NButton>
      <NButton quaternary :loading="loading" @click="load">{{ t('common.refresh') }}</NButton>
      <!-- 计划组 / 依赖图（ORCH-01 / ORCH-02）是计划的子页面，入口放在这里：
           没有入口的页面等于不存在，只能靠手输 URL 才能打开。 -->
      <RouterLink v-slot="{ navigate }" to="/plans/groups" custom>
        <NButton quaternary data-testid="entry-plan-groups" @click="navigate">
          {{ t('plans.groupsEntry') }}
        </NButton>
      </RouterLink>
      <RouterLink v-slot="{ navigate }" to="/plans/graph" custom>
        <NButton quaternary data-testid="entry-plan-graph" @click="navigate">
          {{ t('plans.graphEntry') }}
        </NButton>
      </RouterLink>
      <RouterLink v-slot="{ navigate }" to="/runs" custom>
        <NButton quaternary @click="navigate">{{ t('plans.backToRuns') }}</NButton>
      </RouterLink>
    </div>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="message" class="text-ok">{{ message }}</p>

    <DataTable
      :columns="columns"
      :data="visiblePlans"
      :loading="loading"
      :row-key="(row: ExecutionPlan) => row.id"
      :empty-title="t('plans.empty')"
      :empty-description="projectId ? undefined : t('overview.noProject')"
      empty-icon="runs"
      :error="Boolean(error)"
    />

    <NPagination
      v-if="plans.length > planRowsPerPage"
      class="pager"
      :page="planPage"
      :page-count="planPageCount"
      :page-slot="7"
      :page-size="planRowsPerPage"
      :page-sizes="planPageSizes"
      show-size-picker
      @update:page="turnPlanPage"
      @update:page-size="changePlanPageSize"
    />

    <div class="card conflict-card" data-testid="plan-conflicts">
      <div class="conflict-head">
        <h2 class="card-title">{{ t('plans.conflictTitle') }}</h2>
        <span class="muted small">{{ t('plans.conflictHint') }}</span>
        <NButton quaternary size="small" :loading="conflictLoading" @click="loadConflicts">
          {{ t('plans.conflictRefresh') }}
        </NButton>
      </div>

      <ErrorLineView v-if="conflictError" :error="conflictError" />
      <p v-else-if="!conflictLoading && conflicts.length === 0" class="text-ok" data-testid="conflict-empty">
        {{ t('plans.conflictEmpty') }}
      </p>
      <template v-else>
        <div class="conflict-list">
          <NCheckbox
            class="conflict-all"
            :checked="allConflictsSelected"
            :indeterminate="selectedConflictKeys.length > 0 && !allConflictsSelected"
            @update:checked="toggleAllConflicts"
          >
            {{ t('plans.conflictSelectAll') }}
          </NCheckbox>
          <label
            v-for="conflict in conflicts"
            :key="conflictKey(conflict)"
            class="conflict-row"
            :data-severity="conflict.severity"
            data-testid="conflict-row"
          >
            <NCheckbox
              :checked="selectedConflictKeys.includes(conflictKey(conflict))"
              @update:checked="toggleConflict(conflictKey(conflict))"
            />
            <span class="conflict-type">{{ conflictTypeLabel(conflict.type) }}</span>
            <span class="conflict-plans">{{ planNames(conflict.planIds) }}</span>
            <span class="muted small">{{ conflict.detail }}</span>
          </label>
        </div>
        <div class="conflict-actions">
          <span class="muted small">{{ t('plans.conflictSelected', { n: selectedPlanIds.length }) }}</span>
          <NButton
            size="small"
            :disabled="selectedPlanIds.length === 0 || batchApplying"
            :loading="batchApplying"
            @click="applyBatch('PAUSED')"
          >
            {{ t('plans.conflictPause') }}
          </NButton>
          <NButton
            size="small"
            :disabled="selectedPlanIds.length === 0 || batchApplying"
            :loading="batchApplying"
            @click="applyBatch('ENABLED')"
          >
            {{ t('plans.conflictEnable') }}
          </NButton>
        </div>
      </template>
      <p v-if="batchNotice" class="text-ok" data-testid="conflict-batch-result">{{ batchNotice }}</p>
    </div>

    <div class="card skipped-card">
      <div class="skipped-head">
        <NButton quaternary size="small" :loading="skippedLoading" @click="toggleSkipped">
          {{ skippedOpen ? t('plans.skippedHide') : t('plans.skippedToggle') }}
        </NButton>
        <span class="muted small">{{ t('plans.skippedHint') }}</span>
      </div>
      <template v-if="skippedOpen">
        <div class="skipped-filters">
          <NDatePicker
            v-model:formatted-value="skippedRange.from"
            class="skipped-from"
            type="date"
            value-format="yyyy-MM-dd"
            clearable
            size="small"
            style="width: 180px"
            :placeholder="t('plans.skippedFrom')"
            @update:formatted-value="onSkippedRangeChange"
          />
          <NDatePicker
            v-model:formatted-value="skippedRange.to"
            class="skipped-to"
            type="date"
            value-format="yyyy-MM-dd"
            clearable
            size="small"
            style="width: 180px"
            :placeholder="t('plans.skippedTo')"
            @update:formatted-value="onSkippedRangeChange"
          />
          <span class="muted small">{{ t('plans.skippedTotal', { n: skippedTotal }) }}</span>
        </div>
        <ErrorLineView v-if="skippedError" :error="skippedError" />
        <DataTable
          v-else
          class="skipped-table"
          :columns="skippedColumns"
          :data="skippedRows"
          :loading="skippedLoading"
          :row-key="(row: SkippedRow) => row.id"
          :empty-title="t('plans.skippedEmpty')"
          empty-icon="runs"
          max-height="240px"
        />
        <div v-if="skippedPageCount > 1" class="skipped-pager">
          <NPagination
            :page="skippedPage"
            :page-count="skippedPageCount"
            size="small"
            @update:page="turnSkippedPage"
          />
        </div>
      </template>
    </div>

    <div v-if="formOpen" class="card form-card">
      <h2 class="card-title">
        {{ editingId === null ? t('plans.createTitle') : t('plans.editTitle') }}
      </h2>

      <div class="form-grid">
        <FormField :label="t('plans.name')" required>
          <NInput v-model:value="form.name" :maxlength="128" />
        </FormField>

        <FormField :label="t('plans.testSet')">
          <NSelect
            v-model:value="form.testSetId"
            :options="testSetOptions"
            clearable
            :placeholder="t('plans.noTestSet')"
          />
        </FormField>

        <FormField :label="t('plans.environment')" :hint="t('plans.environmentHint')">
          <NInput v-model:value="form.environmentIdText" />
        </FormField>

        <FormField :label="t('plans.concurrency')">
          <NInputNumber v-model:value="form.concurrency" :min="1" :max="16" />
        </FormField>

        <FormField :label="t('plans.timeout')">
          <NInputNumber v-model:value="form.timeoutSeconds" :min="1" :max="86400" />
        </FormField>

        <FormField :label="t('plans.includeTag')">
          <NInput v-model:value="form.includeTag" :maxlength="64" />
        </FormField>

        <FormField :label="t('plans.cron')">
          <NInput v-model:value="form.cron" :maxlength="64" />
        </FormField>

        <FormField :label="t('plans.timezone')">
          <NInput v-model:value="form.timezone" :maxlength="64" />
        </FormField>
      </div>

      <FormField :label="t('plans.suitePaths')">
        <NInput
          v-model:value="form.suitePathsText"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 6 }"
        />
      </FormField>

      <div class="preview-row">
        <NButton quaternary @click="previewCron">{{ t('plans.preview') }}</NButton>
        <span class="muted">{{ t('plans.nextThree') }}</span>
        <span v-for="time in previewTimes" :key="time" class="preview-time">
          {{ formatDateTime(time) }}
        </span>
        <ErrorLineView v-if="previewError" :error="previewError" />
      </div>

      <fieldset class="deps">
        <legend>{{ t('plans.dependencies') }}</legend>
        <p class="muted small">{{ t('plans.dependencyHint') }}</p>
        <div v-if="availableDependencies.length" class="dep-list">
          <NCheckbox
            v-for="plan in availableDependencies"
            :key="plan.id"
            :checked="form.dependencyIds.includes(plan.id)"
            @update:checked="toggleDependency(plan.id)"
          >
            {{ plan.name }}
          </NCheckbox>
        </div>
        <p v-else class="muted small">{{ t('plans.noDependencies') }}</p>
      </fieldset>

      <div class="form-actions">
        <NButton type="primary" :disabled="!canSubmit" :loading="saving" @click="submit">
          {{ t('common.save') }}
        </NButton>
        <NButton quaternary @click="closeForm">{{ t('common.cancel') }}</NButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.small {
  font-size: var(--font-size-xs);
}

.plan-name {
  font-weight: 600;
}

.form-card {
  margin-top: var(--space-4);
}

.skipped-card {
  margin-top: var(--space-4);
}

.conflict-card {
  margin-top: var(--space-4);
}

.conflict-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-3);
}

.conflict-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.conflict-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  /* FAIL 是硬故障（永远不触发/等不到），用左侧色条区分，不靠文字提示 */
  border-left: 3px solid var(--warn);
}

.conflict-row[data-severity='FAIL'] {
  border-left-color: var(--fail);
}

.conflict-type {
  font-weight: 600;
  min-width: 90px;
}

.conflict-plans {
  font-weight: 500;
}

.conflict-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.skipped-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.skipped-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.skipped-table {
  margin-top: var(--space-3);
}

.skipped-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-3);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-3) var(--space-4);
}

.preview-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  margin: var(--space-3) 0;
}

.preview-time {
  padding: 2px var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  font-size: var(--font-size-xs);
}

.deps {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-3);
}

.dep-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--space-2);
  max-height: 180px;
  overflow: auto;
}

.form-actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-4);
}

/* 操作列多个 text 按钮并排：h() 渲染的节点拿不到 scoped 类，用 :deep 兜 */
:deep(.row-actions) {
  display: flex;
  gap: var(--space-2);
}
</style>
