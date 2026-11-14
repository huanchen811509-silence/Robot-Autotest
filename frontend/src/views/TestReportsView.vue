<script setup lang="ts">
/**
 * W2 / RPTX-03 对外测试报告列表 + 建草稿（FR-v3.10-02 / FR-v3.10-03）。
 *
 * v3.10 起「建草稿」分两种来源，都不再手打 runId：
 *   ① 按执行记录（默认）：从 RunPicker 下拉选一条 run，标题自动带出（可改）；
 *   ② 按用例集（FR-v3.10-03）：选用例集，数据源取该集**最近一次执行**；
 *      也可以在「指定 RUN_ID」里钉死某一次（留空 = 最近一次）。
 * 两条路径最终都落成一条 run，后端用同一段代码建快照——所以「旧路径零回归」不需要
 * 另写一套回归，也不存在两条实现慢慢分叉的问题。
 *
 * 手打入口仍然是**降级**而非删除（判据④）：归档 run 被清掉记录后，仍可能有人拿着
 * RUN_ID 要出一份报告，一刀切删掉会断掉这条路。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NCheckbox, NInput, NModal, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import ListScaffold from '@/components/ListScaffold.vue'
import ConfirmAction from '@/components/ConfirmAction.vue'
import ReadOnlyNotice from '@/components/ReadOnlyNotice.vue'
import { useTableFilters, type TableFilterColumn } from '@/composables/useTableFilters'
import { listCrudApi } from '@/api/listCrud'
import RunPicker from '@/components/RunPicker.vue'
import CaseSetPicker from '@/components/CaseSetPicker.vue'
import { useAuthStore } from '@/stores/auth'
import { testReportsApi, type TestReportItem } from '@/api/testReports'
import type { TestSet } from '@/api/cases'
import type { ExecutionRun } from '@/api/execution'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref<TestReportItem[]>([])
const loading = ref(false)
const failed = ref(false)
const notice = ref('')
const title = ref('')
const advanced = ref(false)
const projectId = computed(() => auth.projectId ?? 0)

/** 来源：run = 按执行记录（默认） / case_set = 按用例集。 */
const source = ref<'run' | 'case_set'>('run')

const runId = ref<string | null>(null)
const manualRunId = ref('')
const caseSetId = ref<number | null>(null)
/** 按用例集时的「指定一次执行」；留空 = 后端取该集最近一次。 */
const caseSetRunId = ref('')

/** 有效 runId（仅 run 模式）：主路径取下拉选中值，高级路径取手输值。 */
const effectiveRunId = computed(() =>
  advanced.value ? manualRunId.value.trim() : (runId.value ?? '').trim(),
)

/** 能不能提交：两种来源各有各的前置条件，缺一不可，但不互相冒充。 */
const canSubmit = computed(() =>
  source.value === 'run' ? Boolean(effectiveRunId.value) : caseSetId.value !== null,
)

/** 来源展示：按 run 的旧报告（sourceType 缺失或 RUN）一律按「按执行记录」解释。 */
function sourceLabel(row: TestReportItem): string {
  const type = (row.sourceType ?? 'RUN').toUpperCase()
  if (type !== 'CASE_SET') return t('testReports.sourceRun')
  return row.caseSetId
    ? t('testReports.sourceCaseSetWith', { id: row.caseSetId })
    : t('testReports.sourceCaseSet')
}

/* ---------------------------------------------------------------- W57-15/32 列表骨架 */
/**
 * 对外测试报告既有「建草稿」是 A 类用户创建入口（v3.10，已有回归覆盖），保留不动；
 * 列表侧本次只补 B 类动作：元数据编辑 / 软删 / 恢复 / 批量，**不新增任何「新增」按钮**。
 * 冻结态不渲染可点动作，改给一句「请先撤销冻结」；软删后导出链接失效也有可读说明。
 */
const reportFilters = useTableFilters({ prefix: 'tr_', columns: ['title', 'runId'] })
const reportFilterColumns: TableFilterColumn[] = [
  { key: 'title', title: t('w57.testReports.colTitle'), mode: 'text' },
  { key: 'runId', title: t('w57.testReports.colRunId'), mode: 'text' },
]
const reportFilterValues = computed<Record<string, string[]>>(() => {
  const out: Record<string, string[]> = {}
  for (const col of reportFilterColumns) {
    const value = reportFilters.texts.value[col.key]
    if (value) out[col.key] = [value]
  }
  return out
})
function onReportFilterChange(next: Record<string, string[]>): void {
  for (const col of reportFilterColumns) reportFilters.setText(col.key, next[col.key]?.[0] ?? '')
}
const visibleRows = computed<TestReportItem[]>(() => {
  const byTitle = (reportFilters.texts.value.title ?? '').toLowerCase()
  const byRun = (reportFilters.texts.value.runId ?? '').toLowerCase()
  const q = reportFilters.q.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (byTitle && !row.title.toLowerCase().includes(byTitle)) return false
    if (byRun && !row.runId.toLowerCase().includes(byRun)) return false
    if (q && !`${row.title} ${row.runId}`.toLowerCase().includes(q)) return false
    return true
  })
})
const reportFiltered = reportFilters.isFiltered

/** 批量选择：单页列表用 id 集合即可，不引服务端全选（本页不分页）。 */
const selectedIds = ref<Set<number>>(new Set())
const selectionCount = computed(() => selectedIds.value.size)
function isSelected(id: number): boolean {
  return selectedIds.value.has(id)
}
function toggleSelected(id: number): void {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}
function clearSelection(): void {
  selectedIds.value = new Set()
}

const editOpen = ref(false)
const editId = ref<number | null>(null)
const editTitle = ref('')
const editRemark = ref('')
const editBusy = ref(false)
const deletedIds = ref<Set<number>>(new Set())
const deleting = ref(false)
const confirmShow = ref(false)
const confirmCount = ref(0)
const confirmImpacts = ref<{ label: string; count: number }[]>([])
const pendingDeleteIds = ref<number[]>([])

function isFrozen(row: TestReportItem): boolean {
  return row.state.toUpperCase() === 'FROZEN' || row.frozenAt !== null
}

function openEdit(row: TestReportItem): void {
  editId.value = row.id
  editTitle.value = row.title
  editRemark.value = ''
  editOpen.value = true
}
function closeEdit(): void {
  editOpen.value = false
  editId.value = null
  editTitle.value = ''
  editRemark.value = ''
}
async function submitEdit(): Promise<void> {
  if (editId.value === null || editBusy.value) return
  editBusy.value = true
  try {
    await listCrudApi.updateTestReport(projectId.value, editId.value, {
      title: editTitle.value.trim(),
      remark: editRemark.value.trim(),
    })
    closeEdit()
    await load()
    notice.value = t('w57.testReports.metaSaved')
  } catch {
    notice.value = t('testReports.failed')
  } finally {
    editBusy.value = false
  }
}
function askDelete(ids: number[]): void {
  if (ids.length === 0) return
  pendingDeleteIds.value = ids
  // 选中条数是确定的事实；关联影响面要等删除后由后端 DeleteResult.impact 回算再展示。
  confirmCount.value = ids.length
  confirmImpacts.value = [{ label: t('w57.testReports.pendingDelete'), count: ids.length }]
  confirmShow.value = true
}
function batchDelete(): void {
  askDelete([...selectedIds.value])
}
async function doDelete(): Promise<void> {
  const ids = pendingDeleteIds.value
  if (ids.length === 0 || deleting.value) return
  deleting.value = true
  try {
    let exports = 0
    let artifacts = 0
    for (const id of ids) {
      const result = await listCrudApi.deleteTestReport(projectId.value, id)
      exports += result.impact.testReportExportCount
      artifacts += result.impact.runArtifactCount
    }
    const next = new Set(deletedIds.value)
    for (const id of ids) next.add(id)
    deletedIds.value = next
    clearSelection()
    confirmShow.value = false
    pendingDeleteIds.value = []
    await load()
    notice.value = t('w57.testReports.batchDeleted', { count: ids.length, exports, artifacts })
  } catch {
    notice.value = t('testReports.failed')
  } finally {
    deleting.value = false
  }
}
async function restoreRow(row: TestReportItem): Promise<void> {
  try {
    await listCrudApi.restoreTestReport(projectId.value, row.id)
    const next = new Set(deletedIds.value)
    next.delete(row.id)
    deletedIds.value = next
    await load()
    notice.value = t('w57.testReports.restored')
  } catch {
    notice.value = t('testReports.failed')
  }
}

const columns = computed<DataTableColumns<TestReportItem>>(() => [
  {
    title: t('w57.testReports.colSelect'),
    key: '__select',
    width: 64,
    render: (row) =>
      h(NCheckbox, {
        checked: isSelected(row.id),
        'onUpdate:checked': () => toggleSelected(row.id),
        'aria-label': t('w57.testReports.selectRow', { title: row.title }),
      }),
  },
  { title: t('testReports.colTitle'), key: 'title', minWidth: 160 },
  { title: t('testReports.colRun'), key: 'runId', minWidth: 160 },
  {
    title: t('testReports.colSource'),
    key: 'sourceType',
    minWidth: 130,
    render: (row) => sourceLabel(row),
  },
  { title: t('testReports.colState'), key: 'state', minWidth: 90 },
  { title: t('testReports.colVersion'), key: 'version', minWidth: 80 },
  {
    title: t('testReports.colCreatedAt'),
    key: 'createdAt',
    minWidth: 150,
    render: (row) => formatDateTime(row.createdAt),
  },
  {
    title: t('w57.testReports.colActions'),
    key: 'actions',
    width: 220,
    render: (row) => {
      if (isFrozen(row)) return h('span', { class: 'muted' }, t('w57.testReports.frozenHint'))
      if (deletedIds.value.has(row.id)) {
        return h(
          NButton,
          { text: true, size: 'small', onClick: () => void restoreRow(row) },
          { default: () => t('w57.testReports.restore') },
        )
      }
      return h('span', { class: 'row-actions' }, [
        h(
          NButton,
          { text: true, size: 'small', onClick: () => openEdit(row) },
          { default: () => t('w57.testReports.edit') },
        ),
        h(
          NButton,
          { text: true, type: 'error', size: 'small', onClick: () => askDelete([row.id]) },
          { default: () => t('w57.testReports.softDelete') },
        ),
      ])
    },
  },
])

async function load(): Promise<void> {
  loading.value = true
  failed.value = false
  try {
    rows.value = await testReportsApi.list(projectId.value)
  } catch {
    failed.value = true
    notice.value = t('testReports.failed')
  } finally {
    loading.value = false
  }
}

/**
 * 选中执行记录后自动带出默认标题（判据③）。
 *
 * 默认标题带上 runId 而不是只有日期：同一份 run 可能建多份报告（不同口径），
 * 只写日期会得到两个一模一样、事后分不清的标题。
 */
function onPickRun(run: ExecutionRun | null): void {
  if (!run) {
    title.value = ''
    return
  }
  title.value = t('testReports.defaultTitle', { runId: run.runId })
}

/** 切来源时清掉另一种来源的选择，避免「界面上是按用例集、提交用的却是上一轮的 run」。 */
function switchSource(next: 'run' | 'case_set'): void {
  if (source.value === next) return
  source.value = next
  title.value = ''
  if (next === 'case_set') {
    runId.value = null
    manualRunId.value = ''
  } else {
    caseSetId.value = null
    caseSetRunId.value = ''
  }
}

/** 选用例集后带出「用例集报告 <名字>」：标题里不写 runId，因为数据源还没定（最近一次）。 */
function onPickCaseSet(picked: TestSet | null): void {
  title.value = picked ? t('testReports.defaultCaseSetTitle', { name: picked.name }) : ''
}

async function create(): Promise<void> {
  if (!canSubmit.value) return
  try {
    if (source.value === 'case_set') {
      await testReportsApi.createFromCaseSet(
        projectId.value,
        caseSetId.value!,
        caseSetRunId.value,
        title.value.trim(),
      )
    } else {
      await testReportsApi.create(projectId.value, effectiveRunId.value, title.value.trim())
    }
    runId.value = null
    manualRunId.value = ''
    caseSetId.value = null
    caseSetRunId.value = ''
    title.value = ''
    notice.value = t('testReports.created')
    await load()
  } catch {
    notice.value = t('testReports.failed')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('testReports.title') }}</h1>
      <p class="muted">{{ t('testReports.subtitle') }}</p>
    </header>

    <form class="bar create-bar" @submit.prevent="create">
      <div class="source-switch" role="group" :aria-label="t('testReports.colSource')">
        <button
          class="btn btn-ghost"
          :class="{ 'is-active': source === 'run' }"
          type="button"
          data-testid="source-run"
          @click="switchSource('run')"
        >
          {{ t('testReports.sourceRun') }}
        </button>
        <button
          class="btn btn-ghost"
          :class="{ 'is-active': source === 'case_set' }"
          type="button"
          data-testid="source-case-set"
          @click="switchSource('case_set')"
        >
          {{ t('testReports.sourceCaseSet') }}
        </button>
      </div>

      <template v-if="source === 'run'">
        <RunPicker v-if="!advanced" v-model="runId" :project-id="projectId" @select="onPickRun" />
        <NInput
          v-else
          v-model:value="manualRunId"
          class="input"
          :placeholder="t('testReports.runPlaceholder')"
          data-testid="manual-run-id"
        />
      </template>
      <template v-else>
        <CaseSetPicker v-model="caseSetId" :project-id="projectId" @select="onPickCaseSet" />
        <NInput
          v-model:value="caseSetRunId"
          class="input"
          :placeholder="t('testReports.caseSetRunOptional')"
          data-testid="case-set-run-id"
        />
      </template>

      <NInput
        v-model:value="title"
        class="title-input"
        :placeholder="t('testReports.titlePlaceholder')"
        data-testid="draft-title"
      />
      <button class="btn" type="submit" :disabled="!canSubmit">
        {{ t('testReports.create') }}
      </button>
      <button
        v-if="source === 'run'"
        class="btn btn-ghost"
        type="button"
        data-testid="toggle-advanced"
        @click="advanced = !advanced"
      >
        {{ advanced ? t('testReports.backToPicker') : t('testReports.advanced') }}
      </button>
      <button class="btn btn-ghost" type="button" @click="load">{{ t('testReports.refresh') }}</button>
    </form>

    <p v-if="notice" class="notice">{{ notice }}</p>

    <ListScaffold
      :columns="columns"
      :data="visibleRows"
      :loading="loading"
      :error="failed"
      :filtered="reportFiltered"
      :empty-title="t('testReports.empty')"
      :row-key="(row: TestReportItem) => row.id"
      :batch-active="selectionCount > 0"
      show-refresh
      @refresh="load"
    >
      <template #title>
        <h2 class="report-list-title">{{ t('w57.testReports.listTitle') }}</h2>
      </template>
      <template #filters>
        <div class="report-filters" data-check-semantic>
          <NInput
            :value="reportFilters.q.value"
            clearable
            style="width: 200px"
            :placeholder="t('w57.testReports.searchReport')"
            :aria-label="t('w57.testReports.searchReport')"
            @update:value="(v: string) => reportFilters.setQuery(v)"
          />
          <ReadOnlyNotice compact :text="t('w57.testReports.readonlyText')" :reason="t('w57.testReports.readonlyReason')" />
        </div>
      </template>
      <template #batch>
        <span>{{ t('w57.testReports.selected', { count: selectionCount }) }}</span>
        <button class="btn" type="button" @click="batchDelete">{{ t('w57.testReports.batchDelete') }}</button>
        <button class="btn" type="button" @click="clearSelection">{{ t('w57.testReports.clearSelection') }}</button>
      </template>
      <!-- 宿主自渲染 DataTable，才能把 update:filters 接回 URL；空态三态口径复用骨架的 emptyProps -->
      <template #table="{ emptyProps }">
        <DataTable
          :columns="columns"
          :data="visibleRows"
          :loading="loading"
          :row-key="(row: TestReportItem) => row.id"
          :error="failed"
          :empty-title="t('testReports.empty')"
          v-bind="emptyProps"
          filterable
          :filter-columns="reportFilterColumns"
          :filters="reportFilterValues"
          @update:filters="onReportFilterChange"
        />
      </template>
    </ListScaffold>

    <!-- 元数据编辑 / 危险操作确认；不渲染任何「新增」按钮 -->
    <NModal v-model:show="editOpen">
      <div class="crud-dialog" role="dialog" aria-modal="true" :aria-label="t('w57.testReports.editMeta')">
        <h2 class="card-title">{{ t('w57.testReports.editMeta') }}</h2>
        <label class="field">
          <span>{{ t('w57.testReports.title') }}</span>
          <NInput v-model:value="editTitle" />
        </label>
        <label class="field">
          <span>{{ t('w57.testReports.remark') }}</span>
          <NInput v-model:value="editRemark" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
        </label>
        <div class="crud-dialog__actions">
          <NButton @click="closeEdit">{{ t('w57.testReports.cancel') }}</NButton>
          <NButton type="primary" :loading="editBusy" @click="submitEdit">{{ t('w57.testReports.save') }}</NButton>
        </div>
      </div>
    </NModal>

    <ConfirmAction
      v-model:show="confirmShow"
      :count="confirmCount"
      :impacts="confirmImpacts"
      :loading="deleting"
      :title="t('w57.testReports.confirmTitle')"
      :description="t('w57.testReports.confirmDesc')"
      @confirm="doDelete"
    />
  </div>
</template>

<style scoped>
.create-bar {
  align-items: center;
  flex-wrap: wrap;
}
.title-input {
  width: 240px;
}
.source-switch {
  display: inline-flex;
  gap: 4px;
}
.source-switch .is-active {
  background: var(--accent-soft);
  color: var(--accent);
}
.case-set-select {
  min-width: 240px;
}

/* W57-32：列表骨架标题（≤2 行）、筛选条、编辑弹层 */
.report-list-title {
  margin: 0;
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  color: var(--text);
  font-size: var(--font-size-md);
  font-weight: 600;
  word-break: break-all;
}

.report-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: var(--space-2) 0;
  font-size: 12px;
  color: var(--text-muted);
}

.crud-dialog {
  width: min(420px, 92vw);
  padding: var(--space-5);
  border-radius: var(--radius-md);
  background: var(--panel);
  box-shadow: var(--shadow);
}

.crud-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-4);
}

/* h() 渲染的操作列按钮不带 scoped 属性 */
:deep(.row-actions) {
  display: inline-flex;
  gap: var(--space-2);
}
</style>
