<script setup lang="ts">
/**
 * 用例集管理（CAS-02 的前端补齐部分）。
 *
 * 后端 `/case-sets` 早已可用（列表 / 新建 / 改名 / 删除 / 成员 / 排序 / 立即执行），
 * 但一直没有页面入口，用户只能通过执行计划间接触达。本页把这条链路补全：
 * 左侧维护集合本身，右侧「管理用例」维护成员与顺序，可一键按集合发起运行。
 */
import { computed, h, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NCheckbox, NInput, NSelect, type DataTableColumns } from 'naive-ui'
import ColumnFilter from '@/components/ColumnFilter.vue'
import { useTableFilters, type TableFilterColumn } from '@/composables/useTableFilters'
import { useServerSelection } from '@/composables/useServerSelection'
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
import { confirmAction } from '@/composables/useConfirm'

const { t } = useI18n()
const auth = useAuthStore()

const projectId = computed(() => auth.projectId ?? 0)

const sets = ref<TestSet[]>([])
const keyword = ref('')
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
const caseOptions = ref<{ label: string; value: number }[]>([])
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
  columns: ['caseCode', 'priority'],
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
]

function onMemberSelected(key: string, values: string[]): void {
  if (memberFilterColumns.find((col) => col.key === key)?.mode === 'text') return
  memberFilters.setFacet(key, values)
}
function onMemberText(key: string, value: string): void {
  if (memberFilterColumns.find((col) => col.key === key)?.mode !== 'text') return
  memberFilters.setText(key, value)
}

const visibleItems = computed<TestCase[]>(() => {
  const q = memberFilters.q.value.trim().toLowerCase()
  const byCode = (memberTexts.value.caseCode ?? '').toLowerCase()
  const priorities = memberFacets.value.priority ?? []
  return items.value.filter((item) => {
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
const visibleSets = computed<TestSet[]>(() => {
  const byCode = (setsFilters.texts.value.setCode ?? '').toLowerCase()
  const byName = (setsFilters.texts.value.name ?? '').toLowerCase()
  const q = setsFilters.q.value.trim().toLowerCase()
  return sets.value.filter((set) => {
    if (byCode && !set.setCode.toLowerCase().includes(byCode)) return false
    if (byName && !set.name.toLowerCase().includes(byName)) return false
    if (q && !`${set.setCode} ${set.name}`.toLowerCase().includes(q)) return false
    return true
  })
})
function onSetsFilterChange(next: Record<string, string[]>): void {
  for (const column of setsFilterColumns) {
    setsFilters.setText(column.key, next[column.key]?.[0] ?? '')
  }
}

/** 服务端选择：全选用筛选条件表达，跨页生效；>10000 需二次确认。 */
const pageIds = computed<number[]>(() => sets.value.map((set) => set.id))
const selection = useServerSelection(pageIds)
const selectionCount = selection.count
const awaitingSelectAllConfirm = selection.awaitingConfirm

function selectAllMatching(): void {
  selection.requestSelectAllMatching(setsFilters.queryParams.value, sets.value.length)
}
function clearSelection(): void {
  selection.clear()
}

watch(setsFilters.revision, () => {
  // 筛选条件一变，旧的「按条件全选」必须作废（W57-16）
  selection.resetForFilters(setsFilters.queryParams.value)
})

const columns = computed<DataTableColumns<TestSet>>(() => [
  {
    title: t('w57.caseSets.colSelect'),
    key: '__select',
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
    width: 300,
    render: (row) =>
      h('div', { class: 'row-actions' }, [
        h(
          NButton,
          { text: true, type: 'primary', size: 'small', onClick: () => void openItems(row) },
          { default: () => t('caseSets.manage') },
        ),
        h(
          NButton,
          { text: true, size: 'small', onClick: () => openEdit(row) },
          { default: () => t('caseSets.edit') },
        ),
        h(
          NButton,
          { text: true, size: 'small', onClick: () => void runNow(row) },
          { default: () => t('caseSets.runNow') },
        ),
        h(
          NButton,
          { text: true, type: 'error', size: 'small', onClick: () => void removeSet(row) },
          { default: () => t('common.delete') },
        ),
      ]),
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
    const page = await caseSetApi.page(projectId.value, {
      keyword: keyword.value.trim() || undefined,
      page: 1,
      size: 100,
    })
    sets.value = page.items
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
    if (editingId.value === null) {
      const created = await caseSetApi.create(projectId.value, {
        setCode: form.value.setCode.trim(),
        name: form.value.name.trim(),
        description,
      })
      message.value = t('caseSets.created', { name: created.name })
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
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    saving.value = false
  }
}

async function removeSet(row: TestSet): Promise<void> {
  if (!(await confirmAction(t('caseSets.confirmDelete', { name: row.name }), { tone: 'danger' })))
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
}

function closeItems(): void {
  activeSet.value = null
  items.value = []
  caseOptions.value = []
  selectedCaseId.value = null
  itemsError.value = null
  assetView.value = null
  assetDiff.value = null
  assetError.value = null
  assetMessage.value = ''
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
  if (!activeSet.value || selectedCaseId.value === null) return
  const picked = caseOptions.value.find((option) => option.value === selectedCaseId.value)
  itemsError.value = null
  message.value = ''
  try {
    await caseSetApi.addItem(projectId.value, activeSet.value.id, selectedCaseId.value)
    selectedCaseId.value = null
    message.value = t('caseSets.itemAdded', { code: picked?.label.split(' ')[0] ?? '' })
    await Promise.all([loadItems(), runCaseSearch('')])
  } catch (e) {
    itemsError.value = localizeError(e)
  }
}

async function removeItem(item: TestCase): Promise<void> {
  if (!activeSet.value) return
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
        @keyup.enter="load"
      />
      <NButton type="primary" @click="openCreate">{{ t('caseSets.create') }}</NButton>
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

    <DataTable
      :columns="columns"
      :data="visibleSets"
      :loading="loading"
      :row-key="(row: TestSet) => row.id"
      :empty-title="t('caseSets.empty')"
      :empty-description="projectId ? t('caseSets.emptyHint') : t('overview.noProject')"
      empty-icon="cases"
      :error="Boolean(error)"
      filterable
      :filter-columns="setsFilterColumns"
      :filters="setsFilterValues"
      @update:filters="onSetsFilterChange"
    />

    <div v-if="activeSet" class="card items-card">
      <div class="items-head">
        <h2 class="card-title">{{ t('caseSets.itemsTitle', { name: activeSet.name }) }}</h2>
        <span class="muted small">{{ t('caseSets.itemsCount', { count: items.length }) }}</span>
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

      <div class="toolbar">
        <NSelect
          v-model:value="selectedCaseId"
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
        <NButton :disabled="selectedCaseId === null" @click="addCase">
          {{ t('caseSets.addCase') }}
        </NButton>
        <NButton quaternary @click="closeItems">{{ t('caseSets.closeItems') }}</NButton>
      </div>

      <ErrorLineView v-if="itemsError" :error="itemsError" />
      <p v-if="itemsLoading" class="muted">{{ t('common.loading') }}</p>
      <p v-else-if="items.length === 0" class="muted">{{ t('caseSets.noItems') }}</p>
      <ul v-else class="set-items">
        <li v-for="(item, index) in visibleItems" :key="item.id" class="set-item">
          <span class="set-item-order">{{ index + 1 }}</span>
          <span class="set-item-code">{{ item.caseCode }}</span>
          <span class="set-item-title">{{ item.title }}</span>
          <span class="row-actions">
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
/* W57-15/16 列表三件套：筛选条与批量条 */
.member-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
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

.set-item {
  display: grid;
  grid-template-columns: 3rem 10rem minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  transition: background-color 0.15s ease;
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
