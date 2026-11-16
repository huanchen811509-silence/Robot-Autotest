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
  type ImportBatchRow,
} from '@/api/globalCases'
import type { PageResult } from '@/api/iam'
import DataTable from '@/components/DataTable.vue'
import ImportWizard from '@/components/ImportWizard.vue'
import CaseCategoryNav, { type CategorySelection } from '@/components/CaseCategoryNav.vue'
import CaseDetailPanel from '@/components/CaseDetailPanel.vue'
import CaseScriptLink from '@/components/CaseScriptLink.vue'
import DetailDock from '@/components/DetailDock.vue'
import { useDetailDock } from '@/composables/useDetailDock'
import { globalGroupedApi, type GroupedCase } from '@/api/platformCaseSets'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/**
 * v5.11.0 / FR-v5.11-02/03：**只留一个视图**——中栏两级分类 + 右表 + 表格下方详情。
 * 原来的「列表 / 分类归组」切换（`?group=1`）已删除：同一批数据两种看法只会让人切来切去。
 *
 * URL 三态（FR-02 判据 6）：`l1`/`l2`（分类）、`uncategorized`（未分类）、`detail`（选中行），
 * 加上既有的 `keyword`/`lifecycleStatus`/`page`，复制链接即可复现同一屏。
 */
const category = ref<CategorySelection>({
  l1: typeof route.query.l1 === 'string' ? route.query.l1 : '',
  l2: typeof route.query.l2 === 'string' ? route.query.l2 : '',
  uncategorized: route.query.uncategorized === '1',
})
const groups = ref<GroupedCase[]>([])
/** 全部用例数：仅在按分类筛选后才需要（不筛时就是 total 本身，省一次请求）。 */
const allTotal = ref(0)

const { selected: detailId, isOpen: detailOpen, toggle: toggleDetail, close: closeDetail } =
  useDetailDock({
    initial: typeof route.query.detail === 'string' ? route.query.detail : null,
  })

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
const keyword = ref(
  typeof route.query.keyword === 'string' ? route.query.keyword : '',
)
const lifecycleStatus = ref('')
const categoryL1 = computed(() => (category.value.uncategorized ? '' : category.value.l1))
const categoryL2 = computed(() => (category.value.uncategorized ? '' : category.value.l2))
const loading = ref(false)
const failed = ref('')
const categories = ref<CaseCategoryRow[]>([])

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
      uncategorized: category.value.uncategorized || undefined,
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

/**
 * 把当前三态写回 URL。只写自己管的几个键，其余（view 等）原样保留 ——
 * 否则从「导入记录」切回来时 `?view=imports` 会被抹掉又弹回清单，看着像页面在抽风。
 */
function syncQuery(): void {
  const query: LocationQueryRaw = { ...route.query }
  const put = (key: string, value: string | undefined): void => {
    if (value) query[key] = value
    else delete query[key]
  }
  put('l1', category.value.l1 || undefined)
  put('l2', category.value.l2 || undefined)
  put('uncategorized', category.value.uncategorized ? '1' : undefined)
  put('detail', detailId.value || undefined)
  put('keyword', keyword.value || undefined)
  put('page', page.value > 1 ? String(page.value) : undefined)
  void router.replace({ query })
}

/** 全部用例数：只有按分类筛过才需要，避免每次进页多打一次接口。 */
async function ensureAllTotal(): Promise<void> {
  if (allTotal.value) return
  try {
    const first = await globalCaseApi.page({ page: 1, size: 1 })
    allTotal.value = first.total
  } catch {
    allTotal.value = total.value
  }
}

async function onCategoryChange(next: CategorySelection): Promise<void> {
  category.value = next
  page.value = 1
  // 换分类时收起详情：留着上一个分类的行，读起来像「筛出来的结果里有它」
  closeDetail()
  await ensureAllTotal()
  syncQuery()
  await load()
}

async function loadGroups(): Promise<void> {
  try {
    groups.value = await globalGroupedApi.grouped()
  } catch {
    groups.value = []
  }
}

function onFilterChange() {
  page.value = 1
  syncQuery()
  void load()
}

function goPage(next: number) {
  const max = Math.max(1, Math.ceil(total.value / size))
  page.value = Math.min(max, Math.max(1, next))
  syncQuery()
  void load()
}

const detailRow = computed(
  () => rows.value.find((row) => String(row.id) === detailId.value) ?? null,
)
const detailTitle = computed(() =>
  detailRow.value
    ? `${detailRow.value.externalId || detailRow.value.caseCode} ${detailRow.value.title}`
    : '',
)

/** 行点击：同一行再点收起，换行原地替换（详情区实例恒为 1）。 */
function selectRow(row: GlobalCaseRow): void {
  toggleDetail(row.id)
  syncQuery()
}

function rowProps(row: GlobalCaseRow): Record<string, unknown> {
  return {
    style: 'cursor: pointer',
    'data-case-row': String(row.id),
    'aria-selected': String(row.id) === detailId.value ? 'true' : 'false',
    onClick: () => selectRow(row),
  }
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
      // v5.11.0 / FR-v5.11-05：脚本列默认显示（文件名由后端算），可隐藏由列设置管
      title: t('w61.script.column'),
      key: 'script',
      width: 200,
      render: (row) =>
        h(CaseScriptLink, {
          scriptPath: row.scriptPath ?? null,
          scriptName: row.scriptName ?? null,
          bindingState: row.bindingState ?? null,
          otherScriptCount: row.otherScriptCount ?? 0,
          absoluteSource: row.scriptAbsoluteSource ?? false,
        }),
    },
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
  {
    // v5.18.0：错误报告下载从弹窗搬到这里——弹窗没了，但「哪批错了、错在哪」仍然要能查
    title: t('global.downloadReport'),
    key: 'errors',
    width: 140,
    render: (batch) =>
      h(
        NButton,
        {
          size: 'small',
          quaternary: true,
          onClick: () => void downloadErrorReport(batch.id),
        },
        () => t('global.downloadReport'),
      ),
  },
])


/* ---------------------------------------------------------------- 导入 */
//
// v5.18.0 / FR-v5.18-01：导入从两步弹窗升级为五步向导（共用 `ImportWizard`）。
// 页面这边只留三样东西：开关、`force` 复选（回放 / 重算语义）、提交后的数据刷新。
// 预检与提交都在向导内完成，页面不再持有 preview / commitResult 两份临时状态。

const importOpen = ref(false)

function openImport(): void {
  importOpen.value = true
}

/** 提交完成：列表、分类树、批次表都要跟着变（新增用例会改分类计数）。 */
async function afterCommit(): Promise<void> {
  await load()
  await loadCategories()
  await loadBatches()
}

/** 结果页「查看导入记录」：切到 `?view=imports` 分支。 */
function goImports(): void {
  void router.replace({ query: { ...route.query, view: 'imports' } })
}

async function downloadErrorReport(batchId: number): Promise<void> {
  const blob = await globalCaseApi.errorReport(batchId)
  triggerDownload(blob, `import-${batchId}-report.csv`)
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
  // 中栏分类树与右表并行拉：树慢不该拖住首屏列表
  void loadGroups()
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

    <!-- 用例清单分支（v5.11.0 / FR-v5.11-02）：中栏两级分类 + 右工作区 + 表格下方详情 -->
    <div v-if="view === 'cases'" class="cases-layout" data-testid="cases-layout">
      <CaseCategoryNav
        :model-value="category"
        :groups="groups"
        :all-count="allTotal || total"
        :active-count="total"
        @update:model-value="onCategoryChange"
      />

      <div class="cases-main">
        <div class="filters">
          <input
            v-model="keyword"
            class="input"
            :placeholder="t('global.searchPlaceholder')"
            @keyup.enter="onFilterChange"
          />
          <select v-model="lifecycleStatus" class="select" @change="onFilterChange">
            <option value="">{{ t('global.allLifecycle') }}</option>
            <option value="active">{{ t('global.lifecycleActive') }}</option>
            <option value="deprecated">{{ t('global.lifecycleDeprecated') }}</option>
          </select>
        </div>

        <p v-if="failed" class="error">{{ failed }}</p>

        <div class="list-area" data-testid="cases-list-area">
          <div data-testid="cases-grid">
            <DataTable
              :columns="columns"
              :data="rows"
              :loading="loading"
              :row-key="(row: GlobalCaseRow) => row.id"
              :row-props="rowProps"
              :error="Boolean(failed)"
              :empty-title="t('global.empty')"
              :max-height="420"
            />
          </div>

          <!-- 详情就在表格正下方（不是弹窗 / 抽屉 / 跳页），与表格同宽 -->
          <DetailDock :open="detailOpen" :title="detailTitle" @close="closeDetail">
            <CaseDetailPanel
              v-if="detailId"
              source="global"
              :case-id="detailId"
            />
          </DetailDock>
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

    <ImportWizard
      v-if="importOpen"
      kind="case"
      @close="importOpen = false"
      @open-batches="goImports"
      @committed="afterCommit"
    />
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
