<script setup lang="ts">
/**
 * 存量同步（v4.9.0 / FR-v4.9-01..07）。
 *
 * 三步闭环：预览（零写入）→ 逐条处理冲突 → 执行；执行后可整单回滚。
 * 预览只展示差异，不自动覆盖；`local_override` 与公共库已删的用例一律 SKIP。
 *
 * v4.11.0 / FR-v4.11-19：两处原生 `<table>` 改走 `DataTable` 壳。
 * 注意列里的 `class="tag" / "select" / "small"` 是 `render()` 里 `h()` 出来的节点，
 * **拿不到本组件的 scope id**（渲染发生在 NDataTable 内部），所以下面样式一律用
 * `:deep()` 锚定，否则壳换掉原生表格后这些样式会静默失效。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import {
  projectSyncApi,
  type SyncItemRow,
  type SyncJobRow,
  type SyncPreview,
} from '@/api/projectSync'
import DataTable from '@/components/DataTable.vue'

const { t } = useI18n()
const auth = useAuthStore()

const projectId = computed(() => auth.projectId)
const preview = ref<SyncPreview | null>(null)
const jobs = ref<SyncJobRow[]>([])
const resolutions = ref<Record<string, string>>({})
const loading = ref(false)
const busy = ref(false)
const failed = ref('')
const notice = ref('')

const conflictItems = computed(
  () => preview.value?.items.filter((item) => item.action === 'CONFLICT') ?? [],
)
const hasPreview = computed(() => !!preview.value)

function actionLabel(action: string): string {
  return t(`sync.action.${action}`)
}

/** 冲突处置三选一：值 → i18n 键后缀（与旧 `<select>` 的 option 一一对应）。 */
const RESOLUTION_LABEL: Record<string, string> = {
  KEEP_LOCAL: 'keepLocal',
  TAKE_REMOTE: 'takeRemote',
  DUPLICATE: 'duplicate',
}

function resetResolutions(items: SyncItemRow[]): void {
  const next: Record<string, string> = {}
  for (const item of items) {
    if (item.action === 'CONFLICT') next[String(item.id)] = 'KEEP_LOCAL'
  }
  resolutions.value = next
}

/* ------------------------------------------------------------ 表格列定义 */

const previewColumns = computed<DataTableColumns<SyncItemRow>>(() => [
  {
    title: t('sync.column.externalId'),
    key: 'externalId',
    width: 170,
    render: (row) => h('span', { class: 'mono' }, row.externalId),
  },
  { title: t('sync.column.title'), key: 'title', minWidth: 200 },
  { title: t('sync.column.category'), key: 'categoryL1', width: 140 },
  {
    title: t('sync.column.action'),
    key: 'action',
    width: 120,
    render: (row) =>
      h('span', { class: ['tag', row.action.toLowerCase()] }, actionLabel(row.action)),
  },
  {
    title: t('sync.column.resolution'),
    key: 'resolution',
    width: 170,
    render: (row) => {
      if (row.action !== 'CONFLICT') return h('span', { class: 'muted' }, '—')
      const id = String(row.id)
      return h(
        'select',
        {
          class: 'select',
          value: resolutions.value[id] ?? 'KEEP_LOCAL',
          'data-testid': `sync-resolution-${id}`,
          onChange: (event: Event) => {
            const value = (event.target as HTMLSelectElement).value
            resolutions.value = { ...resolutions.value, [id]: value }
          },
        },
        Object.entries(RESOLUTION_LABEL).map(([value, label]) =>
          h('option', { value }, t(`sync.resolve.${label}`)),
        ),
      )
    },
  },
  {
    title: t('sync.column.message'),
    key: 'message',
    minWidth: 200,
    render: (row) => h('span', { class: 'muted small' }, row.message),
  },
])

const jobColumns = computed<DataTableColumns<SyncJobRow>>(() => [
  { title: '#', key: 'id', width: 90, render: (row) => h('span', { class: 'mono' }, row.id) },
  {
    title: t('sync.column.status'),
    key: 'status',
    width: 120,
    render: (row) => h('span', { class: 'tag' }, statusLabel(row.status)),
  },
  { title: t('sync.column.total'), key: 'total', width: 80 },
  { title: t('sync.action.NEW'), key: 'newCount', width: 80 },
  { title: t('sync.action.UPDATE'), key: 'updatedCount', width: 90 },
  { title: t('sync.action.CONFLICT'), key: 'conflictCount', width: 100 },
  {
    title: t('sync.column.createdAt'),
    key: 'createdAt',
    width: 170,
    render: (row) => h('span', { class: 'muted small' }, row.createdAt),
  },
  {
    title: t('sync.column.executedAt'),
    key: 'executedAt',
    width: 170,
    render: (row) => h('span', { class: 'muted small' }, row.executedAt || '—'),
  },
  {
    title: '',
    key: 'rollback',
    width: 100,
    // 只有 DONE 才可回滚：其余状态回滚是后端 409，按钮不该出现在不可用的行上。
    render: (row) =>
      row.status === 'DONE'
        ? h(
            'button',
            {
              class: 'link danger',
              disabled: busy.value,
              'data-testid': `sync-rollback-${row.id}`,
              onClick: () => void rollback(row),
            },
            t('sync.rollback'),
          )
        : null,
  },
])

async function loadJobs(): Promise<void> {
  if (!projectId.value) return
  try {
    jobs.value = await projectSyncApi.jobs(projectId.value, 20)
  } catch {
    jobs.value = []
  }
}

async function load(): Promise<void> {
  failed.value = ''
  if (!projectId.value) {
    failed.value = t('sync.noProject')
    return
  }
  loading.value = true
  try {
    await loadJobs()
  } finally {
    loading.value = false
  }
}

async function runPreview(): Promise<void> {
  if (!projectId.value) return
  busy.value = true
  failed.value = ''
  notice.value = ''
  try {
    const result = await projectSyncApi.preview(projectId.value)
    preview.value = result
    resetResolutions(result.items)
    await loadJobs()
  } catch (error) {
    failed.value = error instanceof Error ? error.message : t('sync.previewFailed')
  } finally {
    busy.value = false
  }
}

async function execute(): Promise<void> {
  if (!projectId.value || !preview.value) return
  busy.value = true
  failed.value = ''
  notice.value = ''
  try {
    const result = await projectSyncApi.execute(
      projectId.value,
      preview.value.jobId,
      resolutions.value,
    )
    notice.value = t('sync.executed', {
      created: result.created,
      updated: result.updated,
      kept: result.conflictsKept,
      duplicated: result.duplicated,
    })
    preview.value = null
    await loadJobs()
  } catch (error) {
    failed.value = error instanceof Error ? error.message : t('sync.executeFailed')
  } finally {
    busy.value = false
  }
}

async function rollback(job: SyncJobRow): Promise<void> {
  if (!projectId.value) return
  if (!window.confirm(t('sync.rollbackConfirm', { id: job.id }))) return
  busy.value = true
  failed.value = ''
  notice.value = ''
  try {
    const result = await projectSyncApi.rollback(projectId.value, job.id)
    notice.value = t('sync.rolledBack', { removed: result.removed, restored: result.restored })
    await loadJobs()
  } catch (error) {
    failed.value = error instanceof Error ? error.message : t('sync.rollbackFailed')
  } finally {
    busy.value = false
  }
}

function statusLabel(status: string): string {
  return t(`sync.status.${status}`)
}

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h1>{{ t('sync.title') }}</h1>
        <p class="muted">{{ t('sync.hint') }}</p>
      </div>
      <div class="head-actions">
        <button class="btn btn-primary" :disabled="busy || !projectId" @click="runPreview">
          {{ t('sync.preview') }}
        </button>
      </div>
    </header>

    <p v-if="failed" class="error">{{ failed }}</p>
    <p v-if="notice" class="notice">{{ notice }}</p>
    <p v-if="loading" class="muted">{{ t('global.loading') }}</p>

    <section v-if="hasPreview" class="panel">
      <header class="panel-head">
        <h2>{{ t('sync.previewTitle', { job: preview?.jobId }) }}</h2>
        <button class="btn" :disabled="busy" @click="execute">{{ t('sync.execute') }}</button>
      </header>
      <div class="summary">
        <span>{{ t('sync.total', { count: preview?.total }) }}</span>
        <span class="tag new">{{ t('sync.action.NEW') }} {{ preview?.newCount }}</span>
        <span class="tag update">{{ t('sync.action.UPDATE') }} {{ preview?.updateCount }}</span>
        <span class="tag conflict">
          {{ t('sync.action.CONFLICT') }} {{ preview?.conflictCount }}
        </span>
        <span class="tag">{{ t('sync.action.UNCHANGED') }} {{ preview?.unchangedCount }}</span>
        <span class="tag">{{ t('sync.action.SKIP') }} {{ preview?.skippedCount }}</span>
      </div>
      <p v-if="conflictItems.length" class="muted small">{{ t('sync.conflictHint') }}</p>

      <div class="table-wrap" data-testid="sync-preview-grid">
        <DataTable
          :columns="previewColumns"
          :data="preview?.items ?? []"
          :row-key="(row: SyncItemRow) => row.id"
          :max-height="480"
          :empty-title="t('sync.total', { count: preview?.total ?? 0 })"
        />
      </div>
    </section>

    <section class="panel">
      <header class="panel-head">
        <h2>{{ t('sync.history') }}</h2>
        <button class="link" @click="loadJobs">{{ t('sync.refresh') }}</button>
      </header>
      <p v-if="jobs.length === 0" class="muted empty">{{ t('sync.noHistory') }}</p>
      <div v-else class="table-wrap" data-testid="sync-jobs-grid">
        <DataTable
          :columns="jobColumns"
          :data="jobs"
          :row-key="(row: SyncJobRow) => row.id"
          :max-height="480"
        />
      </div>
    </section>
  </section>
</template>

<style scoped>
.page-head,
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.head-actions {
  display: flex;
  gap: var(--space-2);
}

.panel {
  margin-top: var(--space-4);
}

.summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: var(--space-2) 0;
}

/*
 * 单元格样式一律 `:deep()`：这些节点由 `render()` 里的 `h()` 产生，渲染发生在
 * NDataTable 内部，不带本组件的 scope id，普通的 `.tag { }` 选择器匹配不到。
 */
:deep(.tag) {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
}

:deep(.tag.new) {
  border-color: var(--ok);
  color: var(--ok);
}

:deep(.tag.update) {
  border-color: var(--accent);
  color: var(--accent);
}

:deep(.tag.conflict) {
  border-color: var(--fail);
  color: var(--fail);
}

/* 横向滚动交给 DataTable 自己（它按列宽算 scroll-x）；外层再套一层 overflow 会出现两条横条。 */
.table-wrap {
  min-width: 0;
}

:deep(.select) {
  padding: 4px 6px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
}

.notice {
  color: var(--ok);
}

:deep(.small) {
  font-size: 12px;
}

.empty {
  padding: var(--space-3) 0;
}
</style>
