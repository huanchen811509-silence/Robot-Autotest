<script setup lang="ts">
/**
 * 公共用例库（v4.6.0 / FR-v4.6-01..13、v4.6.1 / FR-v4.6-20..24；v4.11.0 / FR-v4.11-26）。
 *
 * 列表 + 一级/二级分类筛选 + 两段式幂等导入（上传 → 预览差异 → 提交）+ 反向导出。
 * 写操作按钮在管理员下才显示；后端仍会二次校验，前端只做显隐。
 *
 * v4.11.0 两处变化：
 * 1. `?view=imports` 把原先页内的 `<details class="batches">` 提级为独立视图分支——侧栏
 *    「导入记录」二级项需要能直达，而折叠块无法被 URL 寻址。两个分支互斥渲染，
 *    切换不重复请求用例列表（`onMounted` 只加载当前分支）。
 * 2. 三处原生 `<table>` 改用 `DataTable` 壳（L-49-06 / FR-v4.11-19），
 *    暗色主题下的表头与吸顶行为才与其余 19 张表一致。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { NButton, type DataTableColumns } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import {
  globalCaseApi,
  type CaseCategoryRow,
  type GlobalCaseRow,
  type GlobalImportPreview,
  type ImportBatchRow,
} from '@/api/globalCases'
import type { PageResult } from '@/api/iam'
import DataTable from '@/components/DataTable.vue'
import CaseCategoryTree from '@/components/CaseCategoryTree.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/**
 * W57-11：列表 / 分类归组切换只增删 URL 上的 `group=1`，**其余筛选参数原样保留**。
 * 归组视图复用 CaseCategoryTree（默认展开到二级、单分类 >200 显示「前 200 / 共 N」）。
 */
const grouped = computed(() => route.query.group === '1')

function setGrouped(on: boolean): void {
  const query: LocationQueryRaw = { ...route.query }
  if (on) query.group = '1'
  else delete query.group
  void router.replace({ query })
}

/**
 * 当前视图：`cases`（默认）/ `imports`。
 *
 * 裸路径 `/g/cases` 就是「用例清单」——该组第一个叶子不带 query，所以不需要
 * `router.replace` 规范化（与 `/g/assets`、`/g/templates` 不同，那两组两个叶子都带 query）。
 */
const view = computed<'cases' | 'imports'>(() =>
  route.query.view === 'imports' ? 'imports' : 'cases',
)

const rows = ref<GlobalCaseRow[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')
const categoryL1 = ref('')
const lifecycleStatus = ref('')
const loading = ref(false)
const failed = ref('')
const categories = ref<CaseCategoryRow[]>([])

const categoryL1Options = computed(() => categories.value.filter((item) => item.level === 1))
const categoryL2Options = computed(() =>
  categories.value.filter(
    (item) => item.level === 2 && item.parentId === parentIdOf(categoryL1.value),
  ),
)
const categoryL2 = ref('')

function parentIdOf(name: string): number | null {
  if (!name) return null
  return categoryL1Options.value.find((item) => item.name === name)?.id ?? null
}

const canWrite = computed(() => auth.isAdmin)

async function load() {
  loading.value = true
  failed.value = ''
  try {
    const result: PageResult<GlobalCaseRow> = await globalCaseApi.page({
      keyword: keyword.value,
      categoryL1: categoryL1.value,
      categoryL2: categoryL2.value,
      lifecycleStatus: lifecycleStatus.value,
      page: page.value,
      size,
    })
    rows.value = result.items
    total.value = result.total
  } catch {
    failed.value = t('global.loadFailed')
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  try {
    categories.value = await globalCaseApi.categories()
  } catch {
    categories.value = []
  }
}

function onCategoryL1Change() {
  categoryL2.value = ''
  page.value = 1
  void load()
}

function onFilterChange() {
  page.value = 1
  void load()
}

function goPage(next: number) {
  const max = Math.max(1, Math.ceil(total.value / size))
  page.value = Math.min(max, Math.max(1, next))
  void load()
}

/* ------------------------------------------------------------ 表格列定义 */

const columns = computed<DataTableColumns<GlobalCaseRow>>(() => {
  const cols: DataTableColumns<GlobalCaseRow> = [
    {
      title: t('global.colCode'),
      key: 'caseCode',
      width: 180,
      render: (row) => row.externalId || row.caseCode,
    },
    { title: t('global.colTitle'), key: 'title', minWidth: 220 },
    {
      title: t('global.colCategory'),
      key: 'categoryL1',
      width: 240,
      render: (row) => {
        if (!row.categoryL1) return '—'
        return row.categoryL2 ? `${row.categoryL1} / ${row.categoryL2}` : row.categoryL1
      },
    },
    { title: t('global.colPriority'), key: 'priority', width: 90 },
    {
      title: t('global.colStatus'),
      key: 'lifecycleStatus',
      width: 110,
      render: (row) =>
        h(
          'span',
          { class: ['badge', row.lifecycleStatus] },
          row.lifecycleStatus === 'active'
            ? t('global.lifecycleActive')
            : t('global.lifecycleDeprecated'),
        ),
    },
    {
      title: t('global.colVersion'),
      key: 'versionNo',
      width: 90,
      render: (row) => `v${row.versionNo}`,
    },
  ]
  if (canWrite.value) {
    cols.push({
      title: t('global.colActions'),
      key: 'actions',
      width: 120,
      render: (row) =>
        h(
          NButton,
          { text: true, type: 'primary', onClick: () => void toggleLifecycle(row) },
          () => (row.lifecycleStatus === 'active' ? t('global.deprecate') : t('global.restore')),
        ),
    })
  }
  return cols
})

const batchColumns = computed<DataTableColumns<ImportBatchRow>>(() => [
  { title: '#', key: 'id', width: 80 },
  { title: t('global.batchFile'), key: 'source_name', minWidth: 220 },
  { title: t('global.colStatus'), key: 'status', width: 120 },
  { title: t('global.batchRows'), key: 'total_rows', width: 110 },
  {
    title: t('global.batchDiff'),
    key: 'diff',
    width: 180,
    render: (batch) =>
      `+${batch.new_count} ~${batch.updated_count} =${batch.unchanged_count} -${batch.deprecated_count}`,
  },
])

const previewColumns = computed<DataTableColumns<GlobalImportPreview['rows'][number]>>(() => [
  { title: '#', key: 'rowNo', width: 70 },
  { title: t('global.colCode'), key: 'caseCode', width: 170 },
  { title: t('global.colTitle'), key: 'title', minWidth: 200 },
  {
    title: t('global.importAction'),
    key: 'action',
    width: 120,
    render: (row) => h('span', { class: ['badge', row.action.toLowerCase()] }, row.action),
  },
  { title: t('global.importMessage'), key: 'message', minWidth: 180 },
])

/* ---------------------------------------------------------------- 导入 */

const importOpen = ref(false)
const preview = ref<GlobalImportPreview | null>(null)
const importing = ref(false)
const importMessage = ref('')
const commitResult = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

function openImport() {
  importOpen.value = true
  preview.value = null
  importMessage.value = ''
  commitResult.value = ''
}

/**
 * 预览导入。
 *
 * `force` 透传给后端（v4.11.0 / FR-v4.11-18）：同一份文件第二次预览默认走「回放」，
 * 想看「相对当前库到底变了什么」必须显式要求重算。默认不勾，保持幂等语义不变。
 */
const forceRecalc = ref(false)

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importing.value = true
  importMessage.value = ''
  commitResult.value = ''
  try {
    preview.value = await globalCaseApi.previewImport(file, forceRecalc.value)
    await loadBatches()
  } catch (error) {
    importMessage.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    importing.value = false
  }
}

async function commitImport() {
  if (!preview.value) return
  importing.value = true
  try {
    const result = await globalCaseApi.commitImport(preview.value.batchId)
    commitResult.value = t('global.importDone', {
      inserted: result.inserted,
      updated: result.updated,
      unchanged: result.unchanged,
      deprecated: result.deprecated,
    })
    await load()
    await loadCategories()
    await loadBatches()
  } catch (error) {
    importMessage.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    importing.value = false
  }
}

async function downloadErrorReport() {
  if (!preview.value) return
  const blob = await globalCaseApi.errorReport(preview.value.batchId)
  triggerDownload(blob, `import-${preview.value.batchId}-report.csv`)
}

async function exportCases() {
  const blob = await globalCaseApi.export({
    categoryL1: categoryL1.value,
    lifecycleStatus: lifecycleStatus.value,
  })
  triggerDownload(blob, 'ed202-cases.xlsx')
}

function triggerDownload(blob: Blob, fallbackName: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fallbackName
  anchor.click()
  URL.revokeObjectURL(url)
}

/* --------------------------------------------------------------- 生命周期 */

async function toggleLifecycle(row: GlobalCaseRow) {
  if (row.lifecycleStatus === 'active') {
    await globalCaseApi.deprecate(row.id)
  } else {
    await globalCaseApi.restore(row.id)
  }
  await load()
}

/* ------------------------------------------------------------------ 批次 */

const batches = ref<ImportBatchRow[]>([])
const batchTotal = ref(0)
const batchPage = ref(1)

async function loadBatches() {
  try {
    const result = await globalCaseApi.batches({ page: batchPage.value, size: 20 })
    batches.value = result.items
    batchTotal.value = result.total
  } catch {
    batches.value = []
  }
}

/** 只在首次进入该分支时拉数据：两个视图来回切不重复请求（FR-v4.11-26 判据）。 */
watch(view, (next) => {
  if (next === 'imports') {
    if (batches.value.length === 0) void loadBatches()
  } else if (rows.value.length === 0) {
    void load()
  }
})

onMounted(async () => {
  await loadCategories()
  if (view.value === 'imports') {
    await loadBatches()
  } else {
    await load()
  }
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h1>{{ view === 'imports' ? t('global.importsTitle') : t('global.casesTitle') }}</h1>
        <p class="muted">
          {{ view === 'imports' ? t('global.importsHint') : t('global.casesHint') }}
        </p>
      </div>
      <div class="page-actions">
        <button v-if="canWrite" class="btn" @click="exportCases">{{ t('global.export') }}</button>
        <button v-if="canWrite" class="btn btn-primary" @click="openImport">
          {{ t('global.import') }}
        </button>
      </div>
    </header>

    <!-- W57-11：列表 / 分类归组切换。独立容器，不进 .page-actions（避免打乱既有写按钮索引） -->
    <div class="view-switch" data-testid="view-switch">
      <button
        type="button"
        class="view-btn"
        :class="{ active: !grouped }"
        :aria-pressed="!grouped"
        data-testid="view-list"
        @click="setGrouped(false)"
      >{{ t('w57.globalCases.list') }}</button>
      <button
        type="button"
        class="view-btn"
        :class="{ active: grouped }"
        :aria-pressed="grouped"
        data-testid="view-grouped"
        @click="setGrouped(true)"
      >{{ t('w57.globalCases.grouped') }}</button>
    </div>

    <!-- 用例清单分支 -->
    <div v-if="view === 'cases' && !grouped" class="cases-layout">
      <aside class="cases-sidebar">
        <h2 class="sidebar-title">{{ t('global.categories') }}</h2>
        <button
          class="category-item"
          :class="{ active: categoryL1 === '' }"
          @click="((categoryL1 = ''), (categoryL2 = ''), onFilterChange())"
        >
          {{ t('global.allCategories') }}
          <span class="count">{{ total }}</span>
        </button>
        <button
          v-for="item in categoryL1Options"
          :key="item.id"
          class="category-item"
          :class="{ active: categoryL1 === item.name }"
          @click="((categoryL1 = item.name), onCategoryL1Change())"
        >
          {{ item.name }}
          <span class="count">{{ item.caseCount }}</span>
        </button>
      </aside>

      <div class="cases-main">
        <div class="filters">
          <input
            v-model="keyword"
            class="input"
            :placeholder="t('global.searchPlaceholder')"
            @keyup.enter="onFilterChange"
          />
          <select v-model="categoryL2" class="select" @change="onFilterChange">
            <option value="">{{ t('global.allCategoriesL2') }}</option>
            <option v-for="item in categoryL2Options" :key="item.id" :value="item.name">
              {{ item.name }}
            </option>
          </select>
          <select v-model="lifecycleStatus" class="select" @change="onFilterChange">
            <option value="">{{ t('global.allLifecycle') }}</option>
            <option value="active">{{ t('global.lifecycleActive') }}</option>
            <option value="deprecated">{{ t('global.lifecycleDeprecated') }}</option>
          </select>
        </div>

        <p v-if="failed" class="error">{{ failed }}</p>

        <div data-testid="cases-grid">
          <DataTable
            :columns="columns"
            :data="rows"
            :loading="loading"
            :row-key="(row: GlobalCaseRow) => row.id"
            :error="Boolean(failed)"
            :empty-title="t('global.empty')"
            :max-height="520"
          />
        </div>

        <div class="pager">
          <button class="btn" :disabled="page <= 1" @click="goPage(page - 1)">
            {{ t('global.prev') }}
          </button>
          <span class="muted">{{ page }} / {{ Math.max(1, Math.ceil(total / size)) }}</span>
          <button
            class="btn"
            :disabled="page >= Math.ceil(total / size)"
            @click="goPage(page + 1)"
          >
            {{ t('global.next') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 分类归组分支（W57-11）：默认折叠到二级，单分类 >200 显示「前 200 / 共 N」 -->
    <div v-else-if="view === 'cases'" class="grouped-pane" data-testid="grouped-view">
      <p class="muted">{{ t('w57.globalCases.hint') }}</p>
      <CaseCategoryTree :selectable="false" />
    </div>

    <!-- 导入记录分支（v4.11.0 从折叠块提级） -->
    <div v-else class="imports-pane" data-testid="imports-grid">
      <div class="filters">
        <span class="muted">{{ t('global.batchTotal', { total: batchTotal }) }}</span>
        <button class="btn" @click="loadBatches">{{ t('global.refresh') }}</button>
      </div>
      <DataTable
        :columns="batchColumns"
        :data="batches"
        :row-key="(row: ImportBatchRow) => row.id"
        :empty-title="t('global.empty')"
        :max-height="560"
      />
    </div>

    <div v-if="importOpen" class="modal-mask" @click.self="importOpen = false">
      <div class="modal">
        <header class="modal-head">
          <h2>{{ t('global.importTitle') }}</h2>
          <button class="link" @click="importOpen = false">{{ t('global.close') }}</button>
        </header>

        <div class="modal-body">
          <p class="muted">{{ t('global.importHint') }}</p>
          <label class="force-line">
            <input v-model="forceRecalc" type="checkbox" data-testid="force-recalc" />
            <span>{{ t('global.importForceRecalc') }}</span>
          </label>
          <input ref="fileInput" type="file" accept=".xlsx,.xls" @change="onFilePicked" />
          <p v-if="importing" class="muted">{{ t('global.loading') }}</p>
          <p v-if="importMessage" class="error">{{ importMessage }}</p>

          <template v-if="preview">
            <div class="diff-cards">
              <span class="diff-card new">+{{ preview.newCount }} {{ t('global.importNew') }}</span>
              <span class="diff-card update">
                ~{{ preview.updateCount }} {{ t('global.importUpdate') }}
              </span>
              <span class="diff-card unchanged">
                ={{ preview.unchangedCount }} {{ t('global.importUnchanged') }}
              </span>
              <span class="diff-card deprecate">
                -{{ preview.deprecateCount }} {{ t('global.importDeprecate') }}
              </span>
              <span v-if="preview.errorCount" class="diff-card error">
                !{{ preview.errorCount }} {{ t('global.importErrors') }}
              </span>
            </div>
            <p v-if="preview.replayed" class="muted">{{ t('global.importReplayed') }}</p>
            <p class="muted mono">
              {{ t('global.assetChecksum') }}：{{ preview.sha256.slice(0, 16) }} ·
              {{ t('global.assetBatch') }} #{{ preview.batchId }}
            </p>
            <p v-if="preview.skippedSheets?.length" class="muted">
              {{ t('global.importSkippedSheets', { sheets: preview.skippedSheets.join('、') }) }}
            </p>
            <p v-if="preview.errorCount" class="error">
              {{ t('global.importErrorHint', { count: preview.errorCount }) }}
              <button class="link" @click="downloadErrorReport">
                {{ t('global.downloadReport') }}
              </button>
            </p>

            <DataTable
              :columns="previewColumns"
              :data="preview.rows"
              :row-key="(row: GlobalImportPreview['rows'][number]) => row.rowNo"
              :max-height="320"
            />

            <p v-if="commitResult" class="success">{{ commitResult }}</p>
            <div class="modal-actions">
              <button class="btn" @click="importOpen = false">{{ t('global.close') }}</button>
              <button class="btn btn-primary" :disabled="importing" @click="commitImport">
                {{ t('global.importCommit') }}
              </button>
            </div>
          </template>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.page-actions {
  display: flex;
  gap: var(--space-2);
}

.view-switch {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: var(--space-3);
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
}

.view-btn {
  padding: 4px 14px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}

.view-btn.active {
  background: var(--accent);
  color: var(--accent-contrast);
  font-weight: 600;
}

.grouped-pane {
  margin-top: var(--space-3);
  max-width: 100%;
  box-sizing: border-box;
}

.cases-layout {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: var(--space-4);
  margin-top: var(--space-3);
}

.cases-sidebar {
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-right: 1px solid var(--border);
  padding-right: var(--space-3);
}

.sidebar-title {
  font-size: 13px;
  color: var(--text-muted);
  margin: 0 0 var(--space-2);
}

.category-item {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
  padding: 6px 8px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text);
  text-align: left;
  cursor: pointer;
}

.category-item:hover,
.category-item.active {
  background: var(--panel);
}

.count {
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.filters {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: var(--space-3);
}

.imports-pane {
  margin-top: var(--space-3);
}

.input,
.select {
  min-width: 180px;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
}

.mono {
  font-family: var(--font-mono, monospace);
}

.badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 11px;
  border: 1px solid var(--border);
}

.badge.active,
.badge.new {
  color: var(--ok);
}

.badge.deprecated,
.badge.deprecate {
  color: var(--fail);
}

.badge.update {
  color: var(--warn);
}

.badge.unchanged {
  color: var(--text-muted);
}

.pager {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.force-line {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-bottom: var(--space-2);
  color: var(--text-muted);
  font-size: 13px;
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}

.modal {
  width: min(900px, 92vw);
  max-height: 86vh;
  overflow: auto;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.diff-cards {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: var(--space-3) 0;
}

.diff-card {
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  font-size: 12px;
}

.diff-card.new {
  color: var(--ok);
}

.diff-card.update {
  color: var(--warn);
}

.diff-card.deprecate,
.diff-card.error {
  color: var(--fail);
}

.diff-card.unchanged {
  color: var(--text-muted);
}

.error {
  color: var(--fail);
}

.success {
  color: var(--ok);
}

.link {
  border: 0;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
  padding: 0;
}

.btn {
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
}

.btn-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (max-width: 900px) {
  .cases-layout {
    grid-template-columns: 1fr;
  }

  .cases-sidebar {
    border-right: 0;
    border-bottom: 1px solid var(--border);
    padding-bottom: var(--space-2);
  }
}
</style>
