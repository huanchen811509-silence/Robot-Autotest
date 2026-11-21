<script setup lang="ts">
/**
 * 用例集管理（CAS-02 的前端补齐部分）。
 *
 * 后端 `/case-sets` 早已可用（列表 / 新建 / 改名 / 删除 / 成员 / 排序 / 立即执行），
 * 但一直没有页面入口，用户只能通过执行计划间接触达。本页把这条链路补全：
 * 左侧维护集合本身，右侧「管理用例」维护成员与顺序，可一键按集合发起运行。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import CaseSetWorkspace from '@/components/CaseSetWorkspace.vue'
import DetailDock from '@/components/DetailDock.vue'
import CaseScriptLink from '@/components/CaseScriptLink.vue'
import CaseSetMemberDetail from '@/components/CaseSetMemberDetail.vue'
import { useDetailDock } from '@/composables/useDetailDock'
import { useI18n } from 'vue-i18n'
import { NButton, NInput, NPagination, NSelect } from 'naive-ui'
import { useTableFilters, type TableFilterColumn } from '@/composables/useTableFilters'
import ColumnFilter from '@/components/ColumnFilter.vue'
import { useListPageSize, type PageSize } from '@/composables/useListPageSize'
import { caseApi, caseSetApi, assetVersionApi, platformCaseSetApi,
         type TestCase, type TestSet, type AssetVersionView, type AssetDiffRow,
         type PlatformCaseSetRow, type SyncStrategy } from '@/api/cases'
import { executionApi } from '@/api/execution'
import { useAuthStore } from '@/stores/auth'
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
const memberIsFiltered = memberFilters.isFiltered
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

/**
 * 服务端查询参数（v5.35 / FR-UIE-01）。
 *
 * 列筛选弹层与「按条件全选」在改版中取消：它们在 320px 中栏里既占宽度又无从使用，
 * 而且「选集合」这个动作本身没有批量语义（批量动作在成员表里）。列表只按关键词查。
 */
// URL 直达要能重现筛选：优先 route.query，浏览器直接粘贴链接时再读 location.search
// （单测里用 history.replaceState 改地址，router 未必已同步）。
const initialQuery = new URLSearchParams(window.location.search)
const filterCode = ref(String(route.query.sets_t_setCode ?? initialQuery.get('sets_t_setCode') ?? ''))
const filterName = ref(String(route.query.sets_t_name ?? initialQuery.get('sets_t_name') ?? ''))

const setsQueryParams = computed<Record<string, string | undefined>>(() => ({
  keyword: keyword.value.trim() || undefined,
  setCode: filterCode.value.trim() || undefined,
  name: filterName.value.trim() || undefined,
}))

/** 精确筛选改到工具栏（原先是 DataTable 的列筛选弹层，320px 中栏里点不开）。 */
function applyFilters(): void {
  page.value = 1
  void load()
}

function clearFilters(): void {
  filterCode.value = ''
  filterName.value = ''
  page.value = 1
  void load()
}

/** 写操作判定（v5.21.0 / W71-05）：行内按钮与模板共用同一 composable。 */
const { can } = useCan()

/** 行内「更多」菜单：同一时刻只开一个。 */
const setMenuId = ref<number | null>(null)
function toggleSetMenu(id: number): void {
  setMenuId.value = setMenuId.value === id ? null : id
}

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
  setMenuId.value = null
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
 * 成员详情上/下一条连续浏览（v5.32.0 / FR-v5.32-04）。
 *
 * 抽屉里逐条看的场景（核对一批成员的分类/优先级）不该让人「关掉→找下一行→再点开」。
 * 顺序按当前列表实际显示顺序，到头即停，不循环——循环会让人误以为还有没看过的。
 */
const detailIndex = computed(() =>
  detailItem.value ? items.value.findIndex((row) => row.id === detailItem.value?.id) : -1,
)
const hasPrevMember = computed(() => detailIndex.value > 0)
const hasNextMember = computed(
  () => detailIndex.value >= 0 && detailIndex.value < items.value.length - 1,
)

function stepMember(delta: number): void {
  const next = items.value[detailIndex.value + delta]
  if (next) openMember(next)
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

/**
 * 批量确认脚本绑定（W81-03 / FR-v5.31-01）。
 *
 * 只对「已绑定待确认」（BOUND_UNVERIFIED）的成员发起：其余状态调了也会被后端拒，
 * 提前筛掉可以少一批无意义的失败明细。失败逐条回报，不整体回滚——已确认的就是已确认。
 */
async function batchConfirmBinding(): Promise<void> {
  if (selectedItemIds.value.length === 0) return
  const picked = new Set(selectedItemIds.value)
  const targets = items.value
    .filter((row) => picked.has(row.id) && row.bindingState === 'BOUND_UNVERIFIED')
    .map((row) => row.id)
  itemsError.value = null
  message.value = ''
  if (targets.length === 0) {
    message.value = t('w62.confirmBindingNone')
    return
  }
  try {
    const result = await caseApi.confirmBindings(projectId.value, targets)
    await loadItems()
    message.value = result.failed.length
      ? t('w62.confirmBindingPartial', { ok: result.confirmed, failed: result.failed.length })
      : t('w62.confirmBindingDone', { ok: result.confirmed })
  } catch (e) {
    itemsError.value = localizeError(e)
  }
}

/** 选中的成员作为一个整体前后挪一位，未选中的相对顺序不变。 */
async function batchMove(delta: number): Promise<void> {  if (!activeSet.value || selectedItemIds.value.length === 0) return
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

    <details class="list-filters">
      <summary>{{ t('common.filter') }}</summary>
      <div class="toolbar">
        <NInput v-model:value="filterCode" clearable style="width: 180px" :placeholder="t('caseSets.setCode')" />
        <NInput v-model:value="filterName" clearable style="width: 200px" :placeholder="t('caseSets.name')" />
        <NButton size="small" @click="applyFilters">{{ t('common.search') }}</NButton>
        <NButton size="small" quaternary @click="clearFilters">{{ t('common.clearFilters') }}</NButton>
      </div>
    </details>

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
    <CaseSetWorkspace :active="activeSet !== null" :drawer="true" @close="closeItems">
      <template #nav>
    <p class="muted list-total" data-testid="case-sets-total">
      {{ t('caseSets.totalCount', { count: total }) }}
    </p>

    <table v-if="sets.length" class="set-table" data-testid="case-set-list">
      <thead>
        <tr>
          <th class="col-code">{{ t('caseSets.setCode') }}</th>
          <th>{{ t('caseSets.name') }}</th>
          <th class="col-desc">{{ t('caseSets.description') }}</th>
          <th class="col-count">{{ t('caseSets.memberCount') }}</th>
          <th class="col-actions">{{ t('common.actions') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in sets"
          :key="row.id"
          class="set-row"
          :class="{ active: activeSet?.id === row.id }"
          :data-testid="`case-set-row-${row.id}`"
          @click="openItems(row)"
        >
          <td class="col-code mono" :title="row.setCode">{{ row.setCode }}</td>
          <td class="cell-ellipsis" :title="row.name">{{ row.name }}</td>
          <td class="col-desc cell-ellipsis muted" :title="row.description ?? ''">{{ row.description || '—' }}</td>
          <td class="col-count muted">{{ t('caseSets.itemsCount', { count: row.itemCount ?? 0 }) }}</td>
          <td class="col-actions row-actions" @click.stop>
            <NButton size="small" type="primary" text :data-testid="`manage-${row.id}`" @click="openItems(row)">
              {{ t('caseSets.manage') }}
            </NButton>
            <NButton
              v-if="can('case:write') || can('execution:write')"
              size="small"
              text
              class="more-btn"
              :data-testid="`more-${row.id}`"
              @click="toggleSetMenu(row.id)"
            >
              {{ t('w57.globalCaseSets.more') }}
            </NButton>
            <span v-if="setMenuId === row.id" class="menu">
              <button v-if="can('case:write')" type="button" class="menu-item" @click="openEdit(row)">
                {{ t('caseSets.edit') }}
              </button>
              <button
                v-if="can('execution:write')"
                type="button"
                class="menu-item"
                @click="runNow(row)"
              >
                {{ t('caseSets.runNow') }}
              </button>
              <button
                v-if="can('case:write')"
                type="button"
                class="menu-item danger"
                @click="removeSet(row)"
              >
                {{ t('common.delete') }}
              </button>
            </span>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-else class="empty-actions empty-state" data-testid="case-sets-empty-actions">
      <p class="muted">{{ t('caseSets.empty') }}</p>
      <p class="muted small">{{ t('caseSets.emptyHint') }}</p>
      <div class="empty-buttons">
        <NButton v-if="can('case:write')" size="small" type="primary" data-testid="case-sets-empty-create" @click="openCreate">
          {{ t('caseSets.create') }}
        </NButton>
        <NButton v-if="can('case:write')" size="small" data-testid="case-sets-empty-import" @click="goImport">
          {{ t('caseSets.emptyActionImport') }}
        </NButton>
        <NButton quaternary size="small" data-testid="case-sets-empty-sample" @click="goSamples">
          {{ t('caseSets.emptyActionSample') }}
        </NButton>
      </div>
    </div>

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
        <NButton size="small" :disabled="selectedItemIds.length === 0" data-testid="member-batch-confirm-binding"
                 @click="batchConfirmBinding">{{ t('w62.batchConfirmBinding') }}</NButton>
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
        <template v-if="detailItem" #actions>
          <NButton size="tiny" :disabled="!hasPrevMember" data-testid="member-prev" @click="stepMember(-1)">
            {{ t('w62.memberPrev') }}
          </NButton>
          <NButton size="tiny" :disabled="!hasNextMember" data-testid="member-next" @click="stepMember(1)">
            {{ t('w62.memberNext') }}
          </NButton>
        </template>
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

    <!-- v5.35 / FR-UIE-06：平台库改为折叠区，默认收起，不与主列表争版面 -->
    <details v-if="isAdmin" class="card platform-card" data-testid="platform-case-sets">
      <summary class="platform-summary">
        <span class="card-title">{{ t('caseSets.platformTitle') }}</span>
        <span class="muted small">{{ t('caseSets.platformHint') }}</span>
      </summary>

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
    </details>
  </div>
</template>

<style scoped>
/* v5.35 / FR-UIE-01：全宽集合列表。原先整张 DataTable 塞在 320px 中栏，
   1920 下集代号与名称被裁光、一行只剩四个按钮。 */
.set-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.set-table th {
  text-align: left;
  font-weight: 600;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  padding: 0.75rem;
  border-bottom: 1px solid var(--border);
}

.set-table td {
  padding: 0.75rem;
  border-bottom: 1px solid var(--border);
  font-size: var(--font-size-sm);
  vertical-align: middle;
}

.set-row {
  cursor: pointer;
}

.set-row:hover {
  background: var(--surface-2);
}

.set-row.active {
  background: var(--surface-2);
  box-shadow: inset 3px 0 0 var(--accent);
}

.set-table .col-code {
  width: 9.5rem;
  /* 集代号是标识符，折行会让行高不齐；超长省略，完整值在 title 里 */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.set-table .col-desc { width: 30%; }
.set-table .col-count { width: 7rem; }
.set-table .col-actions { width: 12rem; white-space: nowrap; }

.cell-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono {
  font-family: var(--font-mono, monospace);
}

.more-btn {
  margin-left: 0.5rem;
}

.menu {
  position: absolute;
  z-index: 30;
  margin-top: 0.25rem;
  min-width: 7rem;
  display: flex;
  flex-direction: column;
  padding: 4px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--panel);
  box-shadow: 0 8px 24px rgb(0 0 0 / 24%);
}

.menu-item {
  text-align: left;
  padding: 6px 8px;
  border: none;
  background: none;
  color: var(--text);
  cursor: pointer;
  font: inherit;
  font-size: var(--font-size-sm);
  border-radius: var(--radius-sm);
}

.menu-item:hover {
  background: var(--surface-2);
}

.menu-item.danger {
  color: var(--fail);
}

.empty-buttons {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

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
  /* v5.35 / FR-UIE-03：脚本列从 8rem 放宽到 10rem，操作列给固定 11rem。
     原先是 auto —— 在 1080px 抽屉里被压到 40px，三个按钮竖排成一字一行，
     单行高 100px（实测截图）。 */
  grid-template-columns: 1.25rem 2.25rem 8.5rem minmax(0, 1fr) 6rem 3.5rem 10rem 11rem;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  transition: background-color 0.15s ease;
}

/* 单元格一律允许收缩，长文本省略而不是换行把行撑高 */
.set-item > * {
  min-width: 0;
}

.set-item-code,
.set-item-title,
.set-item-category,
.set-item-script {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.set-item-script :deep(a),
.set-item-script :deep(span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  max-width: 100%;
  vertical-align: bottom;
}

/* 平台用例集只读行只有 4 个字段，单独声明，避免被上面 8 列撑错位 */
.set-item-readonly {
  grid-template-columns: 2.25rem 8.5rem minmax(0, 1fr) 3.5rem;
}

/* 表格单元格里的按钮是 h() 渲染的，拿不到 scoped 属性，用 :deep 兜布局 */
:deep(.row-actions) {
  display: flex;
  flex-wrap: nowrap;
  gap: var(--space-1);
  justify-content: flex-end;
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

.platform-summary {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  cursor: pointer;
  list-style: none;
}

.platform-summary::-webkit-details-marker {
  display: none;
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
