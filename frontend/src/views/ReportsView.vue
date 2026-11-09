<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { NButton, NPagination, type DataTableColumns } from 'naive-ui'
import { reportsApi, type ReportListItem, type RetentionResult } from '@/api/reports'
import { useAuthStore } from '@/stores/auth'
import DataTable from '@/components/DataTable.vue'
import { useListPageSize, type PageSize } from '@/composables/useListPageSize'
import ErrorLineView from '@/components/ErrorLine.vue'
import StatusTag from '@/components/StatusTag.vue'
import AppIcon from '@/components/AppIcon.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { formatBytes } from '@/utils/format'
import { runStatusLabel, runStatusTone } from '@/utils/statusLabels'
import { confirmAction } from '@/composables/useConfirm'

const { t, te } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const items = ref<ReportListItem[]>([])
const total = ref(0)
const page = ref(1)
// FR-v3.9-04：档位化 + 记忆 + 视口估算
const { size, pageSizes } = useListPageSize()
const loading = ref(false)
const error = ref<ErrorLine | null>(null)

// 产物清理（RPT-06）：先 dry-run 展示清单，二次确认后才执行。
const retentionOpen = ref(false)
const retentionLoading = ref(false)
const retentionError = ref<ErrorLine | null>(null)
const retention = ref<RetentionResult | null>(null)
const retentionDone = ref<RetentionResult | null>(null)

const projectId = computed(() => auth.projectId ?? 0)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

/**
 * 报告列表列定义（UX-04b 逐页迁移）。
 *
 * 状态列换成 `StatusTag`（图标 + 文字双编码）而不是纯文本：报告页最常被扫的就是
 * 「这条跑过没有、过没过」，只靠文字要一行行读。色档统一走 `runStatusTone`，
 * 与运行列表保持一致，避免同一状态在两页里长得不一样。
 */
const columns = computed<DataTableColumns<ReportListItem>>(() => [
  {
    title: t('reports.runId'),
    key: 'runId',
    width: 220,
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', onClick: () => openReport(row.runId) },
        { default: () => row.runId },
      ),
  },
  {
    title: t('reports.state'),
    key: 'state',
    width: 120,
    render: (row) =>
      h(StatusTag, { label: runStatusLabel(row.state), tone: runStatusTone(row.state) }),
  },
  {
    title: t('reports.counts'),
    key: 'counts',
    width: 130,
    render: (row) =>
      h('span', { class: 'mono' }, [
        h('span', { class: 'text-ok' }, String(row.passCount)),
        ' / ',
        h('span', { class: 'text-fail' }, String(row.failCount)),
        ' / ',
        h('span', { class: 'muted' }, String(row.skipCount)),
      ]),
  },
  { title: t('reports.startedAt'), key: 'startedAt', width: 190, render: (row) => row.startedAt || '—' },
  {
    title: t('reports.exportable'),
    key: 'exportable',
    minWidth: 220,
    render: (row) =>
      row.exportable
        ? h('span', { class: 'text-ok' }, t('reports.exportable'))
        : h('span', { class: 'text-fail' }, [
            t('reports.notExportable'),
            h('span', { class: 'missing-keys' }, row.metadataMissing.join(', ')),
          ]),
  },
])

/** 清理清单（dry-run 预览）：三元列的只读表 */
const retentionColumns = computed<DataTableColumns<RetentionResult['items'][number]>>(() => [
  { title: t('reports.retention.runId'), key: 'runId' },
  { title: t('reports.retention.bytes'), key: 'bytes', width: 140, render: (row) => formatBytes(row.bytes) },
  { title: t('reports.retention.reason'), key: 'reason', render: (row) => reasonLabel(row.reason) },
])

async function load(): Promise<void> {
  error.value = null
  if (!projectId.value) {
    items.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const result = await reportsApi.page(projectId.value, page.value, size.value)
    items.value = result.items
    total.value = result.total
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
}

/** 页大小切换（FR-v3.9-04）：改档位即回到第 1 页重新取数。 */
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

function openReport(runId: string): void {
  void router.push({ name: 'report-detail', params: { runId } })
}

function reasonLabel(reason: string): string {
  const key = `reports.retention.reasons.${reason}`
  return te(key) ? t(key) : reason
}

async function openRetention(): Promise<void> {
  retentionOpen.value = true
  retentionDone.value = null
  await runRetention(true)
}

async function runRetention(dryRun: boolean): Promise<void> {
  retentionError.value = null
  retentionLoading.value = true
  try {
    retention.value = await reportsApi.retention(projectId.value, dryRun)
    if (!dryRun) retentionDone.value = retention.value
  } catch (e) {
    retentionError.value = localizeError(e)
  } finally {
    retentionLoading.value = false
  }
}

async function executeRetention(): Promise<void> {
  if (!(await confirmAction(t('reports.retention.confirm'), { tone: 'danger' }))) return
  await runRetention(false)
  if (retention.value && !retention.value.dryRun) {
    retentionOpen.value = true
    await load()
  }
}

function closeRetention(): void {
  retentionOpen.value = false
}

watch(
  projectId,
  () => {
    page.value = 1
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="page is-list">
  <div class="w2-entry">
    <button class="btn" type="button" @click="router.push('/test-reports')">
      {{ t('testReports.open') }}
    </button>
  </div>
    <div class="page-head">
      <h1 class="page-title">{{ t('menu.reports') }}</h1>
    </div>

    <div class="toolbar">
      <NButton quaternary @click="load">
        <template #icon><AppIcon name="refresh" :size="16" /></template>
        {{ t('common.refresh') }}
      </NButton>
      <NButton v-if="auth.isAdmin" quaternary @click="openRetention">
        <template #icon><AppIcon name="trash" :size="16" /></template>
        {{ t('reports.retention.open') }}
      </NButton>
      <!-- 报告模板（TPLX-01）：决定对外导出有哪些字段、什么顺序。 -->
      <RouterLink v-slot="{ navigate }" to="/reports/templates" custom>
        <NButton quaternary data-testid="entry-report-templates" @click="navigate">
          {{ t('reports.templatesEntry') }}
        </NButton>
      </RouterLink>
    </div>

    <section v-if="retentionOpen && auth.isAdmin" class="card wide-card retention-panel">
      <div class="section-head">
        <h2 class="card-title">{{ t('reports.retention.title') }}</h2>
        <NButton quaternary size="small" @click="closeRetention">
          {{ t('reports.retention.close') }}
        </NButton>
      </div>
      <p class="muted">{{ t('reports.retention.hint') }}</p>

      <ErrorLineView v-if="retentionError" :error="retentionError" />
      <p v-if="retentionLoading" class="muted">{{ t('common.loading') }}</p>

      <template v-else-if="retention">
        <p v-if="retentionDone" class="text-ok">
          {{
            t('reports.retention.done', {
              count: retentionDone.runCount,
              files: retentionDone.deletedFiles,
              size: formatBytes(retentionDone.totalBytes),
            })
          }}
        </p>
        <p class="muted">
          {{ t('reports.retention.keptRecent', { count: retention.keptRecentRuns }) }}
          · {{ t('reports.retention.projectBytes', { size: formatBytes(retention.projectBytes) }) }}
        </p>

        <p v-if="retention.items.length === 0" class="muted">
          {{ t('reports.retention.empty') }}
        </p>
        <template v-else>
          <p class="muted">
            {{
              t('reports.retention.total', {
                count: retention.runCount,
                size: formatBytes(retention.totalBytes),
              })
            }}
          </p>
          <DataTable
            :columns="retentionColumns"
            :data="retention.items"
            :row-key="(row: RetentionResult['items'][number]) => row.runId"
            :error="Boolean(retentionError)"
          />
          <div v-if="retention.dryRun" class="toolbar">
            <NButton type="primary" :loading="retentionLoading" @click="executeRetention">
              {{
                retentionLoading ? t('reports.retention.executing') : t('reports.retention.execute')
              }}
            </NButton>
          </div>
        </template>
      </template>
    </section>

    <ErrorLineView v-if="error" :error="error" />

    <div class="list-fill">
      <DataTable
        :columns="columns"
        :data="items"
        :loading="loading"
        :row-key="(row: ReportListItem) => row.runId"
        :empty-title="t('common.noData')"
        :empty-description="projectId ? undefined : t('overview.noProject')"
        empty-icon="reports"
        :error="Boolean(error)"
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
      @update:page-size="changePageSize"
      @update:page="turnPage"
    />
  </div>
</template>

<style scoped>
.missing-keys {
  color: var(--text-muted);
  font-size: 12px;
  margin-left: 6px;
}

.retention-panel {
  margin-bottom: 16px;
}
</style>
