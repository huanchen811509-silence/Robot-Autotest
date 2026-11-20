<script setup lang="ts">
/**
 * 用例集管理（CAS-02 的前端补齐部分）。
 *
 * 后端 `/case-sets` 早已可用（列表 / 新建 / 改名 / 删除 / 成员 / 排序 / 立即执行），
 * 但一直没有页面入口，用户只能通过执行计划间接触达。本页把这条链路补全：
 * 左侧维护集合本身，右侧「管理用例」维护成员与顺序，可一键按集合发起运行。
 */
import { computed, h, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import CaseSetWorkspace from '@/components/CaseSetWorkspace.vue'
import DetailDock from '@/components/DetailDock.vue'
import CaseScriptLink from '@/components/CaseScriptLink.vue'
import CaseSetMemberDetail from '@/components/CaseSetMemberDetail.vue'
import { useDetailDock } from '@/composables/useDetailDock'
import { useI18n } from 'vue-i18n'
import { NButton, NCheckbox, NInput, NPagination, NSelect, type DataTableColumns } from 'naive-ui'
import ColumnFilter from '@/components/ColumnFilter.vue'
import { useTableFilters, type TableFilterColumn } from '@/composables/useTableFilters'
import { useServerSelection } from '@/composables/useServerSelection'
import { useListPageSize, type PageSize } from '@/composables/useListPageSize'
import { caseApi, caseSetApi, assetVersionApi, platformCaseSetApi,
         type TestCase, type TestSet, type AssetVersionView, type AssetDiffRow,
         type PlatformCaseSetRow, type SyncStrategy } from '@/api/cases'
import { executionApi } from '@/api/execution'
import { useAuthStore } from '@/stores/auth'
import DataTable from '@/components/DataTable.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import FormField from '@/components/FormField.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { EMPTY } from '@/utils/format'
import { buildMemberCsv } from '@/utils/memberCsv'
import { confirmAction } from '@/composables/useConfirm'
import { useCan } from '@/composables/useCan'

const { t } = useI18n()
const auth = useAuthStore()

// v5.12 / W62-02：三栏 + 成员详情 Dock。URL 由页面统一写一次（与 5.11 的口径一致），
// 组件各自 replace 会互相覆盖成「最后一个赢」。
const route = useRoute()
const router = useRouter()
const memberDock = useDetailDock({
  initial: typeof route.query.detail === 'string' ? route.query.detail : null,
})

const projectId = computed(() => auth.projectId ?? 0)

const sets = ref<TestSet[]>([])
const keyword = ref('')
// v5.8.0 FR-v5.8-03：列表改服务端分页 + 服务端筛选，计数取后端 total
const page = ref(1)
const total = ref(0)
const { size, pageSizes } = useListPageSize()
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const message = ref('')
const saving = ref(false)

const formOpen = ref(false)
const editingId = ref<number | null>(null)
const form = ref({ setCode: '', name: '', description: '' })

const activeSet = ref<TestSet | null>(null)
const items = ref<TestCase[]>([])
const itemsLoading = ref(false)
const itemsError = ref<ErrorLine | null>(null)
const selectedCaseId = ref<number | null>(null)
/** W62-05：成员级批量选择（与集合级 selection 完全隔离）。 */
const selectedItemIds = ref<number[]>([])
const caseOptions = ref<{ label: string; value: number }[]>([])
// v5.24.0 / W74-03：候选多选 + 命中总数（修评审"只能单选、无计数、底部裁切"）
const selectedCaseIds = ref<number[]>([])
const caseTotal = ref(0)
const caseLoading = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | undefined

/** 资产版本面板（PUBX-01/02）：读数、差异列表、同步/分叉动作。 */
const assetView = ref<AssetVersionView | null>(null)
const assetDiff = ref<AssetDiffRow[] | null>(null)
const assetLoading = ref(false)
const assetBusy = ref(false)
const assetError = ref<ErrorLine | null>(null)
const assetMessage = ref('')
/** N9：FULL 整体覆盖；ADD_ONLY 只补平台新增，保留本地修改与顺序。 */
const syncStrategy = ref<SyncStrategy>('FULL')

const syncStrategyOptions = computed(() => [
  { label: t('caseSets.assetStrategyFull'), value: 'FULL' },
  { label: t('caseSets.assetStrategyAddOnly'), value: 'ADD_ONLY' },
])

// ---------------------------------------------------------------- 平台用例集库（N9，只读）

const isAdmin = computed(() => auth.isAdmin)
const platformKeyword = ref('')
const platformSets = ref<PlatformCaseSetRow[]>([])
const platformLoading = ref(false)
const platformError = ref<ErrorLine | null>(null)
const platformOpenId = ref<number | null>(null)
const platformItems = ref<TestCase[]>([])
const platformItemsLoading = ref(false)

const canSubmit = computed(
  () =>
    form.value.setCode.trim().length > 0 &&
    form.value.name.trim().length > 0 &&
    !saving.value,
)

/* ---------------------------------------------------------------- W57-15/16/17 列表三件套 */

/**
 * 成员表筛选：URL 化（prefix `member_`），与集合表筛选互不覆盖。
 * 成员是 `<ul>` 顺序列表（上移/下移要按完整顺序提交），所以这里只做「显示层过滤」，
 * 排序动作仍按 `items.value` 的原始下标执行 —— 见下方 `move(item, delta)`。
 */
const memberFilters = useTableFilters({
  prefix: 'member_',
  columns: ['caseCode', 'priority', 'bindingState'],
})
const memberFacets = memberFilters.facets
const memberTexts = memberFilters.texts
const memberFilterColumns: TableFilterColumn[] = [
  { key: 'caseCode', title: t('w57.caseSets.colCaseCode'), mode: 'text' },
  {
    key: 'priority',
    title: t('w57.caseSets.colPriority'),
    mode: 'multi',
    options: [
      { label: t('w57.caseSets.priorityHigh'), value: 'HIGH' },
      { label: t('w57.caseSets.priorityMedium'), value: 'MEDIUM' },
      { label: t('w57.caseSets.priorityLow'), value: 'LOW' },
    ],
  },
  {
    key: 'bindingState',
    title: t('cases.scriptState'),
    mode: 'multi',
    options: [
      { label: t('cases.scriptVerified'), value: 'BOUND_VERIFIED' },
      { label: t('cases.scriptUnverified'), value: 'BOUND_UNVERIFIED' },
      { label: t('cases.scriptUnbound'), value: 'UNBOUND' },
      { label: t('cases.scriptNotApplicable'), value: 'NOT_APPLICABLE' },
    ],
  },
]

function onMemberSelected(key: string, values: string[]): void {
  if (memberFilterColumns.find((col) => col.key === key)?.mode === 'text') return
  memberFilters.setFacet(key, values)
}
function onMemberText(key: string, value: string): void {
  if (memberFilterColumns.find((col) => col.key === key)?.mode !== 'text') return
  memberFilters.setText(key, value)
}

/** FR-READY-03：集合脚本覆盖率（VERIFIED+UNVERIFIED 计入已绑定；NOT_APPLICABLE 不计分母）。 */
const scriptCoverage = computed(() => {
  const countable = items.value.filter((i) => i.bindingState !== 'NOT_APPLICABLE')
  const bound = countable.filter((i) =>
    i.bindingState === 'BOUND_VERIFIED' || i.bindingState === 'BOUND_UNVERIFIED').length
  return { bound, total: countable.length }
})

const visibleItems = computed<TestCase[]>(() => {
  const q = memberFilters.q.value.trim().toLowerCase()
  const byCode = (memberTexts.value.caseCode ?? '').toLowerCase()
  const priorities = memberFacets.value.priority ?? []
  const states = memberFacets.value.bindingState ?? []
  return items.value.filter((item) => {
    if (states.length > 0 && !states.includes(item.bindingState ?? 'UNBOUND')) return false
    if (priorities.length > 0 && !priorities.includes(item.priority)) return false
    if (byCode && !item.caseCode.toLowerCase().includes(byCode)) return false
    if (q && !`${item.caseCode} ${item.title}`.toLowerCase().includes(q)) return false
    return true
  })
})

/** 集合表筛选：走 DataTable 的可选 filterable 弹层，状态仍落在 URL（prefix `sets_`）。 */
const setsFilters = useTableFilters({ prefix: 'sets_', columns: ['setCode', 'name'] })
const setsFilterColumns: TableFilterColumn[] = [
  { key: 'setCode', title: t('w57.caseSets.colSetCode'), mode: 'text' },
  { key: 'name', title: t('w57.caseSets.colName'), mode: 'text' },
]
const setsFilterValues = computed<Record<string, string[]>>(() => {
  const out: Record<string, string[]> = {}
  for (const [key, value] of Object.entries(setsFilters.texts.value)) out[key] = [value]
  return out
})
const memberIsFiltered = memberFilters.isFiltered
function onSetsFilterChange(next: Record<string, string[]>): void {
  for (const column of setsFilterColumns) {
    setsFilters.setText(column.key, next[column.key]?.[0] ?? '')
  }
  // 筛选条件交给服务端：回到第 1 页重新查询。本地再筛一遍会把「第 2 页无结果」变成假空。
  page.value = 1
  void load()
}

/** 服务端筛选参数：与 `caseSetApi.page` 同形，条件选择也复用它。 */
const setsQueryParams = computed<Record<string, string | undefined>>(() => ({
  keyword: keyword.value.trim() || undefined,
  setCode: (setsFilters.texts.value.setCode ?? '').trim() || undefined,
  name: (setsFilters.texts.value.name ?? '').trim() || undefined,
}))

/** 服务端选择：全选用筛选条件表达，跨页生效；>10000 需二次确认。 */
const pageIds = computed<number[]>(() => sets.value.map((set) => set.id))
const selection = useServerSelection(pageIds)
const selectionCount = selection.count
const awaitingSelectAllConfirm = selection.awaitingConfirm

function selectAllMatching(): void {
  selection.requestSelectAllMatching(setsQueryParams.value, total.value)
}
function clearSelection(): void {
  selection.clear()
}

watch(setsFilters.revision, () => {
  // 筛选条件一变，旧的「按条件全选」必须作废（W57-16）
  selection.resetForFilters(setsQueryParams.value)
  page.value = 1
  void load()
})

/** 写操作判定（v5.21.0 / W71-05）：行内按钮走 h()，用不了 v-can，与模板共用同一 composable。 */
const { can } = useCan()

const columns = computed<DataTableColumns<TestSet>>(() => [
  {
    title: t('w57.caseSets.colSelect'),    key: '__select',
    width: 64,
    render: (row) =>
      h(NCheckbox, {
        checked: selection.isSelected(row.id),
        'onUpdate:checked': () => selection.toggle(row.id),
        'aria-label': t('w57.caseSets.selectRow', { name: row.name }),
      }),
  },
  { title: t('caseSets.setCode'), key: 'setCode', width: 160 },
  { title: t('caseSets.name'), key: 'name', minWidth: 200 },
  {
    title: t('caseSets.description'),
    key: 'description',
    minWidth: 240,
    render: (row) => row.description || EMPTY,
  },
  {
    title: t('common.actions'),
    key: 'actions',
    // W69-04/W69-05：三栏版式下表宽 964 而容器只有 764，操作列原被右栏盖住
    fixed: 'right',
    width: 300,
    render: (row) =>
      // v5.21.0 / FR-v5.21-03 / W71-05：viewer 一个写按钮都不渲染。
      // 四个按钮各自判码：管理/编辑/删除属 case:write，立即执行属 execution:write
      // （后端分别是 CASE:UPDATE/DELETE 与 EXECUTION:CREATE，不共用一把钥匙）。
      can('case:write') || can('execution:write')
        ? h('div', { class: 'row-actions' }, [
            can('case:write') &&
              h(
                NButton,
                { text: true, type: 'primary', size: 'small', onClick: () => void openItems(row) },
                { default: () => t('caseSets.manage') },
              ),
            can('case:write') &&
              h(
                NButton,
                { text: true, size: 'small', onClick: () => openEdit(row) },
                { default: () => t('caseSets.edit') },
              ),
            can('execution:write') &&
              h(
                NButton,
                { text: true, size: 'small', onClick: () => void runNow(row) },
                { default: () => t('caseSets.runNow') },
              ),
            can('case:write') &&
              h(
                NButton,
                { text: true, type: 'error', size: 'small', onClick: () => void removeSet(row) },
                { default: () => t('common.delete') },
              ),
          ])
        : h('span', { class: 'row-actions row-actions--readonly' }, '—'),
  },
])

async function load(): Promise<void> {
  error.value = null
  if (!projectId.value) {
    sets.value = []
    return
  }
  loading.value = true
  try {
    const result = await caseSetApi.page(projectId.value, {
      ...setsQueryParams.value,
      page: page.value,
      size: size.value,
    })
    sets.value = result.items
    total.value = result.total
    restoreSelectionFromQuery()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  editingId.value = null
  form.value = { setCode: '', name: '', description: '' }
}

function openCreate(): void {
  resetForm()
  formOpen.value = true
}

/**
 * v5.22.0 / FR-v5.22-06 判据 06-2：空态的另外两个动作。
 *
 * <p>空态只给「清筛选 / 重试」的时代，新项目打开这一页只有一句"还没有用例集"，
 * 用户不知道下一步该点哪里。三个动作对应三条真实入口：
 * 新建（本页弹窗）/ 导入（预检页）/ 查看样例（平台级公共样例集，按一级分类组织）。
 *
 * <p>「新建」与「导入」都按 `case:write` 显隐：两者最终都会写用例集，viewer 进到
 * 导入页也提交不了（门禁 64 role-buttons 判据是"viewer 在用例集页写按钮数为 0"，
 * 曾因这里漏守卫被扫出「导入用例」一颗而判红）。「查看样例」是只读导航，给所有角色。
 */
function goImport(): void {
  void router.push({ name: 'imports' })
}

function goSamples(): void {
  void router.push({ name: 'global-case-sets' })
}

function openEdit(row: TestSet): void {
  editingId.value = row.id
  form.value = {
    setCode: row.setCode,
    name: row.name,
    description: row.description ?? '',
  }
  formOpen.value = true
}

function closeForm(): void {
  formOpen.value = false
  resetForm()
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  error.value = null
  message.value = ''
  saving.value = true
  try {
    const description = form.value.description.trim() || undefined
    // v5.22.0 / FR-v5.22-06 判据 06-1：新建成功即选中并展开成员面板。
    // 先建出来再 `openItems`，是因为面板标题取的是 `activeSet.name`，拿不到 id/name 就没法展开。
    let created: TestSet | null = null
    if (editingId.value === null) {
      created = await caseSetApi.create(projectId.value, {
        setCode: form.value.setCode.trim(),
        name: form.value.name.trim(),
        description,
      })
    } else {
      const updated = await caseSetApi.update(projectId.value, editingId.value, {
        name: form.value.name.trim(),
        description,
      })
      message.value = t('caseSets.updated', { name: updated.name })
      if (activeSet.value?.id === updated.id) activeSet.value = updated
    }
    closeForm()
    await load()
    if (created) {
      await openItems(created)
      // 顺序不能反：`openItems` 开头会把 `message` 清空（切集合时清掉上一条提示是它的既定语义），
      // 先置消息就会被吃掉，「已创建」的反馈一句都不剩。
      message.value = t('caseSets.created', { name: created.name })
    }
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    saving.value = false
  }
}

async function removeSet(row: TestSet): Promise<void> {
  // W62-06：文案含集合名与成员数，取消后零写入
  if (!(await confirmAction(t('w62.confirmRemoveSet', { name: row.name, count: row.itemCount ?? 0 }), { tone: 'danger' })))
    return
  error.value = null
  message.value = ''
  try {
    await caseSetApi.remove(projectId.value, row.id)
    message.value = t('caseSets.deleted', { name: row.name })
    if (activeSet.value?.id === row.id) closeItems()
    if (editingId.value === row.id) closeForm()
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

/** 关键字变更后必须回到第 1 页：停在第 3 页上搜一个只命中第 1 页的词会得到假空。 */
function onKeywordSearch(): void {
  page.value = 1
  void load()
}

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

async function loadItems(): Promise<void> {
  if (!activeSet.value) return
  itemsError.value = null
  itemsLoading.value = true
  try {
    items.value = await caseSetApi.items(projectId.value, activeSet.value.id)
  } catch (e) {
    itemsError.value = localizeError(e)
  } finally {
    itemsLoading.value = false
  }
}

async function openItems(row: TestSet): Promise<void> {
  activeSet.value = row
  selectedCaseId.value = null
  message.value = ''
  await Promise.all([loadItems(), searchCases(''), loadAsset(row.id)])
  syncQuery()
}

function closeItems(): void {
  activeSet.value = null
  items.value = []
  caseOptions.value = []
  selectedCaseIds.value = []
  caseTotal.value = 0
  itemsError.value = null
  assetView.value = null
  assetDiff.value = null
  assetError.value = null
  assetMessage.value = ''
  memberDock.close()
  syncQuery()
}

/** `?set=<id>&detail=<itemId>`：刷新后集合与成员都回到原位（FR-v5.12-01 判据 6）。 */
function syncQuery(): void {
  const query: LocationQueryRaw = { ...route.query }
  if (activeSet.value) query.set = String(activeSet.value.id)
  else delete query.set
  if (memberDock.isOpen.value) query.detail = memberDock.selected.value
  else delete query.detail
  void router.replace({ query })
}

function restoreSelectionFromQuery(): void {
  const raw = route.query.set
  if (typeof raw !== 'string' || activeSet.value) return
  const id = Number(raw)
  if (!Number.isFinite(id)) return
  const hit = sets.value.find((row) => row.id === id)
  if (hit) void openItems(hit)
}

const detailItem = computed<TestCase | null>(
  () => items.value.find((row) => String(row.id) === memberDock.selected.value) ?? null,
)
const detailOpen = computed(() => memberDock.isOpen.value && detailItem.value !== null)
const detailTitle = computed(() =>
  detailItem.value ? `${detailItem.value.caseCode} · ${detailItem.value.title}` : '',
)

function openMember(item: TestCase): void {
  memberDock.toggle(item.id)
  syncQuery()
}

function closeMember(): void {
  memberDock.close()
  syncQuery()
}

/**
 * 候选用例走服务端搜索（300ms 防抖）：台账可能有上千条，一次性拉全量既慢又占内存，
 * 而 `NSelect` 的本地过滤只能过滤已加载的部分，用户会误以为「搜不到就是没有」。
 */
function searchCases(query: string): void {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    void runCaseSearch(query)
  }, 300)
}

async function runCaseSearch(query: string): Promise<void> {
  if (!projectId.value) return
  caseLoading.value = true
  try {
    const page = await caseApi.page(projectId.value, {
      keyword: query.trim() || undefined,
      page: 1,
      size: 50,
    })
    const existing = new Set(items.value.map((item) => item.id))
    caseTotal.value = page.total
    caseOptions.value = page.items
      .filter((item) => !existing.has(item.id))
      .map((item) => ({ label: `${item.caseCode} ${item.title}`, value: item.id }))
  } catch (e) {
    itemsError.value = localizeError(e)
  } finally {
    caseLoading.value = false
  }
}

async function addCase(): Promise<void> {
  if (!activeSet.value || selectedCaseIds.value.length === 0) return
  itemsError.value = null
  message.value = ''
  try {
    // v5.24.0 / W74-01+03：一次 API 批量加入，不再 N 次串行 addItem
    const result = await caseSetApi.batchAddItems(
      projectId.value, activeSet.value.id, [...selectedCaseIds.value])
    selectedCaseIds.value = []
    message.value = t('caseSets.itemsBatchAdded', {
      added: result.added, skipped: result.skipped,
    })
    await Promise.all([loadItems(), runCaseSearch('')])
  } catch (e) {
    itemsError.value = localizeError(e)
  }
}

function toggleItem(id: number, checked: boolean): void {
  selectedItemIds.value = checked
    ? [...new Set([...selectedItemIds.value, id])]
    : selectedItemIds.value.filter((value) => value !== id)
}

function selectAllItems(): void {
  selectedItemIds.value = items.value.map((row) => row.id)
}

async function batchRemove(): Promise<void> {
  if (!activeSet.value || selectedItemIds.value.length === 0) return
  const count = selectedItemIds.value.length
  if (!(await confirmAction(t('w62.confirmRemoveItems', { count }), { tone: 'danger' }))) return
  itemsError.value = null
  message.value = ''
  try {
    await caseSetApi.batchRemoveItems(projectId.value, activeSet.value.id, selectedItemIds.value)
    selectedItemIds.value = []
    memberDock.close()
    syncQuery()
    message.value = t('w62.batchRemoved', { count })
    await loadItems()
  } catch (e) {
    itemsError.value = localizeError(e)
  }
}

/** 选中的成员作为一个整体前后挪一位，未选中的相对顺序不变。 */
async function batchMove(delta: number): Promise<void> {
  if (!activeSet.value || selectedItemIds.value.length === 0) return
  const picked = new Set(selectedItemIds.value)
  const chosen = items.value.filter((row) => picked.has(row.id))
  const others = items.value.filter((row) => !picked.has(row.id))
  const firstIndex = items.value.findIndex((row) => picked.has(row.id))
  const insertAt = Math.max(0, Math.min(others.length, firstIndex + delta))
  const next = [...others.slice(0, insertAt), ...chosen, ...others.slice(insertAt)]
  items.value = next
  itemsError.value = null
  try {
    await caseSetApi.reorder(projectId.value, activeSet.value.id, next.map((row) => row.id))
    message.value = t('caseSets.orderSaved')
  } catch (e) {
    itemsError.value = localizeError(e)
    await loadItems()
  }
}

function exportCsv(): void {
  const chosen = selectedItemIds.value.length
    ? items.value.filter((row) => selectedItemIds.value.includes(row.id))
    : items.value
  const csv = buildMemberCsv(chosen, [
    t('w62.csv.order'), t('w62.csv.code'), t('w62.csv.title'),
    t('w62.csv.category'), t('w62.csv.script'),
  ])
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  if (typeof URL.createObjectURL !== 'function') return
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${activeSet.value?.setCode ?? 'case-set'}-members.csv`
  link.click()
  URL.revokeObjectURL(url)
}

async function removeItem(item: TestCase): Promise<void> {
  if (!activeSet.value) return
  if (!(await confirmAction(t('w62.confirmRemoveItems', { count: 1 }), { tone: 'danger' }))) return
  itemsError.value = null
  message.value = ''
  try {
    await caseSetApi.removeItem(projectId.value, activeSet.value.id, item.id)
    message.value = t('caseSets.itemRemoved', { code: item.caseCode })
    await Promise.all([loadItems(), runCaseSearch('')])
  } catch (e) {
    itemsError.value = localizeError(e)
  }
}

/**
 * 上移/下移按「成员对象」定位原始下标：成员列表被筛选显示成子集时，
 * 显示层的 index 不能直接拿去 splice 完整列表，否则会移动错一条。
 */
async function move(item: TestCase, delta: number): Promise<void> {
  if (!activeSet.value) return
  const index = items.value.findIndex((row) => row.id === item.id)
  if (index < 0) return
  const target = index + delta
  if (target < 0 || target >= items.value.length) return
  const next = [...items.value]
  const [moved] = next.splice(index, 1)
  next.splice(target, 0, moved)
  items.value = next
  itemsError.value = null
  message.value = ''
  try {
    await caseSetApi.reorder(
      projectId.value,
      activeSet.value.id,
      next.map((item) => item.id),
    )
    message.value = t('caseSets.orderSaved')
  } catch (e) {
    itemsError.value = localizeError(e)
    await loadItems()
  }
}

async function runNow(row: TestSet): Promise<void> {
  if (!(await confirmAction(t('caseSets.confirmRun', { name: row.name })))) return
  error.value = null
  message.value = ''
  try {
    const run = await executionApi.runTestSet(projectId.value, row.id, {})
    message.value = t('caseSets.triggered', { runId: run.runId })
  } catch (e) {
    error.value = localizeError(e)
  }
}

// ---------------------------------------------------------------- 资产版本（PUBX-01/02）

async function loadAsset(setId: number): Promise<void> {
  if (!projectId.value) return
  assetLoading.value = true
  try {
    assetView.value = await assetVersionApi.version(projectId.value, setId)
  } catch (e) {
    // 400 = 未登记版本信息（旧数据），面板显示占位而不是整页报错
    assetError.value = localizeError(e)
  } finally {
    assetLoading.value = false
  }
}

async function checkDiff(): Promise<void> {
  if (!activeSet.value) return
  assetBusy.value = true
  assetError.value = null
  assetMessage.value = ''
  try {
    assetDiff.value = await assetVersionApi.diff(projectId.value, activeSet.value.id)
  } catch (e) {
    assetError.value = localizeError(e)
  } finally {
    assetBusy.value = false
  }
}

async function syncAsset(): Promise<void> {
  if (!activeSet.value) return
  if (!(await confirmAction(t('caseSets.assetConfirmSync')))) return
  assetBusy.value = true
  assetError.value = null
  assetMessage.value = ''
  try {
    assetView.value = await assetVersionApi.sync(
      projectId.value,
      activeSet.value.id,
      syncStrategy.value,
    )
    assetDiff.value = []
    assetMessage.value = t('caseSets.assetSynced', { version: assetView.value.assetVersion })
    await loadItems()
  } catch (e) {
    assetError.value = localizeError(e)
  } finally {
    assetBusy.value = false
  }
}

async function divergeAsset(): Promise<void> {
  if (!activeSet.value) return
  if (!(await confirmAction(t('caseSets.assetConfirmDiverge')))) return
  assetBusy.value = true
  assetError.value = null
  assetMessage.value = ''
  try {
    assetView.value = await assetVersionApi.diverge(projectId.value, activeSet.value.id)
    assetMessage.value = t('caseSets.assetDiverged')
  } catch (e) {
    assetError.value = localizeError(e)
  } finally {
    assetBusy.value = false
  }
}

// ---------------------------------------------------------------- 平台用例集库（N9，只读）

async function loadPlatform(): Promise<void> {
  platformError.value = null
  platformLoading.value = true
  try {
    const page = await platformCaseSetApi.page({
      keyword: platformKeyword.value.trim() || undefined,
      page: 1,
      size: 100,
    })
    platformSets.value = page.items
  } catch (e) {
    platformError.value = localizeError(e)
  } finally {
    platformLoading.value = false
  }
}

/** 只读展开：同一行再点一次即收起，切换行时重新拉该集成员。 */
async function togglePlatformItems(row: PlatformCaseSetRow): Promise<void> {
  if (platformOpenId.value === row.id) {
    platformOpenId.value = null
    platformItems.value = []
    return
  }
  platformOpenId.value = row.id
  platformItems.value = []
  platformItemsLoading.value = true
  platformError.value = null
  try {
    platformItems.value = await platformCaseSetApi.items(row.id)
  } catch (e) {
    platformError.value = localizeError(e)
  } finally {
    platformItemsLoading.value = false
  }
}

watch(
  isAdmin,
  (admin) => {
    if (admin) void loadPlatform()
  },
  { immediate: true },
)

const isBound = computed(() => assetView.value?.sourceAssetId !== null)

const forkClass = computed(() =>
  assetView.value?.forkState === 'DIVERGED' ? 'is-diverged' : 'is-synced',
)

const forkLabel = computed(() =>
  assetView.value?.forkState === 'DIVERGED'
    ? 'caseSets.assetForkDiverged'
    : 'caseSets.assetForkSynced',
)

watch(projectId, () => {
  closeForm()
  closeItems()
  void load()
})

void load()
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">{{ t('caseSets.title') }}</h1>
    </div>

    <div class="toolbar">
      <NInput
        v-model:value="keyword"
        clearable
        style="width: 200px"
        :placeholder="t('common.searchPlaceholder')"
        :aria-label="t('common.searchPlaceholder')"
        @keyup.enter="onKeywordSearch"
      />
      <NButton v-can="'case:write'" type="primary" @click="openCreate">{{ t('caseSets.create') }}</NButton>
      <NButton quaternary :loading="loading" @click="load">{{ t('common.refresh') }}</NButton>
      <RouterLink v-slot="{ navigate }" to="/cases" custom>
        <NButton quaternary @click="navigate">{{ t('caseSets.backToCases') }}</NButton>
      </RouterLink>
    </div>

    <p class="muted">{{ t('caseSets.subtitle') }}</p>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="message" class="text-ok">{{ message }}</p>

    <form v-if="formOpen" class="card form-card" @submit.prevent="submit">
      <h2 class="card-title">
        {{ editingId === null ? t('caseSets.createTitle') : t('caseSets.editTitle') }}
      </h2>
      <FormField :label="t('caseSets.setCode')" required :hint="t('caseSets.codeHint')">
        <NInput
          v-model:value="form.setCode"
          :disabled="editingId !== null"
          :placeholder="t('caseSets.setCode')"
        />
      </FormField>
      <FormField :label="t('caseSets.name')" required>
        <NInput v-model:value="form.name" :placeholder="t('caseSets.name')" />
      </FormField>
      <FormField :label="t('caseSets.description')">
        <NInput
          v-model:value="form.description"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 3 }"
          :placeholder="t('caseSets.description')"
        />
      </FormField>
      <div class="toolbar">
        <NButton type="primary" attr-type="submit" :loading="saving" :disabled="!canSubmit">
          {{ t('common.save') }}
        </NButton>
        <NButton quaternary @click="closeForm">{{ t('common.cancel') }}</NButton>
      </div>
    </form>

    <!-- v5.12 / W62-02：中栏集合列表 + 右工作区成员；壳只管形态，不碰数据。 -->
    <!-- W69-05：窄屏下右栏是抽屉，收起抽屉等价于收起当前集合（单一关闭语义）。 -->
    <CaseSetWorkspace :active="activeSet !== null" @close="closeItems">
      <template #nav>
    <!-- W57-16：全选用筛选条件表达；>10000 命中先二次确认，不静默选中上万条。 -->
    <div class="batch-bar" data-testid="case-sets-batch">
      <template v-if="awaitingSelectAllConfirm">
        <span class="muted">
          {{ t('w57.caseSets.confirmAllHint', { count: selection.confirmThreshold }) }}
        </span>
        <NButton size="small" type="primary" @click="selection.confirmSelectAllMatching()">
          {{ t('w57.caseSets.confirmAll') }}
        </NButton>
        <NButton size="small" quaternary @click="selection.cancelSelectAllMatching()">{{ t('w57.caseSets.cancel') }}</NButton>
      </template>
      <template v-else>
        <span v-if="selectionCount > 0" class="muted">{{ t('w57.caseSets.selectedCount', { count: selectionCount }) }}</span>
        <NButton size="small" quaternary @click="selectAllMatching">{{ t('w57.caseSets.selectAllMatching') }}</NButton>
        <NButton v-if="selectionCount > 0" size="small" quaternary @click="clearSelection">
          {{ t('w57.caseSets.clear') }}
        </NButton>
      </template>
    </div>

    <p class="muted list-total" data-testid="case-sets-total">
      {{ t('caseSets.totalCount', { count: total }) }}
    </p>

    <DataTable
      :columns="columns"
      :data="sets"
      :loading="loading"
      :row-key="(row: TestSet) => row.id"
      :empty-title="t('caseSets.empty')"
      :empty-description="projectId ? t('caseSets.emptyHint') : t('overview.noProject')"
      empty-icon="cases"
      :error="Boolean(error)"
      :forbidden="Boolean(error?.forbidden)"
      :empty-illustration="setsFilters.isFiltered.value ? 'no-result' : 'no-data'"
      filterable
      :filter-columns="setsFilterColumns"
      :filters="setsFilterValues"
      @update:filters="onSetsFilterChange"
    >
      <!-- v5.20.0 / FR-v5.20-01：无结果 → 清筛选；加载失败 → 重试 -->
      <!-- v5.22.0 / FR-v5.22-06 判据 06-2：真·空列表 → 三个动作（新建 / 导入 / 查看样例） -->
      <template #emptyAction>
        <NButton v-if="setsFilters.isFiltered.value" size="small" @click="setsFilters.clearAll()">
          {{ t('common.clearFilters') }}
        </NButton>
        <NButton
          v-else-if="error && !error.forbidden"
          size="small"
          data-testid="case-sets-empty-retry"
          @click="load"
        >
          {{ t('common.retry') }}
        </NButton>
        <div v-else class="empty-actions" data-testid="case-sets-empty-actions">
          <NButton
            v-if="can('case:write')"
            size="small"
            type="primary"
            data-testid="case-sets-empty-create"
            @click="openCreate"
          >
            {{ t('caseSets.create') }}
          </NButton>
          <NButton
            v-if="can('case:write')"
            size="small"
            data-testid="case-sets-empty-import"
            @click="goImport"
          >
            {{ t('caseSets.emptyActionImport') }}
          </NButton>
          <NButton quaternary size="small" data-testid="case-sets-empty-sample" @click="goSamples">
            {{ t('caseSets.emptyActionSample') }}
          </NButton>
        </div>
      </template>
    </DataTable>

    <NPagination
      v-if="total > 0"
      class="pager"
      data-testid="case-sets-pager"
      :page="page"
      :page-count="pageCount"
      :page-slot="7"
      :page-size="size"
      :page-sizes="pageSizes"
      show-size-picker
      @update:page-size="changePageSize"
      @update:page="turnPage"
    />
      </template>
      <template #workspace>
    <div v-if="activeSet" class="card items-card">
      <div class="items-head">
        <h2 class="card-title">{{ t('caseSets.itemsTitle', { name: activeSet.name }) }}</h2>
        <span class="muted small">{{ t('caseSets.itemsCount', { count: items.length }) }}</span>
        <span class="muted small" data-testid="set-script-coverage">
          {{ t('cases.scriptCoverage', { bound: scriptCoverage.bound, total: scriptCoverage.total }) }}
        </span>
      </div>

      <!-- W57-15/17：成员表筛选（URL 化，prefix member_），与集合表筛选隔离。 -->
      <div class="member-filters" data-check-semantic data-testid="member-filters">
        <ColumnFilter
          v-for="col in memberFilterColumns"
          :key="col.key"
          :column-key="col.key"
          :title="col.title"
          :mode="col.mode"
          :options="col.options ?? []"
          :selected="memberFacets[col.key] ?? []"
          :text="memberTexts[col.key] ?? ''"
          @update:selected="(v: string[]) => onMemberSelected(col.key, v)"
          @update:text="(v: string) => onMemberText(col.key, v)"
          @clear="memberFilters.clearColumn(col.key)"
        />
        <NInput
          :value="memberFilters.q.value"
          clearable
          style="width: 180px"
          :placeholder="t('w57.caseSets.searchMember')"
          :aria-label="t('w57.caseSets.searchMember')"
          @update:value="(v: string) => memberFilters.setQuery(v)"
        />
      </div>

      <!-- W62-05：成员批量动作条。未选中时按钮 disabled，不是隐藏后仍可点。 -->
      <div class="member-batch" data-testid="member-batch">
        <span class="muted" data-testid="member-batch-count">
          {{ t('w62.selectedCount', { count: selectedItemIds.length }) }}
        </span>
        <NButton size="small" :disabled="selectedItemIds.length === 0" data-testid="member-batch-remove"
                 @click="batchRemove">{{ t('w62.batchRemove') }}</NButton>
        <NButton size="small" :disabled="selectedItemIds.length === 0" data-testid="member-batch-up"
                 @click="batchMove(-1)">{{ t('w62.moveUp') }}</NButton>
        <NButton size="small" :disabled="selectedItemIds.length === 0" data-testid="member-batch-down"
                 @click="batchMove(1)">{{ t('w62.moveDown') }}</NButton>
        <NButton size="small" :disabled="selectedItemIds.length === 0" data-testid="member-batch-export"
                 @click="exportCsv">{{ t('w62.batchExport') }}</NButton>
        <NButton size="small" quaternary data-testid="member-batch-select-all" @click="selectAllItems">
          {{ t('w57.caseSets.selectAllMatching') }}
        </NButton>
        <span v-if="selectedItemIds.length === 0" class="muted small">{{ t('w62.batchDisabledHint') }}</span>
      </div>

      <div class="toolbar">
        <NSelect
          v-model:value="selectedCaseIds"
          multiple
          filterable
          remote
          clearable
          style="width: 340px"
          :options="caseOptions"
          :loading="caseLoading"
          :placeholder="t('caseSets.addPlaceholder')"
          :aria-label="t('caseSets.addPlaceholder')"
          @search="searchCases"
        />
        <span class="muted small" data-testid="case-picker-count">
          {{ t('caseSets.pickerCount', { total: caseTotal, selected: selectedCaseIds.length }) }}
        </span>
        <NButton :disabled="selectedCaseIds.length === 0" @click="addCase">
          {{ t('caseSets.addCase') }}
        </NButton>
        <NButton quaternary @click="closeItems">{{ t('caseSets.closeItems') }}</NButton>
      </div>

      <ErrorLineView v-if="itemsError" :error="itemsError" />
      <p v-if="itemsLoading" class="muted">{{ t('common.loading') }}</p>
      <p v-else-if="items.length === 0" class="muted">{{ t('caseSets.noItems') }}</p>
      <ul v-else class="set-items">
        <li
          v-for="(item, index) in visibleItems"
          :key="item.id"
          class="set-item"
          :data-testid="`set-item-${item.id}`"
          @click="openMember(item)"
        >
          <input
            type="checkbox"
            class="member-check"
            :checked="selectedItemIds.includes(item.id)"
            :aria-label="t('w62.selectMember')"
            @click.stop
            @change="toggleItem(item.id, ($event.target as HTMLInputElement).checked)"
          />
          <span class="set-item-order">{{ index + 1 }}</span>
          <span class="set-item-code">{{ item.caseCode }}</span>
          <span class="set-item-title">{{ item.title }}</span>
          <span class="set-item-category">{{ item.category || EMPTY }}</span>
          <span class="set-item-priority">{{ item.priority || EMPTY }}</span>
          <span class="set-item-script">
            <CaseScriptLink
              :script-path="item.scriptPath"
              :script-name="item.scriptName"
              :binding-state="item.bindingState"
              :other-script-count="item.otherScriptCount"
              :absolute-source="item.scriptAbsoluteSource"
            />
          </span>
          <span class="row-actions" @click.stop>
            <!-- 筛选态下显示顺序 ≠ 完整顺序，禁用排序避免按显示下标移动错一条 -->
            <NButton
              text
              size="small"
              :disabled="index === 0 || memberIsFiltered"
              @click="move(item, -1)"
            >
              {{ t('caseSets.moveUp') }}
            </NButton>
            <NButton
              text
              size="small"
              :disabled="index === visibleItems.length - 1 || memberIsFiltered"
              @click="move(item, 1)"
            >
              {{ t('caseSets.moveDown') }}
            </NButton>
            <NButton text type="error" size="small" @click="removeItem(item)">
              {{ t('caseSets.remove') }}
            </NButton>
          </span>
        </li>
      </ul>

      <!-- W62-02：成员详情停靠在成员列表之后，不是弹窗 / 抽屉 / 跳页 -->
      <DetailDock :open="detailOpen" :title="detailTitle" @close="closeMember">
        <CaseSetMemberDetail v-if="detailItem" :member="detailItem" />
      </DetailDock>

      <div v-if="assetView" class="asset-panel" data-testid="asset-panel">
        <div class="asset-head">
          <h3 class="asset-title">{{ t('caseSets.assetTitle') }}</h3>
          <span data-testid="asset-fork" :class="['asset-fork', forkClass]">
            {{ t(forkLabel) }}
          </span>
          <span
            v-if="assetView.sourceAssetId !== null"
            data-testid="asset-has-new"
            :class="['asset-new', { 'is-new': assetView.hasNewVersion }]"
          >
            {{
              assetView.hasNewVersion
                ? t('caseSets.assetHasNew', { version: assetView.latestAssetVersion ?? '?' })
                : t('caseSets.assetUpToDate')
            }}
          </span>
        </div>
        <p class="muted small">
          {{ t('caseSets.assetReadout', { version: assetView.assetVersion }) }}
          <template v-if="assetView.contentHash">
            · hash {{ assetView.contentHash.slice(0, 12) }}
          </template>
        </p>

        <div v-if="isBound" class="toolbar">
          <NSelect
            v-model:value="syncStrategy"
            style="width: 180px"
            :options="syncStrategyOptions"
            :aria-label="t('caseSets.assetStrategy')"
            data-testid="asset-strategy"
          />
          <NButton size="small" :disabled="assetBusy" data-testid="asset-diff" @click="checkDiff">
            {{ t('caseSets.assetCheckDiff') }}
          </NButton>
          <NButton size="small" type="primary" :loading="assetBusy" data-testid="asset-sync"
                   @click="syncAsset">
            {{ t('caseSets.assetSync') }}
          </NButton>
          <NButton size="small" quaternary :disabled="assetBusy" data-testid="asset-diverge"
                   @click="divergeAsset">
            {{ t('caseSets.assetDiverge') }}
          </NButton>
        </div>
        <p v-else class="muted small">{{ t('caseSets.assetNoSource') }}</p>

        <ErrorLineView v-if="assetError" :error="assetError" />
        <p v-if="assetMessage" class="text-ok">{{ assetMessage }}</p>

        <ul v-if="assetDiff !== null && assetDiff.length > 0" class="asset-diff"
            data-testid="asset-diff-list">
          <li v-for="row in assetDiff" :key="`${row.type}-${row.caseCode}`"
              class="asset-diff-row" data-testid="diff-row">
            <span :class="['asset-diff-type', `is-${row.type}`]">
              {{ t(`caseSets.assetDiff.${row.type}`) }}
            </span>
            <span class="set-item-code">{{ row.caseCode }}</span>
            <span class="set-item-title">{{ row.title }}</span>
            <span class="asset-diff-detail">{{ row.detail }}</span>
          </li>
        </ul>
        <p v-else-if="assetDiff !== null" class="muted small" data-testid="diff-empty">
          {{ t('caseSets.assetDiffEmpty') }}
        </p>
      </div>
    </div>
      </template>
    </CaseSetWorkspace>

    <div v-if="isAdmin" class="card platform-card" data-testid="platform-case-sets">
      <div class="items-head">
        <h2 class="card-title">{{ t('caseSets.platformTitle') }}</h2>
        <span class="muted small">{{ t('caseSets.platformHint') }}</span>
      </div>

      <div class="toolbar">
        <NInput
          v-model:value="platformKeyword"
          clearable
          style="width: 200px"
          :placeholder="t('common.searchPlaceholder')"
          :aria-label="t('common.searchPlaceholder')"
          @keyup.enter="loadPlatform"
        />
        <NButton quaternary :loading="platformLoading" @click="loadPlatform">
          {{ t('common.refresh') }}
        </NButton>
      </div>

      <ErrorLineView v-if="platformError" :error="platformError" />
      <p v-if="platformLoading" class="muted">{{ t('common.loading') }}</p>
      <p v-else-if="platformSets.length === 0" class="muted">{{ t('caseSets.platformEmpty') }}</p>
      <ul v-else class="platform-sets">
        <li
          v-for="row in platformSets"
          :key="row.id"
          class="platform-set"
          data-testid="platform-set-row"
        >
          <span class="set-item-code">{{ row.setCode }}</span>
          <span class="set-item-title">{{ row.name }}</span>
          <span class="muted small">
            {{ t('caseSets.platformMembers', { count: row.itemCount }) }}
          </span>
          <span class="muted small">
            {{ t('caseSets.platformVersion', { version: row.assetVersion }) }}
          </span>
          <span class="muted small">
            {{ t('caseSets.platformRefs', { count: row.referenceCount }) }}
          </span>
          <NButton text size="small" @click="togglePlatformItems(row)">
            {{
              platformOpenId === row.id
                ? t('caseSets.closeItems')
                : t('caseSets.platformViewMembers')
            }}
          </NButton>
        </li>
      </ul>

      <div v-if="platformOpenId !== null" class="platform-items" data-testid="platform-items">
        <p v-if="platformItemsLoading" class="muted">{{ t('common.loading') }}</p>
        <p v-else-if="platformItems.length === 0" class="muted">{{ t('caseSets.noItems') }}</p>
        <ul v-else class="set-items">
          <li
            v-for="(item, index) in platformItems"
            :key="item.id"
            class="set-item set-item-readonly"
          >
            <span class="set-item-order">{{ index + 1 }}</span>
            <span class="set-item-code">{{ item.caseCode }}</span>
            <span class="set-item-title">{{ item.title }}</span>
            <span class="muted small">{{ item.priority }}</span>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* v5.22.0 / FR-v5.22-06 判据 06-2：空态三个动作一行排开；窄屏允许换行，
   但按钮本身不拉伸（拉伸后三个动作看起来像一个分段控件，反而分不清是三个入口）。 */
.empty-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  justify-content: center;
}
/* W57-15/16 列表三件套：筛选条与批量条 */
.member-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.list-total {
  margin: 8px 0 4px;
}

.pager {
  margin-top: 12px;
}

.batch-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  font-size: var(--font-size-sm);
}

.items-card {
  margin-top: var(--space-5);
  /* 全局 .card 默认 max-width:520px，成员表 7 列挤不下就会一列一变行；
     成员面板必须吃满右栏（宽屏三栏 / 窄屏抽屉都要）。 */
  max-width: none;
}

.items-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
}

.set-items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

/* 成员行 8 列：勾选 / 序号 / 编号 / 标题 / 分类 / 优先级 / 脚本 / 操作。
   原先只声明 4 列，后加的 4 个字段全部掉到第二行（第一列被挤成"UEC测 试"两行），
   v5.19 W69-05 一并修正。 */
.set-item {
  display: grid;
  grid-template-columns: 1.25rem 2.25rem 8.5rem minmax(0, 1fr) 6rem 3.5rem 8rem auto;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  transition: background-color 0.15s ease;
}

/* 平台用例集只读行只有 4 个字段，单独声明，避免被上面 8 列撑错位 */
.set-item-readonly {
  grid-template-columns: 2.25rem 8.5rem minmax(0, 1fr) 3.5rem;
}

/* 表格单元格里的按钮是 h() 渲染的，拿不到 scoped 属性，用 :deep 兜布局 */
:deep(.row-actions) {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.set-item:hover {
  background: var(--surface-hover);
}

.set-item-order {
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.set-item-code {
  font-family: var(--font-mono);
  font-size: var(--font-size-sm);
}

.set-item-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- 资产版本面板（PUBX-01/02） ---- */
.asset-panel {
  margin-top: var(--space-4);
  padding-top: var(--space-4);
  border-top: 1px dashed var(--border);
}

.asset-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.asset-title {
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

.asset-fork {
  padding: 0 var(--space-2);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-sm);
}

.asset-fork.is-synced {
  background: var(--surface-hover);
  color: var(--text-muted);
}

.asset-fork.is-diverged {
  background: var(--accent-soft);
  color: var(--text);
  font-weight: 600;
}

.asset-new {
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

.asset-new.is-new {
  color: var(--accent);
  font-weight: 600;
}

.asset-diff {
  list-style: none;
  margin: var(--space-2) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.asset-diff-row {
  display: grid;
  grid-template-columns: 4.5rem 10rem minmax(0, 1fr) auto;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
}

.asset-diff-type {
  font-size: var(--font-size-sm);
  text-align: center;
}

.asset-diff-type.is-ADDED {
  color: var(--text);
  font-weight: 600;
}

.asset-diff-type.is-MODIFIED {
  color: var(--text-muted);
}

.asset-diff-type.is-REMOVED {
  color: var(--text-muted);
  text-decoration: line-through;
}

.asset-diff-detail {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- 平台用例集库（N9，只读） ---- */
.platform-card {
  margin-top: var(--space-5);
}

.platform-sets {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.platform-set {
  display: grid;
  grid-template-columns: 10rem minmax(0, 1fr) auto auto auto auto;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
}

.platform-set:hover {
  background: var(--surface-hover);
}

.platform-items {
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px dashed var(--border);
}

.set-item-readonly {
  background: var(--panel);
}
</style>
