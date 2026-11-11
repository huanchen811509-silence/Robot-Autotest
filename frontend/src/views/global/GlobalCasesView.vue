<script setup lang="ts">
/**
 * 公共用例库（v4.6.0 / FR-v4.6-01..13、v4.6.1 / FR-v4.6-20..24）。
 *
 * 列表 + 一级/二级分类筛选 + 两段式幂等导入（上传 → 预览差异 → 提交）+ 反向导出。
 * 写操作按钮在管理员下才显示；后端仍会二次校验，前端只做显隐。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import {
  globalCaseApi,
  type CaseCategoryRow,
  type GlobalCaseRow,
  type GlobalImportPreview,
  type ImportBatchRow,
} from '@/api/globalCases'
import type { PageResult } from '@/api/iam'

const { t } = useI18n()
const auth = useAuthStore()

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

// ------------------------------------------------------------------ 导入

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

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importing.value = true
  importMessage.value = ''
  commitResult.value = ''
  try {
    preview.value = await globalCaseApi.previewImport(file)
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

// --------------------------------------------------------------- 生命周期

async function toggleLifecycle(row: GlobalCaseRow) {
  if (row.lifecycleStatus === 'active') {
    await globalCaseApi.deprecate(row.id)
  } else {
    await globalCaseApi.restore(row.id)
  }
  await load()
}

// ------------------------------------------------------------------ 批次

const batches = ref<ImportBatchRow[]>([])
const batchTotal = ref(0)
const batchPage = ref(1)

async function loadBatches() {
  try {
    const result = await globalCaseApi.batches({ page: batchPage.value, size: 5 })
    batches.value = result.items
    batchTotal.value = result.total
  } catch {
    batches.value = []
  }
}

onMounted(async () => {
  await Promise.all([load(), loadCategories(), loadBatches()])
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h1>{{ t('global.casesTitle') }}</h1>
        <p class="muted">{{ t('global.casesHint') }}</p>
      </div>
      <div class="page-actions">
        <button v-if="canWrite" class="btn" @click="exportCases">{{ t('global.export') }}</button>
        <button v-if="canWrite" class="btn btn-primary" @click="openImport">
          {{ t('global.import') }}
        </button>
      </div>
    </header>

    <div class="cases-layout">
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
        <div v-if="loading" class="muted">{{ t('global.loading') }}</div>

        <table v-else class="case-table">
          <thead>
            <tr>
              <th>{{ t('global.colCode') }}</th>
              <th>{{ t('global.colTitle') }}</th>
              <th>{{ t('global.colCategory') }}</th>
              <th>{{ t('global.colPriority') }}</th>
              <th>{{ t('global.colStatus') }}</th>
              <th>{{ t('global.colVersion') }}</th>
              <th v-if="canWrite">{{ t('global.colActions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td class="mono">{{ row.externalId || row.caseCode }}</td>
              <td>{{ row.title }}</td>
              <td>
                <span v-if="row.categoryL1">{{ row.categoryL1 }}</span>
                <span v-if="row.categoryL2" class="muted"> / {{ row.categoryL2 }}</span>
              </td>
              <td>{{ row.priority }}</td>
              <td>
                <span class="badge" :class="row.lifecycleStatus">
                  {{
                    row.lifecycleStatus === 'active'
                      ? t('global.lifecycleActive')
                      : t('global.lifecycleDeprecated')
                  }}
                </span>
              </td>
              <td>v{{ row.versionNo }}</td>
              <td v-if="canWrite">
                <button class="link" @click="toggleLifecycle(row)">
                  {{
                    row.lifecycleStatus === 'active'
                      ? t('global.deprecate')
                      : t('global.restore')
                  }}
                </button>
              </td>
            </tr>
            <tr v-if="rows.length === 0">
              <td :colspan="canWrite ? 7 : 6" class="muted empty">{{ t('global.empty') }}</td>
            </tr>
          </tbody>
        </table>

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

        <details class="batches">
          <summary>{{ t('global.batchHistory') }}（{{ batchTotal }}）</summary>
          <table class="case-table">
            <thead>
              <tr>
                <th>#</th>
                <th>{{ t('global.batchFile') }}</th>
                <th>{{ t('global.colStatus') }}</th>
                <th>{{ t('global.batchRows') }}</th>
                <th>{{ t('global.batchDiff') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="batch in batches" :key="batch.id">
                <td>{{ batch.id }}</td>
                <td>{{ batch.source_name }}</td>
                <td>{{ batch.status }}</td>
                <td>{{ batch.total_rows }}</td>
                <td class="mono">
                  +{{ batch.new_count }} ~{{ batch.updated_count }} ={{ batch.unchanged_count }}
                  -{{ batch.deprecated_count }}
                </td>
              </tr>
              <tr v-if="batches.length === 0">
                <td colspan="5" class="muted">{{ t('global.empty') }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </div>
    </div>

    <div v-if="importOpen" class="modal-mask" @click.self="importOpen = false">
      <div class="modal">
        <header class="modal-head">
          <h2>{{ t('global.importTitle') }}</h2>
          <button class="link" @click="importOpen = false">{{ t('global.close') }}</button>
        </header>

        <div class="modal-body">
          <p class="muted">{{ t('global.importHint') }}</p>
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

            <div class="preview-scroll">
              <table class="case-table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>{{ t('global.colCode') }}</th>
                    <th>{{ t('global.colTitle') }}</th>
                    <th>{{ t('global.importAction') }}</th>
                    <th>{{ t('global.importMessage') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in preview.rows" :key="row.rowNo">
                    <td>{{ row.rowNo }}</td>
                    <td class="mono">{{ row.caseCode }}</td>
                    <td>{{ row.title }}</td>
                    <td>
                      <span class="badge" :class="row.action.toLowerCase()">{{ row.action }}</span>
                    </td>
                    <td class="muted">{{ row.message }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

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
  margin-bottom: var(--space-3);
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

.case-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.case-table th,
.case-table td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  vertical-align: top;
}

.case-table th {
  color: var(--text-muted);
  font-weight: 600;
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

.empty {
  text-align: center;
  padding: var(--space-4);
}

.batches {
  margin-top: var(--space-4);
}

.batches summary {
  cursor: pointer;
  color: var(--text-muted);
  margin-bottom: var(--space-2);
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
  width: min(920px, 92vw);
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

.preview-scroll {
  max-height: 320px;
  overflow: auto;
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
  color: var(--accent, #2563eb);
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
  background: var(--accent, #2563eb);
  border-color: var(--accent, #2563eb);
  color: #fff;
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
