<script setup lang="ts">
import { computed, h, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  NButton,
  NCheckbox,
  NInput,
  NInputNumber,
  NModal,
  NPagination,
  NPopover,
  NRadioButton,
  NRadioGroup,
  NSelect,
  type DataTableColumns,
} from 'naive-ui'
import {
  caseApi,
  type BatchAction,
  type BatchResult,
  type CapabilityStat,
  type ImportPreview,
  type ImportRow,
  type TestCase,
} from '@/api/cases'
import { useAuthStore } from '@/stores/auth'
import { executionApi, type BlockedCase, type SafetyPrecheck } from '@/api/execution'
import DataTable from '@/components/DataTable.vue'
import { useListPageSize, type PageSize } from '@/composables/useListPageSize'
import CaseDetailPanel from '@/components/CaseDetailPanel.vue'
import SelectionBar from '@/components/SelectionBar.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import StatusTag from '@/components/StatusTag.vue'
import AppIcon from '@/components/AppIcon.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageToolbar from '@/components/ui/PageToolbar.vue'
import FilterBar from '@/components/ui/FilterBar.vue'
import ColumnSettingsDialog from '@/components/ui/ColumnSettingsDialog.vue'
import { reconcileColumns, visibleKeys, type ColumnOption } from '@/components/ui/columnSettings'
import {
  applyViewState,
  canDeleteView,
  loadLocalColumns,
  saveLocalColumns,
  useSavedViews,
} from '@/composables/useSavedViews'
import type { CaseViewPayload, SavedView } from '@/api/views'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { EMPTY } from '@/utils/format'
import { useSelection } from '@/composables/useSelection'
import { useDraft, isDraftDirty } from '@/composables/useDraft'
import { confirmAction } from '@/composables/useConfirm'
import {
  PRIORITIES,
  REVIEW_STATUSES,
  SAFETY_LEVELS,
  blocksExecution,
  priorityLabel,
  reviewStatusLabel,
  reviewStatusTone,
  safetyLevelLabel,
  safetyLevelTone,
} from '@/utils/statusLabels'

const { t } = useI18n()
const auth = useAuthStore()

const keyword = ref('')
const priority = ref('')
const reviewStatus = ref('')
const domain = ref('')
const safetyLevel = ref('')
const page = ref(1)
// 页大小进入视图 payload 后必须可回填，不能再是常量；
// 同时要跨页记忆 + 按视口估算（FR-v3.9-04），统一走 useListPageSize。
const { size, pageSizes } = useListPageSize()
const total = ref(0)
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const message = ref('')

const cases = ref<TestCase[]>([])
const capabilities = ref<CapabilityStat[]>([])
const showForm = ref(false)
const showImport = ref(false)

// ---- 批量选择（UX-02，FR-v0.2-35/36）----
const pageCaseIds = computed(() => cases.value.map((c) => c.id))
const selection = useSelection(pageCaseIds)
const {
  selectedIds,
  count: selectedCount,
  crossPageCount,
  pageAllSelected,
  pageIndeterminate,
  isSelected,
  toggle,
  togglePage,
  retain,
  clear: clearSelection,
} = selection
const batchResult = ref<BatchResult | null>(null)
/** 最近一次批量动作名：SelectionBar 据此判断是否可撤销（删除等不可逆动作不显示）。 */
const batchAction = ref<string | null>(null)
const batchLoading = ref(false)
const batchPriority = ref('P1')
const batchDomain = ref('')
const batchSafety = ref('L1')

// 勾选用例执行（EXEC-02）：绑定解析在后端，前端只负责收集参数
const runOpen = ref(false)
const runLoading = ref(false)
const runEnv = ref<number | null>(null)
const runConcurrency = ref(1)
const runTimeout = ref(3600)
/** EXEC-12：单用例超时；0 = 沿用后端全局默认。 */
const runTestTimeout = ref(0)
const runTag = ref('')
const runError = ref<ErrorLine | null>(null)

/** 用例表单字段（新建与编辑共用）；草稿也按同一结构落盘。 */
interface CaseForm {
  caseCode: string
  title: string
  category: string
  priority: string
  safetyLevel: string
  domainTag: string
  capability: string
  precondition: string
  expected: string
  description: string
}

function emptyForm(): CaseForm {
  return {
    caseCode: '',
    title: '',
    category: '',
    priority: 'P2',
    // 新建即「未判定」：安全级是需要人工判断的结论，不能默认给个 L1 蒙过去（ADR-0013）
    safetyLevel: 'LX',
    domainTag: '',
    capability: '',
    precondition: '',
    expected: '',
    description: '',
  }
}

const form = reactive<CaseForm>(emptyForm())
/** 打开表单时的快照：草稿脏值判断与「没改就不保存」都以此为基准。 */
let formInitial: CaseForm = emptyForm()

/** 编辑中的用例 id；null 表示新建。 */
const editingId = ref<number | null>(null)
const draftOpen = ref(false)
const draftSavedAt = ref<number | null>(null)
const pendingDraft = ref<CaseForm | null>(null)

const caseDraft = useDraft<CaseForm>(() => ({
  projectId: projectId.value,
  formKey: 'case',
  recordId: editingId.value,
}))

let draftTimer: ReturnType<typeof setTimeout> | undefined

function snapshotForm(): CaseForm {
  return { ...form }
}

function resetForm(next: CaseForm = emptyForm()): void {
  Object.assign(form, next)
  formInitial = { ...next }
}

function clearDraftTimer(): void {
  if (draftTimer) {
    clearTimeout(draftTimer)
    draftTimer = undefined
  }
}

/** 编辑中每 1.5 秒防抖保存；没改动过就不写，避免打开表单也留下一份空草稿。 */
watch(
  form,
  () => {
    if (!showForm.value) return
    clearDraftTimer()
    draftTimer = setTimeout(() => {
      if (!showForm.value) return
      if (!isDraftDirty(snapshotForm(), formInitial)) return
      caseDraft.save(snapshotForm())
    }, 1500)
  },
  { deep: true },
)

function draftTimeLabel(): string {
  if (!draftSavedAt.value) return ''
  return new Date(draftSavedAt.value).toLocaleString()
}

/** 同一记录再次打开：有比初始值更新的草稿才提示，纯空草稿直接丢掉。 */
function promptRestoreDraft(): void {
  const draft = caseDraft.read()
  if (!draft || !isDraftDirty(draft.value, formInitial)) {
    if (draft) caseDraft.clear()
    return
  }
  pendingDraft.value = draft.value
  draftSavedAt.value = draft.savedAt
  draftOpen.value = true
}

function restoreDraft(): void {
  if (pendingDraft.value) Object.assign(form, pendingDraft.value)
  pendingDraft.value = null
  draftOpen.value = false
  message.value = t('draft.restored')
}

function discardDraft(): void {
  caseDraft.clear()
  pendingDraft.value = null
  draftOpen.value = false
  draftSavedAt.value = null
}

function openCreateForm(): void {
  editingId.value = null
  resetForm()
  showForm.value = true
  error.value = null
  message.value = ''
  promptRestoreDraft()
}

function openEditForm(item: TestCase): void {
  editingId.value = item.id
  resetForm({
    caseCode: item.caseCode,
    title: item.title,
    category: item.category ?? '',
    priority: item.priority,
    safetyLevel: item.safetyLevel,
    domainTag: item.domainTag ?? '',
    capability: item.capability ?? '',
    precondition: item.precondition ?? '',
    expected: item.expected ?? '',
    description: item.description ?? '',
  })
  showForm.value = true
  error.value = null
  message.value = ''
  promptRestoreDraft()
}

/** 关闭表单保留草稿：有改动就立即落盘并提示，不把用户输入静默丢掉。 */
function closeForm(): void {
  clearDraftTimer()
  if (showForm.value && isDraftDirty(snapshotForm(), formInitial)) {
    caseDraft.save(snapshotForm())
    message.value = t('draft.savedHint')
  }
  showForm.value = false
  editingId.value = null
}

const importFile = ref<File | null>(null)
const preview = ref<ImportPreview | null>(null)
const importMessage = ref('')

const projectId = computed(() => auth.projectId ?? 0)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

// 下拉项首项 valueless 的「全部」用空串表示，与 AuditView 保持一致（不引入 null 语义）
const priorityOptions = computed(() => [
  { label: t('cases.allPriority'), value: '' },
  ...PRIORITIES.map((item) => ({ label: priorityLabel(item), value: item })),
])
const reviewOptions = computed(() => [
  { label: t('cases.allStatus'), value: '' },
  ...REVIEW_STATUSES.map((item) => ({ label: reviewStatusLabel(item), value: item })),
])
const safetyOptions = computed(() => [
  { label: t('cases.allSafety'), value: '' },
  ...SAFETY_LEVELS.map((item) => ({ label: safetyLevelLabel(item), value: item })),
])
const formPriorityOptions = computed(() =>
  PRIORITIES.map((item) => ({ label: priorityLabel(item), value: item })),
)
const formSafetyOptions = computed(() =>
  SAFETY_LEVELS.map((item) => ({ label: safetyLevelLabel(item), value: item })),
)

/**
 * 选中项里禁止自动执行的用例（ADR-0013：L2 高危 / LX 未判定按 L2 对待）。
 *
 * 勾选是**跨页**的：当前页之外那些用例的安全级前端根本看不到，只靠本地过滤会漏。
 * 因此选中集合一变就调后端预检（`POST /execution/cases/safety-precheck`），以预检结果为准；
 * 预检还没回来（或请求失败）时退回「只看当前页」的本地判断，宁可先挡住眼前这一页，
 * 真正兜底的仍是执行入口的整单 403。
 */
const precheck = ref<SafetyPrecheck | null>(null)
const precheckedKey = ref('')

const selectionKey = computed(() => [...selectedIds.value].sort((a, b) => a - b).join(','))

const blockedSelected = computed<BlockedCase[]>(() => {
  if (precheck.value && precheckedKey.value === selectionKey.value) {
    return precheck.value.blocked
  }
  return cases.value
    .filter((item) => isSelected(item.id) && blocksExecution(item.safetyLevel))
    .map((item) => ({ caseId: item.id, caseCode: item.caseCode, safetyLevel: item.safetyLevel }))
})
const blockedCodes = computed(() =>
  blockedSelected.value.map((item) => `${item.caseCode}(${item.safetyLevel})`).join('、'),
)

// ---- 保存视图 / 列设置（v3.1.0「顺手」）----
const CASE_COLUMN_KEYS = [
  'caseCode',
  'title',
  'priority',
  'safetyLevel',
  'reviewStatus',
  'domainTag',
  'capability',
] as const
type CaseColumnKey = (typeof CASE_COLUMN_KEYS)[number]

const CASE_COLUMN_LABELS: Record<CaseColumnKey, () => string> = {
  caseCode: () => t('cases.caseCode'),
  title: () => t('cases.title'),
  priority: () => t('cases.priority'),
  safetyLevel: () => t('cases.safetyLevel'),
  reviewStatus: () => t('cases.status'),
  domainTag: () => t('cases.domain'),
  capability: () => t('cases.capability'),
}

const columnOrder = ref<CaseColumnKey[]>([...CASE_COLUMN_KEYS])
const hiddenColumns = ref<CaseColumnKey[]>([])
const showColumns = ref(false)

const columnOptions = computed<ColumnOption[]>(() =>
  columnOrder.value.map((key) => ({
    key,
    label: CASE_COLUMN_LABELS[key](),
    visible: !hiddenColumns.value.includes(key),
  })),
)

function defaultColumnOptions(): ColumnOption[] {
  return CASE_COLUMN_KEYS.map((key) => ({
    key,
    label: CASE_COLUMN_LABELS[key](),
    visible: true,
  }))
}

/** 把「可见列 + 顺序」一次性写进状态；不传 keys 表示恢复默认（全可见）。 */
function applyColumnKeys(keys: string[] | null): void {
  const reconciled = reconcileColumns(defaultColumnOptions(), keys)
  columnOrder.value = reconciled.map((column) => column.key as CaseColumnKey)
  hiddenColumns.value = reconciled
    .filter((column) => !column.visible)
    .map((column) => column.key as CaseColumnKey)
}

const savedViews = useSavedViews(() => projectId.value, 'case')
const views = savedViews.views
const viewsLoading = savedViews.loading
const activeViewId = ref<number | null>(null)
const activeView = computed(
  () => views.value.find((view) => view.id === activeViewId.value) ?? null,
)
const showViewMenu = ref(false)
const showSaveView = ref(false)
const viewName = ref('')
const viewDefault = ref(false)
const viewError = ref('')
const filtersCollapsed = ref(false)

const activeFilterCount = computed(
  () => [keyword, priority, reviewStatus, domain, safetyLevel]
    .filter((field) => field.value.trim() !== '').length,
)

let viewsSeq = 0
/** 应用视图时由页面自己 load 一次，阻止筛选 watcher 再发一遍相同请求 */
let skipFilterReload = false

function currentPayload(): CaseViewPayload {
  return {
    keyword: keyword.value,
    domain: domain.value,
    priority: priority.value,
    reviewStatus: reviewStatus.value,
    safetyLevel: safetyLevel.value,
    size: size.value,
    columns: columnOrder.value.filter((key) => !hiddenColumns.value.includes(key)),
    sort: null,
  }
}

function isOwnView(view: SavedView): boolean {
  return canDeleteView(view, auth.user?.id)
}

/** 应用视图：回填筛选/页大小/列，page 归 1（不改后端分页语义）。 */
function applySavedView(view: SavedView): void {
  const payload = savedViews.resolve(view)
  const state = applyViewState(payload)
  skipFilterReload = true
  keyword.value = state.keyword
  domain.value = state.domain
  priority.value = state.priority
  reviewStatus.value = state.reviewStatus
  safetyLevel.value = state.safetyLevel
  size.value = state.size as PageSize
  applyColumnKeys(state.columns)
  page.value = state.page
  activeViewId.value = view.id
  showViewMenu.value = false
  viewError.value = ''
  void load()
  void nextTick(() => {
    skipFilterReload = false
  })
}

function applyDefaultView(items: SavedView[]): void {
  if (activeViewId.value !== null) return
  const defaultView = items.find((view) => view.isDefault)
  if (defaultView) applySavedView(defaultView)
}

async function loadViews(): Promise<void> {
  const seq = ++viewsSeq
  const items = await savedViews.load()
  if (seq !== viewsSeq) return
  applyDefaultView(items)
}

function restoreLocalColumns(): void {
  applyColumnKeys(loadLocalColumns(projectId.value, 'case'))
}

function clearFilters(): void {
  keyword.value = ''
  domain.value = ''
  priority.value = ''
  reviewStatus.value = ''
  safetyLevel.value = ''
  activeViewId.value = null
}

function openSaveView(): void {
  showViewMenu.value = false
  viewName.value = ''
  viewDefault.value = false
  viewError.value = ''
  showSaveView.value = true
}

async function submitView(): Promise<void> {
  viewError.value = ''
  const result = await savedViews.save(viewName.value, currentPayload(), viewDefault.value)
  if (!result.ok) {
    if (result.reason === 'empty') viewError.value = t('views.emptyName')
    else if (result.reason === 'conflict') {
      viewError.value = t('views.conflict', { name: viewName.value.trim() })
    } else {
      viewError.value = t('views.saveFailed')
      error.value = localizeError(result.error ?? savedViews.error.value)
    }
    return
  }
  activeViewId.value = result.view.id
  showSaveView.value = false
  message.value = t('views.saved')
}

async function deleteView(view: SavedView): Promise<void> {
  if (!(await confirmAction(t('views.confirmDelete', { name: view.name }), { tone: 'danger' }))) {
    return
  }
  const ok = await savedViews.remove(view.id)
  if (!ok) {
    error.value = localizeError(savedViews.error.value)
    return
  }
  if (activeViewId.value === view.id) activeViewId.value = null
  message.value = t('views.deleted')
}

async function setDefaultView(view: SavedView): Promise<void> {
  const ok = await savedViews.makeDefault(view.id)
  if (!ok) {
    error.value = localizeError(savedViews.error.value)
    return
  }
  message.value = t('views.defaultSaved')
}

/** 列设置确定：有当前视图就写回 payload，否则只存本机（projectId + scope）。 */
async function onColumnsConfirm(options: ColumnOption[]): Promise<void> {
  applyColumnKeys(visibleKeys(options))
  const view = activeView.value
  if (view && isOwnView(view)) {
    const ok = await savedViews.updatePayload(view.id, currentPayload())
    if (!ok) {
      error.value = localizeError(savedViews.error.value)
      return
    }
    message.value = t('views.columnsSaved')
    return
  }
  saveLocalColumns(projectId.value, 'case', columnOrder.value.filter(
    (key) => !hiddenColumns.value.includes(key),
  ))
  message.value = t('views.columnsSavedLocal')
}

/**
 * 用例列定义（UX-04b 逐页迁移）。
 *
 * 勾选列**没有**用 NDataTable 内置的 `type: 'selection'`：内置列只认 `checked-row-keys`，
 * 它会在数据刷新时把不在当前页的键丢掉，而 `useSelection` 的契约是**跨页保留**
 * （FR-v0.2-35）。所以这里自己渲染 `NCheckbox`，状态仍由 `useSelection` 持有，
 * 表头半选态也照旧从 `pageIndeterminate` 派生。
 */
const dataColumns = computed<Record<CaseColumnKey, DataTableColumns<TestCase>[number]>>(() => ({
  caseCode: {
    title: t('cases.caseCode'),
    key: 'caseCode',
    width: 120,
    ellipsis: { tooltip: true },
  },
  title: {
    title: t('cases.title'),
    key: 'title',
    minWidth: 220,
    ellipsis: { tooltip: true },
  },
  priority: {
    title: t('cases.priority'),
    key: 'priority',
    width: 90,
    render: (row) => priorityLabel(row.priority),
  },
  safetyLevel: {
    title: t('cases.safetyLevel'),
    key: 'safetyLevel',
    width: 120,
    render: (row) =>
      h(StatusTag, {
        label: safetyLevelLabel(row.safetyLevel),
        tone: safetyLevelTone(row.safetyLevel),
      }),
  },
  reviewStatus: {
    title: t('cases.status'),
    key: 'reviewStatus',
    width: 110,
    render: (row) =>
      h(StatusTag, {
        label: reviewStatusLabel(row.reviewStatus),
        tone: reviewStatusTone(row.reviewStatus),
      }),
  },
  domainTag: {
    title: t('cases.domain'),
    key: 'domainTag',
    width: 120,
    render: (row) => row.domainTag || EMPTY,
  },
  capability: {
    title: t('cases.capability'),
    key: 'capability',
    width: 140,
    render: (row) => row.capability || EMPTY,
  },
}))

const columns = computed<DataTableColumns<TestCase>>(() => [
  {
    // W0.8 / N14：行内展开详情（前置条件/步骤/预期），按需拉 GET /cases/{id}。
    type: 'expand',
    width: 32,
    renderExpand: (row) =>
      h(CaseDetailPanel, { projectId: projectId.value, caseId: row.id }),
  },
  {
    key: 'selection',
    width: 36,
    // 勾选框在单元格里默认左贴边，与表头框对不齐；居中后上下两排才在一条竖线上
    align: 'center',
    title: () =>
      h(NCheckbox, {
        checked: pageAllSelected.value,
        indeterminate: pageIndeterminate.value,
        'aria-label': t('cases.batch.barLabel'),
        onUpdateChecked: () => togglePage(),
      }),
    render: (row) =>
      h(NCheckbox, {
        checked: isSelected(row.id),
        'aria-label': `${t('cases.caseCode')}: ${row.caseCode}`,
        onUpdateChecked: () => toggle(row.id),
      }),
  },
  ...columnOrder.value
    .filter((key) => !hiddenColumns.value.includes(key))
    .map((key) => dataColumns.value[key]),
  {
    title: t('common.actions'),
    key: 'actions',
    width: 240,
    render: (row) =>
      h('div', { class: 'row-actions' }, [
        (row.reviewStatus === 'draft' || row.reviewStatus === 'rejected') &&
          h(
            NButton,
            {
              text: true,
              size: 'small',
              onClick: () => openEditForm(row),
            },
            { default: () => t('common.edit') },
          ),
        (row.reviewStatus === 'draft' || row.reviewStatus === 'rejected') &&
          h(
            NButton,
            {
              text: true,
              type: 'primary',
              size: 'small',
              onClick: () => void submitCase(row),
            },
            { default: () => t('cases.submit') },
          ),
        row.reviewStatus === 'pending' &&
          h(
            NButton,
            {
              text: true,
              type: 'primary',
              size: 'small',
              onClick: () => void reviewCase(row, true),
            },
            { default: () => t('cases.approve') },
          ),
        row.reviewStatus === 'pending' &&
          h(
            NButton,
            {
              text: true,
              type: 'warning',
              size: 'small',
              onClick: () => void reviewCase(row, false),
            },
            { default: () => t('cases.reject') },
          ),
        h(
          NButton,
          {
            text: true,
            type: 'error',
            size: 'small',
            onClick: () => void removeCase(row),
          },
          { default: () => t('common.delete') },
        ),
      ]),
  },
])

async function load(): Promise<void> {
  error.value = null
  message.value = ''
  if (!projectId.value) {
    cases.value = []
    capabilities.value = []
    return
  }
  loading.value = true
  try {
    const [result, caps] = await Promise.all([
      caseApi.page(projectId.value, {
        keyword: keyword.value,
        priority: priority.value,
        reviewStatus: reviewStatus.value,
        domain: domain.value,
        safetyLevel: safetyLevel.value,
        page: page.value,
        size: size.value,
      }),
      caseApi.capabilities(projectId.value),
    ])
    cases.value = result.items
    total.value = result.total
    capabilities.value = caps
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
}

// immediate 不能省：projectId 来自 localStorage，进入页面时通常「一开始就是目标值」，
// 不加 immediate 时 watch 不触发，load() 永远不执行 —— 页面会一直显示「暂无数据」，
// 而用户看不出是没请求还是真没数据。ReportsView 一直是这么写的，这三页此前漏了。
watch([projectId], () => {
  page.value = 1
  showForm.value = false
  showImport.value = false
  clearDraftTimer()
  editingId.value = null
  preview.value = null
  // 切项目后选择集合里的 id 属于旧项目，必须清空，否则批量动作会打到错误项目
  clearSelection()
  batchResult.value = null
  batchAction.value = null
  activeViewId.value = null
  restoreLocalColumns()
  void loadViews()
  void load()
}, { immediate: true })

watch([keyword, priority, reviewStatus, domain, safetyLevel, size], () => {
  if (skipFilterReload) return
  page.value = 1
  void load()
})

/**
 * 选中集合变化 → 后端安全级预检（EXEC-15 残余）。
 *
 * 200ms 防抖：连点勾选时不必每条都打一次接口。用自增序号丢弃迟到响应，
 * 避免「先发的请求后回来」把新选中集合的结论覆盖成旧的。
 */
let precheckTimer: number | undefined
let precheckSeq = 0

watch(selectedIds, () => {
  window.clearTimeout(precheckTimer)
  if (selectedIds.value.size === 0 || !projectId.value) {
    precheck.value = null
    precheckedKey.value = ''
    return
  }
  precheckTimer = window.setTimeout(() => void loadPrecheck(), 200)
})

onBeforeUnmount(() => window.clearTimeout(precheckTimer))

async function loadPrecheck(): Promise<void> {
  const pid = projectId.value
  const ids = [...selectedIds.value]
  if (!pid || ids.length === 0) return
  const seq = ++precheckSeq
  try {
    const result = await executionApi.safetyPrecheck(pid, ids)
    if (seq !== precheckSeq) return
    precheck.value = result
    precheckedKey.value = selectionKey.value
  } catch {
    // 预检失败不阻塞操作：清空结果让它退回本地判断，执行入口仍会整单拦截
    if (seq === precheckSeq) {
      precheck.value = null
      precheckedKey.value = ''
    }
  }
}

/**
 * 页大小切换（FR-v3.9-04）。
 * 不改页码也能用：`watch([... size])` 会把 page 归 1 再 load，
 * 所以这里只写值，避免同一动作发两次请求。
 */
function changePageSize(next: number): void {
  size.value = next as PageSize
}

function turnPage(next: number): void {
  if (next < 1 || next > pageCount.value) return
  page.value = next
  void load() // 选择跨页保留（FR-v0.2-35），翻页不清空
}

/** 展开/收起勾选用例的执行表单（EXEC-02）。 */
function toggleRunForm(): void {
  // L2/LX 在执行入口就被拒（ADR-0013），这里提前给出原因，别让用户点了才看到 403
  if (!runOpen.value && blockedSelected.value.length > 0) {
    error.value = {
      summary: t('cases.run.blocked', { codes: blockedCodes.value }),
      detail: null,
    }
    return
  }
  runOpen.value = !runOpen.value
  runError.value = null
  message.value = ''
}

/**
 * 勾选用例执行（EXEC-02，v0.4 候选提前实现）：只传用例 id，绑定与未绑定校验都由后端完成。
 * 未绑定的用例由后端整单 400 并列出编号，这里原样把错误展示出来。
 */
async function submitRun(): Promise<void> {
  if (selectedCount.value === 0) return
  if (blockedSelected.value.length > 0) {
    runError.value = {
      summary: t('cases.run.blocked', { codes: blockedCodes.value }),
      detail: null,
    }
    return
  }
  runLoading.value = true
  runError.value = null
  message.value = ''
  try {
    const run = await executionApi.runCases(projectId.value, {
      caseIds: [...selectedIds.value],
      environmentId: runEnv.value,
      concurrency: runConcurrency.value,
      timeoutSeconds: runTimeout.value,
      testTimeoutSeconds: Number(runTestTimeout.value) || 0,
      includeTag: runTag.value.trim() || null,
    })
    runOpen.value = false
    batchResult.value = null
    clearSelection()
    message.value = t('cases.run.created', { runId: run.runId })
  } catch (e) {
    runError.value = localizeError(e)
  } finally {
    runLoading.value = false
  }
}

/**
 * 批量动作（FR-v0.2-36~39）：部分成功返回 200，失败项保留勾选可重试。
 * 仅「ids 空」不发起请求；参数级 400（如驳回缺意见）在本地前置拦截。
 *
 * 驳回意见仍走 `window.prompt`：项目还没接 `NDialogProvider`（只有 NConfigProvider），
 * 换成 `useDialog` 要先在 App.vue 加 provider，属于另一处改动。此处保持既有行为，
 * 已登记为待办，不在本次视图迁移里顺手改。
 */
async function runBatch(action: BatchAction): Promise<void> {
  error.value = null
  message.value = ''
  const ids = [...selection.selectedIds.value]
  if (ids.length === 0) return

  let comment: string | undefined
  if (action === 'reject') {
    const input = window.prompt(t('cases.batch.rejectComment'))
    comment = input?.trim() ?? ''
    if (!comment) return // 驳回必填意见，空则中止
  }
  if (action === 'delete' && !(await confirmAction(t('cases.batch.confirmDelete'), { tone: 'danger' }))) return
  if (action === 'setPriority' && !batchPriority.value) return
  if (action === 'setDomainTag' && !batchDomain.value.trim()) {
    error.value = { summary: t('cases.batch.setDomainTag'), detail: null }
    return
  }
  if (action === 'setSafetyLevel' && !batchSafety.value) return

  batchLoading.value = true
  try {
    const result = await caseApi.batch(projectId.value, {
      action,
      ids,
      comment,
      priority: action === 'setPriority' ? batchPriority.value : undefined,
      domainTag: action === 'setDomainTag' ? batchDomain.value.trim() : undefined,
      safetyLevel: action === 'setSafetyLevel' ? batchSafety.value : undefined,
    })
    batchResult.value = result
    batchAction.value = action
    // 失败项保留选中：重试只作用于它们（FR-v0.2-36）
    retain(result.items.filter((i) => !i.ok).map((i) => i.id))
    await load()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    batchLoading.value = false
  }
}

function onSelectionClear(): void {
  clearSelection()
  batchResult.value = null
  batchAction.value = null
}

/** 撤销成功后后端已恢复数据，重新拉列表，避免页面继续显示被撤掉的状态。 */
function onUndone(): void {
  void load()
}

async function createCase(): Promise<void> {
  error.value = null
  message.value = ''
  if (!form.caseCode.trim() || !form.title.trim()) {
    error.value = { summary: t('cases.requiredCodeTitle'), detail: null }
    return
  }
  const command = {
    caseCode: form.caseCode.trim(),
    title: form.title.trim(),
    category: form.category,
    priority: form.priority,
    safetyLevel: form.safetyLevel,
    domainTag: form.domainTag,
    capability: form.capability,
    precondition: form.precondition,
    expected: form.expected,
    description: form.description,
  }
  try {
    if (editingId.value !== null) {
      await caseApi.update(projectId.value, editingId.value, command)
    } else {
      await caseApi.create(projectId.value, command)
    }
    // 提交成功即清除草稿：留着只会在下次打开时提示恢复一份已经生效的内容
    clearDraftTimer()
    caseDraft.clear()
    showForm.value = false
    editingId.value = null
    resetForm()
    await load()
    message.value = t('common.saved')
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function submitCase(item: TestCase): Promise<void> {
  await act(() => caseApi.submit(projectId.value, item.id))
}

async function reviewCase(item: TestCase, approved: boolean): Promise<void> {
  await act(() => caseApi.review(projectId.value, item.id, approved))
}

async function act(action: () => Promise<unknown>): Promise<void> {
  error.value = null
  try {
    await action()
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function removeCase(item: TestCase): Promise<void> {
  if (!(await confirmAction(t('common.confirmDelete'), { tone: 'danger' }))) return
  await act(() => caseApi.remove(projectId.value, item.id))
}

function pickFile(event: Event): void {
  const input = event.target as HTMLInputElement
  importFile.value = input.files?.[0] ?? null
  preview.value = null
  importMessage.value = ''
}

async function runPreview(): Promise<void> {
  error.value = null
  importMessage.value = ''
  preview.value = null
  if (!importFile.value) {
    error.value = { summary: t('cases.import.pickFile'), detail: null }
    return
  }
  try {
    preview.value = await caseApi.previewImport(projectId.value, importFile.value)
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function runCommit(): Promise<void> {
  if (!preview.value || preview.value.errors.length > 0) {
    error.value = { summary: t('cases.import.fixErrors'), detail: null }
    return
  }
  try {
    const rows: ImportRow[] = preview.value.rows
    const result = await caseApi.commitImport(projectId.value, rows)
    importMessage.value = t('cases.import.result', {
      imported: result.imported,
      skipped: result.skipped,
    })
    preview.value = null
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}
</script>

<template>
  <div class="page is-list">
    <PageHeader :title="t('menu.cases')" />

    <PageToolbar>
      <template #filters>
        <FilterBar
          :count="activeFilterCount"
          :collapsed="filtersCollapsed"
          @update:collapsed="filtersCollapsed = $event"
          @clear="clearFilters"
        >
          <NInput
            v-model:value="keyword"
            clearable
            style="width: 200px"
            :placeholder="t('common.searchPlaceholder')"
            :aria-label="t('common.searchPlaceholder')"
            @keyup.enter="load"
          />
          <NSelect
            v-model:value="priority"
            :options="priorityOptions"
            style="width: 140px"
            :aria-label="t('cases.priority')"
          />
          <NSelect
            v-model:value="reviewStatus"
            :options="reviewOptions"
            style="width: 140px"
            :aria-label="t('cases.status')"
          />
          <NSelect
            v-model:value="safetyLevel"
            :options="safetyOptions"
            style="width: 140px"
            :aria-label="t('cases.safetyLevel')"
          />
          <NInput
            v-model:value="domain"
            clearable
            style="width: 140px"
            :placeholder="t('cases.domain')"
            :aria-label="t('cases.domain')"
          />
        </FilterBar>
      </template>

      <template #actions>
        <NPopover
          v-model:show="showViewMenu"
          trigger="click"
          placement="bottom-start"
          style="max-width: 320px"
        >
          <template #trigger>
            <NButton quaternary>
              {{ t('views.label') }}
              <AppIcon name="chevronDown" :size="16" />
            </NButton>
          </template>
          <div class="views-menu">
            <p v-if="viewsLoading" class="views-hint">{{ t('common.loading') }}</p>
            <p v-else-if="views.length === 0" class="views-hint">{{ t('views.empty') }}</p>
            <ul v-else class="views-list">
              <li
                v-for="view in views"
                :key="view.id"
                class="views-item"
                :class="{ 'views-item--active': view.id === activeViewId }"
              >
                <button type="button" class="views-apply" @click="applySavedView(view)">
                  <span class="views-name">
                    <AppIcon v-if="view.id === activeViewId" name="check" :size="16" />
                    {{ view.name }}
                  </span>
                  <span v-if="view.isDefault" class="views-badge">{{ t('views.default') }}</span>
                </button>
                <button
                  v-if="isOwnView(view) && !view.isDefault"
                  type="button"
                  class="views-icon"
                  :title="t('views.setDefault')"
                  :aria-label="t('views.setDefault')"
                  @click="setDefaultView(view)"
                >
                  ☆
                </button>
                <button
                  v-if="isOwnView(view)"
                  type="button"
                  class="views-icon views-icon--danger"
                  :title="t('views.delete')"
                  :aria-label="t('views.delete')"
                  @click="deleteView(view)"
                >
                  <AppIcon name="trash" :size="16" />
                </button>
              </li>
            </ul>
            <div class="views-menu__foot">
              <NButton size="small" type="primary" @click="openSaveView">
                {{ t('views.saveCurrent') }}
              </NButton>
            </div>
          </div>
        </NPopover>

        <NButton quaternary @click="showColumns = true">
          <AppIcon name="sliders" :size="16" />
          {{ t('ui.columnSettings') }}
        </NButton>
        <NButton
          :type="showForm ? 'default' : 'primary'"
          @click="showForm ? closeForm() : openCreateForm()"
        >
          {{ showForm ? t('common.cancel') : t('common.create') }}
        </NButton>
        <NButton quaternary @click="showImport = !showImport">{{ t('cases.import.title') }}</NButton>
        <RouterLink v-slot="{ navigate }" to="/cases/sets" custom>
          <NButton quaternary @click="navigate">{{ t('caseSets.title') }}</NButton>
        </RouterLink>
        <NButton quaternary :loading="loading" @click="load">{{ t('common.refresh') }}</NButton>
      </template>
    </PageToolbar>

    <NModal v-model:show="showSaveView">
      <div class="view-dialog" role="dialog" aria-modal="true" :aria-label="t('views.saveTitle')">
        <h2 class="view-dialog__title">{{ t('views.saveTitle') }}</h2>
        <p class="view-dialog__label">{{ t('views.name') }}</p>
        <NInput
          v-model:value="viewName"
          :placeholder="t('views.namePlaceholder')"
          @keyup.enter="submitView"
        />
        <NCheckbox v-model:checked="viewDefault" class="view-dialog__default">
          {{ t('views.setNameDefault') }}
        </NCheckbox>
        <p v-if="viewError" class="view-dialog__error" role="alert">{{ viewError }}</p>
        <div class="view-dialog__actions">
          <NButton quaternary @click="showSaveView = false">{{ t('ui.cancel') }}</NButton>
          <NButton type="primary" @click="submitView">{{ t('views.saveCurrent') }}</NButton>
        </div>
      </div>
    </NModal>

    <ColumnSettingsDialog
      v-model:show="showColumns"
      :columns="columnOptions"
      @confirm="onColumnsConfirm"
    />

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="message" class="text-ok">{{ message }}</p>

    <form v-if="showForm" class="inline-form" @submit.prevent="createCase">
      <NInput v-model:value="form.caseCode" style="width: 140px" :placeholder="t('cases.caseCode')" />
      <NInput v-model:value="form.title" style="width: 240px" :placeholder="t('cases.title')" />
      <NInput v-model:value="form.category" style="width: 140px" :placeholder="t('cases.category')" />
      <NSelect
        v-model:value="form.priority"
        :options="formPriorityOptions"
        style="width: 120px"
        :aria-label="t('cases.priority')"
      />
      <NSelect
        v-model:value="form.safetyLevel"
        :options="formSafetyOptions"
        style="width: 140px"
        :aria-label="t('cases.safetyLevel')"
      />
      <NInput v-model:value="form.domainTag" style="width: 140px" :placeholder="t('cases.domain')" />
      <NInput
        v-model:value="form.capability"
        style="width: 140px"
        :placeholder="t('cases.capability')"
      />
      <NInput
        v-model:value="form.precondition"
        type="textarea"
        style="width: 340px"
        :autosize="{ minRows: 1, maxRows: 3 }"
        :placeholder="t('cases.precondition')"
      />
      <NInput
        v-model:value="form.expected"
        type="textarea"
        style="width: 340px"
        :autosize="{ minRows: 1, maxRows: 3 }"
        :placeholder="t('cases.expected')"
      />
      <NInput
        v-model:value="form.description"
        type="textarea"
        style="width: 340px"
        :autosize="{ minRows: 1, maxRows: 3 }"
        :placeholder="t('cases.description')"
      />
      <NButton type="primary" attr-type="submit">{{ t('common.save') }}</NButton>
    </form>

    <NModal v-model:show="draftOpen">
      <div class="view-dialog" role="dialog" aria-modal="true" :aria-label="t('draft.found')">
        <h2 class="view-dialog__title">{{ t('draft.found') }}</h2>
        <p class="view-dialog__label">
          {{ t('draft.restoreHint', { time: draftTimeLabel() }) }}
        </p>
        <div class="view-dialog__actions">
          <NButton quaternary @click="draftOpen = false">{{ t('common.cancel') }}</NButton>
          <NButton quaternary @click="discardDraft">{{ t('draft.discard') }}</NButton>
          <NButton type="primary" @click="restoreDraft">{{ t('draft.restore') }}</NButton>
        </div>
      </div>
    </NModal>

    <div v-if="showImport" class="card import-card">
      <h2 class="card-title">{{ t('cases.import.title') }}</h2>
      <p class="muted">{{ t('cases.import.hint') }}</p>
      <input type="file" accept=".xlsx,.xls" @change="pickFile" />
      <div class="toolbar">
        <NButton @click="runPreview">{{ t('cases.import.preview') }}</NButton>
        <NButton
          quaternary
          :disabled="!preview || preview.errors.length > 0"
          @click="runCommit"
        >
          {{ t('cases.import.commit') }}
        </NButton>
      </div>
      <p v-if="preview" class="muted">
        {{ t('cases.import.summary', { total: preview.totalRows, valid: preview.validRows }) }}
      </p>
      <ul v-if="preview && preview.errors.length" class="error-list">
        <li v-for="e in preview.errors.slice(0, 10)" :key="`${e.rowNum}-${e.field}`">
          {{ e.rowNum }} / {{ e.field }}：{{ e.message }}
        </li>
      </ul>
      <p v-if="importMessage" class="text-ok">{{ importMessage }}</p>
    </div>

    <SelectionBar
      v-if="selectedCount > 0 || batchResult !== null"
      :count="selectedCount"
      :cross-page="crossPageCount"
      :result="batchResult"
      :loading="batchLoading"
      :project-id="projectId"
      :action="batchAction"
      @clear="onSelectionClear"
      @undone="onUndone"
    >
      <NButton
        type="primary"
        :disabled="batchLoading || blockedSelected.length > 0"
        @click="toggleRunForm"
      >
        {{ t('cases.run.open') }}
      </NButton>
      <span v-if="blockedSelected.length" class="muted">
        {{ t('cases.run.blocked', { codes: blockedCodes }) }}
      </span>
      <NButton quaternary :disabled="batchLoading" @click="runBatch('submit')">
        {{ t('cases.batch.submit') }}
      </NButton>
      <NButton quaternary :disabled="batchLoading" @click="runBatch('approve')">
        {{ t('cases.batch.approve') }}
      </NButton>
      <NButton quaternary :disabled="batchLoading" @click="runBatch('reject')">
        {{ t('cases.batch.reject') }}
      </NButton>
      <NSelect
        v-model:value="batchPriority"
        :options="formPriorityOptions"
        style="width: 120px"
        :disabled="batchLoading"
        :aria-label="t('cases.priority')"
      />
      <NButton quaternary :disabled="batchLoading" @click="runBatch('setPriority')">
        {{ t('cases.batch.setPriority') }}
      </NButton>
      <NInput
        v-model:value="batchDomain"
        style="width: 140px"
        :placeholder="t('cases.domain')"
        :disabled="batchLoading"
      />
      <NButton quaternary :disabled="batchLoading" @click="runBatch('setDomainTag')">
        {{ t('cases.batch.setDomainTag') }}
      </NButton>
      <NSelect
        v-model:value="batchSafety"
        :options="formSafetyOptions"
        style="width: 140px"
        :disabled="batchLoading"
        :aria-label="t('cases.safetyLevel')"
      />
      <NButton quaternary :disabled="batchLoading" @click="runBatch('setSafetyLevel')">
        {{ t('cases.batch.setSafetyLevel') }}
      </NButton>
      <NButton quaternary type="error" :disabled="batchLoading" @click="runBatch('delete')">
        {{ t('cases.batch.delete') }}
      </NButton>
    </SelectionBar>

    <div v-if="runOpen && selectedCount > 0" class="card run-card">
      <h2 class="card-title">{{ t('cases.run.title', { count: selectedCount }) }}</h2>
      <p class="muted">{{ t('cases.run.hint') }}</p>
      <div class="toolbar">
        <NInputNumber
          v-model:value="runEnv"
          :min="1"
          style="width: 140px"
          :placeholder="t('cases.run.environment')"
        />
        <span class="muted">{{ t('cases.run.concurrency') }}</span>
        <NRadioGroup v-model:value="runConcurrency">
          <NRadioButton :value="1">1</NRadioButton>
          <NRadioButton :value="2">2</NRadioButton>
          <NRadioButton :value="4">4</NRadioButton>
        </NRadioGroup>
        <NInputNumber
          v-model:value="runTimeout"
          :min="60"
          :max="86400"
          style="width: 140px"
          :placeholder="t('cases.run.timeout')"
        />
        <NInputNumber
          v-model:value="runTestTimeout"
          :min="0"
          :max="86400"
          style="width: 140px"
          :placeholder="t('cases.run.testTimeout')"
        />
        <NInput
          v-model:value="runTag"
          style="width: 140px"
          :placeholder="t('cases.run.tag')"
        />
        <NButton type="primary" :loading="runLoading" :disabled="blockedSelected.length > 0" @click="submitRun">
          {{ t('cases.run.submit') }}
        </NButton>
        <NButton quaternary :disabled="runLoading" @click="runOpen = false">
          {{ t('common.cancel') }}
        </NButton>
      </div>
      <ErrorLineView v-if="runError" :error="runError" />
    </div>

    <div class="list-fill">
      <DataTable
        :columns="columns"
        :data="cases"
        :loading="loading"
        :row-key="(row: TestCase) => row.id"
        :empty-title="t('common.noData')"
        :empty-description="projectId ? undefined : t('overview.noProject')"
        empty-icon="cases"
        :error="Boolean(error)"
        :empty-illustration="keyword ? 'no-result' : 'no-data'"
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

    <section v-if="capabilities.length" class="card">
      <h2 class="card-title">{{ t('cases.capabilityIndex') }}</h2>
      <p class="muted">
        <span v-for="cap in capabilities" :key="cap.key" class="cap-item">
          {{ cap.key }}（{{ cap.count }}）
        </span>
      </p>
    </section>
  </div>
</template>

<style scoped>
/* 操作列多个 text 按钮并排：h() 渲染的节点拿不到 scoped 类，用 :deep 兜 */
:deep(.row-actions) {
  display: flex;
  gap: var(--space-2);
}

.views-menu {
  min-width: 240px;
}

.views-hint {
  margin: 0;
  padding: var(--space-2);
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}

.views-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 45vh;
  overflow: auto;
}

.views-item {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  border-radius: var(--radius-md);
}

.views-item:hover {
  background: var(--surface-hover);
}

.views-item--active {
  background: var(--accent-soft);
}

.views-apply {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  min-width: 0;
  padding: var(--space-2);
  border: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: var(--font-size-sm);
  text-align: left;
  cursor: pointer;
}

.views-name {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.views-badge {
  flex: none;
  padding: 0 var(--space-1);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.views-icon {
  flex: none;
  padding: 4px;
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  font: inherit;
}

.views-icon:hover {
  color: var(--text);
  background: var(--surface-2);
}

.views-icon--danger:hover {
  color: var(--fail);
}

.views-icon:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.views-menu__foot {
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.view-dialog {
  width: min(400px, calc(100vw - 32px));
  padding: var(--space-5);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  background: var(--panel);
  color: var(--text);
  box-shadow: var(--shadow);
}

.view-dialog__title {
  margin: 0 0 var(--space-4);
  font-size: var(--font-size-md);
}

.view-dialog__label {
  margin: 0 0 var(--space-1);
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}

.view-dialog__default {
  margin-top: var(--space-3);
}

.view-dialog__error {
  margin: var(--space-3) 0 0;
  font-size: var(--font-size-sm);
  color: var(--fail);
}

.view-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-5);
}

@media (prefers-reduced-motion: reduce) {
  .views-item {
    transition: none;
  }
}
</style>
